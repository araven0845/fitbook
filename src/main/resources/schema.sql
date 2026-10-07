-- FitBook (CMPE 172 Term Project) - SQL schema
-- Hand-written SQL, loaded over JDBC on every startup. Tables are dropped and
-- recreated each run (see DROP TABLE IF EXISTS below) so the app always
-- boots from a known, gradeable state; seed.sql repopulates sample data after.

DROP TABLE IF EXISTS appointments;
DROP TABLE IF EXISTS availability_slots;
DROP TABLE IF EXISTS services;
DROP TABLE IF EXISTS providers;
DROP TABLE IF EXISTS users;

-- Every account: customers and providers both log in through this table.
CREATE TABLE users (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    username      TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,               -- BCrypt hash, never plaintext
    role          TEXT NOT NULL CHECK (role IN ('CUSTOMER', 'PROVIDER')),
    full_name     TEXT NOT NULL,
    email         TEXT NOT NULL UNIQUE,
    created_at    TEXT NOT NULL DEFAULT (datetime('now', 'localtime'))
);

-- A provider profile is a 1-to-1 extension of a user with role = 'PROVIDER'.
CREATE TABLE providers (
    id         INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id    INTEGER NOT NULL UNIQUE REFERENCES users(id),
    specialty  TEXT NOT NULL,                  -- e.g. Strength Coaching, Yoga, HIIT
    bio        TEXT
);

-- Bookable service/class type offered by the studio.
CREATE TABLE services (
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    name              TEXT NOT NULL,           -- e.g. "1:1 Personal Training"
    duration_minutes  INTEGER NOT NULL CHECK (duration_minutes > 0),
    price             REAL NOT NULL CHECK (price >= 0),
    description       TEXT
);

-- An open time slot a provider has made bookable for a given service.
-- Times are stored as local 'yyyy-MM-dd HH:mm' text so they sort and compare
-- lexicographically.
-- `version` is the optimistic-locking counter: every state change (book,
-- re-open on cancel, provider removal) bumps it, and the booking UPDATE only
-- succeeds if the version it read is still current.
CREATE TABLE availability_slots (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    provider_id  INTEGER NOT NULL REFERENCES providers(id),
    service_id   INTEGER NOT NULL REFERENCES services(id),
    start_time   TEXT NOT NULL,
    end_time     TEXT NOT NULL,
    status       TEXT NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'BOOKED', 'CANCELLED')),
    version      INTEGER NOT NULL DEFAULT 0,
    CHECK (end_time > start_time)
);

-- A provider cannot have two live slots starting at the same instant. The
-- index is partial so a removed (CANCELLED) slot does not block recreating one.
CREATE UNIQUE INDEX uq_slots_provider_start
    ON availability_slots (provider_id, start_time)
    WHERE status <> 'CANCELLED';

-- A customer's booking of one slot for one service. COMPLETED is not stored:
-- it is derived at read time for BOOKED appointments whose slot has ended.
CREATE TABLE appointments (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    slot_id      INTEGER NOT NULL REFERENCES availability_slots(id),
    customer_id  INTEGER NOT NULL REFERENCES users(id),
    service_id   INTEGER NOT NULL REFERENCES services(id),
    status       TEXT NOT NULL DEFAULT 'BOOKED' CHECK (status IN ('BOOKED', 'CANCELLED', 'COMPLETED')),
    created_at   TEXT NOT NULL DEFAULT (datetime('now', 'localtime'))
);

-- *** Double-booking guard (database-level backstop) ***
-- A partial unique index: at most one non-cancelled appointment may reference
-- the same slot_id. If two booking transactions ever raced past the
-- application-level checks, one INSERT would fail this constraint, so the
-- database itself is the final word on "double-booked". Because the index
-- excludes CANCELLED rows, cancelling an appointment frees the slot for a
-- fresh booking without violating uniqueness against the old row.
-- Paired with availability_slots.version (checked by the booking UPDATE in the
-- same transaction before this INSERT) this is the "unique constraint +
-- version check" guard, proven by the two-thread test.
CREATE UNIQUE INDEX uq_appointments_active_slot
    ON appointments (slot_id)
    WHERE status <> 'CANCELLED';

CREATE INDEX idx_slots_provider ON availability_slots (provider_id);
CREATE INDEX idx_slots_service ON availability_slots (service_id);
CREATE INDEX idx_slots_status_start ON availability_slots (status, start_time);
CREATE INDEX idx_appointments_customer ON appointments (customer_id);
