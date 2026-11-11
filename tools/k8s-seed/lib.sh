#!/usr/bin/env bash
# Helper dung chung cho tools/k8s-seed.sh va cac phase trong tools/k8s-seed/.
# Khong chua bi mat: mat khau demo la gia tri DEV chung, token chi nam trong thu muc tam.

GATEWAY="${GATEWAY_URL:-http://localhost:8080}"
PASSWORD="${SEED_PASSWORD:-Fcourt@123456}"
PGPOD="${PGPOD:-fcourt-postgres-1}"
PGNS="${PGNS:-data}"
SEED_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TMPD="${SEED_TMP:-$(mktemp -d)}"
mkdir -p "$TMPD/tok"
command -v jq >/dev/null 2>&1 || { [ -d /c/Users/PC/.claude/jobs/50160017/tmp/bin ] && PATH="$PATH:/c/Users/PC/.claude/jobs/50160017/tmp/bin"; }
command -v jq >/dev/null 2>&1 || { echo "can jq trong PATH"; exit 1; }

log() { echo ">> $*"; }

# psql <db> [args...] ; SQL doc tu stdin hoac -c
psql_db() { local db="$1"; shift
  kubectl -n "$PGNS" exec -i "$PGPOD" -c postgres -- psql -v ON_ERROR_STOP=1 -U postgres -d "$db" -At -q "$@"; }

# login <email> -> access token (cache 10 phut, tu cho khi gateway rate limit)
login() {
  local email="$1" f="$TMPD/tok/$1"
  if [ -s "$f" ] && [ $(( $(date +%s) - $(date -r "$f" +%s) )) -lt 600 ]; then cat "$f"; return; fi
  local i out code
  for i in 1 2 3 4 5 6 7 8; do
    out=$(curl -s -w '\n%{http_code}' -X POST "$GATEWAY/api/auth/login" -H 'Content-Type: application/json' \
      -d "{\"email\":\"$email\",\"password\":\"$PASSWORD\"}")
    code="${out##*$'\n'}"
    if [ "$code" = 200 ]; then echo "${out%$'\n'*}" | jq -r .accessToken | tee "$f"; return; fi
    if [ "$code" = 429 ]; then sleep 15; continue; fi
    echo "login $email -> HTTP $code" >&2; return 1
  done
  echo "login $email: rate limited" >&2; return 1
}

# api <METHOD> <path> <token|-> [json-body] [extra curl args...]  -> body on stdout, HTTP code in $TMPD/code
# 429 (gateway) duoc thu lai.
api() {
  local m="$1" p="$2" t="$3" body="${4:-}"; shift 4 2>/dev/null || shift $#
  local args=(-s -X "$m" "$GATEWAY$p" -H 'Content-Type: application/json')
  [ "$t" != "-" ] && args+=(-H "Authorization: Bearer $t")
  [ -n "$body" ] && args+=(-d "$body")
  local i out
  for i in 1 2 3 4 5; do
    out=$(curl "${args[@]}" "$@" -w '\n%{http_code}')
    API_CODE="${out##*$'\n'}"; API_BODY="${out%$'\n'*}"
    [ "$API_CODE" = 429 ] && { sleep 3; continue; }
    break
  done
  printf "%s" "$API_CODE" > "$TMPD/code"
  printf "%s" "$API_BODY"
}
uuid() { cat /proc/sys/kernel/random/uuid 2>/dev/null || powershell -NoProfile -Command '[guid]::NewGuid().ToString()' | tr -d '\r'; }
winpath() { cygpath -m "$1" 2>/dev/null || echo "$1"; }
code() { cat "$TMPD/code"; }
# upload <METHOD> <path> <token> <field> <file> -> body; code in $TMPD/code. Moi lan tai lai tao key moi tren S3, nen goi co dieu kien.
upload() {
  local out i
  for i in 1 2 3 4 5; do
    out=$(curl -s -X "$1" "$GATEWAY$2" -H "Authorization: Bearer $3" -F "$4=@$(winpath "$5");type=image/jpeg" -w '\n%{http_code}')
    [ "${out##*$'\n'}" = 429 ] && { sleep 3; continue; }; break
  done
  printf "%s" "${out##*$'\n'}" > "$TMPD/code"; printf "%s" "${out%$'\n'*}"
}
