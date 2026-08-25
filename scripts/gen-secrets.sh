#!/usr/bin/env bash
# Генерирует .env.lab с credentials лаборатории (README §42: реальные значения
# никогда не попадают в Git). Идемпотентен: существующий файл не трогает.
# Файл читают: scripts/apply-secrets.sh (в VM) и фаза 5/8 (Helm values, GitHub Secrets).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
F="$ROOT/.env.lab"

if [[ -f "$F" ]]; then
  echo "already exists, not regenerated: $F"
  exit 0
fi

b64() { openssl rand -base64 "$1" | tr -d '\n'; }
hex() { openssl rand -hex "$1"; }

cat > "$F" <<EOF
POSTGRES_USERNAME=cloudshare_user
POSTGRES_PASSWORD=$(hex 16)
JWT_SECRET=$(hex 48)
CRYPTO_MASTER_KEK=$(b64 32)
MINIO_ACCESS_KEY=cloudshare-minio
MINIO_SECRET_KEY=$(hex 16)
REDIS_CACHE_PASSWORD=$(hex 16)
REDIS_SECURITY_PASSWORD=$(hex 16)
REDIS_RATELIMIT_PASSWORD=$(hex 16)
EOF

chmod 600 "$F"
echo "written: $F (chmod 600, gitignored)"
