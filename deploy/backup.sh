#!/bin/sh
# Run from deploy/ after reviewing runtime config. No credentials in backup filenames/logs.
set -eu
umask 077
backup_dir=${1:?provide a protected backup directory}
mkdir -p "$backup_dir"
stamp=$(date -u +%Y%m%dT%H%M%SZ)
# A cluster dump preserves roles and ownership; output contains password hashes.
file="$backup_dir/cluster-$stamp.sql"
tmp=$(mktemp "$backup_dir/.backup.XXXXXX")
trap 'rm -f "$tmp"' EXIT HUP INT TERM
docker compose --env-file .env --env-file runtime.env -f compose.yml exec -T postgres pg_dumpall -U postgres > "$tmp"
test -s "$tmp"
mv "$tmp" "$file"
printf 'Backup created: %s\n' "$file"
