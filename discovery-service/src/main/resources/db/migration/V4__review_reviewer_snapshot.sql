-- Snapshot of the reviewer shown publicly, taken when the review is written (discovery never reads the auth DB).
-- Nullable: reviews created before this migration keep NULL and are served with a generic name and no avatar.
ALTER TABLE reviews
    ADD COLUMN reviewer_display_name varchar(80),
    ADD COLUMN reviewer_avatar_url varchar(2048);
