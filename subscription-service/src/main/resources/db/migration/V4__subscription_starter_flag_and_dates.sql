-- The free starter subscription is replaced when the owner buys a plan; paid periods are never cut short.
-- Existing (seeded) subscriptions count as paid.
ALTER TABLE public.subscriptions ADD COLUMN starter boolean DEFAULT false NOT NULL;

-- start_date..end_date are both inclusive, so a subscription lasts at least one day.
ALTER TABLE public.subscriptions
    ADD CONSTRAINT ck_subscriptions_dates CHECK (end_date >= start_date);

CREATE INDEX idx_subscriptions_admin ON public.subscriptions USING btree (admin_id) WHERE (deleted_at IS NULL);
