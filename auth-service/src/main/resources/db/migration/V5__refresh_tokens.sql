-- SCRUM-6: opaque refresh tokens. Only the SHA-256 of the token is stored, so a
-- database leak does not expose usable tokens. Tokens rotate on every refresh;
-- presenting a token that was already rotated (rotated_at set) is a replay and
-- revokes the whole session set. A token revoked any other way (logout, password
-- change, locked account) is simply refused.
CREATE TABLE public.refresh_tokens (
    id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id bigint NOT NULL REFERENCES public.users (id) ON DELETE CASCADE,
    token_hash char(64) NOT NULL UNIQUE,
    expires_at timestamptz NOT NULL,
    revoked_at timestamptz,
    rotated_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT refresh_tokens_rotated_is_revoked CHECK (rotated_at IS NULL OR revoked_at IS NOT NULL)
);

-- Revoking every live session of a user (reuse detection, password change).
CREATE INDEX idx_refresh_tokens_user_active ON public.refresh_tokens (user_id)
    WHERE revoked_at IS NULL;
