-- slot-hold-engine (slot_hold_db) - schema khoi tao, tach tu bo Flyway V1-V23 cua ban cu.
-- Bang: slot_holds

\restrict VkJm6bPMAlUs2AZ408QZ18PdXQbmFsxmwIk1kdnTFRcwAhestWAGFJRIkDwmmx1

CREATE TABLE public.slot_holds (
    id bigint NOT NULL,
    court_id bigint NOT NULL,
    customer_id bigint,
    guest_phone character varying(20),
    date date NOT NULL,
    slot_start time without time zone NOT NULL,
    slot_end time without time zone NOT NULL,
    expired_at timestamp with time zone NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    booking_id bigint,
    CONSTRAINT chk_slot_holds_customer_or_guest CHECK (((customer_id IS NOT NULL) OR (guest_phone IS NOT NULL)))
);

ALTER TABLE public.slot_holds ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.slot_holds_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

ALTER TABLE ONLY public.slot_holds
    ADD CONSTRAINT slot_holds_pkey PRIMARY KEY (id);

CREATE UNIQUE INDEX uq_slot_holds_court_date_start ON public.slot_holds USING btree (court_id, date, slot_start);
-- Cross-service FK removed: slot_holds.booking_id -> bookings.id (booking-service). Keep as plain ID column; validate via API/event.
-- Cross-service FK removed: slot_holds.court_id -> courts.id (facility-service). Keep as plain ID column; validate via API/event.
-- Cross-service FK removed: slot_holds.customer_id -> users.id (auth-service). Keep as plain ID column; validate via API/event.

\unrestrict VkJm6bPMAlUs2AZ408QZ18PdXQbmFsxmwIk1kdnTFRcwAhestWAGFJRIkDwmmx1

;
