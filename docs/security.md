# Security

> Status: частично заполнен (модель runner и секретов), остальное — по мере реализации.

## Секреты (§42)

В Git не хранится ни одного реального credential. Минимальный набор:

| Secret | Где живёт | Куда попадает |
|---|---|---|
| `POSTGRES_USERNAME` / `POSTGRES_PASSWORD` | GitHub Secrets / локальный env | Kubernetes Secret |
| `S3_ACCESS_KEY` / `S3_SECRET_KEY` | GitHub Secrets / локальный env | Kubernetes Secret |
| `JWT_SECRET`, `CRYPTO_MASTER_KEK` | GitHub Secrets / локальный env | Kubernetes Secret |
| Redis passwords ×3 | GitHub Secrets / локальный env | Kubernetes Secret |
| `DOCKERHUB_USERNAME` / `DOCKERHUB_TOKEN` | GitHub Secrets | CI push |
| `GITHUB_RUNNER_TOKEN` | одноразовый, env при установке runner | Ansible role, не сохраняется |

Приложение дополнительно валидирует секреты на старте (`SecretsStartupValidator`: длина,
запрет известных дефолтов) — fail-closed.

## Self-hosted runner в публичном репозитории

Риск: публичный репозиторий + self-hosted runner — известная опасная комбинация.
README §9 требует задокументировать это явно.

Принятые митигации (обязательны к соблюдению):

1. Runner регистрируется **только** для этого репозитория, группа `default`.
2. **Actions → General → Fork pull request workflows**: требуется approval для workflow
   запусков от форков; workflows из PR форков runner'ом не выполняются.
3. CD-джобы триггерятся только `push` в `main` и тегами — не `pull_request_target`.
4. Runner работает под выделенным непривилегированным пользователем внутри Lima VM
   (не root), Docker-сокет — через группу.
5. Runner-токен регистрации — одноразовый, передаётся через env, никогда не пишется в Git.
6. Секреты недоступны из PR-контекста форков (GitHub не пробрасывает secrets в fork PR).

## TODO

- TLS на edge (Nginx) — фаза 3/4
- Network policies между namespace — оценить по принципу §49 (фаза 6)
- Ротация JWT_SECRET / KEK и её последствия для данных — фаза 9
