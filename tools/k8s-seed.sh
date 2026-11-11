#!/usr/bin/env bash
# Seed du lieu demo len cluster EKS (kubectl context fcourt-prod). Chay lai nhieu lan van an toan (idempotent).
#
#   kubectl -n fcourt-prod port-forward svc/gateway-service 8080:8080 &     # hoac GATEWAY_URL=http://<alb>
#   export AWS_PROFILE=tpn12 AWS_REGION=ap-southeast-1                      # cho phase anh / bien lai (aws s3)
#   bash tools/k8s-seed.sh [phase ...]
#
# Phase (mac dinh chay het, theo thu tu): accounts catalog images ops bookings online reviews verify
#   accounts  tai khoan moi role (mat khau chung, staff, owner duoc duyet)       01-accounts.sh
#   catalog   chi nhanh, san (them san), bang gia, anh san                        02-catalog.sh
#   images    anh chi nhanh, avatar, anh san pham POS (S3 branches/seed/)        03-images.sh
#   ops       san pham POS, voucher, nhan vien + ca lam viec, don POS            04-ops.sh
#   bookings  lich su + dat san sap toi, thanh toan, hoan tien, fact dashboard   05-bookings.sh (SQL)
#   online    probe luong dat san qua API                                        06-online.sh
#   reviews   danh gia + phan hoi chu san                                        07-reviews.sh
#   verify    dang nhap tung tai khoan                                           99-verify.sh
# Anh nguon nam o tools/seed-assets/ (JPEG <= 300 KB). Nen lai anh moi: node tools/k8s-seed/compress-images.cjs <png-dir> <out-dir> (can sharp).
# Yeu cau: kubectl (context dung), aws cli, curl, jq. Gateway gioi han dang nhap 10 lan/phut/IP: token duoc cache 10 phut, 429 tu thu lai.
set -uo pipefail
cd "$(dirname "$0")"
. k8s-seed/lib.sh
PHASES="${*:-accounts catalog images ops bookings online reviews verify}"
fail=0
for p in $PHASES; do
  f=$(ls k8s-seed/[0-9][0-9]-$p.sh 2>/dev/null | head -1)
  [ -n "$f" ] || { echo "phase khong ton tai: $p"; exit 1; }
  ( set -e; . "$f" ) || { echo "!! phase $p loi (chay lai se tiep tuc nho idempotent)"; fail=1; }
done
echo "Mat khau chung: $PASSWORD"
exit $fail
