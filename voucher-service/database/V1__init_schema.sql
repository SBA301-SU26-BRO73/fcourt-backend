-- voucher-service (voucher_db) - schema khoi tao, tach tu bo Flyway V1-V23 cua ban cu.
-- Bang: vouchers

\restrict kwM2680ZJsOk8jXZfKitC28OnRz8cMc359AsPDH1T9olIgwUze8nhRuUpJWoLTJ

CREATE TABLE public.vouchers (
    id bigint NOT NULL,
    code character varying(50) NOT NULL,
    discount_type character varying(20) NOT NULL,
    discount_value numeric(10,2) NOT NULL,
    min_order_value numeric(10,2) DEFAULT 0,
    max_discount_value numeric(10,2),
    usage_limit integer NOT NULL,
    usage_count integer DEFAULT 0 NOT NULL,
    start_date timestamp with time zone NOT NULL,
    expiry_date timestamp with time zone NOT NULL,
    active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    updated_at timestamp with time zone DEFAULT CURRENT_TIMESTAMP NOT NULL,
    deleted_at timestamp with time zone,
    branch_id bigint
);

CREATE SEQUENCE public.vouchers_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.vouchers_id_seq OWNED BY public.vouchers.id;

ALTER TABLE ONLY public.vouchers ALTER COLUMN id SET DEFAULT nextval('public.vouchers_id_seq'::regclass);

ALTER TABLE ONLY public.vouchers
    ADD CONSTRAINT vouchers_code_key UNIQUE (code);

ALTER TABLE ONLY public.vouchers
    ADD CONSTRAINT vouchers_pkey PRIMARY KEY (id);
-- Cross-service FK removed: vouchers.branch_id -> branches.id (facility-service). Keep as plain ID column; validate via API/event.

\unrestrict kwM2680ZJsOk8jXZfKitC28OnRz8cMc359AsPDH1T9olIgwUze8nhRuUpJWoLTJ

;
