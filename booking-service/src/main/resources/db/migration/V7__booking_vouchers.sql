ALTER TABLE bookings ADD COLUMN voucher_application_id uuid UNIQUE;
ALTER TABLE bookings ADD COLUMN voucher_id bigint;
ALTER TABLE bookings ADD COLUMN original_amount numeric(10,2);
ALTER TABLE bookings ADD COLUMN discount_amount numeric(10,2);
ALTER TABLE bookings ADD COLUMN payment_frozen boolean NOT NULL DEFAULT false;
ALTER TABLE bookings ADD CONSTRAINT booking_voucher_amounts CHECK (
    (voucher_application_id IS NULL AND voucher_id IS NULL AND original_amount IS NULL AND discount_amount IS NULL)
    OR (voucher_application_id IS NOT NULL AND voucher_id IS NOT NULL AND original_amount IS NOT NULL AND discount_amount IS NOT NULL
        AND voucher_id > 0 AND original_amount > 0 AND discount_amount >= 0
        AND total_price >= 0 AND original_amount = total_price + discount_amount)
);
