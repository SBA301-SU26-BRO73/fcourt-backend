-- Ghi nhận người hủy khi chủ sân hủy booking (POST /api/admin/bookings/{id}/cancel).
-- NULL khi khách tự hủy (customer_id của booking chính là người hủy).
ALTER TABLE bookings ADD COLUMN cancelled_by bigint;
ALTER TABLE bookings ADD COLUMN cancelled_by_role varchar(20);
