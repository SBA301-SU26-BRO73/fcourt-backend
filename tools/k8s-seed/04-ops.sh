#!/usr/bin/env bash
# Phase 4: san pham POS, voucher (API), nhan vien phu + ca lam viec (SQL: API tao staff tren EKS dang 503 do auth thieu STAFF_URL).
PUBLIC_BASE="${PUBLIC_BASE:-$(kubectl -n fcourt-prod get cm facility-service-config -o jsonpath='{.data.STORAGE_PUBLIC_BASE_URL}')}"
IMGP="$PUBLIC_BASE/branches/seed"
# ten|don vi|nhom|gia|ton|anh|ap dung (all|bad|pick|ball)
read -r -d '' PRODUCTS <<DATA || true
Nước suối Aquafina 500ml|chai|Đồ uống|10000|150|product-water.jpg|all
Pocari Sweat 350ml|chai|Đồ uống|20000|90|product-sportsdrink.jpg|all
Revive chanh muối 500ml|chai|Đồ uống|15000|110|product-sportsdrink.jpg|all
Khăn thể thao|cái|Phụ kiện|35000|40|product-towel-grip.jpg|all
Bánh protein bar|thanh|Đồ ăn nhẹ|30000|60|product-energy-bar.jpg|all
Ống cầu lông Hải Yến S70 (12 quả)|ống|Cầu lông|230000|35|product-shuttlecock.jpg|bad
Thuê vợt cầu lông|giờ|Thuê dụng cụ|20000|25|product-racket.jpg|bad
Quấn cán vợt Yonex|cuộn|Phụ kiện|25000|80|product-towel-grip.jpg|bad
Bóng pickleball Franklin X-40|quả|Pickleball|60000|50|product-pickleball-paddle.jpg|pick
Thuê vợt pickleball|giờ|Thuê dụng cụ|30000|20|product-pickleball-paddle.jpg|pick
Thuê bóng và áo bib|bộ|Thuê dụng cụ|50000|15||ball
DATA

log "4a. San pham POS (POST /api/admin/pos/products)"
BR=$(psql_db facility_db -F'|' -c "select b.id, b.admin_id, (select court_type_id from courts c where c.branch_id=b.id order by id limit 1) from branches b order by id" </dev/null)
while IFS='|' read -r id admin tid; do
  tok=$(login "owner0$admin@demo.com")
  case "$tid" in 6) kind=bad;; 1) kind=pick;; *) kind=ball;; esac
  have=$(api GET "/api/admin/pos/products?branchId=$id&size=100" "$tok" | jq -r '(.content // .items // .)[]?.name')
  while IFS='|' read -r name unit cat price stock img applies; do
    [ -n "$name" ] || continue
    [ "$applies" = all -o "$applies" = "$kind" ] || continue
    echo "$have" | grep -qxF "$name" && continue
    imgjson=null; [ -n "$img" ] && imgjson="\"$IMGP/$img\""
    api POST /api/admin/pos/products "$tok" "{\"branchId\":$id,\"name\":\"$name\",\"unit\":\"$unit\",\"category\":\"$cat\",\"price\":$price,\"stockQuantity\":$stock,\"imageUrl\":$imgjson}" \
      -H "Idempotency-Key: $(uuid)" >/dev/null
    [ "$(code)" = 201 -o "$(code)" = 200 ] || echo "   product branch $id '$name' -> $(code)"
  done <<<"$PRODUCTS"
done <<<"$BR"
# san pham thu cong da tao luc kiem thu (khong anh): gan anh
tok=$(login owner01@demo.com)
pid=$(api GET "/api/admin/pos/products?branchId=1&size=100" "$tok" | jq -r '(.content // .items // .)[]? | select(.name=="Nước suối Aquafina 500ml" and .imageUrl==null) | .id')
[ -n "$pid" ] && api PUT "/api/admin/pos/products/$pid" "$tok" "{\"name\":\"Nước suối Aquafina 500ml\",\"unit\":\"chai\",\"category\":\"Đồ uống\",\"price\":10000,\"active\":true,\"imageUrl\":\"$IMGP/product-water.jpg\"}" -H "Idempotency-Key: $(uuid)" >/dev/null

log "4b. Voucher (POST /api/admin/vouchers)"
tok=$(login owner01@demo.com); api DELETE /api/admin/vouchers/1 "$tok" >/dev/null 2>&1 || true  # voucher TESTV1 tao luc kiem thu, neu con
NOW=$(date -u +%Y-%m-%dT00:00:00Z); START=$(date -u -d "-10 days" +%Y-%m-%dT00:00:00Z); END=$(date -u -d "+75 days" +%Y-%m-%dT00:00:00Z)
while IFS='|' read -r id admin tid; do
  tok=$(login "owner0$admin@demo.com")
  mk() { api POST /api/admin/vouchers "$tok" "$1" >/dev/null; c=$(code); [ "$c" = 201 -o "$c" = 409 ] || echo "   voucher branch $id -> $c $API_BODY" | cut -c1-200; }
  mk "{\"code\":\"FCOURT10-$id\",\"branchId\":$id,\"discountType\":\"PERCENTAGE\",\"discountValue\":10,\"maxDiscountAmount\":50000,\"minOrderAmount\":100000,\"startDate\":\"$START\",\"endDate\":\"$END\",\"usageLimit\":300}"
  mk "{\"code\":\"GIAM30K-$id\",\"branchId\":$id,\"discountType\":\"FIXED_AMOUNT\",\"discountValue\":30000,\"minOrderAmount\":150000,\"startDate\":\"$START\",\"endDate\":\"$END\",\"usageLimit\":150}"
  [ $((id % 5)) = 0 ] && mk "{\"code\":\"KHAITRUONG-$id\",\"branchId\":$id,\"discountType\":\"PERCENTAGE\",\"discountValue\":20,\"maxDiscountAmount\":80000,\"minOrderAmount\":0,\"startDate\":\"2026-08-01T00:00:00Z\",\"endDate\":\"2026-09-30T00:00:00Z\",\"usageLimit\":100,\"status\":\"EXPIRED\"}"
done <<<"$BR"

log "4c. Nhan vien phu staff06..14 (POST /api/admin/staff) + ca lam viec (PUT /api/admin/staff/{id})"
while IFS='|' read -r email name phone br; do
  [ -n "$email" ] || continue
  [ -n "$(psql_db auth_db -c "select 1 from users where email='$email'" </dev/null)" ] && continue
  admin=$(psql_db facility_db -c "select admin_id from branches where id=$br" </dev/null); tok=$(login "owner0$admin@demo.com")
  api POST /api/admin/staff "$tok" "{\"branchId\":$br,\"email\":\"$email\",\"password\":\"$PASSWORD\",\"fullName\":\"$name\",\"phone\":\"$phone\",\"shifts\":[{\"dayOfWeek\":1,\"startTime\":\"06:00\",\"endTime\":\"14:00\"},{\"dayOfWeek\":2,\"startTime\":\"06:00\",\"endTime\":\"14:00\"},{\"dayOfWeek\":3,\"startTime\":\"06:00\",\"endTime\":\"14:00\"}]}" \
    -H "Idempotency-Key: $(uuid)" >/dev/null
  [ "$(code)" = 201 -o "$(code)" = 200 ] || echo "   staff $email -> $(code)"
done <<'DATA'
staff06@demo.com|Phan Thị Ngọc Diệp|0929000006|3
staff07@demo.com|Lâm Quốc Huy|0929000007|4
staff08@demo.com|Cao Thị Bích Ngân|0929000008|5
staff09@demo.com|Tô Minh Nhật|0929000009|6
staff10@demo.com|Hồ Thị Kim Chi|0929000010|17
staff11@demo.com|Mai Văn Phúc|0929000011|18
staff12@demo.com|Châu Bảo Trân|0929000012|19
staff13@demo.com|Đinh Công Danh|0929000013|21
staff14@demo.com|Vương Hải Đăng|0929000014|1
DATA
# ca lam viec day du cho nhan vien chua co ca (staff01..05, staff.local): sang T2-T6, chieu T7-CN luan phien
ST=$(psql_db staff_db -F'|' -c "select s.id, s.branch_id, s.full_name, s.phone from staff s where s.deleted_at is null and not exists (select 1 from staff_work_shifts w where w.staff_id=s.id) order by s.id" </dev/null)
while IFS='|' read -r sid br nm ph; do
  [ -n "$sid" ] || continue
  admin=$(psql_db facility_db -c "select admin_id from branches where id=$br" </dev/null); tok=$(login "owner0$admin@demo.com")
  if [ $((sid % 2)) = 0 ]; then a=06:00; b=14:00; else a=14:00; b=22:00; fi
  sh=""; for d in 1 2 3 4 5; do sh="$sh{\"dayOfWeek\":$d,\"startTime\":\"$a\",\"endTime\":\"$b\"},"; done
  sh="$sh{\"dayOfWeek\":6,\"startTime\":\"08:00\",\"endTime\":\"20:00\"}"
  api PUT "/api/admin/staff/$sid" "$tok" "{\"fullName\":\"$nm\",\"phone\":\"$ph\",\"shifts\":[$sh]}" >/dev/null
  [ "$(code)" = 200 ] || echo "   shifts staff $sid -> $(code)"
done <<<"$ST"

log "4d. Don hang POS tien mat (POST /api/staff/pos/orders, 6 don/chi nhanh co nhan vien)"
SB=$(psql_db staff_db -F'|' -c "select distinct on (branch_id) branch_id, email from staff where deleted_at is null and email like '%@demo.com' order by branch_id, id" </dev/null)
while IFS='|' read -r br em; do
  [ -n "$br" ] || continue
  tok=$(login "$em") || continue
  have=$(api GET "/api/staff/pos/orders?limit=1" "$tok" | jq -r 'length // 0' 2>/dev/null || echo 0)
  [ "${have:-0}" != 0 ] && continue
  ids=$(api GET "/api/staff/pos/products?branchId=$br" "$tok" | jq -r '.[]|select(.active and .stockQuantity>20)|.id' | head -8 | tr '\n' ' ')
  set -- $ids; [ $# -ge 3 ] || continue
  for k in 1 2 3 4 5 6; do
    a=${!k:-$1}; b=${@:$(( (k % $#) + 1 )):1}
    api POST /api/staff/pos/orders "$tok" "{\"paymentMethod\":\"CASH\",\"items\":[{\"productId\":$a,\"quantity\":$((1 + k % 3))},{\"productId\":$b,\"quantity\":$((1 + (k+1) % 2))}]}" -H "Idempotency-Key: $(uuid)" >/dev/null
    [ "$(code)" = 201 -o "$(code)" = 200 ] || { echo "   pos order branch $br -> $(code) $API_BODY" | cut -c1-200; break; }
  done
done <<<"$SB"
