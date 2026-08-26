#!/usr/bin/env bash
# Полный bootstrap лаборатории с нуля (README §47):
# infrastructure -> configuration -> cluster -> platform -> app -> observability.
# Выполняется на developer machine (macOS). Идемпотентен: существующие слои
# обновляются, а не пересоздаются.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

step() { printf "\n=== %s\n" "$*"; }

step "terraform: Lima VM"
terraform -chdir=terraform init -input=false
terraform -chdir=terraform apply -auto-approve -input=false

step "ansible inventory из terraform outputs"
scripts/gen-inventory.sh

step "ansible: host configuration (docker / kind / helm / nginx / runner)"
(cd ansible && ansible-playbook playbooks/site.yml)

# Членство в группе docker действует только для новых SSH-сессий:
# сбрасываем мастер-соединение Lima перед следующим шагом.
SSH_CONFIG="$(terraform -chdir=terraform output -raw ssh_config_path)"
ssh -O exit -F "$SSH_CONFIG" "$(awk 'tolower($1)=="host"{print $2; exit}' "$SSH_CONFIG")" 2>/dev/null || true

step "локальные secrets (.env.lab, в Git не попадает)"
scripts/gen-secrets.sh

step "kind cluster + platform dependencies + buckets"
(cd ansible && ansible-playbook playbooks/cluster.yml)

step "application deploy + smoke test"
(cd ansible && ansible-playbook playbooks/app.yml)

step "observability stack (prometheus / loki / alloy / dashboards)"
(cd ansible && ansible-playbook playbooks/monitoring.yml)

printf "\nBOOTSTRAP COMPLETE.\n"
echo "Edge (нужны записи в /etc/hosts хоста: api.lab.local и grafana.lab.local -> 127.0.0.1):"
echo "  curl -H 'Host: api.lab.local' http://127.0.0.1/actuator/health/liveness"
