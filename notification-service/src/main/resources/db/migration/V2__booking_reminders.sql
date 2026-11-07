CREATE TABLE booking_reminders (
    booking_id bigint PRIMARY KEY,
    recipient_email varchar(255) NOT NULL,
    recipient_name varchar(150) NOT NULL,
    booking_code varchar(100) NOT NULL,
    branch_name varchar(255) NOT NULL,
    court_name varchar(255) NOT NULL,
    starts_at timestamptz NOT NULL,
    ticket_code varchar(128) NOT NULL,
    reminder_sent boolean NOT NULL DEFAULT false,
    created_at timestamptz NOT NULL DEFAULT now(),
    sent_at timestamptz
);
CREATE INDEX idx_booking_reminders_due ON booking_reminders(starts_at)
    WHERE reminder_sent = false;

CREATE TABLE cancelled_booking_reminders (
    booking_id bigint PRIMARY KEY
);
