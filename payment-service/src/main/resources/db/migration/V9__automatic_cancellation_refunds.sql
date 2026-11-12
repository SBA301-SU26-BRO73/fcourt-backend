-- Owner/guest cancellations enter the refund queue before bank information is collected.
-- booking_id is already UNIQUE (V1); all writers also use the same advisory transaction lock.
ALTER TABLE refund_requests ALTER COLUMN refund_bank_name DROP NOT NULL;
ALTER TABLE refund_requests ALTER COLUMN refund_bank_account_number DROP NOT NULL;
ALTER TABLE refund_requests ALTER COLUMN refund_bank_account_name DROP NOT NULL;
ALTER TABLE refund_requests ADD COLUMN customer_phone varchar(32);
