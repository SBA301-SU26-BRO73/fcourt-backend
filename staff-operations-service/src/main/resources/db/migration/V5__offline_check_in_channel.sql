ALTER TABLE staff_check_in_audit DROP CONSTRAINT staff_check_in_audit_channel_check;
ALTER TABLE staff_check_in_audit ADD CONSTRAINT staff_check_in_audit_channel_check
    CHECK(channel IN ('QR','MANUAL','OFFLINE'));
