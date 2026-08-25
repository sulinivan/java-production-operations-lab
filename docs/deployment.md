# Deployment

> Status: scaffold — заполняется в фазах 2–4, 8.

## Предварительные требования

- macOS host: terraform, ansible, limactl, gh
- Docker Hub аккаунт (namespace: `sulinivan`)
- GitHub Secrets: `DOCKERHUB_USERNAME`, `DOCKERHUB_TOKEN`, `GITHUB_RUNNER_TOKEN`

## Bootstrap

```bash
scripts/bootstrap.sh   # TODO фаза 7: terraform apply → ansible site → kind create → platform deps
```

## Модель окружения

- TODO: developer machine / Lima VM / kind — схема и порты (§41) — фаза 3

## Версионирование (§43)

- Git tag: `v1.0.0`
- Docker tag: `1.0.0` (без `latest` для deployment)
- Helm release revision: инкрементируется каждым `helm upgrade`

## Процедура деплоя

- TODO: ручной deploy (`scripts/deploy.sh`) — фаза 7
- TODO: автоматический CD из GitHub Actions — фаза 8

## Deployment verification (§38)

```bash
scripts/smoke-test.sh  # health → upload → list → download → delete → проверка объекта в MinIO
```

## Rollback (§39)

- TODO: helm rollback процедура и критерии — фаза 9
