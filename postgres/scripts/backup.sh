#!/usr/bin/env bash
# Backup PostgreSQL -> MinIO bucket postgres-backups (README §18).
# Имя файла несёт timestamp + database identifier + pg-версию (требование §18).
# Выполняется ВНУТРИ Lima VM (kubectl доступен пользователю VM).
#
# Backup без проверки восстановления невалиден: за ним следует
#   CONFIRM=yes scripts/restore.sh <имя-файла>
set -euo pipefail

NS="${SMOKE_NS:-cloudshare}"
BUCKET="postgres-backups"
TS=$(date -u +%Y%m%d-%H%M%S)

PG_VER=$(kubectl -n "$NS" exec deploy/postgres -- sh -c 'psql --version' | awk '{print $3}')
FILE="cloudshare-${TS}-pg${PG_VER}.sql.gz"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

MC_ALIAS='mc alias set q http://localhost:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null;'

echo "== pg_dump -> ${FILE}"
kubectl -n "$NS" exec deploy/postgres -- sh -c 'pg_dump -U "$POSTGRES_USER" -d cloudshare' |
  gzip > "${TMP}/${FILE}"
[[ -s "${TMP}/${FILE}" ]] || { echo "FAIL: пустой дамп" >&2; exit 1; }
SHA=$(sha256sum "${TMP}/${FILE}" | cut -d" " -f1)

echo "== upload к s3://${BUCKET}/"
kubectl -n "$NS" exec -i deploy/minio -- bash -c "${MC_ALIAS} mc pipe q/${BUCKET}/${FILE}" < "${TMP}/${FILE}"

SIZE=$(kubectl -n "$NS" exec deploy/minio -- bash -c "${MC_ALIAS} mc ls q/${BUCKET}/${FILE}" | awk '{print $4}')
[[ -n "$SIZE" && "$SIZE" != "0" ]] || { echo "FAIL: объект не появился в bucket" >&2; exit 1; }

echo "BACKUP OK: ${FILE} (${SIZE} bytes, sha256=${SHA})"
