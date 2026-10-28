# FitBook — CMPE 172 Term Project (Milestone 3)

Online Appointment Scheduling System, fitness-studio scenario: members book
sessions with trainers for a given service (personal training, HIIT, yoga,
mobility). Individually built for SJSU CMPE 172 — Enterprise Software
Platforms.

## What it implements

**Milestone 1 (skeleton, tag `milestone-1`)**: layered `controller` →
`service` → `repository` → JDBC with hand-written SQL and no ORM, the 5-table
schema with a double-booking guard, `schema.sql` / `seed.sql` loaded on
startup, strict DTOs, and `GET /` plus `GET /slots`.

**Milestone 2 (booking and concurrency)**:

- **Login and role-based access control** — username + password, passwords
  stored as BCrypt hashes, server-side session on login, the role read from
  the user's row over JDBC. A handler interceptor enforces `@RequiresRole`:
  not logged in → 401 (browsers are redirected to `/login`), wrong role → 403.
- **Customer features** — browse/filter/paginate open slots (provider,
  service, date; SQL `LIMIT`/`OFFSET`), book a slot, view my appointments
  (upcoming and history), cancel my appointment (owner only).
- **Provider features** — create and remove availability slots (each tied to a
  service; the end time is the start plus the service duration), view the
  appointments booked with me.
- **Appointment status** — `BOOKED`, `CANCELLED`, and `COMPLETED` (a `BOOKED`
  appointment whose slot has ended reads back as `COMPLETED`).
- **Concurrency and transactions** — see the design summary below.
- **Enterprise behavior** — `@Valid` request validation, and one global
  exception handler returning standard status codes (400 invalid input,
  401 not logged in, 403 wrong role / not the owner, 404 not found, 409 slot
  conflict, 500 unexpected) with no stack traces sent to the client.
- **Tests** — unit tests for the booking rules, owner-only cancel, retry
  policy, provider rules, login and validation; a two-thread test that books
  the same slot concurrently against a real SQLite file and asserts exactly one
  booking succeeds; MockMvc tests for RBAC and error responses.

**Milestone 3 (integration, distribution and deployment)**:

- **Distribution boundary** — a mock notification service (`POST /notifications`,
  a second controller in this project) and a `NotificationClient` gateway that
  the booking code calls with one coarse-grained `NotificationRequest` DTO
  (recipient, type, message, idempotency key). A confirmation is sent on both
  book and cancel.
- **Fault handling** — connect and read timeouts, up to 3 attempts, and a
  fallback: if delivery still fails, the app logs a warning, counts it, and the
  booking stays confirmed. Notification is sent only after the booking
  transaction has committed (commit first, then notify), and every retry reuses
  the same idempotency key so the receiver stores it once.
- **Observability** — a logging plan, `GET /health` with a database check, and
  Micrometer metrics (booking outcomes, booking latency, notification results).
- **Delivery** — a `Dockerfile`, a `docker-compose.yml`, and a GitHub Actions
  workflow that builds, runs the tests, and builds the image on every push.
- **Tests** — an integration test with a stub notification server shows a
  booking still succeeds when the service is down, slow, or returning 503, and
  that the notice goes out only after commit.

## Demo accounts

`seed.sql` creates four accounts. They all use the demo password
`Fitbook2026!` (stored only as BCrypt hashes):

| Username        | Role     | Name             |
|-----------------|----------|------------------|
| `member_arjun`  | CUSTOMER | Arjun Ravendran  |
| `member_lena`   | CUSTOMER | Lena Park        |
| `coach_maya`    | PROVIDER | Maya Chen        |
| `coach_diego`   | PROVIDER | Diego Alvarez    |

Slot times in `seed.sql` are relative to when the app starts, so there are
always upcoming open slots, one upcoming booking (Lena), and Arjun has one
past (`COMPLETED`) and one `CANCELLED` appointment to show history.

## Endpoints

| Method & path                         | Who        | What                                              |
|---------------------------------------|------------|---------------------------------------------------|
| `GET /`                               | anyone     | Home: trainers and services                       |
| `GET /slots`                          | anyone     | JSON page of open slots (`providerId`, `serviceId`, `date`, `page`, `size`) |
| `GET /browse`                         | anyone     | Filterable, paginated slot list (HTML)            |
| `GET /login`, `POST /login`           | anyone     | Log in                                            |
| `POST /logout`                        | logged in  | Log out                                           |
| `GET /book?slotId=`, `POST /book`     | customer   | Booking form / book (`slotId`, `serviceId`)       |
| `GET /confirmation/{id}`              | customer   | Confirmation (owner only)                         |
| `GET /my-appointments`                | customer   | Upcoming and history                              |
| `POST /appointments/{id}/cancel`      | customer   | Cancel (owner only)                               |
| `GET /provider`                       | provider   | My slots + create-slot form                       |
| `POST /provider/slots`                | provider   | Create a slot (`serviceId`, `startTime`)          |
| `POST /provider/slots/{id}/remove`    | provider   | Remove one of my open slots                       |
| `GET /provider/appointments`          | provider   | Bookings made with me                             |
| `POST /notifications`                 | (internal) | Mock notification service: accepts a `NotificationRequest` (JSON) |
| `GET /health`                         | anyone     | 200 `UP` / 503 `DOWN`, includes a database check  |
| `GET /actuator/metrics/{name}`        | anyone     | Metrics (only `metrics` is exposed under `/actuator`) |

## Concurrency and transaction design

- **The race**: two customers book the same open slot at the same moment. Both
  read the slot as `OPEN`; without protection both would insert an appointment.
- **Transaction**: `BookingTransactions.book` is one `@Transactional`
  (`SERIALIZABLE`, which is what SQLite provides) unit — read slot, validate,
  flip the slot to `BOOKED`, insert the appointment. It all commits or all
  rolls back. SQLite runs transactions as `BEGIN IMMEDIATE` (set in
  `application.yml`) so concurrent writers queue on the write lock instead of
  failing mid-transaction.
- **Optimistic version check**: `availability_slots.version`. The booking
  `UPDATE ... SET status='BOOKED', version=version+1 WHERE id=? AND version=?
  AND status='OPEN'` affects 0 rows if anyone changed the slot since it was
  read, which aborts the transaction.
- **Database backstop**: the partial unique index
  `uq_appointments_active_slot` on `appointments(slot_id) WHERE status <>
  'CANCELLED'` rejects a second active appointment for a slot even if the
  application checks were bypassed.
- **Retry**: `BookingServiceImpl` retries only transient failures (a version
  mismatch or SQLite lock contention) up to 3 times with a short backoff, each
  attempt in a fresh transaction. A taken slot is a definitive 409 and is
  never retried.

## Distribution boundary

```
Client -> Appointment Scheduler -> Notification Service
```

See `docs/distribution-diagram.png` (source `docs/distribution-diagram.dot`).

1. The booking transaction commits (`BookingTransactions`).
2. Only then does `BookingServiceImpl` call `NotificationService`, which builds one
   `NotificationRequest` and hands it to the `NotificationClient` gateway.
3. `HttpNotificationClient` POSTs it to `/notifications` with a 500 ms connect and
   1.5 s read timeout. I/O errors, timeouts and 5xx are retried up to 3 times with
   a growing pause; a 4xx is not retried.
4. If every attempt fails, `NotificationService` logs a WARN, increments
   `fitbook.notifications{result=failed}`, and returns normally, so the
   booking is never failed or undone.

Why one coarse-grained call: a remote call is slow and can fail, so one
`NotificationRequest` carries everything needed (recipient, type, message,
idempotency key) in a single round trip. Settings are in `application.yml`
under `fitbook.notification`; `NOTIFICATION_BASE_URL` points the gateway at a
separate service (blank means the in-app mock), and the interface stays the same.

## Logging plan

Logs use SLF4J, one line per event, `key=value` style, written to the console.

| Level | What is logged | Example line the code emits |
|-------|----------------|-----------------------------|
| INFO  | Business events and normal outcomes | `Booking confirmed appointmentId=4 slotId=2 customerId=3` |
| INFO  | Expected conflicts (a slot taken) | `Booking conflict slotId=2 customerId=4 reason=This slot is no longer available` |
| INFO  | Cancellations and notification results | `Appointment cancelled appointmentId=4 customerId=3`, `Notification sent key=appointment-4-confirmed type=BOOKING_CONFIRMED customerId=3` |
| INFO  | The mock service receiving a message | `Notification received key=appointment-4-confirmed type=BOOKING_CONFIRMED recipient=...`, `Duplicate notification ignored key=...` |
| WARN  | A failed notification attempt (retryable) | `Notification attempt 2/3 failed key=appointment-4-confirmed reason=I/O error on POST request...` |
| WARN  | The fallback: delivery gave up | `Notification appointment-4-confirmed not delivered, booking is unaffected: Notification appointment-4-confirmed not delivered after 3 attempts` |
| WARN  | Booking retries exhausted | `Giving up after 3 attempts: ...` |
| ERROR | Unexpected failures (full stack trace stays in the log, never sent to the client) | `Unhandled exception for POST /book` |
| ERROR | Database unreachable | `Health check failed: database unreachable: ...` |

Passwords, tokens and session ids are never logged.

## Health and metrics

`GET /health` runs a real `SELECT 1` against the database. No login is needed.

```json
{"status":"UP","checks":{"database":{"status":"UP","responseTimeMs":3}},"timestamp":"2026-10-28T03:30:52Z"}
```

It returns HTTP 503 with `"status":"DOWN"` when the database cannot be reached.

Metrics (Micrometer), at `GET /actuator/metrics/{name}`:

| Metric | Meaning |
|--------|---------|
| `fitbook.bookings` (tag `result` = success / conflict / rejected / error) | Booking outcomes, so failed bookings are visible |
| `fitbook.booking.latency` | Time to complete a booking (count, total, max) |
| `fitbook.cancellations` | Cancellations |
| `fitbook.notifications` (tag `result` = sent / failed) | Notification deliveries and fallbacks |

Only `metrics` is exposed under `/actuator`; env, beans and the rest are not.

## Failure scenario and recovery

**Scenario:** the notification service is slow or down while a customer books.

- The booking transaction has already committed, so the slot and appointment are safe.
- The gateway times out or is refused, retries (3 attempts, same idempotency key),
  then falls back: WARN log, `fitbook.notifications{result=failed}` counter, and the
  customer still gets their confirmation page.
- **Recovery:** when the service returns, new notifications flow normally. A
  rising `failed` counter and the WARN lines show which notices were missed, and
  because every notice has a deterministic key (`appointment-{id}-confirmed`),
  re-sending one later cannot create a duplicate.

## Tech stack

Java 21, Spring Boot 3.3.4, Spring JDBC (`JdbcTemplate`), Thymeleaf +
Bootstrap, BCrypt via `spring-security-crypto`, `RestClient` for the
notification call, Micrometer metrics, SQLite via `org.xerial:sqlite-jdbc`,
Maven. JUnit 5 + Mockito for tests. Docker and GitHub Actions for delivery.

SQLite is used so a grader can clone the repo and run it with nothing else
installed (no DB server, no Docker). Every repository method is hand-written
SQL scoped to one table/join, so moving to MySQL or PostgreSQL later is a
datasource change rather than a rewrite.

## Prerequisites

- JDK 21
- Maven 3.9+

No database server, Docker, or other services are required.

## Build, run, test

```bash
mvn clean package        # compiles and runs all tests
mvn spring-boot:run      # or: java -jar target/fitbook.jar
mvn test                 # tests only
```

### Docker

```bash
docker compose up --build        # app on http://localhost:8080
curl http://localhost:8080/health
docker compose down
```

SQLite is an embedded file, so compose runs the app only (the database file lives
on the `fitbook-data` volume, and the mock notification service runs inside the app).
The compose file has a health check that polls `/health`.

### CI

`.github/workflows/ci.yml` runs on every push and pull request: it sets up JDK 21,
runs `mvn -B verify` (build plus all tests), then builds the Docker image.

The app starts on **http://localhost:8080**. On every startup it drops and
recreates all tables from `schema.sql`, then reloads `seed.sql`, so you always
start from the same known dataset. Log in at `/login` with a demo account.

Quick API check:

```bash
curl http://localhost:8080/slots
curl "http://localhost:8080/slots?serviceId=1&page=0&size=5"
```

## Configuration (environment variables)

| Variable         | Default          | Purpose                                   |
|-------------------|------------------|--------------------------------------------|
| `SERVER_PORT`      | `8080`           | HTTP port                                  |
| `FITBOOK_DB_PATH`  | `./fitbook.db`   | Path to the SQLite database file           |
| `NOTIFICATION_BASE_URL` | blank (in-app mock) | Base URL of the notification service |

No secrets are required or committed. The demo accounts are seed data for local
use only.

## Security notes

- Passwords are BCrypt-hashed; a failed login does not reveal whether the
  username exists. The session id changes on login (no session fixation), the
  cookie is `HttpOnly` and `SameSite=Lax`, and session ids never appear in URLs.
- There are no CSRF tokens; `SameSite=Lax` stops browsers sending the session
  cookie on cross-site POSTs, which covers the common case for this scope.

## Project layout

```
src/main/java/com/fitbook/
  controller/   Page controllers (Home, Browse, Auth, Booking, Provider) + SlotApiController (JSON)
  service/      Business rules: Auth, Booking (+ BookingTransactions), Provider, Slot, Catalog
  repository/   Jdbc*Repository: all hand-written SQL
  security/     @RequiresRole, AuthInterceptor, session user resolver
  web/          GlobalExceptionHandler, ApiError, shared model attributes
  notification/ Mock notification service, gateway (timeout/retry), fallback service
  health/       GET /health
  config/       Clock, BCrypt encoder, SQLite exception translator, notification HTTP client, MVC wiring
  dto/          Strict DTOs and request records (validated with @Valid)
  model/        Internal row records and enums (never returned by controllers)
src/main/resources/
  schema.sql    5 tables + double-booking guard
  seed.sql      Demo accounts, trainers, services, slots, appointments
  templates/    Thymeleaf views
src/test/java/com/fitbook/
  service/                Unit tests (mocked repositories)
  BookingConcurrencyTest  Two-thread race tests against real SQLite
  AuthAndRbacTest         Login, 401/403, validation and error-response tests
docs/
  er-diagram.png, er-diagram.mmd         ER diagram (Mermaid source + render)
  block-diagram.png, block-diagram.mmd   System block diagram
  wireframes/                            4 wireframes + combined wireframes.pdf
  distribution-diagram.png, .dot         Client -> Scheduler -> Notification Service
Dockerfile, docker-compose.yml           Container packaging
.github/workflows/ci.yml                 Build, test and image build on every push
```

## Request flow

`POST /book` → `AuthInterceptor` (customer session required) →
`BookingController` (`@Valid` binds a `BookingRequest`) → `BookingServiceImpl`
(retry policy) → `BookingTransactions` (`@Transactional`) → `SlotRepository` /
`AppointmentRepository` (JDBC) → SQLite → `AppointmentDTO` → redirect to
`/confirmation/{id}` → Thymeleaf view. Any failure is turned into a status code
by `GlobalExceptionHandler`.

`GET /slots` → `SlotApiController` → `SlotService` → `SlotRepository`
(hand-written SQL with `LIMIT`/`OFFSET`) → `AvailabilitySlotDTO` list as JSON.

## AI assistance disclosure

Built with Claude (Anthropic) as an AI coding assistant per the course's
allowed-tools policy: it scaffolded the project, wrote the JDBC
repository/service/controller layers, the schema/seed SQL, the authentication,
booking and concurrency code, the notification boundary, health and metrics,
Docker and CI files, the tests, and generated the diagrams and wireframes. Per course policy this disclosure covers the code and
artifacts only — the milestone reports are written independently.

## Code-walkthrough videos

- Milestone 2: https://drive.google.com/file/d/1hQCZ7GnZpjeq6cb3hwav3qtXhuQKrXTV/view?usp=sharing
- Milestone 3: _TODO: add the unlisted YouTube / Google Drive link here before submission._
