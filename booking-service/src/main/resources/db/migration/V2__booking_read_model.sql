ALTER TABLE bookings ADD COLUMN branch_id bigint;
ALTER TABLE bookings ADD COLUMN branch_name text;
ALTER TABLE bookings ADD COLUMN branch_address text;
ALTER TABLE bookings ADD COLUMN branch_phone varchar(20);
ALTER TABLE bookings ADD COLUMN court_name text;
ALTER TABLE bookings ADD COLUMN customer_name text;
ALTER TABLE bookings ADD COLUMN hold_token_hash varchar(64);
ALTER TABLE bookings ADD COLUMN hold_expires_at timestamptz;
ALTER TABLE bookings ADD COLUMN qr_image_url text;
ALTER TABLE bookings ADD COLUMN receipt_image_url text;
ALTER TABLE bookings ADD COLUMN payment_status varchar(25);
CREATE INDEX idx_bookings_customer_history ON bookings(customer_id,created_at DESC,id DESC);
CREATE TABLE booking_inbox(event_id uuid PRIMARY KEY, received_at timestamptz NOT NULL DEFAULT now());
-- Snapshots are populated by booking creation/facility integration; legacy rows stay readable.
CREATE INDEX idx_booking_slots_booking ON booking_slots(booking_id,slot_start,id);
