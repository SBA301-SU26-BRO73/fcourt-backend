-- SCRUM-40: one-time password reset tokens. Only the SHA-256 of the emailed token is stored.
-- A new request invalidates earlier unused tokens of the same user.
CREATE TABLE public.password_reset_tokens (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id bigint NOT NULL REFERENCES public.users (id) ON DELETE CASCADE,
    token_hash char(64) NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL,
    used_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);

-- Invalidating open tokens and the per-user request cooldown both look at the user's recent rows.
CREATE INDEX idx_password_reset_tokens_user_created ON public.password_reset_tokens (user_id, created_at);
