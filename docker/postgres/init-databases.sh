#!/bin/sh
# Tao database rieng cho tung service (danh sach trong services.txt) va chay cac
# script trong <service>/database/*.sql theo thu tu phien ban (V1, V2, ...).
# Chi chay lan dau khi volume trong. Muon chay lai tu dau: docker compose down -v
set -e

CONF=/repo/docker/postgres/services.txt

while read -r svc db; do
  case "$svc" in ''|'#'*) continue ;; esac
  echo ">> $svc -> $db"
  psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d postgres -c "CREATE DATABASE \"$db\""
  for f in $(ls "/repo/$svc/database/"*.sql 2>/dev/null | sort -V); do
    echo "   apply $(basename "$f")"
    psql -v ON_ERROR_STOP=1 -U "$POSTGRES_USER" -d "$db" -f "$f"
  done
done < "$CONF"
