# Design — Order Ingestion & Management System

Stack: Java 21, Spring Boot 4.1, Spring MVC, Spring Data JPA, H2 (in-memory),
React (Vite) frontend. Chosen for fast setup and zero external infrastructure
— the reviewer can run the whole system with `./mvnw spring-boot:run`.

## Contents

1. [Overview](#overview)
2. [System-wide conventions](#system-wide-conventions)
3. [Ingestion pipelines](#ingestion-pipelines) — CSV, webhook, polling API
4. [Order model & lifecycle](#order-model--lifecycle)
5. [Robot dispatch](#robot-dispatch)
6. [Order history & read APIs](#order-history--read-apis)
7. [Demo mocks](#demo-mocks)
8. [Frontend](#frontend)
9. [Security — not implemented](#security--not-implemented-prototype-gap)
10. [API reference](#api-reference)

## Overview

The system ingests food orders from three independent pipelines (CSV
uploads, webhook stream, polling API), normalizes them into a single
internal order model, and tracks each order through its lifecycle up to
dispatch (handed to the robot). Every pipeline is a thin adapter that feeds
one common ingestion path, so transport concerns (HTTP, polling, file
upload) stay separate from order-merging logic.

**Granularity: sources speak different units; our schema speaks one.**
The key structural difference between the pipelines is the *unit of
transmission* — what one message in the feed describes:

| pipeline    | unit of transmission | updates?                                  |
|-------------|----------------------|-------------------------------------------|
| CSV         | one complete order (items as free text) | never — a row arrives once |
| Webhook     | one complete order (items array, total) | order-scoped (e.g. `update: ["cancelled"]` reusing the order_id) |
| Polling API | **one item** (dish) with its own status | item-scoped — each delta entry updates one item; the "order" is never transmitted as a whole, it exists only as the set of entries sharing an `order` number |

Our storage model is deliberately uniform regardless of source: one `orders`
row per order, `order_items` rows per dish, `order_history` + `item_history`
snapshots per version. Each adapter's job is translating its source's
granularity into that model:

- **CSV / webhook** arrive order-shaped — a direct mapping: one incoming
  record → one order row plus its item rows.
- **The polling API** arrives item-shaped — its adapter *reconstructs*
  orders from fragments: the first delta entry with an unseen order number
  creates the order row; each item hash becomes an `order_items` row
  (matched by its external item id on later deltas); subsequent entries for
  known hashes are item-level updates.

That reconstruction raised a design question: one API order's items
legitimately hold different source statuses at the same moment (some
`delivered`, some `with_courier`), while `orders.order_status` is a single
column. The implemented answer: the two are different axes and are NOT
rolled up. `orders.order_status` tracks OUR dispatch lifecycle only;
the source's per-item progress lives verbatim on the items
(`order_items.source_status`) and is visible item-by-item in the details
and history APIs. CSV and webhook orders don't have this problem — their
sources carry no item-level status.

## System-wide conventions

**Time: everything runs on UTC, and no DB timestamp carries timezone
ambiguity.** The application clock is `Clock.systemUTC()`; the per-minute
dispatch-count buckets (`orders_processed.time`, and the
`/v1/orders-dispatched` from/to parameters) are `yyyyMMddHHmm` strings
formatted in UTC. Every `Instant` entity attribute is persisted as
**milliseconds since the epoch** (BIGINT) via a globally auto-applied JPA
converter (`InstantMillisConverter`) rather than as a SQL timestamp — the
one exception is the `shedlock` table, whose TIMESTAMP columns are
ShedLock's own schema contract. Meal windows are the one deliberately
zone-tagged config: `dispatch.windows` bounds are wall-clock times in the
zone they name ("07:00 PST"), converted to UTC instants at scheduling time
using that zone's DST rules — a bare-UTC window would silently shift the
local meal hour when DST flips. Two further real-deployment refinements,
both display/derivation concerns on top of unchanged UTC storage:
per-order local timezones (orders from different geographic locations
mapping their meal windows to their own local time, derived from order
location or source metadata), and per-user display timezones — in
production every user has their own default timezone and the UI renders
all times in it, converting at the display layer only; the prototype's
UI shows raw UTC instead.

**Layering.** Controllers (`com.lab37.controller`) → services
(`com.lab37.service`) → Spring Data repositories (`com.lab37.repository`)
over entities (`com.lab37.model`). Runtime config lives in
`application.yaml`, not code.

**Every order write is snapshotted.** Creation, content update, delta
application, VIP patch, cancellation, dispatch, or a no-dispatch closure —
each appends one `order_history` snapshot (the order minus updated_at,
keyed by order_id + version) together with one `item_history` row per
current item (name, price, external item id, source status, and our
CREATED/DISPATCHED item status) under the same version, written as a unit
by `HistoryRecorder`. Each version is therefore a complete picture: what
the order looked like AND which items it had, in what state, at that step.

**Index audit** (all generated from entity annotations):
`upload_jobs (status, created_at)` for the job claim and
`upload_jobs (created_at)` for the jobs listing;
`orders (order_status, vip DESC, created_at)` for dispatch pickup (matching
its VIP-first ordering), `orders (job_id)` for per-job lookups, and
`orders (external_order_id)` (unique) for webhook/API idempotency checks
and source-id search; `order_items (order_id)` for per-order fetches and
`order_items (external_item_id)` (unique) for API item upserts;
`order_history (order_id, version)` and `item_history (order_id,
order_version)` for history reads; `webhook_queue (status, created_on)` and
`api_order_queue (status, created_on)` for the consumer scans.
`orders_processed`, `upload_files`, and `api_polling` are only ever
accessed by primary key.

## Ingestion pipelines

### CSV uploads (orders_1..4.csv)

**What it is.** Order batches styled after survey-form output (sample
files: `src/main/resources/order-sources/orders_1..4.csv`), uploaded a few
times per day.

**Design: direct upload endpoint + async job tracking.** CSVs are
submitted via `POST /v1/ingest` as a multipart file upload. The uploaded
file's raw bytes are first persisted to the `upload_files` table (see the
prototype simplifications below) so the raw batch survives any processing
failure and can be replayed. Each accepted upload is recorded as a row in
the `upload_jobs` table (id UUID, file_name, status
QUEUED/RUNNING/DONE/FAILED, attempts, error, byte_offset,
created_at/locked_at/started_at/finished_at) and the endpoint immediately
returns `202 Accepted` with the job in `QUEUED` state and a self link:

```json
{ "jobId": "…", "status": "QUEUED", "links": { "self": "/v1/jobs/{jobId}" } }
```

`GET /v1/jobs/{jobId}` returns the job row, letting the uploader poll
processing progress. `GET /v1/jobs` lists all upload jobs, newest first,
with an optional `createdAfter` query parameter (millis since epoch,
"created at or after") — backed by the dedicated `upload_jobs (created_at)`
index (the claim query's `(status, created_at)` index can't serve a bare
created_at range).

**Processing: batched, resumable.** A scheduled worker
(`ingest.poll-interval`) claims one job at a time and converts the CSV into
`orders` rows (one per CSV line: first_name, last_name, items, notes,
tomorrow, meal, plus order id, job_id, order_type, version starting at 1,
created_at/updated_at), with history snapshots per the system-wide
convention. Rows are processed in batches of `ingest.batch-size` (default
1000); each batch commits its orders, their history snapshots, and the
job's advanced `byte_offset` in one transaction. A crash mid-file
therefore loses at most the current uncommitted batch, and the next run
resumes reading the file at the last committed byte offset — no lost rows,
no duplicates. Malformed rows (wrong column count, where fields can't be
mapped at all) are skipped and logged, so a partially bad file still
ingests its good rows; a row whose *schedule* can't be computed — blank
meal/tomorrow, an unknown meal, or a non-boolean tomorrow value — is still
saved as an order, but as `UNFULFILLED` with the reason recorded in the
order's `error` column. Nothing valid enough to map is silently dropped.
Every order that becomes `UNFULFILLED`, here and later in the dispatcher,
is logged at WARN — that's the monitoring signal for fulfillment problems.

**No cross-upload idempotency — a known limitation.** CSV rows carry no
source order id (`external_order_id` stays null for SVC_FILE orders), so
unlike the webhook and polling pipelines there is nothing to dedupe on:
uploading the same file twice creates every order twice. The byte-offset
resume above protects against duplicates *within* one job's processing,
but each upload is a new job and each row a new order. Real protection
would need the source to supply row identity — or a heuristic such as a
file-content hash rejecting an identical re-upload — neither of which the
survey-style feed provides today.

**Parsing detail.** Quoted CSV fields may contain commas AND newlines (the
sample data does both), so parsing is record-based via commons-csv with
byte tracking for the resume offsets. The items field splits into dish
names on commas and newlines, except commas inside parentheses
("Milkshakes (vanilla, chocolate)" is one item). CSV item prices stay null
— survey orders are priced elsewhere.

**Prototype simplifications: DB-stored files, DB-as-queue, one job per
file.** Three deliberate early-stage choices:

- *Uploaded files live in the DB, so the app runs on multiple boxes
  unchanged.* Uploaded CSV bytes are stored in the `upload_files` table
  (one row per job), not on local disk — the database is the only shared
  state. The job row keeps the original file name as provenance: nothing
  reads it programmatically, but logs and the jobs API use it so an
  operator can tell *which* upload a job was. Any worker on any box can
  process any job: point every instance at the same DB (the `postgres`
  Spring profile does exactly that) and the system scales out with no code
  changes — every coordination point is already DB-backed: job claims
  (`FOR UPDATE SKIP LOCKED`), the dispatch loop (ShedLock), rate-limit
  counters, and file content. One DB-dialect detail is config: the atomic
  job-claim statement differs between H2 (`FINAL TABLE`) and PostgreSQL
  (`UPDATE … RETURNING`), selected by `ingest.claim-dialect` (the Postgres
  variant is not exercised by the H2-based test suite). The trade-off:
  each file is held in memory and in a DB row — fine for small survey
  CSVs, wrong for large files, at which point the production fix is object
  storage (store the S3 key on the job row and stream), changing neither
  the queue, the API, nor the job model.
- *No message broker — the `upload_jobs` table is the queue.* A worker
  polls for `QUEUED` rows and claims them by flipping status to `RUNNING`
  in one atomic statement. At prototype scale this buys zero extra
  infrastructure, a queue transactional with the job state itself (no
  dual-write), and SQL inspectability. The costs — polling latency, DB
  load, row-level locking — only matter at volumes far beyond a few
  uploads per day; production would publish the jobId to a real queue and
  keep the table as the system of record.
- *One job per uploaded file — no chunk splitting.* Survey CSVs are small,
  so a whole file is a single unit of work; per-row validation already
  prevents one bad row from failing the batch, which is the main
  failure-isolation benefit chunking would buy.

Both queue/storage decisions are confined behind the ingestion path:
switching to a broker or S3 later changes how jobs are dispatched, not the
API contract or the job model.

**Tradeoff: direct endpoint vs. object storage (S3).** Production bulk
file ingestion usually goes through object storage: the client uploads to
a presigned S3 URL and a bucket-notification-triggered worker parses
asynchronously — buying raw-file durability, decoupling from HTTP timeouts
and app memory, replayability, and no API-tier load spikes. The direct
endpoint was chosen here because the described volume (a handful of small
CSVs per day) doesn't need it, the assignment values a locally-runnable
system (an S3 flow would spend the time budget on localstack instead of
order logic), and the pipeline is an adapter — swapping the transport
later would not touch parsing, validation, or order handling.

### Webhook stream (webhook_orders.jsonl)

**Intake: ack fast, process async.** `POST /v1/webhook` accepts one order
payload and replies 200 immediately — the request path does no parsing or
validation at all; it persists the raw body verbatim into the
`webhook_queue` table and returns. This matches webhook semantics under
bursty traffic: senders time out and re-deliver quickly, so the receiver's
job is durable capture, not synchronous processing.

`webhook_queue` columns: id, `created_on`, `payload` (the raw JSON),
`status`, `retry_count`, `error` — indexed on (status, created_on) to
match the consumer's scan. This table is the demo's stand-in for a real
queue with retries (see the table-as-queue discussion in the polling
section — the same trade).

**Async consumer** (`processWebHookRequests`, every
`webhook.poll-interval` = 10s): reads entries with status `RECEIVED` or
`ERROR` and `retry_count` below `webhook.max-retries` (5), oldest first,
parses each payload, and populates orders/items/history — all inserts for
one payload in a single transaction (`WebhookOrderProcessor`), so a
failure can never leave a partial order. Failure taxonomy:

- *Terminal — `PROCESSING_FAILURE`, never retried:* an entry older than
  `dispatch.immediate-cancel-after` (30m) has missed its usefulness window
  (these are immediate orders — the customer isn't waiting anymore); an
  unparseable payload can't be fixed by retrying. The reason is recorded
  in the entry's `error` column and WARN-logged.
- *Transient — `ERROR`, retried:* anything else (e.g. a DB hiccup)
  increments `retry_count` and the entry is picked up again next tick
  until the max; exhausted entries simply stop being selected.

**Idempotency and updates.** The webhook `order_id` is stored as
`orders.external_order_id` (nullable, unique-indexed) and is how repeat
records are interpreted. A record reusing a known order_id **without** an
update flag is a *content update* — the customer changed their order —
applied only while the order is still `CREATED`: fields and items are
replaced, `version` bumps, and a history snapshot is appended. A record
with `update: ["cancelled"]` marks the order `CANCELLED` the same way. A
dispatched order (fully or partially) can no longer be changed at all —
updates and cancellations alike are refused up front with a WARN log,
since the robot is already making the food. The unique index is the
concurrency backstop: two racing deliveries of the same new order cannot
both insert; the loser's transaction fails and its retry takes the update
path.

### Polling API (api_responses.jsonl)

End-to-end flow: `PollingApiPoller` (one instance, ShedLock) → validate
response, enqueue it as ONE `api_order_queue` row (raw body) → advance the
persisted `time_since` cursor → async queue consumer parses the body,
groups items by order, and applies each order via `ApiOrderProcessor` (one
transaction + row lock per order, entry-level retries on failure) →
orders/items/history updated → item `source_status` decides what the robot
dispatch may include.

**Poller.** `PollingApiPoller` calls the external orders API
(`polling.endpoint`) every `polling.poll-interval` (10s) with a
`time_since` query parameter, under ShedLock (`pollApiOrders`) so only one
instance polls at a time. The `time_since` cursor is **persisted in the
single-row `api_polling` table** (`last_polled`, millis since epoch) —
shared by all instances and restart-safe; an empty table means "never
polled" and the poller starts from the epoch. A `"response": 500` body
(the sample stream contains several), an HTTP failure, or an unparseable
response is logged as an error and leaves the cursor in place, so the same
window is re-polled — safe, because applying a delta twice is a no-op.

**Delta shape and the intake queue.** Each response's `data` is a flat map
keyed by the source's *item id* (a 64-char hex hash —
`order_items.external_item_id` is sized at 128 chars for it); the item
value carries the source's numeric order id, stored as a string in
`orders.external_order_id` (the column is a string precisely so webhook
UUIDs and API numerics share it). A usable (200, parseable) response is
enqueued as **one `api_order_queue` row holding the raw body** — the
polling twin of `webhook_queue`, and deliberately a *single* atomic
insert: one row per order would risk a half-enqueued response (some saves
succeed, one fails, the cursor holds, the window re-polls, and the
succeeded orders get enqueued twice). The cursor advances only after that
row is durably saved; an individual order failing later does NOT hold the
cursor hostage.

**Queue consumer.** The consumer (`polling.queue-poll-interval`) parses
each stored body, groups its entries by order (a response routinely mixes
items of up to 30 orders in the sample), and applies one order per
transaction. On partial failure the entry goes to ERROR **with its payload
rewritten to only the failed orders' items** — the consumer knows exactly
which orders failed, so a retry (up to `polling.max-retries`) replays
nothing that already succeeded. Per-order idempotency remains the backstop
for the one window this can't cover (a crash after applying some orders
but before the shrunken entry is saved — the full body is retried, and the
already-applied orders no-op). An unparseable stored body is terminal
(PROCESSING_FAILURE). None of this partial-failure machinery exists on the
webhook queue, deliberately: a webhook entry holds exactly one order, so
entry granularity and processing granularity coincide there. The polling
pipeline's unit of arrival (a multi-order response) differs from its unit
of work (an order) — the shrinking reconciles the two. No expiry deadline
on this queue: unlike webhook orders, these deltas carry status updates
for orders already in flight, which stay worth applying late.

**Table-as-queue vs. a real queue.** `api_order_queue` (like
`webhook_queue`) is a table playing the role a message queue (SQS/
RabbitMQ) would play in production. Every queue concept maps onto columns
and a scheduled scan:

| queue concept        | real queue (e.g. SQS)              | our table                                  |
|----------------------|------------------------------------|--------------------------------------------|
| enqueue              | publish message                    | INSERT row (atomic, and can join a DB tx)  |
| deliver              | broker pushes / consumer long-poll | scheduler scans `status IN (RECEIVED, ERROR) AND retry_count < max`, oldest first (backed by the (status, created_on) index) |
| retry                | visibility timeout re-delivers     | failed entry flips to ERROR with `retry_count + 1` — the row carries the retry state — and the next scan picks it up |
| give up / dead-letter| maxReceiveCount → DLQ              | `retry_count` reaches the max (entry stops matching the scan) or PROCESSING_FAILURE for unfixable payloads; rows stay queryable for triage |
| acknowledge          | delete message                     | status → PROCESSED (row is kept)           |

What the table buys at prototype scale: zero infrastructure, enqueue
transactional with the rest of our state, SQL-inspectable (a "DLQ" is just
`WHERE status = 'PROCESSING_FAILURE'`). What it costs, and why production
would switch: retry cadence is the scan interval (no per-message backoff —
a `next_attempt_at` column could add it), delivery latency is bounded by
the poll interval, the scan loads the DB, and PROCESSED rows accumulate
until pruned. The swap is confined to the edges — parsing, grouping, and
`ApiOrderProcessor` are untouched.

**Per-order processing (`ApiOrderProcessor`).** Same concurrency contract
as the webhook consumer (see Concurrency below): the order row is read
through a pessimistic `FOR UPDATE` lock, so the robot dispatcher can't
pick the order up mid-update. An unseen order id creates the order
(`API_PULL`, `CREATED`); item entries upsert by external item id — new
hashes become `order_items` rows (name, per-item price, source status),
known hashes get their `source_status` advanced. When anything actually
changed, the order's items text and `amount` (sum of non-cancelled item
prices) are recomputed, the version bumps, and a history snapshot is
appended; replaying an already-applied delta changes nothing and appends
no history.

**Item-level source status is the source's, and only the source's.**
`order_items.source_status` holds the API's own item lifecycle (ordered →
processing → with_courier → delivered → cancelled) verbatim; cancellation
is an order-level status (`orders.order_status = CANCELLED`), and an
item-level cancellation is simply `source_status = cancelled`. CSV/webhook
items have no source status (null), which is fine. Our own per-item
`status` is deliberately minimal — two states (`CREATED`/`DISPATCHED`, see
Robot dispatch) recording only whether WE sent the item to the robot. A
`cancelled` delta for an item is honored only while the item is still
`ordered` (or has no source status): it hasn't been handed to the robot
yet. Cancelling an item that's already `processing` or beyond is too late
— the cancellation is refused and all we can do is WARN-log the failure.

## Order model & lifecycle

**Line items: the `order_items` table.** Each order's dishes are
normalized into `order_items` rows (id UUID, order_id, item_name,
item_price, the source's `external_item_id`/`source_status` for polling
items, and our two-state `status`). This mirrors the granularity the
sources actually have (see the Overview table). The raw items text is also
retained on the order row so history snapshots stay self-contained.

**Order provenance: `order_type`.** Every order (and history snapshot)
records which pipeline it arrived through: `SVC_FILE`, `WEBHOOK`, or
`API_PULL`. The three sources have disjoint identity schemes and never
reference each other, so provenance is pure metadata after normalization —
but it's what per-pipeline dedup keys and the source-specific fields
(`order_source`, `restaurant`, `amount` — webhook; meal/tomorrow — CSV)
hang off.

**Statuses.** `order_status` tracks the dispatch lifecycle — `CREATED` →
`DISPATCHED` / `PARTIALLY_DISPATCHED`, or terminally `UNFULFILLED`,
`CANCELLED`, `ERROR`, `DROPPED` — separate from the upload job's
processing status. `dispatch_time` stays null until the order is actually
sent to the robot. Orders may be changed up until dispatch — a change
(say breakfast → dinner) recomputes the dispatch window, which is why the
window is stored per order rather than derived on the fly, and why it is
snapshotted into history.

**Dispatch scheduling: meal windows (CSV orders).** `tomorrow` + `meal`
determine *when* a CSV order should be dispatched:

| meal      | dispatch window       |
|-----------|-----------------------|
| breakfast | 7:00 – 9:00 Pacific   |
| lunch     | 11:30 – 13:30 Pacific |
| dinner    | 17:30 – 19:30 Pacific |

The windows are configured under `dispatch.windows` in `application.yaml`,
not hardcoded, as wall-clock times tagged with a zone ("07:00 PST" — short
ids map to real zones, so Pacific DST applies; full ids like
America/New_York work; no zone means UTC; bad config fails at startup). At
ingestion each order gets `dispatch_time_interval_start/end` computed as
UTC instants (`DispatchWindowCalculator`) — "today" and the bounds are
resolved in the window's own zone, so 7am Pacific is 15:00Z in January and
14:00Z in July; `tomorrow = true` schedules the next day's window. A same-day order
arriving after its window has ended is past cutoff: unschedulable, saved
`UNFULFILLED` with the reason in `error`. An order arriving mid-window is
still schedulable ("process as many as we can" until the window closes).

**Immediate orders (webhook / polling API).** These carry no meal/tomorrow
— they dispatch as soon as possible. How long may one wait for robot
capacity before we give up? `dispatch.immediate-cancel-after` (default
**30 minutes**): an immediate order not dispatched within that long of its
last update stops being eligible.

**Overload rejection at intake (`DROPPED`).** Both immediate pipelines
reject up front rather than accept-then-cancel — a rejection now is a
better customer experience than a cancelled order 30 minutes later.
`DispatchBacklogEstimator` counts the orders currently competing for robot
slots (`countDispatchable`, the exact dispatch-pickup predicate) and
compares it to what the robot can drain within the cancel horizon
(`max-per-minute × immediate-cancel-after`, 3000 at defaults). At or past
that, a new webhook or polling-API order would expire in the queue before
its turn, so it is saved immediately with `order_status = DROPPED` and
`error = "system overload"` (WARN-logged, never queued for dispatch).
DROPPED is a dedicated status — distinct from UNFULFILLED — precisely so
overload rejections are directly countable in metrics. The check runs only
at order creation; updates to existing orders and CSV orders (which wait
for meal windows anyway) are not subject to it.

**Oversized orders are rejected, not truncated into a dispatch.** All
three pipelines validate item lengths (`OrderLengthValidator`): an order
whose combined item list exceeds 4096 characters, or containing any single
item name over 1024 characters, is saved for visibility but marked
`order_status = ERROR` with `error = "order length exceeds maximum limit"`
— never sent to the robot, since a truncated or enormous item list could
confuse it. The polling pipeline re-checks on every delta (deltas grow an
order's item list over time); the webhook pipeline re-checks on content
updates. Separately, the stored `orders.items`/`orders.notes` display
strings (varchar 4096) and `order_items.item_name` (varchar 1024) truncate
silently at the column limit as a storage guard — `order_items` remains
the source of truth for dispatch.

**VIP orders don't wait.** `orders.vip` (default false, mirrored into
history) makes an order due IMMEDIATELY — overriding the CSV meal window
and the immediate-order freshness horizon — and puts it ahead of every
non-VIP order in the dispatch pickup. The flag is set via
`PATCH /v1/orders/{id} {"vip": true|false}`, allowed only while the order
is still CREATED (row-locked against the dispatcher, version bump +
history snapshot on change) — the escape hatch for an order that must not
wait, whether behind a busy queue or a future meal window.

### Concurrency: locks and versions

*Update/dispatch race:* all order writers read the order through a
pessimistic row lock (`SELECT … FOR UPDATE`) — the webhook updater and
`ApiOrderProcessor` via `findByExternalOrderIdForUpdate`, the dispatcher
and VIP patch via `findByIdForUpdate` — so an order mid-update cannot
simultaneously be picked up for dispatch and vice versa; the lock loser
sees the winner's committed state (the dispatcher sends the *updated*
content, or the updater sees a dispatched status and refuses).

*Duplicate robot submissions — optimistic locking as the backstop.* The
order's `version` column is Hibernate-managed (`@Version`): every UPDATE
carries `WHERE version = <value read>` and increments it, so a lost update
fails at commit with `OptimisticLockingFailureException` instead of
silently overwriting. For dispatch this means two concurrent attempts to
transition the same order cannot both commit — the order is sent to the
robot at most once per CREATED lifecycle. The two mechanisms are layered
deliberately: pessimistic locks *prevent* conflicts on the known write
paths (no retries in the common case), while `@Version` *detects* them on
every entity write, catching any future code path that forgets the locking
read. In normal operation the tripwire never fires.

*Residual gap, out of DB scope:* the robot call itself isn't transactional
with the DB — a crash after calling the robot but before commit would
revert the order to CREATED and allow a re-send. The fix when a real robot
API exists is an idempotency key on the request (our order UUID already
serves), letting the robot dedupe.

## Robot dispatch

`RobotDispatcher.dispatch(orderId)` hands one order to the robot for
assembly. The robot integration is a skeleton for now: the request payload
is generated and logged (an audit trail of exactly what was sent until a
real robot API exists), and the order is transitioned in the DB.

```json
{"orderId": "d5e13e9e-a0d3-4cae-8a63-0ba6c4c9ad7f",
 "items": [
   {"itemId": "0b6f0f39-3f24-4e5f-9c3a-9f6d2a1c8e11", "itemName": "burger"},
   {"itemId": "7c1d2b58-6a90-4d02-b1de-44f0c2a97b3d", "itemName": "fries"}]}
```

**The payload uses OUR ids, not the source's.** `orderId` is our `orders`
id and each item's `itemId` is our `order_items` id — never the source's
`external_item_id`, which is null for CSV/webhook items and so can't key
every item. Every id in the payload maps straight back to one of our rows,
so when the robot later reports per-item information (progress, failures,
completion), it can key its responses by `itemId` and we can correlate them
without any translation step.

**Rules.**

- **Only `CREATED` orders may be dispatched.** Any other status is a
  validation error — dispatch throws and changes nothing. This is also
  what makes "orders may be changed up until dispatch" safe.
- **The payload includes only items the robot should make**: items with no
  source status (CSV/webhook) or source status `ordered`. A
  source-cancelled item must not be made, and anything further along is
  already being handled by the source's own flow.
- **An empty payload is never sent.** An order with nothing left to make
  is closed instead — `CANCELLED` when the source cancelled every item,
  otherwise `UNFULFILLED` with the reason (e.g. "all items already handled
  by the source at dispatch time") — and does not consume rate-limit
  budget.
- **A partial send is a distinct order status.** When the payload includes
  some but not all items, the order lands in `PARTIALLY_DISPATCHED`
  instead of `DISPATCHED`, so partially-made orders are directly
  searchable (`/v1/orders?status=PARTIALLY_DISPATCHED`). Both are equally
  final: a dispatched order can no longer be changed.
- On dispatch the order gets `dispatch_time` stamped, `updated_at`
  refreshed, its `version` bumped, and a history snapshot appended — the
  dispatch event is visible in history like any other change.

**What was sent is recorded on the items themselves.** Each `order_items`
row carries our two-state `status` (`ItemStatus`): `CREATED` as ingested,
flipped to `DISPATCHED` inside the dispatch transaction for exactly the
items included in the payload — flipped BEFORE the history snapshot, so
the dispatch version's `item_history` rows record the split. The source
keeps advancing `source_status` afterwards, but our flag doesn't move: an
item still `CREATED` on a dispatched order was deliberately not sent. The
UI marks items "sent to robot" / "not sent" from this flag on both the
details card and the history view.

**Dispatch loop and rate limiting.** A scheduled loop
(`dispatch.poll-interval`, 10s — kept short so the last tick of each
minute lands near the minute boundary, wasting less of the rate budget)
picks up due orders and dispatches them, capped at
`dispatch.max-per-minute` (100 TPM) so the robot is never flooded.

- *Sliding-window rate limiting, tracked in a DB table.* The
  `orders_processed` table (`time` in `yyyyMMddHHmm` UTC, `count`) keeps
  per-minute counters; every dispatch increments the current minute. Each
  tick computes the effective rate as the current minute's count plus the
  previous minute's count weighted by how much of the trailing 60s window
  it still covers ((60 − seconds) / 60). A fixed bucket alone would allow
  2× the limit across a minute boundary; the sliding window smooths that.
  *In real life this would be Redis* (atomic `INCR` + `EXPIRE`): cheaper,
  naturally shared, self-cleaning — the DB stands in for the same
  zero-infrastructure reason as the job queue.
- *At the limit:* dispatching stops for the tick with a WARN; later ticks
  pick up the waiting orders. Window counts vs. queue depth also feed the
  intake overload rejection (`DROPPED`, see the lifecycle section).
- *Pickup order and eligibility.* VIP orders first, then oldest
  (`ORDER BY vip DESC, created_at`, backed by the matching index). A CSV
  order is eligible only while inside its stored dispatch window; an
  immediate order while fresh (last updated within
  `dispatch.immediate-cancel-after` — an order change resets its clock,
  consistent with "changes allowed until dispatch"); a VIP order is due
  immediately regardless of either.
- *Stale orders are never sent — and get closed by the freshness sweep.*
  An order the dispatcher didn't get to in time — a CSV order whose window
  closed, or an immediate order that aged past the freshness horizon —
  permanently stops matching the pickup query. `FreshnessSweeper`
  (every `dispatch.sweep-interval`, 15m, under ShedLock) finds exactly
  those orders (`findStaleForUpdate`, the row-locked complement of the
  pickup predicate) and closes them as `UNFULFILLED` with
  `error = "not dispatched in time"`, WARN-logged and history-snapshotted
  like every other fulfillment failure — so they're countable instead of
  lingering as apparently-live CREATED rows. VIP orders are never swept
  (they're due immediately), and in the up-to-15-minute window before the
  sweep reaches an order, a source update (which resets the freshness
  clock) or a VIP promotion can still revive it.
- *Single dispatcher — by design.* Concurrent dispatchers would race on
  two independent pieces of state: the rate-limit counters (both see a
  full budget and together blow past the limit) and order selection (both
  submit the same order — the robot makes Alice's burger twice while the
  counters stay within budget). A single dispatcher eliminates both with
  one decision, and costs nothing since the limit is global — parallel
  dispatchers add no throughput. Enforced in code, not just deployment:
  the loop runs under **ShedLock** (`@SchedulerLock(name =
  "dispatchDueOrders")`, backed by the `shedlock` table from `schema.sql`);
  each tick, whichever instance acquires the lock runs and the others skip.
  `lockAtMostFor = 5m` bounds how long a crashed holder can stall
  dispatching, and the provider uses DB time so instances don't need
  synchronized clocks. If a single dispatcher ever became the bottleneck,
  parallel dispatch requires pairing (1) atomic budget reservation (Redis
  Lua, or `UPDATE … SET count = count + 1 WHERE count < :allowed`) with
  (2) an atomic per-order claim (`FOR UPDATE SKIP LOCKED` pickup, or a
  compare-and-swap status flip). Both are required — not worth the moving
  parts while one dispatcher trivially saturates 100 TPM.

## Order history & read APIs

The write side is the system-wide snapshot convention (see above): every
order write appends an `order_history` + `item_history` snapshot set under
the order's version, via `HistoryRecorder`. The read side (full parameter
details in the API reference):

- `GET /v1/orders/{orderId}` — current order paired with its current
  items (including each item's source status and sent-to-robot flag).
- `GET /v1/orders/{orderId}/history` — the full event log: order snapshots
  oldest-version-first, each paired with its item snapshots. This is where
  a stuck order explains itself — e.g. an UNFULFILLED polling order shows
  its items advancing at the source, version by version, while it waited
  for robot capacity.
- `GET /v1/orders` — order search by status, type, order/source id, and
  created/updated time, paginated.
- `GET /v1/orders-dispatched` — per-minute dispatch counts, the
  throughput-over-time complement to the per-order histories.
- `GET /v1/jobs`, `GET /v1/jobs/{jobId}` — upload-job listing and status.

## Demo mocks

All demo traffic mocking lives in `MockController` under `/mocks`, with
the sample data for all three pipelines in
`src/main/resources/order-sources/` (orders_1..4.csv,
webhook_orders.jsonl, api_responses.jsonl — loaded as Spring resources, so
config may point at classpath: or file: locations). One control endpoint
drives everything — the JSON body of `POST /mocks/controls` may combine
any of three options (400 when none is given; current state via GET on the
same path). The frontend's Admin screen is a UI over exactly these
controls:

- `"pollingApiEnabled": true|false` — whether the mock polling API
  (`GET /mocks/orders`, which `polling.endpoint` targets — the app polls
  itself on port 8081) serves data. It starts disabled, always answering
  an empty delta. Enabled, it replays `polling.mock-data-file` one line
  per poll — a request with a HIGHER time_since than the previous one
  advances to the next line, the SAME time_since returns the same line
  again (an unadvanced cursor re-reads the same window, mirroring real
  delta semantics). Scripted error (non-200) lines are the exception:
  served once, then a re-poll of the same window advances past them — a
  real API's transient 500s clear on retry, whereas replaying them
  deterministically would trap the poller (which correctly holds its
  cursor on an error) in an infinite loop. When the sample data runs out
  the mock logs that once and serves empty deltas from then on. Received
  poll responses are INFO-logged so data arrival is visible, behind
  `polling.log-responses` (disable in production for performance).
- `"sendWebhookTraffic": true` — fires every event of
  `webhook.mock-data-file` at the real intake endpoint
  (`webhook.endpoint`, this app's own `/v1/webhook`) as fast as a
  10-thread pool allows, blocking until done; the response carries
  `"webhookSent"`/`"webhookFailed"` counts — failed sends are counted and
  logged, not retried.
- `"uploadFile": "orders_1.csv"` — POSTs the named CSV from the
  order-sources folder to the real ingest endpoint (`ingest.endpoint`,
  this app's own `/v1/ingest`) as a multipart upload, exactly as a user
  would; the ingest response (job id, QUEUED status) is passed through
  under `"upload"`. Plain file names only — path separators and `..` are
  rejected.

## Frontend

**Order Console** — a React (Vite) app in `frontend/`, built straight into
`src/main/resources/static/`, so the Spring app serves the UI itself at
`http://localhost:8081/` with no second server and no CORS (hash-based
routing avoids any server-side fallback config). Dev mode: `npm run dev`
serves on :5173 and proxies `/v1` + `/mocks` to :8081.

Two screens:

- **Admin** (`#/`) — the demo driver: the three mock controls (polling
  replay on/off, the 1,004-event webhook burst, sample CSV upload with
  live job status) wired to `POST /mocks/controls`, next to a throughput
  chart of robot dispatches per minute for the last hour
  (`/v1/orders-dispatched`, polled every 10s). The chart zero-fills the
  minutes the sparse API omits, draws a dashed reference line at the
  100/min limit, dims the still-accumulating current minute, and pairs
  with a last-full-minute stat tile. Hand-rolled SVG — no chart library.
- **Orders** (`#/orders`) — the order rail: paginated newest-first list
  over `/v1/orders` with id search (order or source id), status and type
  filters, and a created/updated at-or-after UTC time filter — all kept in
  the URL, so drill-in and back preserves the view and filtered views are
  shareable. Waiting CSV orders show their meal window on the rail ("waits
  for lunch window · 11:30–13:30") so they don't look stuck. Each row
  drills into `#/orders/{id}`: full ids, details, items with source
  statuses and sent-to-robot marks, the complete version history with
  per-version item states, and the VIP queue-jump button (PATCH) while the
  order is still CREATED.

Only dependencies: react, react-dom, react-router-dom. Visual language is
the subject's own: ticket-paper surfaces, ink text, monospace for data,
one teal accent for actions/marks (CVD-validated against the surface),
brass reserved for VIP, and labeled status chips (never color alone). All
displayed times are UTC, matching the backend convention — a prototype
shortcut: in production each user would have a default timezone and every
displayed time would be converted to it at the display layer (see the time
conventions section), with storage staying UTC.

## Security — not implemented (prototype gap)

**There is no authentication or authorization anywhere in this
prototype.** Every API and both UI screens are open to anyone who can
reach the port. A real deployment needs authn (who is calling) and authz
(what they may do) before any of this faces users; concretely:

- **Order visibility must be scoped to the caller.** `GET /v1/orders`,
  `/v1/orders/{id}`, and `/v1/orders/{id}/history` return any order to
  anyone. Orders would need an owner (derived from the authenticated
  principal at intake) and every read filtered by it, so users can only
  look at their own orders.
- **The admin surface must be operator-only.** The Admin screen and
  everything it drives — `/mocks/*`, the dispatch metrics, the jobs
  listing — are operational tooling and need an operator/admin role.
- **Queue position must not be user-controlled.** `PATCH /v1/orders/{id}
  {"vip": true}` makes an order dispatch immediately; left open, any
  customer could promote their own order (or demote someone else's). VIP
  changes belong to an operator role (or a trusted internal caller), never
  the order's owner by right.
- Supporting cast for production: the H2 console must be disabled, and the
  webhook/ingest intake endpoints need caller verification of their own
  (e.g. webhook signatures, upload authentication) rather than accepting
  anonymous traffic.

## API reference

All times are UTC; timestamp parameters are millis since epoch unless
noted. Ids in paths are UUIDs (malformed ids → 400, unknown ids → 404).

### Order intake & management (`/v1`)

| Method | Path | Parameters | Description |
|--------|------|------------|-------------|
| POST | `/v1/ingest` | multipart `file` (a .csv) | Queues a CSV upload for async processing. `202` with `{jobId, status, links.self}`; `400` for a missing/non-CSV file. |
| GET | `/v1/jobs` | `createdAfter` (optional, millis, "created at or after") | All upload jobs, newest first. |
| GET | `/v1/jobs/{jobId}` | — | One upload job's processing status (QUEUED/RUNNING/DONE/FAILED, attempts, byte_offset, error). `404` if unknown. |
| POST | `/v1/webhook` | JSON body: one webhook order event (see webhook pipeline section) | Fast-ack intake: persists the raw payload to webhook_queue and returns `200`; conversion to orders is async. Re-POST with a known `order_id` updates the order; `"update": ["cancelled"]` cancels it. |
| GET | `/v1/orders` | `status` (optional, case-insensitive `OrderStatus` name; unknown → `400`); `type` (optional, case-insensitive `OrderType` name — SVC_FILE / WEBHOOK / API_PULL; unknown → `400`); `orderId` (optional, exact UUID match; malformed → `400`); `sourceOrderId` (optional, exact match on the source system's id); `createdTime` OR `updatedTime` (optional, millis, "at or after" — both together → `400`); `page` (default 0); `size` (default 20, max 200) | Order search, newest created first. Both id filters are index-backed (PK / the unique `external_order_id` index). Returns `{content: [...], page: {size, number, totalElements, totalPages}}`. |
| GET | `/v1/orders/{orderId}` | — | Order details: `{order, items}` — the order plus its individual items with source statuses and our two-state item `status` (`DISPATCHED` = included in the robot payload). `404` if unknown. |
| PATCH | `/v1/orders/{orderId}` | JSON body `{"vip": true\|false}` (required → else `400`) | Flips the VIP flag (dispatch immediately, ahead of the queue). Only while CREATED (`409` otherwise); no-op changes don't bump the version. Returns the updated `{order, items}`. |
| GET | `/v1/orders/{orderId}/history` | — | The order's history, oldest version first: `[{order: snapshot, items: [item states at that version]}, …]`. `404` if unknown. |
| GET | `/v1/orders-dispatched` | `from`, `to` (optional, `yyyyMMddHHmm` UTC minutes, inclusive; malformed or `from > to` → `400`; both default to the last hour) | Robot dispatches per minute: `[{minute, count}, …]`, oldest first. Sparse — minutes without dispatches are omitted. |

### Demo mocks (`/mocks`)

| Method | Path | Parameters | Description |
|--------|------|------------|-------------|
| POST | `/mocks/controls` | JSON body, any combination of: `pollingApiEnabled` (bool), `sendWebhookTraffic` (bool), `uploadFile` (order-sources CSV name) — none → `400` | One control endpoint for all traffic mocks (see the Demo mocks section). Response echoes the current `pollingApiEnabled` plus each action's result (`webhookSent`/`webhookFailed`, `upload`). |
| GET | `/mocks/controls` | — | Current mock state: `{pollingApiEnabled}`. |
| GET | `/mocks/orders` | `time_since` (millis; the poller's cursor) | The in-app mock polling API the poller targets; replays sample data one line per poll (delta semantics in the Demo mocks section). |
