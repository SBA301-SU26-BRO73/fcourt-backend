-- Brute-force throttle for password checks (login by email, change password by user id).
-- One row per key: failures counted inside a window, then a temporary lock. Keys exist for
-- unknown emails too, so a 429 does not reveal whether an email is registered.
CREATE TABLE public.failed_attempts (
    attempt_key varchar(300) PRIMARY KEY,
    failures integer NOT NULL,
    window_started_at timestamptz NOT NULL,
    locked_until timestamptz
);
