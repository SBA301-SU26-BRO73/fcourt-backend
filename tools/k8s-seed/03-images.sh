#!/usr/bin/env bash
# Phase 3: anh chi nhanh, anh san, avatar (qua API upload) va anh san pham POS (khong co API upload -> aws s3 cp vao prefix public branches/seed/).
# FORCE_IMAGES=1: xoa anh chi nhanh cu qua API va tai lai (anh san ghi de); mac dinh bo qua doi tuong da co anh.
A="$SEED_DIR/seed-assets"
log "3a. Anh chi nhanh: anh the branch-<id 2 chu so>.jpg (san dung mon cua chi nhanh) + anh chung cua loai san (POST /api/admin/branches/{id}/images)"
# Anh dau (sort_order nho nhat) la anh the o trang chi nhanh (BranchEventWriter.coverImage), nen tai anh the truoc.
BR=$(psql_db facility_db -F'|' -c "select b.id, b.admin_id, (select court_type_id from courts c where c.branch_id=b.id order by id limit 1) from branches b where deleted_at is null order by id" </dev/null)
declare -A IMG_BY_TYPE=([1]=court-pickleball [2]=court-football [3]=court-tennis [4]=court-basketball [6]=court-badminton)
while IFS='|' read -r id admin tid; do
  tok=$(login "owner0$admin@demo.com")
  cur=$(api GET "/api/admin/branches/$id/images" "$tok")
  if [ "$(echo "$cur" | jq 'length')" -gt 0 ]; then
    [ "${FORCE_IMAGES:-0}" = 1 ] || continue
    for iid in $(echo "$cur" | jq -r '.[].id'); do
      api DELETE "/api/admin/branches/images/$iid" "$tok" >/dev/null
      [ "$(code)" = 204 ] || echo "   branch $id delete image $iid -> $(code)"
    done
  fi
  second="${IMG_BY_TYPE[$tid]}"; card="branch-$(printf %02d "$id")"
  [ -f "$A/$card.jpg" ] || card="$second"
  for f in "$card" "$second"; do
    upload POST "/api/admin/branches/$id/images" "$tok" files "$A/$f.jpg" >/dev/null
    [ "$(code)" = 201 -o "$(code)" = 200 ] || echo "   branch $id image $f -> $(code)"
  done
done <<<"$BR"

log "3a2. Anh san theo loai san (POST /api/admin/courts/{id}/image)"
CT=$(psql_db facility_db -F'|' -c "select c.id, b.admin_id, c.court_type_id from courts c join branches b on b.id=c.branch_id where c.deleted_at is null and b.deleted_at is null order by c.id" </dev/null)
while IFS='|' read -r cid admin tid; do
  tok=$(login "owner0$admin@demo.com")
  if [ "${FORCE_IMAGES:-0}" != 1 ]; then
    [ -z "$(api GET "/api/admin/courts/$cid" "$tok" | jq -r '.imageUrl // empty')" ] || continue
  fi
  upload POST "/api/admin/courts/$cid/image" "$tok" file "$A/${IMG_BY_TYPE[$tid]}.jpg" >/dev/null
  [ "$(code)" = 200 ] || echo "   court $cid image -> $(code)"
done <<<"$CT"

log "3b. Avatar (PUT /api/auth/profile/avatar)"
while IFS='|' read -r email img; do
  has=$(psql_db auth_db -c "select coalesce(avatar_url,'') from users where email='$email'" </dev/null)
  [ -n "$has" ] && continue
  tok=$(login "$email")
  upload PUT /api/auth/profile/avatar "$tok" file "$A/$img.jpg" >/dev/null
  [ "$(code)" = 200 ] || echo "   avatar $email -> $(code)"
done <<'DATA'
customer01@demo.com|avatar-2
customer02@demo.com|avatar-1
customer03@demo.com|avatar-1
customer04@demo.com|avatar-4
customer05@demo.com|avatar-3
owner01@demo.com|avatar-3
owner02@demo.com|avatar-1
owner03@demo.com|avatar-2
owner04@demo.com|avatar-3
owner05@demo.com|avatar-4
staff01@demo.com|avatar-1
staff02@demo.com|avatar-2
DATA

log "3c. Anh san pham POS (S3 branches/seed/)"
S3_BUCKET="${S3_BUCKET:-$(kubectl -n fcourt-prod get cm facility-service-config -o jsonpath='{.data.STORAGE_S3_BUCKET}')}"
PUBLIC_BASE="${STORAGE_PUBLIC_BASE_URL:-$(kubectl -n fcourt-prod get cm facility-service-config -o jsonpath='{.data.STORAGE_PUBLIC_BASE_URL}')}"
export AWS_PROFILE="${AWS_PROFILE:-tpn12}" AWS_REGION="${AWS_REGION:-ap-southeast-1}"
for f in "$A"/product-*.jpg; do
  k="branches/seed/$(basename "$f")"
  aws s3api head-object --bucket "$S3_BUCKET" --key "$k" >/dev/null 2>&1 || \
    aws s3 cp "$(winpath "$f")" "s3://$S3_BUCKET/$k" --content-type image/jpeg --cache-control "public,max-age=31536000" --only-show-errors
done
echo "   $PUBLIC_BASE/branches/seed/product-water.jpg -> $(curl -s -o /dev/null -w '%{http_code}' "$PUBLIC_BASE/branches/seed/product-water.jpg")"
