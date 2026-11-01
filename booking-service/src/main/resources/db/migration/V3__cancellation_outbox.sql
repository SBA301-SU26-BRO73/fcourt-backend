ALTER TABLE bookings ADD COLUMN cancellation_reason varchar(500);
ALTER TABLE bookings ADD COLUMN cancelled_at timestamptz;
ALTER TABLE bookings ADD COLUMN refund_amount numeric(10,2);
ALTER TABLE booking_slots ADD COLUMN active boolean NOT NULL DEFAULT true;
UPDATE booking_slots SET active=false WHERE booking_id IN (SELECT id FROM bookings WHERE status='CANCELLED');
DROP INDEX uq_booking_slots_court_date_start;
CREATE UNIQUE INDEX uq_active_booking_slots ON booking_slots(court_id,booking_date,slot_start) WHERE active;
CREATE TABLE booking_outbox(
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 event_id uuid NOT NULL UNIQUE,
 booking_id bigint NOT NULL,
 payload jsonb NOT NULL,
 published_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_booking_outbox_pending ON booking_outbox(id) WHERE published_at IS NULL;
