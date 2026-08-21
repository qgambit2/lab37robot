# Order Ingestion & Management System

Ingests food orders from three pipelines (CSV uploads, webhook stream,
polling API), tracks them through their lifecycle, and dispatches them to a
robot for assembly. Design decisions and architecture: see
[DESIGN.md](DESIGN.md). AI usage log: [AI_LOG.md](AI_LOG.md).

## Prerequisites

- Java 21 (everything else is self-contained: embedded H2 database, Maven
  wrapper included, UI pre-built — no external services needed)

## Run

```bash
./mvnw spring-boot:run
```

Then open the **Order Console** at **http://localhost:8081/**:

- **Admin** — start the sample traffic (polling replay, webhook burst, CSV
  upload) with one click each, and watch robot throughput live.
- **Orders** — every order, searchable and filterable, with drill-in to an
  order's items and full version history.

The app is self-explanatory from there. Terminal workflows (curl, mock
controls, direct order injection, DB inspection), frontend development, and
running on a shared PostgreSQL are covered in
[AdvancedUsage.md](AdvancedUsage.md).

## Run the tests

```bash
./mvnw test
```
