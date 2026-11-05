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
CREATE INDEX idx_online_hold_releases_pending ON online_hold_releases(booking_id)
    WHERE released_at IS NULL;
CREATE INDEX idx_online_pending_expiry ON bookings(hold_expires_at,id)
    WHERE status='PENDING_PAYMENT';
