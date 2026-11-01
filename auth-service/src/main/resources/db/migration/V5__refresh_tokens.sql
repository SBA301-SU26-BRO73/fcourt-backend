-- SCRUM-6: opaque refresh tokens. Only the SHA-256 of the token is stored, so a
-- database leak does not expose usable tokens. Tokens rotate on every refresh;
-- a revoked token that is presented again revokes the whole session set.
CREATE TABLE public.refresh_tokens (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id bigint NOT NULL REFERENCES public.users (id) ON DELETE CASCADE,
    token_hash char(64) NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);

-- Revoking every live session of a user (reuse detection, password change).
CREATE INDEX idx_refresh_tokens_user_active ON public.refresh_tokens (user_id)
    WHERE revoked_at IS NULL;
