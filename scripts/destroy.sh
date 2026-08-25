#!/usr/bin/env bash
# Уничтожение лаборатории: terraform destroy удаляет Lima VM вместе со всеми
# данными (PVC, backups в MinIO, state кластера). DESTRUCTIVE:
#   CONFIRM=yes scripts/destroy.sh
set -euo pipefail
[[ "${CONFIRM:-}" == "yes" ]] ||
  { echo "Уничтожает Lima VM и ВСЕ данные лаборатории." >&2; echo "Подтверждение: CONFIRM=yes $0" >&2; exit 1; }
cd "$(dirname "$0")/../terraform"
terraform destroy -auto-approve -input=false
