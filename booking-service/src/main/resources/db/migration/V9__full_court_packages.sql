-- SCRUM-242 (SCRUM-114): full-court package command + member bookings.
-- One command per (actor, Idempotency-Key). The priced slots and totals are frozen here at the first attempt so a
-- replay never reprices. reservation_id is the facility-service reservation of the CURRENT attempt; a failed attempt
-- is rolled back at facility (its id is then burnt) and a retry with the same key gets a fresh reservation_id.
--   RESERVING  command stored, facility reserve / booking not finished (no bookings exist yet)
--   BOOKED     member bookings exist, facility commit still pending (reconciler retries it)
--   COMMITTED  member bookings exist and the facility locks are committed
--   FAILED     compensated: facility reservation rolled back, no bookings
--   LOCK_LOST  bookings exist but facility refused the commit (lease expired); booking_slots still blocks the slots;
--              repaired by re-reserving (relock) and committing
--   CANCELLING all member bookings cancelled, facility lock release not yet confirmed (reconciler retries)
--   CANCELLED  member bookings cancelled and facility lock released
CREATE TABLE full_court_packages (
    id               uuid PRIMARY KEY,
    actor_id         bigint       NOT NULL,
    command_key      uuid         NOT NULL,
    fingerprint      varchar(64)  NOT NULL,
    branch_id        bigint       NOT NULL,
    booking_date     date         NOT NULL,
    start_time       time         NOT NULL,
    end_time         time         NOT NULL,
    contact_name     varchar(150) NOT NULL,
    contact_phone    varchar(20)  NOT NULL,
    court_hours      numeric(8,2) NOT NULL,
    subtotal         numeric(10,2) NOT NULL,
    discount_percent numeric(5,2) NOT NULL,
    discount_amount  numeric(10,2) NOT NULL,
    total_amount     numeric(10,2) NOT NULL,
    deposit_percent  numeric(5,2) NOT NULL DEFAULT 0,
    deposit_amount   numeric(10,2) NOT NULL DEFAULT 0,
    currency         varchar(3)   NOT NULL DEFAULT 'VND',
    priced_slots     jsonb        NOT NULL,
    reservation_id   uuid         NOT NULL UNIQUE,
    attempt          integer      NOT NULL DEFAULT 1,
    state            varchar(12)  NOT NULL,
    failure          varchar(120),
    cancel_reason    varchar(500),
    cancelled_at     timestamptz,
    created_at       timestamptz  NOT NULL DEFAULT now(),
    updated_at       timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT full_court_packages_state_check CHECK (state IN ('RESERVING', 'BOOKED', 'COMMITTED', 'FAILED', 'LOCK_LOST', 'CANCELLING', 'CANCELLED')),
    CONSTRAINT full_court_packages_total_check CHECK (total_amount = subtotal - discount_amount AND total_amount >= 0),
    CONSTRAINT full_court_packages_deposit_check CHECK (deposit_amount >= 0 AND deposit_amount <= total_amount),
    CONSTRAINT uq_full_court_packages_command UNIQUE (actor_id, command_key)
);

CREATE TABLE full_court_package_bookings (
    package_id uuid   NOT NULL REFERENCES full_court_packages (id),
    court_id   bigint NOT NULL,
    booking_id bigint NOT NULL UNIQUE REFERENCES bookings (id),
    PRIMARY KEY (package_id, court_id)
);

CREATE INDEX idx_full_court_packages_open ON full_court_packages (updated_at) WHERE state IN ('RESERVING', 'BOOKED', 'CANCELLING');
CREATE INDEX idx_full_court_packages_branch ON full_court_packages (branch_id, state, created_at DESC);
