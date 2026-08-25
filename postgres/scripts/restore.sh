#!/usr/bin/env bash
# Restore PostgreSQL из backup, лежащего в MinIO postgres-backups (README §19).
# DESTRUCTIVE: пересоздаёт базу cloudshare. Требует подтверждения:
#   CONFIRM=yes scripts/restore.sh <имя-файла-в-bucket>
# После restore выполняется restart приложения (чистое переподключение пула),
# application-level доказательство — scripts/verify-restore.sh (§19).
set -euo pipefail

[[ "${CONFIRM:-}" == "yes" ]] ||
  { echo "DESTRUCTIVE: пересоздание базы cloudshare." >&2; echo "Подтверждение: CONFIRM=yes $0 <backup-file>" >&2; exit 1; }

BACKUP="${1:?usage: CONFIRM=yes $0 <backup-file>}"
NS="${SMOKE_NS:-cloudshare}"
BUCKET="postgres-backups"

MC_ALIAS='mc alias set q http://localhost:9000 "$MINIO_ROOT_USER" "$MINIO_ROOT_PASSWORD" >/dev/null;'

echo "== download ${BACKUP} из s3://${BUCKET}/"
kubectl -n "$NS" exec deploy/minio -- bash -c "${MC_ALIAS} mc cat q/${BUCKET}/${BACKUP}" > "/tmp/${BACKUP}"
[[ -s "/tmp/${BACKUP}" ]] || { echo "FAIL: файл не скачался" >&2; exit 1; }
gunzip -f "/tmp/${BACKUP}"
SQL="/tmp/${BACKUP%.gz}"

echo "== drop & recreate database (terminates active connections)"
kubectl -n "$NS" exec deploy/postgres -- bash -c \
  'psql -U "$POSTGRES_USER" -d postgres -c "DROP DATABASE IF EXISTS cloudshare WITH (FORCE);" -c "CREATE DATABASE cloudshare;"'

echo "== применяю дамп"
kubectl -n "$NS" exec -i deploy/postgres -- \
  psql -U cloudshare_user -d cloudshare -v ON_ERROR_STOP=1 -q < "$SQL"

echo "== restart приложения (чистое переподключение Hikari-пула)"
kubectl -n "$NS" rollout restart deploy/cloudshare
kubectl -n "$NS" rollout status --timeout=300s deploy/cloudshare

rm -f "/tmp/${SQL}" "/tmp/${BACKUP}"
echo "RESTORE OK: база пересоздана, приложение переподключено."
echo "Дальнейшее доказательство через API: scripts/verify-restore.sh [<email> <password>]"
