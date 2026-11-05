CREATE TABLE online_booking_commands (
    actor_id bigint NOT NULL,
    command_key uuid NOT NULL,
    fingerprint varchar(64) NOT NULL,
    booking_id bigint NOT NULL REFERENCES bookings(id),
    PRIMARY KEY(actor_id,command_key)
);
CREATE TABLE online_hold_releases (
    booking_id bigint PRIMARY KEY REFERENCES bookings(id),
    hold_hash varchar(64) NOT NULL UNIQUE,
    released_at timestamptz
);
