# Advanced Usage

Everything here is optional — the app starts and works with just the
[README](README.md) steps, and the Order Console UI covers day-to-day use.
This page is for driving or inspecting the system from the terminal, working
on the frontend, and non-default deployments.

## Frontend development

The built UI ships in `src/main/resources/static/` and is served by the app
itself. To rebuild after changing the frontend source in `frontend/`:

```bash
cd frontend && npm install && npm run build   # emits into src/main/resources/static
```

For frontend development with hot reload (proxies API calls to :8081):

```bash
cd frontend && npm run dev   # UI on http://localhost:5173
```

## Mocking from the terminal

The Admin screen drives all of this with buttons; the same controls are
plain HTTP. All demo traffic mocking lives in `MockController` under
`/mocks`, and the sample data for all three pipelines ships inside the app
at `src/main/resources/order-sources/`:

| pipeline    | sample data          | mocked by                       |
|-------------|----------------------|---------------------------------|
| polling API | api_responses.jsonl  | `{"pollingApiEnabled": true}`   |
| webhook     | webhook_orders.jsonl | `{"sendWebhookTraffic": true}`  |
| CSV upload  | orders_1..4.csv      | `{"uploadFile": "orders_1.csv"}`|

Everything is driven through one control endpoint, `POST /mocks/controls`.
Its JSON body may combine any of the three options above (a body with none
of them is a 400); the response always echoes the current
`pollingApiEnabled` state plus the result of each action that ran.
`GET /mocks/controls` returns the current state:

```bash
curl http://localhost:8081/mocks/controls
# → {"pollingApiEnabled": false}
```

**Polling API mock** — an in-app stand-in for the external orders API the
poller hits with `?time_since=` every 10s. Disabled (the initial state) it
always answers an empty delta; enable it with:

```bash
curl -X POST http://localhost:8081/mocks/controls \
  -H 'Content-Type: application/json' -d '{"pollingApiEnabled": true}'
```

Enabled, it replays api_responses.jsonl **one line per poll**: a request
with a higher `time_since` than the previous one advances to the next
line, the same `time_since` re-serves the same line (an unadvanced cursor
re-reads the same window, like a real delta API). Scripted error (non-200)
lines are served once and then skipped on the retry — a real API's
transient 500s clear on retry. When the file is exhausted the mock logs
that once and serves empty deltas from then on. Watch the log for
`Polling API response …` lines followed by orders being created
(`polling.log-responses` toggles the response logging). Send
`{"pollingApiEnabled": false}` to pause mid-replay; re-enabling resumes
where it stopped.

**Webhook traffic mock** — replays all 1004 webhook_orders.jsonl events
against the real intake endpoint (`/v1/webhook`) as fast as a 10-thread
pool allows, blocking until every event has been attempted:

```bash
curl -X POST http://localhost:8081/mocks/controls \
  -H 'Content-Type: application/json' -d '{"sendWebhookTraffic": true}'
# → {"pollingApiEnabled": …, "webhookSent": 1004, "webhookFailed": 0}
```

Failed sends are counted and logged, not retried.

**CSV upload mock** — posts one of the sample CSVs from order-sources to
the real ingest endpoint (`/v1/ingest`) as a multipart upload, exactly as
a user would; the ingest response is passed through under `"upload"`:

```bash
curl -X POST http://localhost:8081/mocks/controls \
  -H 'Content-Type: application/json' -d '{"uploadFile": "orders_1.csv"}'
# → {"pollingApiEnabled": …, "upload": {"jobId": "…", "status": "QUEUED", …}}
```

Plain file names only (`orders_1.csv` … `orders_4.csv`); unknown names and
paths are rejected with a 400.

## Inject orders directly

The real intake endpoints also accept traffic without the mocks:

**CSV upload**:

```bash
curl -F file=@src/main/resources/order-sources/orders_1.csv http://localhost:8081/v1/ingest
# → {"jobId":"…","status":"QUEUED","links":{"self":"/v1/jobs/{jobId}"}}

curl http://localhost:8081/v1/jobs/{jobId}   # processing status of the upload
```

**Webhook order** (delivery-aggregator style — any line of
webhook_orders.jsonl is a valid body):

```bash
curl -X POST http://localhost:8081/v1/webhook \
  -H 'Content-Type: application/json' \
  -d '{"order_id": "c59a0083-581d-4eb9-946e-98b32890be3a", "order_source": "Overeats",
       "restaurant": "Sam & Ella'\''s", "first_name": "Laura", "last_name": "Kinney",
       "total": 144.16, "items": ["Lemon meringue pie", "Chicken fried steak"], "notes": ""}'
```

Re-POST the same `order_id` with changed fields to update the order (allowed
until it is dispatched), or add `"update": ["cancelled"]` to cancel it.

## Inspect state from the terminal

- **Read API** (all times UTC; time params are millis since epoch — full
  parameter reference in [DESIGN.md](DESIGN.md)):

  ```bash
  # one order with its individual items and their source statuses (404 if unknown)
  curl http://localhost:8081/v1/orders/{orderId}
  # → {"order": {...}, "items": [{"itemName": …, "sourceStatus": …, "itemPrice": …}, …]}

  # its history, oldest version first — each snapshot paired with the item
  # states recorded at that version
  curl http://localhost:8081/v1/orders/{orderId}/history
  # → [{"order": {...v1...}, "items": [...]}, {"order": {...v2...}, "items": [...]}, …]

  # order search: all filters optional — status, type, orderId/sourceOrderId,
  # plus createdTime OR updatedTime (millis, "at or after"; both together is
  # a 400). Paginated (page/size, default 20, max 200), newest created first.
  curl 'http://localhost:8081/v1/orders?status=DISPATCHED&createdTime=1787265000000&page=0&size=20'
  # → {"content": [...], "page": {"size": 20, "number": 0, "totalElements": …, "totalPages": …}}

  # robot dispatches per minute (yyyyMMddHHmm UTC; defaults to the last hour)
  curl 'http://localhost:8081/v1/orders-dispatched?from=202608210000&to=202608210059'

  # all upload jobs, newest first; createdAfter (millis) narrows the listing
  curl 'http://localhost:8081/v1/jobs?createdAfter=1787265000000'

  # make an order VIP — it dispatches immediately, ahead of all non-VIP
  # orders (only while CREATED: 409 otherwise; 404 if unknown)
  curl -X PATCH http://localhost:8081/v1/orders/{orderId} \
    -H 'Content-Type: application/json' -d '{"vip": true}'
  ```

- **H2 console**: http://localhost:8081/h2-console — JDBC URL
  `jdbc:h2:mem:orders`, user `sa`, empty password. Tables: `orders`,
  `order_items`, `order_history`, `item_history`, `upload_jobs`,
  `upload_files`, `webhook_queue`, `api_order_queue`, `orders_processed`,
  `api_polling`, `shedlock`.
- **Logs** (stdout, timestamped): robot dispatches are logged as
  `Dispatching order to robot: {"orderId": …, "items": […]}`; ingestion
  problems are WARNs, processing failures are ERRORs.

## Configuration

Everything lives in `src/main/resources/application.yaml`: meal dispatch
windows, robot rate limit (`dispatch.max-per-minute`), worker intervals,
retry limits, the polling endpoint.

## Optional: multiple instances on a shared PostgreSQL

Only for running several app instances against one database — a single
instance should use the default H2 run. Requires a PostgreSQL reachable at
`localhost:5432` with database/user/password `orders` (adjust
`application-postgres.yaml` to match yours), e.g.:

```bash
docker run --name lab37-postgres -p 5432:5432 \
  -e POSTGRES_DB=orders -e POSTGRES_USER=orders -e POSTGRES_PASSWORD=orders \
  -d postgres:17

./mvnw spring-boot:run -Dspring-boot.run.profiles=postgres
```

Do **not** activate the `postgres` Spring profile for a normal run — it
expects that PostgreSQL and the app will fail at startup with
"Connection … refused" if none is running. (If you see that error, a run
configuration or `SPRING_PROFILES_ACTIVE` environment variable is
activating the profile — clear it and run the plain command from the
README.)
