#!/usr/bin/env bash
# Application-level proof восстановления (README §19).
# "pg_restore completed successfully" недостаточно. Доказывается:
#   1. Java переподключилась, readiness UP
#   2. REST API работает на восстановленной базе (register/login)
#   3. (опционально) известный пользователь видит свои файлы:
#      scripts/verify-restore.sh <email> <password>
set -euo pipefail

BASE="${SMOKE_BASE:-http://127.0.0.1:8080}"
fail() { echo "FAIL: $*" >&2; exit 1; }

echo "== 1. readiness после restore"
curl -fsS "$BASE/actuator/health/readiness" | grep -q '"UP"' || fail "readiness не UP"

echo "== 2. register/login на восстановленной базе"
U="vres$(date +%s)"
PASS="Vq7!${U}Zp2#Lt"
CODE=$(curl -sS -o /tmp/vreg.json -w '%{http_code}' -X POST "$BASE/api/v1/auth/register" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"$U\",\"email\":\"$U@lab.local\",\"password\":\"$PASS\"}")
[[ "$CODE" == "201" ]] || fail "register -> HTTP $CODE: $(cat /tmp/vreg.json)"
rm -f /tmp/vreg.json

TOKEN=$(curl -fsS -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d "{\"usernameOrEmail\":\"$U@lab.local\",\"password\":\"$PASS\"}" |
  jq -er '.data.accessToken') || fail "login нового пользователя не удался"
curl -fsS "$BASE/api/v1/files?page=0&size=1" -H "Authorization: Bearer $TOKEN" |
  jq -e '.data.totalElements' >/dev/null || fail "list files недоступен"

if [[ $# -ge 2 ]]; then
  EMAIL="$1"; PASSWORD="$2"
  echo "== 3. известный пользователь $EMAIL видит свои данные после restore"
  ETOKEN=$(curl -fsS -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
    -d "{\"usernameOrEmail\":\"$EMAIL\",\"password\":\"$PASSWORD\"}" |
    jq -er '.data.accessToken') || fail "login известного пользователя не удался — данные не восстановлены?"
  COUNT=$(curl -fsS "$BASE/api/v1/files?page=0&size=1" -H "Authorization: Bearer $ETOKEN" |
    jq '.data.totalElements')
  [[ "$COUNT" -ge 1 ]] || fail "у $EMAIL нет файлов после restore (ожидался >= 1)"
  echo "   файлов у пользователя: $COUNT"
fi

echo "VERIFY OK: восстановленная база обслуживает приложение через REST API"
