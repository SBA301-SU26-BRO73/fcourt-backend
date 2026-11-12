#!/usr/bin/env bash
# Phase 1: tai khoan (giong compose-seed.sh nhung chay tren EKS qua kubectl exec + gateway).
log "1. Dat mat khau DEV chung + ten tieng Viet cho tai khoan demo (SQL tren auth_db)"
psql_db auth_db <<SQL
CREATE EXTENSION IF NOT EXISTS pgcrypto;
UPDATE users SET password_hash = crypt('${PASSWORD}', gen_salt('bf', 10)), password_changed_at = NULL
 WHERE (email LIKE '%@demo.com' OR email LIKE '%@fcourt.test')
   AND password_hash <> crypt('${PASSWORD}', password_hash);
UPDATE users u SET full_name = v.n FROM (VALUES
 ('owner01@demo.com','Nguyễn Văn Hùng'),('owner02@demo.com','Trần Minh Quân'),('owner03@demo.com','Lê Thị Thu Hà'),
 ('owner04@demo.com','Phạm Quốc Bảo'),('owner05@demo.com','Hoàng Thị Mai Anh'),('owner06@demo.com','Võ Thanh Tùng'),
 ('owner07@demo.com','Đặng Ngọc Lan'),('owner08@demo.com','Bùi Anh Khoa'),
 ('customer01@demo.com','Nguyễn Thị Lan Phương'),('customer02@demo.com','Trần Đức Anh'),('customer03@demo.com','Lê Hoàng Nam'),
 ('customer04@demo.com','Phạm Thùy Linh'),('customer05@demo.com','Vũ Minh Khang'),
 ('staff01@demo.com','Đỗ Văn Thành'),('staff02@demo.com','Ngô Thị Hạnh'),('staff03@demo.com','Dương Quốc Việt'),
 ('staff04@demo.com','Lý Thanh Tâm'),('staff05@demo.com','Trịnh Hải Yến'),
 ('superadmin@demo.com','Quản Trị Hệ Thống FCOURT')) AS v(e,n)
 WHERE u.email = v.e AND u.full_name IS DISTINCT FROM v.n;
SQL

log "   cho gateway thay auth-service (toi da 90s)"
for i in $(seq 1 30); do
  c=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$GATEWAY/api/auth/login" -H "Content-Type: application/json" -d "{}" || true)
  [ "$c" = 400 ] && break; sleep 3
done

log "2. Dang ky customer moi qua API"
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$GATEWAY/api/auth/register/customer" -H 'Content-Type: application/json' \
  -d "{\"fullName\":\"Khách Local\",\"email\":\"customer.local@fcourt.test\",\"phone\":\"0911000001\",\"password\":\"$PASSWORD\"}")
echo "   register customer -> HTTP $code (201 moi tao, 409 da co)"

log "3. Dang ky chu san moi (multipart) + super admin duyet"
png="$TMPD/doc.png"
printf '\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR\x00\x00\x00\x01\x00\x00\x00\x01\x08\x06\x00\x00\x00\x1f\x15\xc4\x89\x00\x00\x00\rIDATx\x9cc\xf8\xff\xff?\x00\x05\xfe\x02\xfe\xa7\x9a\xa0\xa0\x00\x00\x00\x00IEND\xaeB`\x82' > "$png"
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$GATEWAY/api/auth/register/court-owner" \
  -F fullName='Chủ Sân Local' -F email=owner.local@fcourt.test -F phone=0912000001 -F "password=$PASSWORD" \
  -F "legalDocuments=@$(winpath "$png");type=image/png" || true)
echo "   register court-owner -> HTTP $code (201 moi tao, 409 da co)"

# mat khau moi tao qua API da dung PASSWORD. Duyet moi owner dang cho (owner06..08 tu Flyway + owner.local)
SA=$(login superadmin@demo.com) || { echo "Khong dang nhap duoc superadmin@demo.com"; exit 1; }
for id in $(api GET /api/super-admin/court-admins/pending "$SA" | jq -r '..|objects|select(has("id") and has("email"))|.id' 2>/dev/null); do
  api PUT "/api/super-admin/court-admins/$id/approve" "$SA" >/dev/null
  echo "   approve pending owner id=$id -> HTTP $API_CODE"
done

log "3b. Gan nhan vien demo vao chi nhanh (SQL) + tao staff.local bang API"
psql_db staff_db <<SQL
INSERT INTO staff (user_id, branch_id, email, full_name, phone)
SELECT v.user_id, v.branch_id, v.email, v.name, v.phone FROM (VALUES
  (12, 1, 'staff01@demo.com', 'Đỗ Văn Thành', '0929000001'), (11, 2, 'staff02@demo.com', 'Ngô Thị Hạnh', '0929000002'),
  (15, 9, 'staff03@demo.com', 'Dương Quốc Việt', '0929000003'), (14, 15, 'staff04@demo.com', 'Lý Thanh Tâm', '0929000004'),
  (13, 16, 'staff05@demo.com', 'Trịnh Hải Yến', '0929000005')) AS v(user_id, branch_id, email, name, phone)
WHERE NOT EXISTS (SELECT 1 FROM staff s WHERE s.user_id = v.user_id);
SQL
OWN=$(login owner01@demo.com)
api POST /api/admin/staff "$OWN" "{\"branchId\":1,\"email\":\"staff.local@fcourt.test\",\"password\":\"$PASSWORD\",\"fullName\":\"Nhân Viên Local\",\"phone\":\"0913000001\",\"shifts\":[{\"dayOfWeek\":1,\"startTime\":\"06:00\",\"endTime\":\"22:00\"}]}" \
  -H "Idempotency-Key: 5eed0000-0000-4000-8000-000000000001" >/dev/null
echo "   create staff via API -> HTTP $API_CODE (201 moi tao, 200 replay, 409 da co)"
if [ "$API_CODE" = 503 ]; then
  # auth-service tren EKS thieu bien STAFF_URL (mac dinh localhost:8090) nen API tao staff tra 503: tao bang SQL.
  echo "   API 503 (auth-service thieu STAFF_URL) -> tao staff.local bang SQL"
  psql_db auth_db <<SQL
INSERT INTO users (email, password_hash, phone, role, status, full_name)
SELECT 'staff.local@fcourt.test', crypt('${PASSWORD}', gen_salt('bf', 10)), '0913000001', 'STAFF', 'ACTIVE', 'Nhân Viên Local'
 WHERE NOT EXISTS (SELECT 1 FROM users WHERE email = 'staff.local@fcourt.test');
SQL
  uid=$(psql_db auth_db -c "select id from users where email='staff.local@fcourt.test'")
  psql_db staff_db <<SQL
INSERT INTO staff (user_id, branch_id, email, full_name, phone)
SELECT $uid, 1, 'staff.local@fcourt.test', 'Nhân Viên Local', '0913000001'
 WHERE NOT EXISTS (SELECT 1 FROM staff WHERE user_id = $uid);
SQL
fi
