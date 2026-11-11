#!/usr/bin/env bash
# Phase 5: dat san lich su (30 ngay qua) + tuong lai (10 ngay toi), thanh toan, hoan tien, fact dashboard, seed truc tiep vao DB cua service so huu
# (booking_db, payment_db, reporting_db). Luong API (hold -> booking -> bien lai -> duyet) hien 503 tren EKS vi booking/payment/slot-hold thieu cac token
# service-to-service (BOOKING_HOLD_SERVICE_TOKEN, PAYMENT_BOOKING_SERVICE_TOKEN, ...) va API khong cho dat gio da qua. Khi token da co, phase 6 (online) dat them qua API.
# Idempotent: bo qua neu da co booking co ma 'FS%'.
log "5. Booking / payment / refund / reporting facts (SQL, moi DB cua service so huu)"
if [ "$(psql_db booking_db -c "select count(*) from bookings where booking_code like 'FS%'" </dev/null)" != "0" ]; then
  echo "   da seed truoc do, bo qua"
else

S3_BUCKET="${S3_BUCKET:-$(kubectl -n fcourt-prod get cm facility-service-config -o jsonpath='{.data.STORAGE_S3_BUCKET}')}"
export AWS_PROFILE="${AWS_PROFILE:-tpn12}" AWS_REGION="${AWS_REGION:-ap-southeast-1}"
RECEIPT_KEY="receipts/seed/receipt-sample.jpg"
aws s3api head-object --bucket "$S3_BUCKET" --key "$RECEIPT_KEY" >/dev/null 2>&1 || \
  aws s3 cp "$(winpath "$SEED_DIR/seed-assets/receipt-sample.jpg")" "s3://$S3_BUCKET/$RECEIPT_KEY" --content-type image/jpeg --only-show-errors

W="$TMPD/book"; mkdir -p "$W"
psql_db facility_db -c "COPY (SELECT c.id, c.branch_id, b.name, concat_ws(', ', b.address, b.ward, b.district, b.city), coalesce(b.phone,''), c.name, c.court_type_id, b.admin_id, b.bank_name, b.bank_bin, b.bank_account_number, b.bank_account_name FROM courts c JOIN branches b ON b.id=c.branch_id WHERE c.deleted_at IS NULL) TO STDOUT WITH CSV" </dev/null > "$W/courts.csv"
psql_db facility_db -c "COPY (SELECT court_id, day_of_week, start_time, id FROM time_slot_templates WHERE deleted_at IS NULL) TO STDOUT WITH CSV" </dev/null > "$W/templates.csv"
psql_db auth_db -c "COPY (SELECT id, full_name, phone FROM users WHERE role='CUSTOMER' AND status='ACTIVE' AND deleted_at IS NULL ORDER BY id) TO STDOUT WITH CSV" </dev/null > "$W/customers.csv"
echo "   $(wc -l < "$W/courts.csv") san, $(wc -l < "$W/customers.csv") khach"

{
cat <<'SQL'
BEGIN;
CREATE TEMP TABLE courts_in(court_id bigint, branch_id bigint, branch_name text, address text, phone text, court_name text, type_id int, admin_id bigint, bank_name text, bank_bin text, bank_acc text, bank_accname text);
COPY courts_in FROM STDIN WITH CSV;
SQL
cat "$W/courts.csv"; echo '\.'
echo "CREATE TEMP TABLE tpl_in(court_id bigint, dow int, st time, id bigint); COPY tpl_in FROM STDIN WITH CSV;"; cat "$W/templates.csv"; echo '\.'
echo "CREATE TEMP TABLE cust_in(id bigint, name text, phone text); COPY cust_in FROM STDIN WITH CSV;"; cat "$W/customers.csv"; echo '\.'
cat <<'SQL'
CREATE TEMP TABLE ncust AS SELECT count(*)::int n FROM cust_in;
CREATE TEMP TABLE cust_rank AS SELECT row_number() OVER (ORDER BY id) - 1 AS k, * FROM cust_in;
CREATE TEMP TABLE hourly(type_id int, morn int, dayp int, pk_wd int, pk_we int);
INSERT INTO hourly VALUES (1,80000,100000,150000,180000),(2,250000,300000,500000,600000),(3,120000,150000,250000,300000),(4,150000,200000,300000,350000),(6,40000,50000,80000,100000);
CREATE TEMP TABLE now_vn AS SELECT (now() AT TIME ZONE 'Asia/Ho_Chi_Minh')::date AS today, (now() AT TIME ZONE 'Asia/Ho_Chi_Minh')::time AS tnow;

-- ung vien: san x ngay (-30..+10) x gio bat dau; chon theo hash co dinh (chay lai ra cung ket qua)
CREATE TEMP TABLE cand AS
SELECT c.*, d::date AS bdate, s.st, abs(hashtextextended(c.court_id||'|'||d::date||'|'||s.st, 11)) AS h
FROM courts_in c, now_vn n,
     generate_series(n.today - 30, n.today + 10, interval '1 day') d,
     (VALUES (time '07:00'),(time '09:00'),(time '16:00'),(time '18:00'),(time '20:00')) s(st);
CREATE TEMP TABLE pick AS
SELECT row_number() OVER (ORDER BY bdate, court_id, st) AS id, cand.*,
       2 + (h/7 % 3)::int AS nslots,
       (h/13 % 100) AS r
FROM cand, now_vn n0
WHERE (h % 100) < (CASE WHEN st >= '18:00' THEN 34 WHEN st = '16:00' THEN 22 WHEN st = '09:00' THEN 12 ELSE 9 END
                   + CASE WHEN extract(dow FROM bdate) IN (0,6) THEN 12 ELSE 0 END - CASE WHEN court_id % 5 = 0 THEN 6 ELSE 0 END);

CREATE TEMP TABLE slots AS
SELECT p.id AS booking_id, p.court_id, p.bdate, (p.st + k * interval '30 minutes')::time AS s_start,
       (p.st + (k+1) * interval '30 minutes')::time AS s_end,
       (CASE WHEN (p.st + k * interval '30 minutes')::time < '08:30' THEN h.morn
             WHEN (p.st + k * interval '30 minutes')::time < '17:00' THEN h.dayp
             WHEN extract(dow FROM p.bdate) IN (0,6) THEN h.pk_we ELSE h.pk_wd END / 2)::numeric AS price,
       t.id AS source_slot_id
FROM pick p JOIN hourly h ON h.type_id = p.type_id
CROSS JOIN LATERAL generate_series(0, p.nslots - 1) k
LEFT JOIN tpl_in t ON t.court_id = p.court_id AND t.dow = extract(dow FROM p.bdate)::int AND t.st = (p.st + k * interval '30 minutes')::time;

CREATE TEMP TABLE plan AS
SELECT p.id AS booking_id, p.court_id, p.branch_id, p.branch_name, p.address, p.phone, p.court_name,
  CASE WHEN p.r % 10 = 0 THEN NULL ELSE cr.id END AS customer_id,
  CASE WHEN p.r % 10 = 0 THEN NULL ELSE cr.name END AS customer_name,
  CASE WHEN p.r % 10 = 0 THEN NULL ELSE cr.phone END AS customer_phone,
  CASE WHEN p.r % 10 = 0 THEN '09' || lpad((10000000 + p.h % 89999999)::text, 8, '0') END AS guest_phone,
  p.bdate, p.st, (p.st + p.nslots * interval '30 minutes')::time AS en, p.nslots,
  (SELECT sum(price) FROM slots s WHERE s.booking_id = p.id) AS total,
  p.admin_id, p.bank_name, p.bank_bin, p.bank_acc, p.bank_accname, p.type_id, p.h, p.r,
  CASE
    WHEN p.bdate < n.today THEN CASE WHEN (p.h/101 % 100) < 84 THEN 'COMPLETED' ELSE 'CANCELLED' END
    WHEN p.bdate = n.today THEN CASE WHEN (p.h/101 % 100) >= 90 THEN 'CANCELLED'
                                     WHEN (p.st + p.nslots * interval '30 minutes')::time <= n.tnow THEN 'COMPLETED'
                                     WHEN p.st <= n.tnow THEN 'CHECKED_IN' ELSE 'CONFIRMED' END
    ELSE CASE WHEN (p.h/101 % 100) < 70 THEN 'CONFIRMED'
              WHEN (p.h/101 % 100) < 78 AND p.bdate <= n.today + 4 THEN 'AWAITING_CONFIRMATION'
              WHEN (p.h/101 % 100) < 90 THEN 'CANCELLED' ELSE 'CONFIRMED' END
  END AS status
FROM pick p JOIN now_vn n ON true
JOIN ncust ON true JOIN cust_rank cr ON cr.k = (p.h / 3) % ncust.n;

ALTER TABLE plan ADD COLUMN created_at timestamptz, ADD COLUMN paid_at timestamptz, ADD COLUMN checkin_code text, ADD COLUMN checked_in_at timestamptz,
  ADD COLUMN completed_at timestamptz, ADD COLUMN cancelled_at timestamptz, ADD COLUMN cancel_reason text, ADD COLUMN refund numeric, ADD COLUMN was_paid boolean;
UPDATE plan SET
  created_at = least(now() - interval '20 minutes', ((bdate - (1 + h % 5) * interval '1 day') + time '08:00' + (h % 700) * interval '1 minute') AT TIME ZONE 'Asia/Ho_Chi_Minh'),
  was_paid = (status <> 'CANCELLED') OR (status = 'CANCELLED' AND h % 5 < 2);
UPDATE plan SET paid_at = CASE WHEN was_paid THEN least(now() - interval '10 minutes', created_at + (10 + h % 50) * interval '1 minute') END,
  checkin_code = CASE WHEN was_paid AND status NOT IN ('AWAITING_CONFIRMATION','CANCELLED') THEN lpad(booking_id::text || '4711', 32, '0') END,
  checked_in_at = CASE WHEN status IN ('COMPLETED','CHECKED_IN') THEN ((bdate + st) AT TIME ZONE 'Asia/Ho_Chi_Minh') - interval '8 minutes' END,
  completed_at = CASE WHEN status = 'COMPLETED' THEN (bdate + en) AT TIME ZONE 'Asia/Ho_Chi_Minh' END,
  cancelled_at = CASE WHEN status = 'CANCELLED' THEN least(now() - interval '5 minutes', created_at + (2 + h % 40) * interval '1 hour') END,
  cancel_reason = CASE WHEN status = 'CANCELLED' THEN (ARRAY['Bận việc đột xuất','Thay đổi kế hoạch','Trời mưa nên hủy','Đổi sang khung giờ khác','Nhóm không đủ người'])[1 + h % 5] END;
-- booking cho khach vang lai da huy thi khong co hoan tien tai khoan
UPDATE plan SET refund = CASE WHEN status = 'CANCELLED' AND was_paid THEN total END;
-- AWAITING_CONFIRMATION: da nop bien lai nhung chua duoc duyet
UPDATE plan SET was_paid = false, paid_at = created_at + interval '12 minutes' WHERE status = 'AWAITING_CONFIRMATION';

INSERT INTO bookings (id, court_id, customer_id, payment_id, guest_phone, date, status, total_price, created_at, updated_at, checkin_code, checked_in_at, completed_at,
   booking_code, branch_id, branch_name, branch_address, branch_phone, court_name, customer_name, qr_image_url, receipt_image_url, payment_status,
   cancellation_reason, cancelled_at, refund_amount, cancelled_by, cancelled_by_role)
OVERRIDING SYSTEM VALUE
SELECT booking_id, court_id, customer_id, booking_id, guest_phone, bdate, status, total, created_at, coalesce(completed_at, cancelled_at, checked_in_at, paid_at, created_at),
   checkin_code, checked_in_at, completed_at, 'FS' || lpad(booking_id::text, 8, '0'), branch_id, branch_name, address, nullif(phone,''), court_name, customer_name,
   CASE WHEN checkin_code IS NOT NULL THEN '/api/payments/' || booking_id || '/ticket/qr' END,
   CASE WHEN was_paid OR status = 'AWAITING_CONFIRMATION' THEN 'receipts/seed/receipt-sample.jpg' END,
   CASE WHEN status = 'AWAITING_CONFIRMATION' THEN 'PENDING' WHEN was_paid THEN 'CONFIRMED' ELSE 'CANCELLED' END,
   cancel_reason, cancelled_at, refund, CASE WHEN status = 'CANCELLED' THEN customer_id END, CASE WHEN status = 'CANCELLED' AND customer_id IS NOT NULL THEN 'CUSTOMER' END
FROM plan;
INSERT INTO booking_slots (booking_id, court_id, booking_date, slot_start, slot_end, price_at_booking, source_slot_id, active)
SELECT s.booking_id, s.court_id, s.bdate, s.s_start, s.s_end, s.price, s.source_slot_id, pl.status <> 'CANCELLED' FROM slots s JOIN plan pl ON pl.booking_id = s.booking_id;
COMMIT;
BEGIN;
COPY (SELECT p.booking_id, p.court_id, p.branch_id, p.branch_name, p.court_name, p.customer_id, coalesce(p.customer_name,''), coalesce(p.customer_phone, p.guest_phone), p.bdate, p.status, p.total,
        'FS' || lpad(p.booking_id::text, 8, '0'), p.created_at, p.paid_at, p.checkin_code, p.cancelled_at, p.cancel_reason, p.refund, p.was_paid, p.admin_id,
        p.bank_name, p.bank_bin, p.bank_acc, p.bank_accname, p.nslots, p.completed_at,
        (SELECT json_agg(json_build_object('startTime', to_char(s.s_start,'HH24:MI'), 'endTime', to_char(s.s_end,'HH24:MI'), 'price', s.price) ORDER BY s.s_start)::text FROM slots s WHERE s.booking_id = p.booking_id),
        (SELECT string_agg(to_char(s.s_start,'HH24:MI:SS'), ';' ORDER BY s.s_start) FROM slots s WHERE s.booking_id = p.booking_id)
      FROM plan p ORDER BY p.booking_id) TO STDOUT WITH CSV;
COMMIT;
SQL
} | psql_db booking_db > "$W/plan.csv"
psql_db booking_db -c "SELECT setval(pg_get_serial_sequence('bookings','id'), greatest((SELECT max(id) FROM bookings), 1))" </dev/null >/dev/null
echo "   booking_db: $(psql_db booking_db -c "select count(*) from bookings where booking_code like 'FS%'" </dev/null) booking"

PLAN_DDL="CREATE TEMP TABLE plan(booking_id bigint, court_id bigint, branch_id bigint, branch_name text, court_name text, customer_id bigint, customer_name text, phone text, bdate date, status text, total numeric, code text, created_at timestamptz, paid_at timestamptz, checkin_code text, cancelled_at timestamptz, cancel_reason text, refund numeric, was_paid boolean, admin_id bigint, bank_name text, bank_bin text, bank_acc text, bank_accname text, nslots int, completed_at timestamptz, slots_json text, starts text);"
{ echo "BEGIN;"; echo "$PLAN_DDL"; echo "COPY plan FROM STDIN WITH CSV;"; cat "$W/plan.csv"; echo '\.'
cat <<'SQL'
INSERT INTO payments (id, branch_id, amount, bill_image_url, status, confirmed_at, confirmed_by, created_at, updated_at, booking_id, checkout, submitted_at, rejection_reason,
   check_in_code, qr_image_url, expires_at, customer_id, customer_phone)
OVERRIDING SYSTEM VALUE
SELECT booking_id, branch_id, total,
  CASE WHEN was_paid OR status = 'AWAITING_CONFIRMATION' THEN 'receipts/seed/receipt-sample.jpg' END,
  CASE WHEN status = 'AWAITING_CONFIRMATION' THEN 'PENDING' WHEN was_paid THEN 'CONFIRMED' ELSE 'CANCELLED' END,
  CASE WHEN was_paid THEN paid_at + interval '3 minutes' END,
  CASE WHEN was_paid THEN admin_id END,
  created_at, coalesce(paid_at, cancelled_at, created_at), booking_id,
  jsonb_build_object('bookingId', booking_id, 'bookingCode', code, 'branchId', branch_id, 'branchName', branch_name, 'courtName', court_name, 'date', bdate,
     'slots', slots_json::jsonb, 'amount', total, 'bankName', bank_name, 'bankBin', bank_bin, 'bankAccountNumber', bank_acc, 'bankAccountName', bank_accname,
     'transferContent', 'FC ' || code, 'customerName', customer_name),
  CASE WHEN was_paid OR status = 'AWAITING_CONFIRMATION' THEN coalesce(paid_at, created_at + interval '15 minutes') END, NULL,
  CASE WHEN was_paid AND status <> 'CANCELLED' THEN checkin_code END,
  CASE WHEN was_paid AND status <> 'CANCELLED' THEN '/api/payments/' || booking_id || '/ticket/qr' END,
  created_at + interval '15 minutes', customer_id, phone
FROM plan;
INSERT INTO refund_requests (booking_id, amount, reason, refund_bank_name, refund_bank_account_number, refund_bank_account_name, status, confirmed_at, created_at, updated_at,
   customer_id, branch_id, bank_bin, admin_note, approved_at)
SELECT booking_id, refund, cancel_reason, 'Vietcombank', '0' || lpad((booking_id * 7919 % 999999999)::text, 9, '0'),
   upper(translate(customer_name, 'ăâđêôơưáàảãạấầẩẫậắằẳẵặéèẻẽẹếềểễệíìỉĩịóòỏõọốồổỗộớờởỡợúùủũụứừửữựýỳỷỹỵĂÂĐÊÔƠƯÁÀẢÃẠẤẦẨẪẬẮẰẲẴẶÉÈẺẼẸẾỀỂỄỆÍÌỈĨỊÓÒỎÕỌỐỒỔỖỘỚỜỞỠỢÚÙỦŨỤỨỪỬỮỰÝỲỶỸỴ', 'aadeoouaaaaaaaaaaaaaaaeeeeeeeeeeeiiiiiooooooooooooooooouuuuuuuuuuuyyyyyAADEOOUAAAAAAAAAAAAAAAEEEEEEEEEEEIIIIIOOOOOOOOOOOOOOOOOUUUUUUUUUUUYYYYY')),
   CASE WHEN bdate >= (now() AT TIME ZONE 'Asia/Ho_Chi_Minh')::date THEN 'PENDING' ELSE 'COMPLETED' END,
   CASE WHEN bdate < (now() AT TIME ZONE 'Asia/Ho_Chi_Minh')::date THEN cancelled_at + interval '1 day' END, cancelled_at, cancelled_at,
   customer_id, branch_id, '970436',
   CASE WHEN bdate < (now() AT TIME ZONE 'Asia/Ho_Chi_Minh')::date THEN 'Đã hoàn tiền 100%' END,
   CASE WHEN bdate < (now() AT TIME ZONE 'Asia/Ho_Chi_Minh')::date THEN cancelled_at + interval '1 day' END
FROM plan WHERE status = 'CANCELLED' AND was_paid AND customer_id IS NOT NULL;
COMMIT;
SQL
} | psql_db payment_db >/dev/null
psql_db payment_db -c "SELECT setval(pg_get_serial_sequence('payments','id'), greatest((SELECT max(id) FROM payments), 1))" </dev/null >/dev/null
echo "   payment_db: $(psql_db payment_db -c "select count(*) from payments" </dev/null) payment, $(psql_db payment_db -c "select count(*) from refund_requests" </dev/null) refund"

{ echo "BEGIN;"; echo "$PLAN_DDL"; echo "COPY plan FROM STDIN WITH CSV;"; cat "$W/plan.csv"; echo '\.'
cat <<'SQL'
INSERT INTO booking_facts (booking_id, branch_id, status, amount, revenue, revenue_at, booking_date, slot_count, last_event_type, created_at, updated_at, court_id, court_name, customer_name)
SELECT booking_id, branch_id,
  CASE WHEN status = 'AWAITING_CONFIRMATION' THEN 'PENDING' WHEN status = 'CANCELLED' THEN 'CANCELLED' ELSE 'CONFIRMED' END,
  total, CASE WHEN status IN ('CONFIRMED','CHECKED_IN','COMPLETED') THEN total ELSE 0 END,
  CASE WHEN status IN ('CONFIRMED','CHECKED_IN','COMPLETED') THEN paid_at + interval '3 minutes' END,
  bdate, nslots,
  CASE status WHEN 'AWAITING_CONFIRMATION' THEN 'PaymentSubmittedEvent' WHEN 'CANCELLED' THEN 'BookingCancelledEvent' WHEN 'COMPLETED' THEN 'BookingCompletedEvent'
              WHEN 'CHECKED_IN' THEN 'BookingCheckedInEvent' ELSE 'PaymentConfirmedEvent' END,
  created_at, coalesce(completed_at, paid_at, cancelled_at, created_at), court_id, court_name, nullif(customer_name, '')
FROM plan ON CONFLICT (booking_id) DO NOTHING;
INSERT INTO booking_slot_facts (booking_id, slot_start)
SELECT p.booking_id, unnest(string_to_array(p.starts, ';'))::time FROM plan p ON CONFLICT DO NOTHING;
COMMIT;
SQL
} | psql_db reporting_db >/dev/null
echo "   reporting_db: $(psql_db reporting_db -c "select count(*) from booking_facts" </dev/null) fact"
fi
