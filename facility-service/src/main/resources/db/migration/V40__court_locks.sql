-- SCRUM-49/111: one table for slot locks, out-of-service windows and private-event (full court) locks.
-- Times are venue-local (Asia/Ho_Chi_Minh), like time_slot_templates. A window is [start_at, end_at).
CREATE TABLE court_locks (
    id             bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    group_id       uuid         NOT NULL,
    branch_id      bigint       NOT NULL REFERENCES branches (id),
    court_id       bigint       NOT NULL REFERENCES courts (id),
    lock_type      varchar(20)  NOT NULL,
    start_at       timestamp    NOT NULL,
    end_at         timestamp    NOT NULL,
    reason         varchar(500) NOT NULL,
    notes          varchar(1000),
    public_reason  varchar(500),
    contact_name   varchar(100),
    contact_phone  varchar(100),
    created_by     bigint       NOT NULL,
    created_at     timestamptz  NOT NULL DEFAULT now(),
    cancelled_at   timestamptz,
    cancelled_by   bigint,
    CONSTRAINT court_locks_type_check CHECK (lock_type IN ('OUT_OF_SERVICE', 'PRIVATE_EVENT')),
    CONSTRAINT court_locks_window_check CHECK (end_at > start_at)
);

CREATE INDEX idx_court_locks_court_window ON court_locks (court_id, start_at, end_at) WHERE cancelled_at IS NULL;
CREATE INDEX idx_court_locks_branch ON court_locks (branch_id, start_at) WHERE cancelled_at IS NULL;
CREATE INDEX idx_court_locks_group ON court_locks (group_id);
