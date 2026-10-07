# FitBook — CMPE 172 Term Project (Milestone 2)

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

## Tech stack

Java 21, Spring Boot 3.3.4, Spring JDBC (`JdbcTemplate`), Thymeleaf +
Bootstrap, BCrypt via `spring-security-crypto`, SQLite via
`org.xerial:sqlite-jdbc`, Maven. JUnit 5 + Mockito for tests.

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
  config/       Clock, BCrypt encoder, SQLite exception translator, MVC wiring
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
booking and concurrency code, the tests, and generated the ER/block diagrams
and wireframes. Per course policy this disclosure covers the code and
artifacts only — the milestone reports are written independently.

## Code-walkthrough video

_TODO: add the unlisted YouTube / Google Drive link here before submission._
