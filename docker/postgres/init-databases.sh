#!/bin/sh
# Tao database rieng cho tung service (danh sach trong services.txt).
# Schema va du lieu mau do Flyway cua tung service tao khi service khoi dong (src/main/resources/db/migration),
# nen script nay KHONG chay SQL nua (neu chay truoc thi Flyway gap schema khong co lich su va bao loi).
# Chi chay lan dau khi volume trong. Muon chay lai tu dau: docker compose down -v
set -e

CONF=/etc/fcourt/services.txt

while read -r svc db; do
  case "$svc" in ''|'#'*) continue ;; esac
  echo ">> $svc -> $db"
  psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d postgres -c "CREATE DATABASE \"$db\""
done < "$CONF"
