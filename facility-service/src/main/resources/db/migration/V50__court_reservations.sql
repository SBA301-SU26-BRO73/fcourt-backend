-- SCRUM-241 (SCRUM-114): atomic full-court reservations. facility-service is the lock authority; booking-service
-- supplies the reservation id and drives reserve -> commit | rollback. State machine:
--   RESERVED --commit--> COMMITTED            (locks never expire again)
--   RESERVED --rollback/expiry--> CANCELLED   (locks are released)
--   COMMITTED --rollback--> CANCELLED         (compensation / package cancellation)
-- A CANCELLED row without branch_id is a tombstone left by a rollback that arrived before its reserve.
CREATE TABLE court_reservations (
    id            uuid PRIMARY KEY,
    branch_id     bigint       REFERENCES branches (id),
    admin_id      bigint,
    fingerprint   varchar(64),
    state         varchar(12)  NOT NULL,
    start_at      timestamp,
    end_at        timestamp,
    expires_at    timestamptz,
    created_at    timestamptz  NOT NULL DEFAULT now(),
    committed_at  timestamptz,
    cancelled_at  timestamptz,
    cancel_cause  varchar(12),
    CONSTRAINT court_reservations_state_check CHECK (state IN ('RESERVED', 'COMMITTED', 'CANCELLED')),
    CONSTRAINT court_reservations_cause_check CHECK (cancel_cause IS NULL OR cancel_cause IN ('ROLLBACK', 'EXPIRED')),
    CONSTRAINT court_reservations_shape_check CHECK (
        (branch_id IS NULL AND state = 'CANCELLED')
        OR (branch_id IS NOT NULL AND fingerprint IS NOT NULL AND start_at IS NOT NULL AND end_at IS NOT NULL
            AND end_at > start_at AND (state <> 'RESERVED' OR expires_at IS NOT NULL)))
);

CREATE INDEX idx_court_reservations_expiry ON court_reservations (expires_at) WHERE state = 'RESERVED';

-- A reservation lock is an ordinary PRIVATE_EVENT lock (group_id = reservation id) so every schedule reader sees it.
-- expires_at is set while RESERVED and cleared on commit; readers ignore a lock whose expires_at has passed.
ALTER TABLE court_locks ADD COLUMN reservation_id uuid REFERENCES court_reservations (id);
ALTER TABLE court_locks ADD COLUMN expires_at timestamptz;
CREATE INDEX idx_court_locks_reservation ON court_locks (reservation_id) WHERE reservation_id IS NOT NULL;
