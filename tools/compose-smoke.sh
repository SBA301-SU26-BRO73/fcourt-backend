#!/usr/bin/env bash
# Smoke test qua gateway sau tools/compose-seed.sh. In bang "role | request | HTTP". Chi dung local.
GATEWAY="${GATEWAY_URL:-http://localhost:8080}"
PASSWORD="${SEED_PASSWORD:-Fcourt@123}"
ORIGIN="${WEB_ORIGIN:-http://localhost:5173}"
DATE="${SMOKE_DATE:-$(date -d '+2 day' +%F 2>/dev/null || date +%F)}"

tok() { curl -s -X POST "$GATEWAY/api/auth/login" -H 'Content-Type: application/json' \
  -d "{\"email\":\"$1\",\"password\":\"$PASSWORD\"}" | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p'; }
T_SA=$(tok superadmin@demo.com); T_OWN=$(tok owner01@demo.com); T_STF=$(tok staff01@demo.com)
T_CUS=$(tok customer01@demo.com); T_NEW=$(tok customer.local@fcourt.test)

chk() { # role method path [token] [body]
  local role=$1 m=$2 p=$3 t=${4:-} b=${5:-}
  local args=(-s -o /dev/null -w '%{http_code}' -X "$m" "$GATEWAY$p" -H 'Content-Type: application/json')
  [ -n "$t" ] && args+=(-H "Authorization: Bearer $t")
  [ -n "$b" ] && args+=(-d "$b")
  printf '| %-11s | %-6s %-60s | %s |\n' "$role" "$m" "$p" "$(curl "${args[@]}")"
}
echo "| Role | Request | HTTP |"; echo "|---|---|---|"
chk guest GET /api/public/branches
chk guest GET /api/public/branches/1
chk guest GET /api/public/branches/1/courts
chk guest GET /api/public/courts/1/slots?date=$DATE
chk guest GET /api/court-types
chk guest GET /api/subscription-plans/all
chk guest GET /api/public/branches/1/reviews
chk guest GET /actuator/health
chk guest GET /swagger-ui.html
chk guest GET /docs/auth-service/v3/api-docs
chk guest GET /api/auth/profile   # 401 mong doi
chk customer GET /api/auth/profile "$T_CUS"
chk customer GET /api/bookings/my "$T_CUS"
chk customer GET /api/payments/my-transactions "$T_CUS"
chk customer GET /api/vouchers "$T_CUS"
chk newcustomer GET /api/auth/profile "$T_NEW"
chk owner GET /api/auth/profile "$T_OWN"
chk owner GET /api/admin/branches "$T_OWN"
chk owner GET /api/admin/bookings "$T_OWN"
chk owner GET /api/admin/dashboard/overview "$T_OWN"
chk owner GET /api/admin/payments/transactions "$T_OWN"
chk owner GET /api/admin/staff "$T_OWN"
chk owner GET /api/admin/vouchers "$T_OWN"
chk owner GET /api/admin/pos/products "$T_OWN"
chk owner GET /api/subscriptions/my-subscription "$T_OWN"
chk owner GET /api/admin/refunds/pending "$T_OWN"
chk staff GET /api/auth/profile "$T_STF"
chk staff GET /api/staff/me "$T_STF"
chk staff GET /api/staff/branches "$T_STF"
chk staff GET /api/staff/schedule "$T_STF"
chk superadmin GET /api/auth/profile "$T_SA"
chk superadmin GET /api/super-admin/users "$T_SA"
chk superadmin GET /api/super-admin/court-admins/pending "$T_SA"
chk superadmin GET /api/super-admin/dashboard/overview "$T_SA"
chk superadmin GET /api/super-admin/pos/products "$T_SA"
chk superadmin GET /api/super-admin/stats/accounts "$T_SA"
echo; echo "CORS preflight tu $ORIGIN:"
curl -s -o /dev/null -D - -X OPTIONS "$GATEWAY/api/auth/login" -H "Origin: $ORIGIN" \
  -H 'Access-Control-Request-Method: POST' -H 'Access-Control-Request-Headers: authorization,content-type' \
  | grep -i -E "^HTTP|access-control" 
