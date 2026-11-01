-- SCRUM-8: court owner (role ADMIN) approval workflow.
-- Additive only: V1 columns are written directly by tools/demo_seed.

-- A super admin can now reject a pending owner registration.
ALTER TABLE public.users DROP CONSTRAINT users_status_check;
ALTER TABLE public.users ADD CONSTRAINT users_status_check CHECK (
    (status)::text = ANY ((ARRAY['PENDING_APPROVAL', 'ACTIVE', 'INACTIVE', 'LOCKED', 'REJECTED'])::text[]));

-- Super admin review queue: pending owners ordered by registration time.
CREATE INDEX idx_users_role_status_created ON public.users (role, status, created_at)
    WHERE deleted_at IS NULL;

-- Transactional outbox for domain events published to Kafka (fcourt.auth.events).
CREATE TABLE public.auth_outbox (
    event_id uuid PRIMARY KEY,
    aggregate_id bigint NOT NULL,
    event_type varchar(60) NOT NULL,
    payload text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    published_at timestamptz
);
CREATE INDEX idx_auth_outbox_pending ON public.auth_outbox (created_at, event_id)
    WHERE published_at IS NULL;
