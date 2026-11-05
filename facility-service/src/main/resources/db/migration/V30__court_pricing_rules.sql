-- SCRUM-80/82: per-court pricing rules (peak / off-peak time bands, optionally limited to weekdays).
-- days_of_week uses the same convention as time_slot_templates.day_of_week: 0 = Sunday .. 6 = Saturday.
-- When no live rule covers a slot, the slot keeps its time_slot_templates.price (default price).
CREATE TABLE public.court_pricing_rules (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    court_id bigint NOT NULL REFERENCES public.courts (id),
    rule_name character varying(100) NOT NULL,
    start_time time without time zone NOT NULL,
    end_time time without time zone NOT NULL,
    price_per_hour numeric(10,2) NOT NULL,
    is_peak_hour boolean DEFAULT false NOT NULL,
    days_of_week smallint[] DEFAULT ARRAY[0,1,2,3,4,5,6]::smallint[] NOT NULL,
    is_active boolean DEFAULT true NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    updated_at timestamp with time zone DEFAULT now() NOT NULL,
    deleted_at timestamp with time zone,
    CONSTRAINT ck_court_pricing_rules_range CHECK (end_time > start_time),
    CONSTRAINT ck_court_pricing_rules_price CHECK (price_per_hour > 0),
    CONSTRAINT ck_court_pricing_rules_days CHECK (
        cardinality(days_of_week) BETWEEN 1 AND 7
        AND days_of_week <@ ARRAY[0,1,2,3,4,5,6]::smallint[])
);

CREATE INDEX ix_court_pricing_rules_court ON public.court_pricing_rules (court_id)
    WHERE is_active AND deleted_at IS NULL;
