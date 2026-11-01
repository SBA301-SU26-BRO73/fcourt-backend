-- Names are unique regardless of case among rows that are not deleted ("Tennis" and "tennis" clash).
DROP INDEX public.uq_court_types_name_active;
CREATE UNIQUE INDEX uq_court_types_name_active ON public.court_types USING btree (lower(name))
    WHERE (deleted_at IS NULL);

DROP INDEX public.uq_subscription_plans_name_active;
CREATE UNIQUE INDEX uq_subscription_plans_name_active ON public.subscription_plans USING btree (lower(name))
    WHERE (deleted_at IS NULL);

-- A price is either absent (not sold for that cycle) or positive: PayOS cannot charge 0, and the free tier is the
-- automatic starter subscription, not a plan priced 0.
ALTER TABLE public.subscription_plans
    ADD CONSTRAINT ck_subscription_plans_prices_positive
    CHECK ((monthly_price IS NULL OR monthly_price > 0) AND (yearly_price IS NULL OR yearly_price > 0));
