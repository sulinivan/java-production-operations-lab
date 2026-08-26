#!/usr/bin/env bash
# Ручной деплой приложения: build (если нужно) -> kind load -> helm upgrade -> smoke.
# Автоматический путь из CI/CD делает те же шаги на self-hosted runner.
#
#   scripts/deploy.sh            # текущий тег по умолчанию (0.1.0)
#   scripts/deploy.sh 1.1.0     # собрать/задеплоить конкретную версию
set -euo pipefail
TAG="${1:-}"
cd "$(dirname "$0")/../ansible"

ARGS=()
[[ -n "$TAG" ]] && ARGS+=(-e "app_image_tag=$TAG")
exec ansible-playbook playbooks/app.yml "${ARGS[@]}"
