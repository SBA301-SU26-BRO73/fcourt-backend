-- auth-service (auth_db) - schema khoi tao, tach tu bo Flyway V1-V23 cua ban cu.
-- Bang: users, user_documents, user_account_action_logs


CREATE TABLE public.user_account_action_logs (
    id bigint NOT NULL,
    target_user_id bigint NOT NULL,
    performed_by_id bigint,
    action character varying(20) NOT NULL,
    previous_status character varying(30),
    new_status character varying(30) NOT NULL,
    reason character varying(500),
    created_at timestamp without time zone DEFAULT CURRENT_TIMESTAMP NOT NULL
);

CREATE SEQUENCE public.user_account_action_logs_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.user_account_action_logs_id_seq OWNED BY public.user_account_action_logs.id;

CREATE TABLE public.user_documents (
    id bigint NOT NULL,
    user_id bigint NOT NULL,
    url text NOT NULL,
    doc_type character varying(20) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    CONSTRAINT user_documents_doc_type_check CHECK (((doc_type)::text = ANY ((ARRAY['LEGAL'::character varying, 'COURT_IMAGE'::character varying])::text[])))
);

ALTER TABLE public.user_documents ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.user_documents_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.users (
    id bigint NOT NULL,
    email character varying(255) NOT NULL,
    password_hash character varying(255) NOT NULL,
    phone character varying(20),
    role character varying(20) NOT NULL,
    status character varying(20) DEFAULT 'PENDING_APPROVAL'::character varying NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    deleted_at timestamp with time zone,
    full_name character varying(150),
    CONSTRAINT users_role_check CHECK (((role)::text = ANY ((ARRAY['CUSTOMER'::character varying, 'STAFF'::character varying, 'ADMIN'::character varying, 'SUPER_ADMIN'::character varying])::text[]))),
    CONSTRAINT users_status_check CHECK (((status)::text = ANY ((ARRAY['PENDING_APPROVAL'::character varying, 'ACTIVE'::character varying, 'INACTIVE'::character varying, 'LOCKED'::character varying])::text[])))
);

ALTER TABLE public.users ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.users_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

ALTER TABLE ONLY public.user_account_action_logs ALTER COLUMN id SET DEFAULT nextval('public.user_account_action_logs_id_seq'::regclass);

ALTER TABLE ONLY public.user_account_action_logs
    ADD CONSTRAINT user_account_action_logs_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.user_documents
    ADD CONSTRAINT user_documents_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_email_key UNIQUE (email);

ALTER TABLE ONLY public.users
    ADD CONSTRAINT users_pkey PRIMARY KEY (id);

CREATE INDEX idx_user_account_action_logs_created_at ON public.user_account_action_logs USING btree (created_at);

CREATE INDEX idx_user_account_action_logs_target_user ON public.user_account_action_logs USING btree (target_user_id);

CREATE UNIQUE INDEX uq_users_phone ON public.users USING btree (phone) WHERE ((phone IS NOT NULL) AND (deleted_at IS NULL));

ALTER TABLE ONLY public.user_account_action_logs
    ADD CONSTRAINT user_account_action_logs_performed_by_id_fkey FOREIGN KEY (performed_by_id) REFERENCES public.users(id);

ALTER TABLE ONLY public.user_account_action_logs
    ADD CONSTRAINT user_account_action_logs_target_user_id_fkey FOREIGN KEY (target_user_id) REFERENCES public.users(id);

ALTER TABLE ONLY public.user_documents
    ADD CONSTRAINT user_documents_user_id_fkey FOREIGN KEY (user_id) REFERENCES public.users(id) ON DELETE CASCADE;


;
