-- In-app notifications shown by the web bell. One row per recipient user.
-- (user_id, dedupe_key) is unique: a replayed or duplicated event can never add a second row for the same user.
-- dedupe_key reuses the business keys of the email idempotency (booking-confirmed:<id>, booking-cancelled:<id>, ...).
CREATE TABLE in_app_notifications (
    id bigserial PRIMARY KEY,
    user_id bigint NOT NULL,
    type varchar(40) NOT NULL,
    title varchar(200) NOT NULL,
    body varchar(1000) NOT NULL,
    link varchar(255),
    dedupe_key varchar(160) NOT NULL,
    read_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_in_app_notifications_user_dedupe UNIQUE (user_id, dedupe_key)
);

CREATE INDEX idx_in_app_notifications_user_created ON in_app_notifications (user_id, created_at DESC, id DESC);
CREATE INDEX idx_in_app_notifications_user_unread ON in_app_notifications (user_id) WHERE read_at IS NULL;
