-- facility-service (facility_db) - schema khoi tao, tach tu bo Flyway V1-V23 cua ban cu.
-- Bang: branches, courts, time_slot_templates

\restrict XsB5NHuY3ptHwuJj77bScbehJArbFWo3RiTykIWdwQgCw2au3WfqAimDasa27gB

CREATE TABLE public.branches (
    id bigint NOT NULL,
    admin_id bigint NOT NULL,
    name character varying(150) NOT NULL,
    address character varying(255) NOT NULL,
    ward character varying(100),
    city character varying(100) NOT NULL,
    phone character varying(20),
    open_time time without time zone NOT NULL,
    close_time time without time zone NOT NULL,
    bank_account_number character varying(50),
    bank_account_name character varying(150),
    bank_name character varying(100),
    bank_qr_image_url text,
    status character varying(20) DEFAULT 'ACTIVE'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    deleted_at timestamp with time zone,
    slot_duration_minutes integer DEFAULT 30 NOT NULL,
    CONSTRAINT branches_status_check CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'INACTIVE'::character varying])::text[]))),
    CONSTRAINT ck_branches_slot_duration_minutes CHECK ((slot_duration_minutes = ANY (ARRAY[30, 60])))
);

ALTER TABLE public.branches ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.branches_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.courts (
    id bigint NOT NULL,
    branch_id bigint NOT NULL,
    name character varying(150) NOT NULL,
    court_type_id bigint NOT NULL,
    description text,
    image_url text,
    status character varying(20) DEFAULT 'ACTIVE'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    deleted_at timestamp with time zone,
    CONSTRAINT courts_status_check CHECK (((status)::text = ANY ((ARRAY['ACTIVE'::character varying, 'INACTIVE'::character varying, 'MAINTENANCE'::character varying])::text[])))
);

ALTER TABLE public.courts ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.courts_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.time_slot_templates (
    id bigint NOT NULL,
    court_id bigint NOT NULL,
    start_time time without time zone NOT NULL,
    end_time time without time zone NOT NULL,
    price numeric(10,2) NOT NULL,
    day_of_week smallint NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    deleted_at timestamp with time zone,
    CONSTRAINT time_slot_templates_day_of_week_check CHECK (((day_of_week >= 0) AND (day_of_week <= 6)))
);

ALTER TABLE public.time_slot_templates ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.time_slot_templates_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

ALTER TABLE ONLY public.branches
    ADD CONSTRAINT branches_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.courts
    ADD CONSTRAINT courts_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.time_slot_templates
    ADD CONSTRAINT time_slot_templates_pkey PRIMARY KEY (id);
-- Cross-service FK removed: branches.admin_id -> users.id (auth-service). Keep as plain ID column; validate via API/event.

ALTER TABLE ONLY public.courts
    ADD CONSTRAINT courts_branch_id_fkey FOREIGN KEY (branch_id) REFERENCES public.branches(id) ON DELETE RESTRICT;
-- Cross-service FK removed: courts.court_type_id -> court_types.id (subscription-service). Keep as plain ID column; validate via API/event.

ALTER TABLE ONLY public.time_slot_templates
    ADD CONSTRAINT time_slot_templates_court_id_fkey FOREIGN KEY (court_id) REFERENCES public.courts(id) ON DELETE RESTRICT;

\unrestrict XsB5NHuY3ptHwuJj77bScbehJArbFWo3RiTykIWdwQgCw2au3WfqAimDasa27gB

;
