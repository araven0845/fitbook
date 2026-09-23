-- FitBook (CMPE 172 Term Project) - SQL schema
-- Hand-written SQL, loaded over JDBC on every startup. Tables are dropped and
-- recreated each run (see DROP TABLE IF EXISTS below) so the skeleton always
-- boots from a known, gradeable state; seed.sql repopulates sample data after.

DROP TABLE IF EXISTS appointments;
DROP TABLE IF EXISTS availability_slots;
DROP TABLE IF EXISTS services;
DROP TABLE IF EXISTS providers;
DROP TABLE IF EXISTS users;

-- Every account: customers and providers/admins both log in through this table.
CREATE TABLE users (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    username      TEXT NOT NULL UNIQUE,
    password_hash TEXT NOT NULL,               -- BCrypt hash (auth added in a later milestone)
    role          TEXT NOT NULL CHECK (role IN ('CUSTOMER', 'PROVIDER')),
    full_name     TEXT NOT NULL,
    email         TEXT NOT NULL UNIQUE,
    created_at    TEXT NOT NULL DEFAULT (datetime('now'))
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
-- `version` supports optimistic-locking concurrency control, so that a
-- booking transaction can re-check version before flipping status to BOOKED.
CREATE TABLE availability_slots (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    provider_id  INTEGER NOT NULL REFERENCES providers(id),
    service_id   INTEGER NOT NULL REFERENCES services(id),
    start_time   TEXT NOT NULL,                -- ISO-8601
    end_time     TEXT NOT NULL,
    status       TEXT NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'BOOKED', 'CANCELED')),
    version      INTEGER NOT NULL DEFAULT 0,
    -- A provider cannot have two slots starting at the same instant.
    UNIQUE (provider_id, start_time)
);

-- A customer's booking of one slot for one service.
CREATE TABLE appointments (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    slot_id      INTEGER NOT NULL REFERENCES availability_slots(id),
    customer_id  INTEGER NOT NULL REFERENCES users(id),
    service_id   INTEGER NOT NULL REFERENCES services(id),
    status       TEXT NOT NULL DEFAULT 'CONFIRMED' CHECK (status IN ('CONFIRMED', 'CANCELED')),
    created_at   TEXT NOT NULL DEFAULT (datetime('now'))
);

-- *** Double-booking guard ***
-- A partial unique index: at most one CONFIRMED appointment may reference the
-- same slot_id at a time. Two concurrent booking transactions racing on the
-- same slot will have one INSERT succeed and the other fail this constraint,
-- so the database itself is the final word on "double-booked", independent of
-- any application-level check. Because the index is filtered to status =
-- 'CONFIRMED', canceling an appointment (status -> 'CANCELED') frees the slot
-- for a fresh booking without violating uniqueness against the old row.
-- Paired with availability_slots.version above (checked in the same
-- transaction before the INSERT), this is the "unique constraint + version
-- check" double-booking guard the project requires, proven with a two-thread
-- test.
CREATE UNIQUE INDEX uq_appointments_active_slot
    ON appointments (slot_id)
    WHERE status = 'CONFIRMED';

CREATE INDEX idx_slots_provider ON availability_slots (provider_id);
CREATE INDEX idx_slots_service ON availability_slots (service_id);
CREATE INDEX idx_slots_status_start ON availability_slots (status, start_time);
