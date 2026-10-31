ALTER TABLE products ADD COLUMN category varchar(60);
ALTER TABLE products ADD COLUMN image_url text;
ALTER TABLE products ADD COLUMN stock_quantity integer NOT NULL DEFAULT 0 CHECK(stock_quantity BETWEEN 0 AND 1000000000);
CREATE INDEX idx_products_branch_active ON products(branch_id, active, id);

ALTER TABLE orders ADD COLUMN order_code varchar(40) UNIQUE;
ALTER TABLE orders ADD COLUMN idempotency_key uuid;
ALTER TABLE orders ADD COLUMN request_fingerprint varchar(64);
ALTER TABLE orders ADD COLUMN version bigint NOT NULL DEFAULT 0;
ALTER TABLE orders ADD CONSTRAINT uq_pos_actor_command UNIQUE(staff_user_id, idempotency_key);
ALTER TABLE order_items ADD COLUMN product_name varchar(150);
ALTER TABLE order_items ADD COLUMN product_unit varchar(30);

CREATE TABLE pos_commands (
    actor_id bigint NOT NULL,
    command_key uuid NOT NULL,
    kind varchar(20) NOT NULL,
    fingerprint varchar(64) NOT NULL,
    product_id bigint NOT NULL REFERENCES products(id),
    PRIMARY KEY(actor_id, command_key)
);
CREATE TABLE pos_stock_movements (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    product_id bigint NOT NULL REFERENCES products(id),
    actor_id bigint NOT NULL,
    command_key uuid NOT NULL,
    kind varchar(20) NOT NULL,
    quantity_delta integer NOT NULL CHECK(quantity_delta <> 0),
    reason varchar(500) NOT NULL,
    order_id bigint REFERENCES orders(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(actor_id, command_key, product_id, kind)
);
CREATE TABLE pos_outbox (
    event_id uuid PRIMARY KEY,
    order_id bigint NOT NULL REFERENCES orders(id),
    kind varchar(20) NOT NULL CHECK(kind IN ('EVENT', 'CHARGE')),
    payload text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz
);
CREATE INDEX idx_pos_outbox_pending ON pos_outbox(created_at, event_id) WHERE published_at IS NULL;
CREATE TABLE pos_settlement_events (
    event_id uuid PRIMARY KEY,
    fingerprint varchar(64) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);
