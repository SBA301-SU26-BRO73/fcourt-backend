-- SCRUM-40: some events carry a secret (PasswordResetRequestedEvent has the raw reset token).
-- Their payload is replaced by metadata only once Kafka acknowledged it, so the token does not
-- stay readable in auth_outbox after delivery.
ALTER TABLE public.auth_outbox ADD COLUMN sensitive boolean NOT NULL DEFAULT false;
