ALTER TABLE payments ADD COLUMN status_version bigint NOT NULL DEFAULT 0;
CREATE FUNCTION payment_status_version() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.status IS DISTINCT FROM OLD.status OR NEW.submitted_at IS DISTINCT FROM OLD.submitted_at
     OR NEW.rejection_reason IS DISTINCT FROM OLD.rejection_reason THEN
   NEW.status_version := OLD.status_version+1;
   NEW.updated_at := clock_timestamp();
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER payment_status_version BEFORE UPDATE ON payments FOR EACH ROW EXECUTE FUNCTION payment_status_version();
