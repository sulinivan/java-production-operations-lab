#!/usr/bin/env bash
# Генерирует ansible/inventory/hosts.ini из terraform outputs.
# Файл генерируемый: содержит локальные пути, в Git не попадает (.gitignore).
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
TF_DIR="$ROOT/terraform"
OUT="$ROOT/ansible/inventory/hosts.ini"

SSH_CONFIG="$(cd "$TF_DIR" && terraform output -raw ssh_config_path)"
HOST_ALIAS="$(cd "$TF_DIR" && terraform output -raw ssh_command | awk '{print $NF}')"
VM_USER="$(awk 'tolower($1)=="user" {print $2; exit}' "$SSH_CONFIG")"

mkdir -p "$(dirname "$OUT")"
cat > "$OUT" <<EOF
[lab]
${HOST_ALIAS}

[lab:vars]
ansible_user=${VM_USER}
ansible_ssh_common_args=-F ${SSH_CONFIG} -o ControlMaster=auto -o ControlPersist=10m
EOF

echo "inventory written: $OUT (${VM_USER}@${HOST_ALIAS})"
