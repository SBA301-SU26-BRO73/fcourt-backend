-- booking-service (booking_db) - schema khoi tao, tach tu bo Flyway V1-V23 cua ban cu.
-- Bang: bookings, booking_slots

\restrict unhsafhfLWBF6HagpKrSdKUF9vIeS3rNfO5UvspVf8PcwBqidjYhwoBDKBlYJnw

CREATE TABLE public.booking_slots (
    id bigint NOT NULL,
    booking_id bigint NOT NULL,
    court_id bigint NOT NULL,
    booking_date date NOT NULL,
    slot_start time without time zone NOT NULL,
    slot_end time without time zone NOT NULL,
    price_at_booking numeric(10,2) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

ALTER TABLE public.booking_slots ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.booking_slots_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.bookings (
    id bigint NOT NULL,
    court_id bigint NOT NULL,
    customer_id bigint,
    payment_id bigint,
    guest_phone character varying(20),
    date date NOT NULL,
    status character varying(25) DEFAULT 'PENDING_PAYMENT'::character varying NOT NULL,
    total_price numeric(10,2) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    checkin_code character varying(36),
    checked_in_at timestamp with time zone,
    completed_at timestamp with time zone,
    booking_code character varying(10) NOT NULL,
    CONSTRAINT bookings_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING_PAYMENT'::character varying, 'AWAITING_CONFIRMATION'::character varying, 'CONFIRMED'::character varying, 'CHECKED_IN'::character varying, 'COMPLETED'::character varying, 'CANCELLED'::character varying])::text[]))),
    CONSTRAINT chk_bookings_customer_or_guest CHECK (((customer_id IS NOT NULL) OR (guest_phone IS NOT NULL)))
);

ALTER TABLE public.bookings ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.bookings_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

ALTER TABLE ONLY public.booking_slots
    ADD CONSTRAINT booking_slots_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.bookings
    ADD CONSTRAINT bookings_pkey PRIMARY KEY (id);

CREATE UNIQUE INDEX uq_booking_slots_court_date_start ON public.booking_slots USING btree (court_id, booking_date, slot_start);

CREATE UNIQUE INDEX uq_bookings_booking_code ON public.bookings USING btree (booking_code);

CREATE UNIQUE INDEX uq_bookings_checkin_code ON public.bookings USING btree (checkin_code);

ALTER TABLE ONLY public.booking_slots
    ADD CONSTRAINT booking_slots_booking_id_fkey FOREIGN KEY (booking_id) REFERENCES public.bookings(id) ON DELETE CASCADE;
-- Cross-service FK removed: booking_slots.court_id -> courts.id (facility-service). Keep as plain ID column; validate via API/event.
-- Cross-service FK removed: bookings.court_id -> courts.id (facility-service). Keep as plain ID column; validate via API/event.
-- Cross-service FK removed: bookings.customer_id -> users.id (auth-service). Keep as plain ID column; validate via API/event.
-- Cross-service FK removed: bookings.payment_id -> payments.id (payment-service). Keep as plain ID column; validate via API/event.

\unrestrict unhsafhfLWBF6HagpKrSdKUF9vIeS3rNfO5UvspVf8PcwBqidjYhwoBDKBlYJnw

;
