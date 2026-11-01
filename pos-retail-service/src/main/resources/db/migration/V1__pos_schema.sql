-- pos-retail-service (pos_retail_db) - schema khoi tao, tach tu bo Flyway V1-V23 cua ban cu.
-- Bang: products, orders, order_items


CREATE TABLE public.order_items (
    id bigint NOT NULL,
    order_id bigint NOT NULL,
    product_id bigint NOT NULL,
    quantity integer NOT NULL,
    price_at_order numeric(10,2) NOT NULL,
    CONSTRAINT order_items_quantity_check CHECK ((quantity > 0))
);

CREATE SEQUENCE public.order_items_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.order_items_id_seq OWNED BY public.order_items.id;

CREATE TABLE public.orders (
    id bigint NOT NULL,
    branch_id bigint NOT NULL,
    booking_id bigint,
    staff_user_id bigint NOT NULL,
    total_amount numeric(10,2) NOT NULL,
    payment_method character varying(20) NOT NULL,
    status character varying(20) NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    paid_at timestamp with time zone
);

CREATE SEQUENCE public.orders_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.orders_id_seq OWNED BY public.orders.id;

CREATE TABLE public.products (
    id bigint NOT NULL,
    branch_id bigint NOT NULL,
    name character varying(150) NOT NULL,
    unit character varying(30),
    price numeric(10,2) NOT NULL,
    active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL
);

CREATE SEQUENCE public.products_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1;

ALTER SEQUENCE public.products_id_seq OWNED BY public.products.id;

ALTER TABLE ONLY public.order_items ALTER COLUMN id SET DEFAULT nextval('public.order_items_id_seq'::regclass);

ALTER TABLE ONLY public.orders ALTER COLUMN id SET DEFAULT nextval('public.orders_id_seq'::regclass);

ALTER TABLE ONLY public.products ALTER COLUMN id SET DEFAULT nextval('public.products_id_seq'::regclass);

ALTER TABLE ONLY public.order_items
    ADD CONSTRAINT order_items_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.orders
    ADD CONSTRAINT orders_pkey PRIMARY KEY (id);

ALTER TABLE ONLY public.products
    ADD CONSTRAINT products_pkey PRIMARY KEY (id);

CREATE INDEX idx_orders_branch_created ON public.orders USING btree (branch_id, created_at DESC);

CREATE INDEX idx_products_branch ON public.products USING btree (branch_id);

ALTER TABLE ONLY public.order_items
    ADD CONSTRAINT order_items_order_id_fkey FOREIGN KEY (order_id) REFERENCES public.orders(id) ON DELETE CASCADE;

ALTER TABLE ONLY public.order_items
    ADD CONSTRAINT order_items_product_id_fkey FOREIGN KEY (product_id) REFERENCES public.products(id);
-- Cross-service FK removed: orders.booking_id -> bookings.id (booking-service). Keep as plain ID column; validate via API/event.
-- Cross-service FK removed: orders.branch_id -> branches.id (facility-service). Keep as plain ID column; validate via API/event.
-- Cross-service FK removed: orders.staff_user_id -> users.id (auth-service). Keep as plain ID column; validate via API/event.
-- Cross-service FK removed: products.branch_id -> branches.id (facility-service). Keep as plain ID column; validate via API/event.


;
