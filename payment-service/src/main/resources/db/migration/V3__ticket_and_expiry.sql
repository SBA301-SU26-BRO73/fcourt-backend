ALTER TABLE payments DROP CONSTRAINT payments_status_check;
ALTER TABLE payments ADD CONSTRAINT payments_status_check CHECK (status IN ('PENDING','CONFIRMED','REJECTED','CANCELLED'));
ALTER TABLE payments ADD COLUMN check_in_code varchar(32) UNIQUE;
ALTER TABLE payments ADD COLUMN qr_image_url text;
ALTER TABLE payments ADD CONSTRAINT confirmed_ticket_only CHECK (check_in_code IS NULL OR status='CONFIRMED');
ALTER TABLE payments ADD COLUMN expires_at timestamptz;
UPDATE payments SET expires_at=(checkout->>'expiresAt')::timestamptz WHERE checkout IS NOT NULL;
CREATE INDEX idx_payment_checkout_expiry ON payments(expires_at)
    WHERE status='PENDING' AND submitted_at IS NULL AND booking_id IS NOT NULL;
