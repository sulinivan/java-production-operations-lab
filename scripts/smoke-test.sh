#!/usr/bin/env bash
# Smoke-тест приложения после деплоя (README §38).
# health -> upload -> list -> download -> delete -> проверка ОБЕИХ сторон
# хранилища: metadata (PostgreSQL через API) и object (MinIO) (README §22).
# Выполняется ВНУТРИ Lima VM: порт 8080 проброшен из kind (kind/cluster.yaml),
# kubectl нужен для проверки объектов MinIO.
set -euo pipefail

BASE="${SMOKE_BASE:-http://127.0.0.1:8080}"
NS="${SMOKE_NS:-cloudshare}"

fail() { echo "FAIL: $*" >&2; exit 1; }
command -v jq >/dev/null || fail "jq required"
command -v kubectl >/dev/null || fail "kubectl required"

# --- 1. health -------------------------------------------------------------
echo "== health endpoints"
curl -fsS "$BASE/actuator/health/liveness"  | grep -q '"UP"' || fail "liveness not UP"
curl -fsS "$BASE/actuator/health/readiness" | grep -q '"UP"' || fail "readiness not UP"

# --- 2. уникальный пользователь ---------------------------------------------
U="smoke$(date +%s)"
EMAIL="$U@lab.local"
PASS="Vq7!${U}Zp2#Lt"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

echo "== register $EMAIL"
CODE=$(curl -sS -o "$TMP/reg.json" -w '%{http_code}' -X POST "$BASE/api/v1/auth/register" \
  -H 'Content-Type: application/json' \
  -d "{\"username\":\"$U\",\"email\":\"$EMAIL\",\"password\":\"$PASS\"}")
[[ "$CODE" == "201" ]] || fail "register -> HTTP $CODE: $(cat "$TMP/reg.json")"

echo "== login"
curl -fsS -X POST "$BASE/api/v1/auth/login" -H 'Content-Type: application/json' \
  -d "{\"usernameOrEmail\":\"$EMAIL\",\"password\":\"$PASS\"}" > "$TMP/login.json"
TOKEN=$(jq -er '.data.accessToken' "$TMP/login.json") || fail "login: no accessToken in response"
AUTH="Authorization: Bearer $TOKEN"

# --- 3. объектная сторона MinIO ----------------------------------------------
obj_count() {
  kubectl -n "$NS" exec deploy/minio -- sh -c \
    'mc alias set q http://localhost:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null && mc ls --recursive q/documents | wc -l' |
    tr -d '[:space:]'
}
BEFORE=$(obj_count)

# --- 4. upload -----------------------------------------------------------------
# Валидный 1x1 PNG: upload проходит magic-byte MIME check (Tika) и ClamAV scan
echo "== upload"
printf '%s' 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==' \
  | base64 -d > "$TMP/pixel.png"
CODE=$(curl -sS -o "$TMP/up.json" -w '%{http_code}' -X POST "$BASE/api/v1/files/upload" \
  -H "$AUTH" -F "file=@$TMP/pixel.png")
[[ "$CODE" == "201" ]] || fail "upload -> HTTP $CODE: $(cat "$TMP/up.json")"
ID=$(jq -er '.data.id' "$TMP/up.json") || fail "upload: no id in response"

AFTER_UP=$(obj_count)
[[ "$AFTER_UP" == $((BEFORE + 1)) ]] || fail "MinIO object NOT created after upload ($BEFORE -> $AFTER_UP)"

# --- 5. list ---------------------------------------------------------------------
echo "== list contains uploaded file"
curl -fsS "$BASE/api/v1/files?page=0&size=100" -H "$AUTH" |
  jq -e --arg id "$ID" '.data.content[] | select(.id == $id)' >/dev/null ||
  fail "uploaded file absent in list"

# --- 6. download roundtrip ---------------------------------------------------------
echo "== download and compare content"
curl -fsS "$BASE/api/v1/files/$ID/download" -H "$AUTH" -o "$TMP/dl.png"
cmp -s "$TMP/pixel.png" "$TMP/dl.png" || fail "downloaded content differs from uploaded"

# --- 7. delete ---------------------------------------------------------------
# ВНИМАНИЕ (README §22): удаление в приложении SOFT-delete. Metadata исчезает
# из API сразу, объект MinIO остаётся до прогона FilePurgeScheduler
# (app.scheduler.file-purge.cron, по умолчанию 02:00 UTC ежедневно).
# Поэтому после DELETE проверяем metadata-сторону строго, а состояние объекта
# фиксируем информационно. Полная проверка purge-цикла — отдельный ops-сценарий.
echo "== delete"
CODE=$(curl -sS -o /dev/null -w '%{http_code}' -X DELETE "$BASE/api/v1/files/$ID" -H "$AUTH")
[[ "$CODE" == "204" ]] || fail "delete -> HTTP $CODE"

curl -fsS "$BASE/api/v1/files?page=0&size=100" -H "$AUTH" |
  jq -e --arg id "$ID" '.data.content[] | select(.id == $id)' >/dev/null &&
  fail "metadata still listed after delete"

CODE=$(curl -sS -o /dev/null -w '%{http_code}' "$BASE/api/v1/files/$ID/download" -H "$AUTH")
[[ "$CODE" == "404" ]] || fail "download after delete -> HTTP $CODE (expected 404)"

AFTER_DEL=$(obj_count)
if [[ "$AFTER_DEL" == "$BEFORE" ]]; then
  echo "info: MinIO object purged immediately"
else
  echo "info: MinIO object retained until FilePurgeScheduler runs (soft-delete, README §22)"
fi

echo "SMOKE OK: health/register/login/upload/list/download/delete verified"
