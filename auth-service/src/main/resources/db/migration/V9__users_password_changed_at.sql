-- SCRUM-40: access tokens issued before the last password change (or reset) are refused, so a
-- stolen access token does not stay usable for the rest of its 15 minutes. NULL = never changed.
ALTER TABLE public.users ADD COLUMN password_changed_at timestamptz;
