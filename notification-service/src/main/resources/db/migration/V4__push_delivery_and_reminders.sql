ALTER TABLE user_devices ADD COLUMN status varchar(16) NOT NULL DEFAULT 'ACTIVE';
ALTER TABLE user_devices ADD CONSTRAINT ck_user_devices_status CHECK (status IN ('ACTIVE', 'DISABLED'));

CREATE TABLE push_deliveries (
    event_id uuid NOT NULL,
    device_id bigint NOT NULL REFERENCES user_devices(id) ON DELETE CASCADE,
    status varchar(16) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    sent_at timestamptz,
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_push_deliveries_event_device UNIQUE(event_id, device_id),
    CONSTRAINT ck_push_deliveries_status CHECK (status IN ('PENDING', 'SENT', 'DISABLED'))
);

ALTER TABLE booking_reminders ADD COLUMN push_reminder_sent boolean NOT NULL DEFAULT false;
ALTER TABLE booking_reminders ADD COLUMN push_reminder_sent_at timestamptz;
ALTER TABLE booking_reminders ADD COLUMN customer_id bigint;
ALTER TABLE booking_reminders ALTER COLUMN recipient_email DROP NOT NULL;
