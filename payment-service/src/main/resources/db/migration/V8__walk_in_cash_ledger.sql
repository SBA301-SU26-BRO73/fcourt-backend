CREATE TABLE walk_in_cash_ledger(
 id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
 event_id uuid NOT NULL UNIQUE,
 booking_id bigint NOT NULL UNIQUE,
 branch_id bigint NOT NULL,
 court_id bigint NOT NULL,
 booking_code varchar(10) NOT NULL,
 receipt_code varchar(64) NOT NULL,
 amount numeric(10,2) NOT NULL CHECK(amount>=0),
 occurred_at timestamptz NOT NULL,
 recorded_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX idx_walk_in_cash_branch_date ON walk_in_cash_ledger(branch_id,occurred_at DESC,id DESC);
CREATE INDEX idx_completed_refund_branch_date ON refund_requests(branch_id,confirmed_at DESC,id DESC) WHERE status='COMPLETED';
