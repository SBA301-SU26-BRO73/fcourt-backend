-- Display snapshot taken at usage time from the booking-service VoucherAppliedEvent.
-- All columns are nullable: legacy rows and events from older producers carry no snapshot.
ALTER TABLE voucher_usages
    ADD COLUMN customer_name varchar(150),
    ADD COLUMN customer_phone varchar(20),
    ADD COLUMN customer_phone_digits varchar(15) CHECK (customer_phone_digits ~ '^[0-9]{9,15}$'),
    ADD COLUMN booking_code varchar(10);

-- Supports bounded prefix search by phone inside one voucher (LIKE 'digits%').
CREATE INDEX voucher_usage_phone_search
    ON voucher_usages(voucher_id, customer_phone_digits varchar_pattern_ops, used_at DESC, booking_id DESC)
    WHERE customer_phone_digits IS NOT NULL;
