CREATE TABLE staff_check_in_audit (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    actor_id bigint NOT NULL,
    booking_id bigint NOT NULL,
    branch_id bigint NOT NULL,
    channel varchar(10) NOT NULL CHECK(channel IN ('QR', 'MANUAL')),
    command_key uuid NOT NULL,
    checked_in_at timestamptz NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(actor_id, command_key)
);
CREATE INDEX idx_check_in_audit_branch_time ON staff_check_in_audit(branch_id, checked_in_at);
