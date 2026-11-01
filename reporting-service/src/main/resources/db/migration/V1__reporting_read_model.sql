-- reporting-service (reporting_db): read model for the owner dashboard (SCRUM-12).
-- Filled only from Kafka events; reporting never queries another service's database.

-- At-least-once delivery: every applied event id is stored once (dedupe).
CREATE TABLE processed_events (
    event_id character varying(100) PRIMARY KEY,
    topic character varying(200) NOT NULL,
    event_type character varying(100) NOT NULL,
    processed_at timestamp with time zone DEFAULT now() NOT NULL
);

-- One row per booking, folded from payment-service and staff-operations-service events.
CREATE TABLE booking_facts (
    booking_id bigint PRIMARY KEY,
    branch_id bigint NOT NULL,
    status character varying(20) NOT NULL,
    amount numeric(12,2) DEFAULT 0 NOT NULL,
    revenue numeric(12,2) DEFAULT 0 NOT NULL,
    revenue_at timestamp with time zone,
    booking_date date,
    slot_count integer DEFAULT 0 NOT NULL,
    last_event_type character varying(100) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT ck_booking_facts_status CHECK (status IN ('PENDING', 'CONFIRMED', 'CANCELLED')),
    CONSTRAINT ck_booking_facts_amounts CHECK (amount >= 0 AND revenue >= 0 AND slot_count >= 0)
);
CREATE INDEX ix_booking_facts_branch_status ON booking_facts (branch_id, status);
CREATE INDEX ix_booking_facts_branch_revenue_at ON booking_facts (branch_id, revenue_at) WHERE revenue_at IS NOT NULL;
CREATE INDEX ix_booking_facts_branch_date ON booking_facts (branch_id, booking_date);

-- Latest facility.branch-updated.v1 snapshot per branch (version guarded).
CREATE TABLE branch_snapshots (
    branch_id bigint PRIMARY KEY,
    version bigint NOT NULL,
    active boolean NOT NULL,
    deleted boolean NOT NULL,
    active_courts integer NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);
