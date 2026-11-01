-- SCRUM-14/16/18: additive columns and support tables for the facility API.
-- V1/V2 columns are kept unchanged: tools/demo_seed writes them directly.

ALTER TABLE public.branches
    ADD COLUMN district character varying(100),
    ADD COLUMN latitude numeric(9,6),
    ADD COLUMN longitude numeric(9,6),
    ADD COLUMN bank_bin character varying(6),
    ADD COLUMN event_version bigint DEFAULT 0 NOT NULL,
    ADD CONSTRAINT ck_branches_latitude CHECK (latitude IS NULL OR latitude BETWEEN -90 AND 90),
    ADD CONSTRAINT ck_branches_longitude CHECK (longitude IS NULL OR longitude BETWEEN -180 AND 180),
    ADD CONSTRAINT ck_branches_coordinates CHECK ((latitude IS NULL) = (longitude IS NULL)),
    ADD CONSTRAINT ck_branches_hours CHECK (close_time > open_time);

ALTER TABLE public.courts
    ADD COLUMN default_price_per_hour numeric(12,2),
    ADD CONSTRAINT ck_courts_default_price CHECK (default_price_per_hour IS NULL OR default_price_per_hour > 0);

ALTER TABLE public.time_slot_templates
    ADD CONSTRAINT ck_time_slot_templates_range CHECK (end_time > start_time),
    ADD CONSTRAINT ck_time_slot_templates_price CHECK (price >= 0);

CREATE INDEX ix_branches_admin ON public.branches (admin_id) WHERE deleted_at IS NULL;
CREATE INDEX ix_courts_branch ON public.courts (branch_id) WHERE deleted_at IS NULL;
-- Court names are unique inside one branch (case-insensitive, ignoring deleted courts).
CREATE UNIQUE INDEX ux_courts_branch_name ON public.courts (branch_id, lower(btrim(name))) WHERE deleted_at IS NULL;
-- At most one live template per court/day/start time; overlap is also rejected by the API.
CREATE UNIQUE INDEX ux_time_slot_templates_live ON public.time_slot_templates (court_id, day_of_week, start_time)
    WHERE is_active AND deleted_at IS NULL;

-- Transactional outbox for facility.branch-updated.v1 (see README "Branch events").
CREATE TABLE public.facility_outbox (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    event_id uuid NOT NULL UNIQUE,
    topic character varying(200) NOT NULL,
    event_key character varying(100) NOT NULL,
    aggregate_version bigint NOT NULL,
    payload jsonb NOT NULL,
    created_at timestamp with time zone DEFAULT now() NOT NULL,
    published_at timestamp with time zone
);
CREATE INDEX ix_facility_outbox_pending ON public.facility_outbox (id) WHERE published_at IS NULL;
