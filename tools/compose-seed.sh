#!/usr/bin/env bash
# Seed tai khoan test cho moi role sau khi `docker compose --profile core|all up -d`.
# Chay lai nhieu lan van an toan (idempotent). Chi dung local, mat khau la gia tri DEV.
#
#   bash tools/compose-seed.sh
#
# Du lieu mau (owner01..05, staff01..05, customer01..05, superadmin, chi nhanh, san, khung gio, goi dang ky)
# do Flyway cua tung service tao san. Script nay:
#   1. dat mat khau DEV chung cho cac tai khoan demo (SQL tren auth_db: demo hash goc khong biet mat khau)
#   2. dang ky them mot customer moi bang API that (qua gateway)
#   3. dang ky mot chu san moi bang API that (multipart), super admin duyet bang API
#   4. dang nhap tung role qua gateway va in token ngan
set -euo pipefail

GATEWAY="${GATEWAY_URL:-http://localhost:8080}"
PASSWORD="${SEED_PASSWORD:-Fcourt@123}"
cd "$(dirname "$0")/.."
DC="docker compose --profile all"
PSQL="$DC exec -T postgres psql -v ON_ERROR_STOP=1 -U ${POSTGRES_USER:-postgres}"

echo ">> 1. Dat mat khau DEV cho tai khoan demo (@demo.com)"
$PSQL -d auth_db -q <<SQL
CREATE EXTENSION IF NOT EXISTS pgcrypto;
UPDATE users SET password_hash = crypt('${PASSWORD}', gen_salt('bf', 10)),
                 password_changed_at = NULL
 WHERE email LIKE '%@demo.com';
SQL

json_field() { sed -n "s/.*\"$1\":\"\([^\"]*\)\".*/\1/p" | head -1; }

echo ">> Cho gateway thay auth-service trong Eureka (toi da 90s)"
for i in $(seq 1 30); do
  c=$(curl -s -o /dev/null -w "%{http_code}" -X POST "$GATEWAY/api/auth/login" -H "Content-Type: application/json" -d "{}" || true)
  [ "$c" = 400 ] && break; sleep 3
done

echo ">> 2. Dang ky customer moi qua API"
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$GATEWAY/api/auth/register/customer" \
  -H 'Content-Type: application/json' \
  -d "{\"fullName\":\"Khach Local\",\"email\":\"customer.local@fcourt.test\",\"phone\":\"0911000001\",\"password\":\"$PASSWORD\"}")
echo "   register customer -> HTTP $code (201 moi tao, 409 da co)"

echo ">> 3. Dang ky chu san moi (multipart) va duyet bang super admin"
png=$(mktemp --suffix=.png)
# PNG 1x1 hop le
printf '\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR\x00\x00\x00\x01\x00\x00\x00\x01\x08\x06\x00\x00\x00\x1f\x15\xc4\x89\x00\x00\x00\rIDATx\x9cc\xf8\xff\xff?\x00\x05\xfe\x02\xfe\xa7\x9a\xa0\xa0\x00\x00\x00\x00IEND\xaeB`\x82' > "$png"
code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$GATEWAY/api/auth/register/court-owner" \
  -F fullName='Chu San Local' -F email=owner.local@fcourt.test -F phone=0912000001 -F "password=$PASSWORD" \
  -F "legalDocuments=@$(cygpath -m "$png" 2>/dev/null || echo "$png");type=image/png" || true)
rm -f "$png"
echo "   register court-owner -> HTTP $code (201 moi tao, 409 da co)"

login() { # email -> access token
  curl -s -X POST "$GATEWAY/api/auth/login" -H 'Content-Type: application/json' \
    -d "{\"email\":\"$1\",\"password\":\"$PASSWORD\"}" | json_field accessToken
}

SA=$(login superadmin@demo.com)
[ -n "$SA" ] || { echo "Khong dang nhap duoc superadmin@demo.com (auth chua san sang?)"; exit 1; }
for id in $(curl -s -H "Authorization: Bearer $SA" "$GATEWAY/api/super-admin/court-admins/pending" \
            | grep -oE '"id":[0-9]*,"(email|fullName)"' | grep -o '[0-9]*'); do
  c=$(curl -s -o /dev/null -w '%{http_code}' -X PUT -H "Authorization: Bearer $SA" "$GATEWAY/api/super-admin/court-admins/$id/approve")
  echo "   approve pending owner id=$id -> HTTP $c"
done

echo ">> 4. Dang nhap tung role qua gateway"
for acct in superadmin@demo.com owner01@demo.com owner02@demo.com staff01@demo.com customer01@demo.com \
            customer.local@fcourt.test owner.local@fcourt.test owner06@demo.com; do
  t=$(login "$acct" || true)
  if [ -n "$t" ]; then s="OK ${t:0:16}..."; else s=FAIL; fi
  echo "   $acct  $s"
done
echo "Mat khau chung: $PASSWORD"
