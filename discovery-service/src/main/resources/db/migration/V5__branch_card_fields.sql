-- List cards show a cover photo and opening hours (open_time/close_time are "HH:mm").
-- Rows projected before this migration stay NULL until the branch next changes.
ALTER TABLE branch_read_model
    ADD COLUMN image_url TEXT,
    ADD COLUMN open_time TEXT,
    ADD COLUMN close_time TEXT;
