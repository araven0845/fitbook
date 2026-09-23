# FitBook — CMPE 172 Term Project (Milestone 1)

Online Appointment Scheduling System, fitness-studio scenario: members book
sessions with trainers for a given service (personal training, HIIT, yoga,
mobility). Individually built for SJSU CMPE 172 — Enterprise Software
Platforms.

## What Milestone 1 implements

- **Layered architecture**: `controller` → `service` → `repository` → JDBC,
  no ORM anywhere (hand-written SQL, `JdbcTemplate` only as a thin JDBC
  helper — no entity mapping, no query generation, no lazy loading).
- **Database**: `schema.sql` creates all 5 entities (`users`, `providers`,
  `services`, `availability_slots`, `appointments`) with PK/FK constraints
  and the double-booking guard (a partial unique index on
  `appointments(slot_id) WHERE status = 'CONFIRMED'`, paired with a
  `version` column on `availability_slots` for optimistic locking).
  `seed.sql` loads sample trainers, services, and slots. Both run
  automatically on every startup.
- **Two working endpoints that read from the DB and return DTOs**:
  - `GET /` — Page Controller (`HomeController`), renders a Thymeleaf view
    listing trainers and services.
  - `GET /slots` — REST endpoint (`SlotApiController`), returns a paginated,
    filterable JSON list of open `AvailabilitySlotDTO`s (`providerId`,
    `serviceId`, `date`, `page`, `size` query params; pagination via SQL
    `LIMIT`/`OFFSET` in `JdbcSlotRepository`).
  - `GET /book` and `GET /confirmation` are static wireframe stubs (no
    backing logic yet).
- **Strict DTOs**: controllers only ever see `dto/*` records, never raw
  database rows.

## Tech stack

Java 21, Spring Boot 3.3.4, Spring JDBC (`JdbcTemplate`), Thymeleaf,
SQLite via `org.xerial:sqlite-jdbc`, Maven.

SQLite was chosen for Milestone 1 specifically so the grader can clone the
repo and run it with nothing else installed (no DB server, no Docker
required yet). Every repository method is hand-written SQL scoped to one
table/join, so swapping the datasource to MySQL or PostgreSQL later is a
config change (`spring.datasource.url` + driver), not a code rewrite.

## Prerequisites

- JDK 21
- Maven 3.9+ (or use the included `mvnw` if you add one)

No database server, Docker, or other services are required for this
milestone.

## Build & run

```bash
mvn clean package
mvn spring-boot:run
# or: java -jar target/fitbook.jar
```

The app starts on **http://localhost:8080**. On every startup it drops and
recreates all tables from `schema.sql`, then reloads `seed.sql`, so you
always start from the same known dataset.

Try it:

```bash
curl http://localhost:8080/               # HTML home page
curl http://localhost:8080/slots           # JSON: paginated open slots
curl "http://localhost:8080/slots?serviceId=1&page=0&size=5"
```

## Configuration (environment variables)

| Variable         | Default          | Purpose                                   |
|-------------------|------------------|--------------------------------------------|
| `SERVER_PORT`      | `8080`           | HTTP port                                  |
| `FITBOOK_DB_PATH`  | `./fitbook.db`   | Path to the SQLite database file           |

No secrets are required or committed for this milestone.

## Project layout

```
src/main/java/com/fitbook/
  controller/   HomeController (Page Controller), SlotApiController (REST)
  service/      CatalogService, SlotService
  repository/   Jdbc*Repository — all hand-written SQL
  dto/          Strict DTOs returned to controllers
src/main/resources/
  schema.sql    All 5 tables + double-booking guard
  seed.sql      Sample trainers, services, slots
  templates/    Thymeleaf views (home, book stub, confirmation stub)
docs/
  er-diagram.png, er-diagram.mmd         ER diagram (Mermaid source + render)
  block-diagram.png, block-diagram.mmd   System block diagram
  wireframes/                            4 wireframes + combined wireframes.pdf
  milestone1-report-outline.md           Outline for the Milestone 1 report
```

## Request flow (for the report)

`GET /` → `HomeController` (Page Controller) → `CatalogService` →
`ProviderRepository`/`ServiceRepository` (JDBC) → SQLite → DTOs → Thymeleaf
view.

`GET /slots` → `SlotApiController` (REST) → `SlotService` →
`SlotRepository` (hand-written SQL with `LIMIT`/`OFFSET`) → SQLite →
`AvailabilitySlotDTO` list serialized to JSON.

## AI assistance disclosure

Built with Claude (Anthropic) as an AI coding assistant per the course's
allowed-tools policy: it scaffolded the Maven/Spring Boot project structure,
wrote the JDBC repository/service/controller layers, the schema/seed SQL,
and generated the ER/block diagrams and wireframes. Per course policy this
disclosure covers the code and artifacts only — the Milestone 1 Report
itself is written independently.

## Code-walkthrough video

_TODO: add the unlisted YouTube / Google Drive link here before submission._
