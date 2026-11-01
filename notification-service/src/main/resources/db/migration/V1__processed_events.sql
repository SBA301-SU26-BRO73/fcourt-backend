-- SCRUM-42: every event that was handled, so a redelivered event never sends a second email.
-- event_id is the producer's eventId. business_key additionally stops two different events that
-- describe the same thing (for example a booking cancelled by the customer and by payment expiry)
-- from producing two emails. No recipient address is stored here.
CREATE TABLE public.processed_events (
    event_id uuid PRIMARY KEY,
    event_type varchar(60) NOT NULL,
    business_key varchar(120) UNIQUE,
    outcome varchar(30) NOT NULL CHECK (outcome IN ('SENT', 'SKIPPED_NO_RECIPIENT')),
    processed_at timestamptz NOT NULL DEFAULT now()
);
