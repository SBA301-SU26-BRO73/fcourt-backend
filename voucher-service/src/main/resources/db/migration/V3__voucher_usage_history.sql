CREATE TABLE voucher_usages (
    id uuid PRIMARY KEY REFERENCES voucher_redemptions(id),
    voucher_id bigint NOT NULL REFERENCES vouchers(id),
    booking_id bigint NOT NULL UNIQUE CHECK (booking_id > 0),
    user_id bigint NOT NULL CHECK (user_id > 0),
    branch_id bigint NOT NULL CHECK (branch_id > 0),
    discount_amount numeric(12,2) NOT NULL CHECK (discount_amount >= 0),
    used_at timestamptz NOT NULL,
    used_at_source varchar(20) NOT NULL CHECK (used_at_source IN ('PAYMENT_CONFIRMED','RECORDED_AT')),
    payment_id bigint NOT NULL CHECK (payment_id > 0),
    UNIQUE(voucher_id,user_id)
);
CREATE INDEX voucher_usage_history_page ON voucher_usages(voucher_id,branch_id,used_at DESC,booking_id DESC);

-- Legacy applications have no original payment timestamp. Preserve the recorded
-- application time explicitly rather than presenting it as payment-confirmed time.
INSERT INTO voucher_usages(id,voucher_id,booking_id,user_id,branch_id,discount_amount,used_at,used_at_source,payment_id)
SELECT id,voucher_id,booking_id,customer_id,branch_id,discount_amount,updated_at,'RECORDED_AT',payment_id
FROM voucher_redemptions WHERE status='APPLIED';
