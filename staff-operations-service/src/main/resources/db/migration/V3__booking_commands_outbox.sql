CREATE TABLE staff_booking_commands (
    actor_id bigint NOT NULL,
    command_key uuid NOT NULL,
    kind varchar(30) NOT NULL,
    fingerprint varchar(64) NOT NULL,
    result jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(actor_id, command_key)
);

CREATE TABLE staff_outbox (
    event_id uuid PRIMARY KEY,
    booking_id bigint NOT NULL,
    payload text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz
);
CREATE INDEX idx_staff_outbox_pending ON staff_outbox(created_at, event_id)
    WHERE published_at IS NULL;
