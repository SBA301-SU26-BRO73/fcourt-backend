-- Keep the customer name snapshot present in the payment checkout event for revenue exports.
ALTER TABLE booking_facts ADD COLUMN customer_name text;
