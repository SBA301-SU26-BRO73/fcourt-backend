ALTER TABLE payments ADD COLUMN customer_phone varchar(20);
CREATE INDEX idx_payments_branch_history ON payments(branch_id,submitted_at DESC,id DESC) WHERE submitted_at IS NOT NULL;
