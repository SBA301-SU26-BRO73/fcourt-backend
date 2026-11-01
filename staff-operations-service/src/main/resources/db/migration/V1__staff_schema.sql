-- staff-operations-service (staff_db) - schema khoi tao, tach tu bo Flyway V1-V23 cua ban cu.
-- Bang: staff


CREATE TABLE public.staff (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    branch_id bigint NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    deleted_at timestamp with time zone
);

ALTER TABLE public.staff ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.staff_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

ALTER TABLE ONLY public.staff
    ADD CONSTRAINT staff_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.staff
    ADD CONSTRAINT staff_user_id_key UNIQUE (user_id);
-- Cross-service FK removed: staff.branch_id -> branches.id (facility-service). Keep as plain ID column; validate via API/event.
-- Cross-service FK removed: staff.user_id -> users.id (auth-service). Keep as plain ID column; validate via API/event.


;
