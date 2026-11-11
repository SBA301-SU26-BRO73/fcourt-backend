#!/usr/bin/env bash
# Smoke test qua gateway sau tools/compose-seed.sh (profile all). In bang "role | request | HTTP". Chi dung local.
GATEWAY="${GATEWAY_URL:-http://localhost:8080}"
PASSWORD="${SEED_PASSWORD:-Fcourt@123456}"
ORIGIN="${WEB_ORIGIN:-http://localhost:5173}"
DATE="${SMOKE_DATE:-$(date -d "+$((3 + RANDOM % 25)) day" +%F)}"   # ngay ngau nhien de chay lai khong dung cho da giu
BRANCH=1; COURT=21   # chi nhanh cua owner01 va mot san cua chi nhanh do (co khung gio)

jf() { sed -n "s/.*\"$1\":\"\{0,1\}\([^\",}]*\)\"\{0,1\}.*/\1/p" | head -1; }
tok() { curl -s -X POST "$GATEWAY/api/auth/login" -H 'Content-Type: application/json' \
  -d "{\"email\":\"$1\",\"password\":\"$PASSWORD\"}" | jf accessToken; }
T_SA=$(tok superadmin@demo.com); T_OWN=$(tok owner01@demo.com); T_STF=$(tok staff01@demo.com)
T_CUS=$(tok customer01@demo.com); T_NEW=$(tok customer.local@fcourt.test)
T_OWL=$(tok owner.local@fcourt.test); T_STL=$(tok staff.local@fcourt.test)

chk() { # role method path [token] [body] [extra curl args...]  -> in dong bang, giu body o $BODY
  local role=$1 m=$2 p=$3 t=${4:-} b=${5:-}; shift 5 2>/dev/null || shift $#
  local args=(-s -w '\n%{http_code}' -X "$m" "$GATEWAY$p" -H 'Content-Type: application/json')
  [ -n "$t" ] && args+=(-H "Authorization: Bearer $t")
  [ -n "$b" ] && args+=(-d "$b")
  local out; out=$(curl "${args[@]}" "$@"); CODE=${out##*$'\n'}; BODY=${out%$'\n'*}
  printf '| %-11s | %-6s %-66s | %s |\n' "$role" "$m" "$p" "$CODE"
}
echo "| Role | Request | HTTP |"; echo "|---|---|---|"
chk guest GET /api/public/branches
chk guest GET /api/public/branches/$BRANCH
chk guest GET /api/public/branches/$BRANCH/courts
chk guest GET "/api/public/courts/$COURT/slots?date=$DATE"
chk guest GET /api/court-types
chk guest GET /api/subscription-plans/all
chk guest GET /api/public/branches/$BRANCH/reviews
chk guest GET /actuator/health
chk guest GET /docs/auth-service/v3/api-docs
chk guest GET /api/auth/profile                    # 401 la dung
chk customer GET /api/auth/profile "$T_CUS"
chk customer GET /api/bookings/my "$T_CUS"
chk customer GET /api/payments/my-transactions "$T_CUS"
chk newcustomer GET /api/auth/profile "$T_NEW"
chk owner GET /api/auth/profile "$T_OWN"
chk owner GET /api/admin/branches "$T_OWN"
chk owner GET /api/admin/branches/$BRANCH/courts "$T_OWN"
chk owner GET "/api/admin/courts/$COURT/time-slot-templates" "$T_OWN"
chk owner GET /api/admin/bookings "$T_OWN"
chk owner GET /api/admin/dashboard/stats "$T_OWN"
chk owner GET "/api/admin/payments/pending?branchId=$BRANCH" "$T_OWN"
chk owner GET "/api/admin/payments/transactions?branchId=$BRANCH" "$T_OWN"
chk owner GET "/api/admin/refunds/pending?branchId=$BRANCH" "$T_OWN"
chk owner GET "/api/admin/staff?branchId=$BRANCH" "$T_OWN"
chk owner GET /api/admin/vouchers "$T_OWN"
chk owner GET "/api/admin/pos/products?branchId=$BRANCH" "$T_OWN"
chk owner GET /api/subscriptions/my-subscription "$T_OWN"
chk newowner GET /api/auth/profile "$T_OWL"
chk newowner GET /api/admin/branches "$T_OWL"
chk staff GET /api/auth/profile "$T_STF"
chk staff GET /api/staff/me "$T_STF"
chk staff GET /api/staff/branches/$BRANCH "$T_STF"
chk staff GET /api/staff/branches/$BRANCH/courts "$T_STF"
chk staff GET /api/staff/schedule/today "$T_STF"
chk staff GET "/api/staff/pos/products?branchId=$BRANCH" "$T_STF"
chk newstaff GET /api/staff/me "$T_STL"
chk superadmin GET /api/auth/profile "$T_SA"
chk superadmin GET /api/super-admin/users "$T_SA"
chk superadmin GET /api/super-admin/court-admins/pending "$T_SA"
chk superadmin GET /api/super-admin/dashboard/overview "$T_SA"
chk superadmin GET /api/super-admin/dashboard/details "$T_SA"
chk superadmin GET /api/super-admin/pos/products "$T_SA"

echo; echo "Guest: giu cho -> dat san -> tra cuu -> huy (khong dang nhap)"
PHONE=0987654321
SLOTS=$(curl -s "$GATEWAY/api/public/courts/$COURT/slots?date=$DATE" | grep -o '"slotId":[0-9]*' | cut -d: -f2 | sed -n "$((2 + RANDOM % 20)),+1p" | paste -sd, -)
chk guest POST /api/bookings/hold-slots "" "{\"courtId\":$COURT,\"date\":\"$DATE\",\"slotIds\":[$SLOTS],\"guestPhone\":\"$PHONE\"}"
HOLD=$(echo "$BODY" | jf holdToken)
KEY=$(printf '%08x-0000-4000-8000-%012x' $RANDOM $RANDOM)
chk guest POST /api/bookings "" "{\"holdToken\":\"$HOLD\",\"courtId\":$COURT,\"date\":\"$DATE\",\"slotIds\":[$SLOTS]}" -H "Idempotency-Key: $KEY" -H "X-Hold-Token: $HOLD"
CODE_B=$(echo "$BODY" | jf bookingCode); ID_B=$(echo "$BODY" | jf id)
echo "   bookingCode=$CODE_B id=$ID_B"
chk guest POST /api/public/bookings/lookup "" "{\"bookingCode\":\"$CODE_B\",\"phone\":\"$PHONE\"}"
chk guest POST /api/public/bookings/cancel "" "{\"bookingCode\":\"$CODE_B\",\"phone\":\"$PHONE\",\"reason\":\"smoke test\"}"
chk guest POST /api/public/bookings/lookup "" "{\"bookingCode\":\"$CODE_B\",\"phone\":\"$PHONE\"}"

echo; echo "Owner: upload anh san (POST/DELETE /api/admin/courts/$COURT/image)"
png=$(mktemp --suffix=.png)
printf '\x89PNG\r\n\x1a\n\x00\x00\x00\rIHDR\x00\x00\x00\x01\x00\x00\x00\x01\x08\x06\x00\x00\x00\x1f\x15\xc4\x89\x00\x00\x00\rIDATx\x9cc\xf8\xff\xff?\x00\x05\xfe\x02\xfe\xa7\x9a\xa0\xa0\x00\x00\x00\x00IEND\xaeB`\x82' > "$png"
f=$(cygpath -m "$png" 2>/dev/null || echo "$png")
c=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$GATEWAY/api/admin/courts/$COURT/image" -H "Authorization: Bearer $T_OWN" -F "file=@$f;type=image/png")
echo "| owner       | POST   /api/admin/courts/$COURT/image (multipart)  | $c |"
chk owner DELETE /api/admin/courts/$COURT/image "$T_OWN"
rm -f "$png"

echo; echo "CORS preflight tu $ORIGIN:"
curl -s -o /dev/null -D - -X OPTIONS "$GATEWAY/api/auth/login" -H "Origin: $ORIGIN" \
  -H 'Access-Control-Request-Method: POST' -H 'Access-Control-Request-Headers: authorization,content-type' \
  | grep -i -E "^HTTP|access-control-allow-(origin|methods)"
