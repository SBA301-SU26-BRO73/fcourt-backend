CREATE TABLE voucher_redemptions (
    id uuid PRIMARY KEY,
    voucher_id bigint NOT NULL REFERENCES vouchers(id),
    booking_id bigint NOT NULL UNIQUE CHECK (booking_id > 0),
    customer_id bigint NOT NULL CHECK (customer_id > 0),
    branch_id bigint NOT NULL CHECK (branch_id > 0),
    original_amount numeric(12,2) NOT NULL CHECK (original_amount > 0),
    discount_amount numeric(12,2) NOT NULL CHECK (discount_amount >= 0),
    final_amount numeric(12,2) NOT NULL CHECK (final_amount >= 0 AND final_amount = original_amount - discount_amount),
    expires_at timestamptz NOT NULL,
    status varchar(10) NOT NULL CHECK (status IN ('RESERVED','APPLIED','RELEASED')),
    payment_id bigint,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK ((status = 'APPLIED') = (payment_id IS NOT NULL))
);
CREATE UNIQUE INDEX voucher_customer_once ON voucher_redemptions(voucher_id,customer_id) WHERE status IN ('RESERVED','APPLIED');
CREATE INDEX voucher_reserved_quota ON voucher_redemptions(voucher_id) WHERE status='RESERVED';
CREATE INDEX voucher_pending_expiry ON voucher_redemptions(expires_at) WHERE status='RESERVED';
CREATE TABLE voucher_inbox(event_id uuid PRIMARY KEY, received_at timestamptz NOT NULL DEFAULT now());
