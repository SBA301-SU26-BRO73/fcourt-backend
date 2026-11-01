-- JDBC-compatible version of database/V1__init_schema.sql, without psql meta commands.
-- payment-service (payment_db) - schema khoi tao, tach tu bo Flyway V1-V23 cua ban cu.
-- Bang: payments, refund_requests


CREATE TABLE public.payments (
    id bigint NOT NULL,
    branch_id bigint NOT NULL,
    amount numeric(10,2) NOT NULL,
    bill_image_url text,
    status character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    confirmed_at timestamp with time zone,
    confirmed_by bigint,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    voucher_id bigint,
    discount_amount numeric(10,2) DEFAULT 0,
    CONSTRAINT payments_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'CONFIRMED'::character varying, 'REJECTED'::character varying])::text[])))
);

ALTER TABLE public.payments ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.payments_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.refund_requests (
    id bigint NOT NULL,
    booking_id bigint NOT NULL,
    amount numeric(10,2) NOT NULL,
    reason text NOT NULL,
    refund_bank_name character varying(100) NOT NULL,
    refund_bank_account_number character varying(50) NOT NULL,
    refund_bank_account_name character varying(150) NOT NULL,
    status character varying(20) DEFAULT 'PENDING'::character varying NOT NULL,
    confirmed_at timestamp with time zone,
    confirmed_by bigint,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    refund_proof_image_url character varying(500),
    CONSTRAINT refund_requests_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING'::character varying, 'CONFIRMED'::character varying])::text[])))
);

ALTER TABLE public.refund_requests ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.refund_requests_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

ALTER TABLE ONLY public.payments
    ADD CONSTRAINT payments_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.refund_requests
    ADD CONSTRAINT refund_requests_booking_id_key UNIQUE (booking_id);

ALTER TABLE ONLY public.refund_requests
    ADD CONSTRAINT refund_requests_pkey PRIMARY KEY (id);

CREATE INDEX idx_refund_requests_booking_branch ON public.refund_requests USING btree (booking_id, status);

CREATE INDEX idx_refund_requests_status ON public.refund_requests USING btree (status);
-- Cross-service FK removed: payments.voucher_id -> vouchers.id (voucher-service). Keep as plain ID column; validate via API/event.
-- Cross-service FK removed: payments.branch_id -> branches.id (facility-service). Keep as plain ID column; validate via API/event.
-- Cross-service FK removed: payments.confirmed_by -> users.id (auth-service). Keep as plain ID column; validate via API/event.
-- Cross-service FK removed: refund_requests.booking_id -> bookings.id (booking-service). Keep as plain ID column; validate via API/event.
-- Cross-service FK removed: refund_requests.confirmed_by -> users.id (auth-service). Keep as plain ID column; validate via API/event.


;
