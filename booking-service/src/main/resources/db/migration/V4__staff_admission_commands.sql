CREATE TABLE booking_staff_commands (
    actor_id bigint NOT NULL,
    command_key varchar(150) NOT NULL,
    fingerprint varchar(64) NOT NULL,
    result jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY(actor_id,command_key)
);
CREATE INDEX booking_branch_daily ON bookings(branch_id,date,id);
