-- reporting-service: court / time-slot dimensions for analytics (SCRUM-43, SCRUM-74).
-- Still filled only from Kafka events. Payment events identify the court by name (unique per branch in
-- facility-service), walk-in events by id, so both columns are optional and resolved at query time.
ALTER TABLE booking_facts ADD COLUMN court_id bigint;
ALTER TABLE booking_facts ADD COLUMN court_name character varying(200);

-- One row per (booking, slot start). Status/date/branch are read from booking_facts, never copied.
CREATE TABLE booking_slot_facts (
    booking_id bigint NOT NULL REFERENCES booking_facts (booking_id) ON DELETE CASCADE,
    slot_start time without time zone NOT NULL,
    PRIMARY KEY (booking_id, slot_start)
);

CREATE INDEX ix_booking_facts_branch_date_status ON booking_facts (branch_id, booking_date, status);
