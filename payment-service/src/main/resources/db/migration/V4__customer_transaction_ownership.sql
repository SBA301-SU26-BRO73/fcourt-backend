ALTER TABLE payments ADD COLUMN customer_id bigint;
CREATE INDEX idx_payments_customer_history ON payments(customer_id,submitted_at DESC,id DESC) WHERE submitted_at IS NOT NULL;
CREATE TABLE payment_customer_sync(customer_id bigint PRIMARY KEY,synced_at timestamptz NOT NULL DEFAULT now());
