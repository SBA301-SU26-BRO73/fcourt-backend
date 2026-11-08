-- Keep the customer name snapshot present in the payment checkout event for revenue exports.
-- Older booking_facts rows are not backfilled from customer services; their customer_name remains NULL.
ALTER TABLE booking_facts ADD COLUMN customer_name text;
