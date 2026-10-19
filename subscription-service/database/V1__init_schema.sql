-- subscription-service (subscription_db) - schema khoi tao, tach tu bo Flyway V1-V23 cua ban cu.
-- Bang: subscription_plans, plan_features, subscriptions, subscription_payments, court_types

\restrict jbejptYjkfO18uVFwojIpTB05hOfiCYZcsIkMSdrA7yffFCMa9AhgjNdLlcl8Pv

CREATE TABLE public.court_types (
    id bigint NOT NULL,
    name character varying(100) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    name_en character varying(100),
    description text,
    icon character varying(50),
    color character varying(20),
    active boolean DEFAULT true NOT NULL,
    deleted_at timestamp with time zone
);

ALTER TABLE public.court_types ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.court_types_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.plan_features (
    id bigint NOT NULL,
    plan_id bigint NOT NULL,
    feature text NOT NULL
);

ALTER TABLE public.plan_features ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.plan_features_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.subscription_payments (
    id bigint NOT NULL,
    order_code bigint NOT NULL,
    admin_id bigint NOT NULL,
    plan_id bigint NOT NULL,
    plan_name character varying(100) NOT NULL,
    billing_cycle character varying(20) NOT NULL,
    amount numeric(19,2) NOT NULL,
    status character varying(20) NOT NULL,
    payment_link_id character varying(100),
    checkout_url text,
    qr_code text,
    payos_reference character varying(100),
    paid_at timestamp with time zone,
    expired_at timestamp with time zone NOT NULL,
    webhook_payload text,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE SEQUENCE public.subscription_payments_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.subscription_payments_id_seq OWNED BY public.subscription_payments.id;

CREATE TABLE public.subscription_plans (
    id bigint NOT NULL,
    name character varying(100) NOT NULL,
    max_courts integer NOT NULL,
    max_branches integer NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    tagline character varying(255),
    monthly_price bigint,
    yearly_price bigint,
    color character varying(20),
    popular boolean DEFAULT false NOT NULL,
    deleted_at timestamp with time zone
);

ALTER TABLE public.subscription_plans ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.subscription_plans_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

CREATE TABLE public.subscriptions (
    id bigint NOT NULL,
    admin_id bigint NOT NULL,
    plan_id bigint NOT NULL,
    max_courts integer NOT NULL,
    max_branches integer NOT NULL,
    start_date date NOT NULL,
    end_date date NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    deleted_at timestamp with time zone
);

ALTER TABLE public.subscriptions ALTER COLUMN id ADD GENERATED ALWAYS AS IDENTITY (
    SEQUENCE NAME public.subscriptions_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

ALTER TABLE ONLY public.subscription_payments ALTER COLUMN id SET DEFAULT nextval('public.subscription_payments_id_seq'::regclass);

ALTER TABLE ONLY public.court_types
    ADD CONSTRAINT court_types_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.plan_features
    ADD CONSTRAINT plan_features_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.subscription_payments
    ADD CONSTRAINT subscription_payments_order_code_key UNIQUE (order_code);

ALTER TABLE ONLY public.subscription_payments
    ADD CONSTRAINT subscription_payments_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.subscription_plans
    ADD CONSTRAINT subscription_plans_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.subscriptions
    ADD CONSTRAINT subscriptions_pkey PRIMARY KEY (id);

CREATE INDEX idx_subscription_payments_admin ON public.subscription_payments USING btree (admin_id);

CREATE INDEX idx_subscription_payments_status ON public.subscription_payments USING btree (status);

CREATE UNIQUE INDEX uq_court_types_name_active ON public.court_types USING btree (name) WHERE (deleted_at IS NULL);

CREATE UNIQUE INDEX uq_subscription_plans_name_active ON public.subscription_plans USING btree (name) WHERE (deleted_at IS NULL);

ALTER TABLE ONLY public.plan_features
    ADD CONSTRAINT plan_features_plan_id_fkey FOREIGN KEY (plan_id) REFERENCES public.subscription_plans(id) ON DELETE CASCADE;
-- Cross-service FK removed: subscription_payments.admin_id -> users.id (auth-service). Keep as plain ID column; validate via API/event.

ALTER TABLE ONLY public.subscription_payments
    ADD CONSTRAINT subscription_payments_plan_id_fkey FOREIGN KEY (plan_id) REFERENCES public.subscription_plans(id);
-- Cross-service FK removed: subscriptions.admin_id -> users.id (auth-service). Keep as plain ID column; validate via API/event.

ALTER TABLE ONLY public.subscriptions
    ADD CONSTRAINT subscriptions_plan_id_fkey FOREIGN KEY (plan_id) REFERENCES public.subscription_plans(id) ON DELETE RESTRICT;

\unrestrict jbejptYjkfO18uVFwojIpTB05hOfiCYZcsIkMSdrA7yffFCMa9AhgjNdLlcl8Pv

;
