# Deployment

> Заполнено в фазе 7. Все процедуры фактически выполнены и проверены.

## Предварительные требования

- macOS host: terraform, ansible, limactl, gh (см. фазы 2–3)
- Docker Hub аккаунт (namespace: `sulinivan`)
- GitHub Secrets для CI/CD (фаза 8): `DOCKERHUB_USERNAME`, `DOCKERHUB_TOKEN`

## Bootstrap с нуля

```bash
scripts/bootstrap.sh
```

Оркестрирует весь конвейер README §47: terraform apply → gen-inventory →
ansible site → gen-secrets → kind cluster + platform → app deploy + smoke →
monitoring. Идемпотентен: повторный запуск обновляет слои, а не пересоздаёт.

## Модель окружения (§41)

```text
developer machine (macOS)
        │  terraform (guidoiaquinti/lima) + ansible over ssh
        ▼
Lima VM java-prod-ops-lab (ubuntu 24.04, 6cpu/12Gi/80Gi)
        ├── docker, kind cluster 'lab', kubectl, helm
        ├── nginx edge: api.lab.local -> :8080, grafana.lab.local -> :3000
        └── self-hosted GitHub Actions runner
```

Порты наружу VM: 80/443 (nginx). Внутри kind: NodePort 30080 (app), 30300 (grafana).

## Версионирование (§43)

| Понятие | Формат | Пример |
|---|---|---|
| Git tag | `vMAJOR.MINOR.PATCH` | `v1.0.0` |
| Docker tag | `MAJOR.MINOR.PATCH` (без `latest`) | `1.0.0` |
| Helm revision | инкремент каждого `helm upgrade` | `1, 2, 3` |

## Процедуры

### Ручной деплой версии

```bash
scripts/deploy.sh [tag]   # build -> kind load -> helm upgrade --atomic -> smoke
```

### Backup / Restore (README §18–19)

Выполняется в VM (`ssh -F ~/.lima/java-prod-ops-lab/ssh.config lima-java-prod-ops-lab`),
скрипты лежат в `/opt/lab/postgres/scripts/`:

```bash
backup.sh                          # pg_dump -> gzip -> MinIO postgres-backups
CONFIRM=yes restore.sh <file>      # drop+recreate database -> dump -> app restart
verify-restore.sh [<email> <pass>] # §19: API работает, известный пользователь видит файлы
```

Backup без проверки восстановления считается невалидным.

### Destroy

```bash
CONFIRM=yes scripts/destroy.sh     # удаляет Lima VM со всеми данными
```
