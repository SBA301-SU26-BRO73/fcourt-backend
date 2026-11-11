#!/usr/bin/env bash
# Phase 2: danh muc (chi nhanh, san, gia, san pham POS, voucher, ca lam viec). Uu tien API; SQL chi cho ca lam viec / nhan vien phu.
OWNER_ASCII=(x "NGUYEN VAN HUNG" "TRAN MINH QUAN" "LE THI THU HA" "PHAM QUOC BAO" "HOANG THI MAI ANH")
log "2a0. Them san (moi chi nhanh >= 2 san, id chia het 3 co 3 san)"
# POST /api/admin/branches/{id}/courts tren EKS hien tra 503 (facility goi /api/internal/subscriptions/admins/{id}/limits, subscription-service khong co route nay).
# Tam thoi nhan ban san + khung gio bang SQL trong facility_db, roi PUT chi nhanh de facility phat lai su kien (snapshot san) cho discovery.
CHANGED=$(psql_db facility_db <<'SQL'
WITH tgt AS (
  SELECT b.id AS branch_id, (SELECT count(*) FROM courts c WHERE c.branch_id=b.id AND c.deleted_at IS NULL) AS cnt, CASE WHEN b.id % 3 = 0 THEN 3 ELSE 2 END AS want
  FROM branches b WHERE b.deleted_at IS NULL),
need AS (SELECT t.branch_id, t.cnt + k AS n FROM tgt t, LATERAL generate_series(1, t.want - t.cnt) k WHERE t.cnt < t.want),
ins AS (
  INSERT INTO courts (branch_id, name, court_type_id, description, image_url, status, default_price_per_hour)
  SELECT n.branch_id, 'Sân ' || n.n, f.court_type_id, f.description, f.image_url, 'ACTIVE', f.default_price_per_hour
  FROM need n JOIN LATERAL (SELECT * FROM courts c WHERE c.branch_id = n.branch_id AND c.deleted_at IS NULL ORDER BY c.id LIMIT 1) f ON true
  RETURNING id, branch_id, court_type_id),
tp AS (
  INSERT INTO time_slot_templates (court_id, start_time, end_time, price, day_of_week, is_active)
  SELECT i.id, t.start_time, t.end_time, t.price, t.day_of_week, t.is_active
  FROM ins i JOIN LATERAL (SELECT * FROM time_slot_templates x WHERE x.deleted_at IS NULL AND x.court_id = (SELECT min(id) FROM courts WHERE branch_id = i.branch_id AND id <> i.id AND court_type_id = i.court_type_id AND deleted_at IS NULL)) t ON true
  RETURNING 1)
SELECT DISTINCT branch_id FROM ins;
SQL
)
FORCE_BRANCHES=" $(echo $CHANGED | tr '\n' ' ') "
[ -n "${CHANGED// /}" ] && echo "   them san cho chi nhanh:$FORCE_BRANCHES"

log "2a. Thong tin chi nhanh (PUT /api/admin/branches/{id})"
while IFS='|' read -r id district ward lat lng; do
  [ -n "$id" ] || continue
  admin=$(psql_db facility_db -c "select admin_id from branches where id=$id" </dev/null)
  tok=$(login "owner0$admin@demo.com")
  cur=$(api GET "/api/admin/branches/$id" "$tok")
  [ "$(echo "$cur" | jq -r ".latitude // empty")" != "" ] && [[ "$FORCE_BRANCHES" != *" $id "* ]] && continue   # da cap nhat
  body=$(echo "$cur" | jq --arg d "$district" --arg w "$ward" --argjson la "$lat" --argjson ln "$lng" --arg n "${OWNER_ASCII[$admin]}" \
    '{name,address,ward:$w,district:$d,city,latitude:$la,longitude:$ln,phone,openTime,closeTime,slotDurationMinutes,bankName,bankBin,bankAccountNumber,bankAccountName:$n,status}')
  api PUT "/api/admin/branches/$id" "$tok" "$body" >/dev/null; [ "$(code)" = 200 ] || echo "   branch $id PUT -> $(code)"
done <<'DATA'
1|Ba Đình|Quán Thánh|21.0405|105.8412
2|Cầu Giấy|Dịch Vọng Hậu|21.0368|105.7826
3|Hoàn Kiếm|Lý Thái Tổ|21.0245|105.8556
4|Đống Đa|Trung Tự|21.0069|105.8319
5|Tây Hồ|Quảng An|21.0638|105.8271
6|Hà Đông|Quang Trung|20.9613|105.7686
7|Thanh Xuân|Nhân Chính|21.0036|105.7997
8|Hai Bà Trưng|Bách Khoa|21.0049|105.8452
9|Hải Châu|Thạch Thang|16.0699|108.2245
10|Thanh Khê|Chính Gián|16.0661|108.2087
11|Sơn Trà|Phước Mỹ|16.0780|108.2455
12|Ngũ Hành Sơn|Mỹ An|16.0478|108.2461
13|Liên Chiểu|Hòa Khánh Bắc|16.0735|108.1509
14|Cẩm Lệ|Khuê Trung|16.0243|108.2062
15|Quận 1|Bến Nghé|10.7743|106.7032
16|Quận 3|Võ Thị Sáu|10.7803|106.6874
17|Bình Thạnh|Phường 22|10.7968|106.7141
18|Quận 7|Tân Phong|10.7298|106.7058
19|Thủ Đức|Bình Thọ|10.8505|106.7667
20|Tân Bình|Phường 12|10.8005|106.6401
21|Gò Vấp|Phường 10|10.8361|106.6659
22|Quận 10|Phường 14|10.7712|106.6663
23|Bình Tân|Bình Trị Đông B|10.7455|106.6082
24|Phú Nhuận|Phường 7|10.7992|106.6812
25|Nhà Bè|Phước Kiển|10.7071|106.7051
DATA

log "2b. San: ten, mo ta, gia mac dinh, anh (PUT court + POST image), bang gia theo khung gio"
# type_id|ten loai|mo ta|gia mac dinh/gio|sang|thuong|cao diem T2-T6|cao diem T7-CN
read -r -d '' TYPES <<'DATA' || true
1|Pickleball|Sân pickleball tiêu chuẩn 6,1 x 13,4 m, mặt sân acrylic êm chân, có lưới thi đấu và đèn LED chống chói.|100000|80000|100000|150000|180000
2|Bóng đá mini|Sân cỏ nhân tạo 5-7 người, cỏ thế hệ mới, đèn pha buổi tối, có ghế nghỉ cho cầu thủ.|300000|250000|300000|500000|600000
3|Tennis|Sân tennis mặt cứng đạt chuẩn, lưới và vạch sơn mới, có khu ghế ngồi và bóng râm.|150000|120000|150000|250000|300000
4|Bóng rổ|Sân bóng rổ trong nhà sàn gỗ, rổ tiêu chuẩn có thể điều chỉnh độ cao, hệ thống đèn chiếu sáng thi đấu.|200000|150000|200000|300000|350000
6|Cầu lông|Sân cầu lông trong nhà, thảm PU chuyên dụng chống trơn, trần cao thoáng, đèn LED không chói.|50000|40000|50000|80000|100000
DATA
IMG_BY_TYPE=([1]=court-pickleball [2]=court-football [3]=court-tennis [4]=court-basketball [6]=court-badminton)
declare -A TNAME TDESC TPRICE TRULE
while IFS='|' read -r t n d p a b c e; do TNAME[$t]=$n; TDESC[$t]=$d; TPRICE[$t]=$p; TRULE[$t]="$a $b $c $e"; done <<<"$TYPES"

COURTS=$(psql_db facility_db -F"|" -c "select c.id, c.branch_id, c.court_type_id, b.admin_id, row_number() over (partition by c.branch_id order by c.id) from courts c join branches b on b.id=c.branch_id where c.deleted_at is null order by c.id" </dev/null)
while IFS='|' read -r cid bid tid admin nth; do
  tok=$(login "owner0$admin@demo.com")
  cur=$(api GET "/api/admin/courts/$cid" "$tok")
  img=$(echo "$cur" | jq -r '.imageUrl // ""')
  body=$(echo "$cur" | jq --arg n "Sân $nth" --arg d "${TDESC[$tid]}" --argjson p "${TPRICE[$tid]}" \
    '{name:$n,courtTypeId,description:$d,imageUrl,defaultPricePerHour:$p,status}')
  [ "$(echo "$cur" | jq -r '.name')" = "Sân $nth" ] && [ "$(echo "$cur" | jq -r '.defaultPricePerHour')" != "null" ] || { api PUT "/api/admin/courts/$cid" "$tok" "$body" >/dev/null; [ "$(code)" = 200 ] || echo "   court $cid PUT -> $(code)"; }
  case "$img" in *.amazonaws.com/branches/*) ;; *)
    f="$SEED_DIR/seed-assets/${IMG_BY_TYPE[$tid]}.jpg"
    [ -f "$f" ] && { upload POST "/api/admin/courts/$cid/image" "$tok" file "$f" >/dev/null; [ "$(code)" = 200 ] || echo "   court $cid image -> $(code)"; } ;;
  esac
  # bang gia: 4 quy tac khong chong nhau
  have=$(api GET "/api/admin/courts/$cid/pricing-rules" "$tok" | jq -r '.[].ruleName')
  set -- ${TRULE[$tid]}
  for r in "Giờ sáng|06:00|08:30|$1|false|0,1,2,3,4,5,6" "Giờ thường|08:30|17:00|$2|false|0,1,2,3,4,5,6" \
           "Cao điểm T2-T6|17:00|22:00|$3|true|1,2,3,4,5" "Cao điểm cuối tuần|17:00|22:00|$4|true|0,6"; do
    IFS='|' read -r rn rs re rp rk rd <<<"$r"
    echo "$have" | grep -qx "$rn" && continue
    api POST "/api/admin/courts/$cid/pricing-rules" "$tok" "{\"ruleName\":\"$rn\",\"startTime\":\"$rs\",\"endTime\":\"$re\",\"pricePerHour\":$rp,\"peakHour\":$rk,\"daysOfWeek\":[$rd]}" >/dev/null
    [ "$(code)" = 201 -o "$(code)" = 409 ] || echo "   rule court $cid $rn -> $(code)"
  done
done <<<"$COURTS"
