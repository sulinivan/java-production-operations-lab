#!/usr/bin/env bash
# Выполняется ВНУТРИ Lima VM (ansible роль cluster копирует его в /opt/lab).
# Создаёт Kubernetes Secrets из .env.lab идемпотентно (--dry-run=client | apply).
set -euo pipefail

ENV_FILE="/opt/lab/.env.lab"
if [[ ! -f "$ENV_FILE" ]]; then
  echo "ERROR: $ENV_FILE not found. Run scripts/gen-secrets.sh on the host first." >&2
  exit 1
fi
# shellcheck disable=SC1090
source "$ENV_FILE"

NS=cloudshare

apply_secret() {
  local name="$1"; shift
  kubectl -n "$NS" create secret generic "$name" "$@" --dry-run=client -o yaml |
    kubectl apply -f - >/dev/null
  echo "secret applied: $name"
}

apply_secret cloudshare-db \
  --from-literal=username="$POSTGRES_USERNAME" \
  --from-literal=password="$POSTGRES_PASSWORD"

apply_secret cloudshare-minio \
  --from-literal=access-key="$MINIO_ACCESS_KEY" \
  --from-literal=secret-key="$MINIO_SECRET_KEY"

apply_secret cloudshare-app \
  --from-literal=jwt-secret="$JWT_SECRET" \
  --from-literal=crypto-master-kek="$CRYPTO_MASTER_KEK" \
  --from-literal=redis-cache-password="$REDIS_CACHE_PASSWORD" \
  --from-literal=redis-security-password="$REDIS_SECURITY_PASSWORD" \
  --from-literal=redis-ratelimit-password="$REDIS_RATELIMIT_PASSWORD"

# DSN для postgres-exporter (фаза 6). Роль pg_monitor выдаётся ролью monitoring.
apply_secret cloudshare-pg-exporter \
  --from-literal=data-source-name="postgresql://${POSTGRES_USERNAME}:${POSTGRES_PASSWORD}@cloudshare-postgres:5432/cloudshare?sslmode=disable"

if [[ -n "${GRAFANA_ADMIN_PASSWORD:-}" ]]; then
  echo "GRAFANA_ADMIN_PASSWORD: present (используется при установке kube-prometheus-stack)"
fi
