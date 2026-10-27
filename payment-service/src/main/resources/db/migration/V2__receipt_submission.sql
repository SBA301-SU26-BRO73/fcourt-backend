ALTER TABLE payments ADD COLUMN booking_id bigint UNIQUE;
ALTER TABLE payments ADD COLUMN checkout jsonb;
ALTER TABLE payments ADD COLUMN submitted_at timestamptz;
ALTER TABLE payments ADD COLUMN rejection_reason varchar(500);
CREATE INDEX idx_payment_pending_branch ON payments(branch_id, submitted_at) WHERE status = 'PENDING';
CREATE TABLE payment_outbox (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id uuid NOT NULL UNIQUE,
    booking_id bigint NOT NULL,
    event_type varchar(60) NOT NULL,
    payload jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz
);
CREATE INDEX idx_payment_outbox_unpublished ON payment_outbox(id) WHERE published_at IS NULL;
