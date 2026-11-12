#!/usr/bin/env bash
# Phase 7: danh gia (POST /api/reviews bang token khach, can booking COMPLETED) + chu san tra loi (POST /api/admin/reviews/{id}/reply).
A="$SEED_DIR/seed-assets"
log "7. Danh gia + phan hoi cua chu san (qua API)"
declare -A CUST_EMAIL TYPE_OF ADMIN_OF REVIMG
while IFS='|' read -r id email; do CUST_EMAIL[$id]=$email; done < <(psql_db auth_db -F'|' -c "select id,email from users where role='CUSTOMER' and status='ACTIVE'" </dev/null)
while IFS='|' read -r cid tid admin; do TYPE_OF[$cid]=$tid; ADMIN_OF[$cid]=$admin; done < <(psql_db facility_db -F'|' -c "select c.id,c.court_type_id,b.admin_id from courts c join branches b on b.id=c.branch_id" </dev/null)

# da co danh gia nao chua -> bo qua booking da danh gia (409 cung duoc bo qua)
DONE=" $(psql_db discovery_db -c "select booking_id from reviews" </dev/null | tr '\n' ' ') "
CAND=$(psql_db booking_db -F'|' -c "select id, customer_id, court_id, branch_id from bookings where status='COMPLETED' and customer_id is not null and booking_code like 'FS%' and id % 11 = 0 order by id" </dev/null)

R5=("Sân đẹp, sạch sẽ, nhân viên nhiệt tình. Sẽ quay lại!" "Mặt sân êm, đèn sáng, đặt sân nhanh gọn. Rất hài lòng." "Chất lượng sân tuyệt vời, giá hợp lý, bãi xe rộng." "Cả nhóm chơi rất vui, sân thoáng mát, có nước uống tiện lợi." "Chủ sân thân thiện, check-in nhanh, sân đúng như ảnh.")
R4=("Sân tốt, giờ cao điểm hơi đông nhưng vẫn ổn." "Sân sạch, giá hợp lý. Chỗ gửi xe hơi chật chút." "Chơi thoải mái, nhân viên hỗ trợ tốt, sẽ ủng hộ tiếp.")
R3=("Sân ổn nhưng giờ tối hơi ồn, điều hòa chưa mát lắm." "Chất lượng tạm được, mong cải thiện thêm vệ sinh khu thay đồ.")
R2=("Sân hơi trơn và đèn có chỗ chưa sáng, mong chủ sân kiểm tra lại.")
R1=("Đến đúng giờ nhưng sân chưa được dọn, mất 15 phút chờ.")
REPLY=("Cảm ơn bạn đã ủng hộ, hẹn gặp lại bạn ở những trận tiếp theo!" "FCOURT xin cảm ơn đánh giá của bạn, chúng tôi sẽ tiếp tục nâng cao chất lượng." "Cảm ơn bạn đã góp ý, chúng tôi đã ghi nhận và sẽ cải thiện sớm nhất." "Rất vui vì bạn hài lòng. Chúc bạn và nhóm luôn khỏe và chơi hay!")

IMG_BY_TYPE=([1]=court-pickleball [2]=court-football [3]=court-tennis [4]=court-basketball [6]=court-badminton)

n=0; replied=0
while IFS='|' read -r bid cust court branch; do
  [ -n "$bid" ] || continue
  [[ "$DONE" == *" $bid "* ]] && continue
  email="${CUST_EMAIL[$cust]:-}"; [ -n "$email" ] || continue
  v=$(( (bid * 7919) % 100 ))
  if   [ $v -lt 52 ]; then rating=5; arr=("${R5[@]}")
  elif [ $v -lt 80 ]; then rating=4; arr=("${R4[@]}")
  elif [ $v -lt 92 ]; then rating=3; arr=("${R3[@]}")
  elif [ $v -lt 97 ]; then rating=2; arr=("${R2[@]}")
  else rating=1; arr=("${R1[@]}"); fi
  comment="${arr[$(( bid % ${#arr[@]} ))]}"
  tok=$(login "$email"); imgs="[]"
  if [ $((bid % 4)) = 0 ]; then
    t=${TYPE_OF[$court]}; k="$cust:$t"
    [ -n "${REVIMG[$k]:-}" ] || REVIMG[$k]=$(upload POST /api/reviews/images "$tok" files "$A/${IMG_BY_TYPE[$t]}.jpg" | jq -r ".urls[0] // empty")
    [ -n "${REVIMG[$k]}" ] && imgs="[\"${REVIMG[$k]}\"]"
  fi
  rid=$(api POST /api/reviews "$tok" "{\"bookingId\":$bid,\"rating\":$rating,\"comment\":\"$comment\",\"imageUrls\":$imgs}" | jq -r '.id // empty')
  [ -n "$rid" ] || { [ "$(code)" = 409 ] || echo "   review booking $bid -> $(code)"; continue; }
  n=$((n+1))
  if [ $((bid % 3)) != 0 ] && [ $rating -ge 3 -o $((bid % 2)) = 0 ]; then
    otok=$(login "owner0${ADMIN_OF[$court]}@demo.com")
    api POST "/api/admin/reviews/$rid/reply" "$otok" "{\"reply\":\"${REPLY[$(( bid % ${#REPLY[@]} ))]}\"}" >/dev/null
    [ "$(code)" = 200 ] && replied=$((replied+1))
  fi
done <<<"$CAND"
echo "   tao $n danh gia moi, $replied phan hoi"
