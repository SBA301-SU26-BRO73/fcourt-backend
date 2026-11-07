CREATE TABLE vouchers (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    code varchar(50) NOT NULL UNIQUE CHECK (code ~ '^[A-Z0-9_-]{1,50}$'),
    branch_id bigint NOT NULL CHECK (branch_id > 0),
    discount_type varchar(20) NOT NULL CHECK (discount_type IN ('PERCENTAGE','FIXED_AMOUNT')),
    discount_value numeric(12,2) NOT NULL CHECK (discount_value > 0),
    max_discount_amount numeric(12,2) CHECK (max_discount_amount > 0),
    min_order_amount numeric(12,2) NOT NULL DEFAULT 0 CHECK (min_order_amount >= 0),
    start_date timestamptz NOT NULL,
    end_date timestamptz NOT NULL CHECK (end_date > start_date),
    usage_limit integer NOT NULL CHECK (usage_limit >= 1),
    used_count integer NOT NULL DEFAULT 0 CHECK (used_count >= 0 AND used_count <= usage_limit),
    status varchar(10) NOT NULL CHECK (status IN ('ACTIVE','EXPIRED')),
    deleted_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CHECK (discount_type <> 'PERCENTAGE' OR discount_value <= 100)
);
CREATE INDEX vouchers_branch_page ON vouchers(branch_id,id DESC) WHERE deleted_at IS NULL;
