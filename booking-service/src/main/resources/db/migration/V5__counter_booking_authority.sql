ALTER TABLE booking_slots ADD COLUMN source_slot_id bigint;
CREATE FUNCTION release_cancelled_booking_slots() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.status='CANCELLED' THEN UPDATE booking_slots SET active=false WHERE booking_id=NEW.id;
  ELSIF OLD.status='CANCELLED' AND NEW.status<>'CANCELLED' THEN UPDATE booking_slots SET active=true WHERE booking_id=NEW.id;
  END IF;
  RETURN NEW;
END;
$$;
CREATE TRIGGER release_cancelled_booking_slots AFTER UPDATE OF status ON bookings FOR EACH ROW EXECUTE FUNCTION release_cancelled_booking_slots();
CREATE TABLE booking_cash_settlements(
  booking_id bigint PRIMARY KEY REFERENCES bookings(id),actor_id bigint NOT NULL,
  surcharge_amount numeric(10,2) NOT NULL CHECK(surcharge_amount>=0),
  total_amount numeric(10,2) NOT NULL CHECK(total_amount>=surcharge_amount),
  completed_at timestamptz NOT NULL,policy_version varchar(64) NOT NULL
);
