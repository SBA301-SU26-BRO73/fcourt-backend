CREATE TABLE booking_pos_charges(
 order_code varchar(40) PRIMARY KEY,booking_id bigint NOT NULL REFERENCES bookings(id),
 branch_id bigint NOT NULL,cashier_user_id bigint NOT NULL,amount numeric(10,2) NOT NULL CHECK(amount>0),
 fingerprint varchar(64) NOT NULL,items jsonb NOT NULL,settled boolean NOT NULL DEFAULT false,
 created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX booking_pos_charges_booking ON booking_pos_charges(booking_id);
CREATE TABLE booking_pos_settlement_outbox(
 event_id uuid PRIMARY KEY,payload jsonb NOT NULL,published_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT now()
);
ALTER TABLE booking_cash_settlements ADD COLUMN pos_amount numeric(10,2) NOT NULL DEFAULT 0 CHECK(pos_amount>=0);
