# Decisions

Архитектурные и операционные решения проекта. Каждое решение отвечает на вопрос README §49:
какую эксплуатационную задачу решает компонент.

Формат: Context → Decision → Consequences.

---

## ADR-001: Monorepo — приложение поглощено как каталог без вложенного `.git`

- **Дата:** 2026-08-25
- **Статус:** принято

**Context.** README §40 требует, чтобы репозиторий содержал application reference, инфраструктуру,
CI/CD и документацию вместе. `cloudshare-app/` имел собственный `.git` (upstream:
`github.com/Dhruv0306/cloudshare-app`, MIT). Вложенный `.git` при пуле родительского репозитория
превращается в «битый» gitlink — файлы приложения вообще не попадут в репозиторий.

**Decision.** Вложенный `.git` удалён; приложение живёт в монорепо как обычный каталог.
История сохранена в upstream-репозитории.

**Consequences.** CI собирает образ из одного checkout'а без submodule-шага. Обновления из
upstream переносятся вручную (приложение по условиям проекта не изменяется без необходимости).
Единственное допустимое изменение зафиксировано в ADR-005.

---

## ADR-002: Workload разворачивается как есть — включая 3× Redis и ClamAV

- **Дата:** 2026-08-25
- **Статус:** принято

**Context.** Приложение помимо PostgreSQL и MinIO (стек из README) требует: Redis cache-aside,
Redis security-instance (fail-closed: blacklist токенов/step-up — недоступность ломает login),
Redis rate-limit (fail-open), ClamAV (сканирование при upload), пароли всех трёх Redis
валидируются на старте (`SecretsStartupValidator`). Это готовый объект эксплуатации —
упрощать его код запрещено смыслом проекта (README §15).

**Decision.** Все зависимости разворачиваются в kind как есть: PostgreSQL, MinIO, 3× Redis
(с паролями), ClamAV. Дополнительные компоненты не «для демонстрации», а потому что без них
workload не работает — принцип §49 соблюдён.

**Consequences.** Бюджет памяти VM поднят до 12 GB. Инцидентные сценарии становятся богаче:
отказ Redis-инстансов даёт разные режимы отказа (fail-closed vs fail-open).

---

## ADR-003: Stateful-компоненты как Deployment + PVC, не StatefulSet

- **Дата:** 2026-08-25
- **Статус:** принято

**Context.** README §12 прямо запрещает автоматически переносить stateful infrastructure в
StatefulSet «ради демонстрации StatefulSet». Лаборатория однонодовая.

**Decision.** PostgreSQL, MinIO и Redis — Deployment + PersistentVolumeClaim (local-path).
Размещение зафиксировано этим ADR и docs/architecture.md; менять между этапами запрещено (§41).

**Consequences.** Нет демонстрации StatefulSet-механики — осознанный отказ. Сценарий data-loss
и restore от этого проще и полностью соответствует целям проекта (§34).

---

## ADR-004: Стек наблюдаемости — kube-prometheus-stack + Loki + Alloy + postgres-exporter

- **Дата:** 2026-08-25
- **Статус:** принято

**Context.** Требования §24–29: метрики Java/K8s/PostgreSQL, alert rules, централизованные логи
со stdout контейнеров, Loki с object storage на MinIO, общая UI — Grafana. ServiceMonitor в
структуре chart (§13) подразумевает Prometheus Operator.

**Decision.** Helm-чарты: `kube-prometheus-stack` (Prometheus + Operator + Alertmanager +
Grafana + kube-state-metrics + node-exporter), `loki` с S3-backend → MinIO bucket `loki`,
`alloy` как log collector (stdout контейнеров → Loki), `prometheus-exporter` для PostgreSQL.
Приложение уже отдаёт `/actuator/prometheus` (Micrometer) — ничего в нём менять не нужно.

**Consequences.** Один оператор закрывает метрики K8s и алертинг из коробки; Loki на MinIO
даёт вторую связь MinIO (documents / postgres-backups / loki), требуемую §20/§27.

---

## ADR-005: Базовый образ приложения alpine → jammy (платформа arm64)

- **Дата:** 2026-08-25
- **Статус:** принято

**Context.** Хост — Apple M1 (arm64). `eclipse-temurin:17-jre-alpine` публикуется только для
linux/amd64 — образ не запустится на arm64 kind-ноде без медленной эмуляции qemu.

**Decision.** В `cloudshare-app/Dockerfile` базовый образ runtime-стадии меняется на
`eclipse-temurin:17-jre-jammy` (multiarch) + установка `wget` для HEALTHCHECK. Diff ~3 строки,
поведение приложения не затронуто. Это единственное изменение приложения в проекте.

**Consequences.** Образ собирается и работает на arm64; CI остаётся переносимым (сборка на
self-hosted runner внутри Lima VM той же архитектуры).

---

## ADR-006: «Сломанный релиз» v1.1.0 — через Helm values, не через код

- **Дата:** 2026-08-25
- **Статус:** принято

**Context.** §39 требует намеренно сломанную версию v1.1.0 для сценария rollback. Модифицировать
Java-код ради поломки — против смысла проекта.

**Decision.** v1.1.0-broken — тот же образ, сломанная конфигурация релиза через Helm values
(невалидный readiness path или URL БД). Отказ воспроизводится на уровне release layer, где его
и чинит `helm rollback`.

**Consequences.** Сценарий §30 выполняется без форка приложения; заодно покрывается Incident 6
(bad configuration, §35).

---

## ADR-007: Публичный репозиторий + self-hosted runner

- **Дата:** 2026-08-25
- **Статус:** принято

**Context.** README §9 рекомендует private repo на время разработки схемы self-hosted runner.
Я выбрал публичный репозиторий.

**Decision.** Репозиторий публичный. Компенсирующие меры описаны в docs/security.md
(изоляция runner, ограничение запуска workflows, секреты только в GitHub Secrets, approval для
внешних PR). Security implications self-hosted runners документированы отдельно — требование §9
выполнено.

**Consequences.** Повышенные требования к гигиене секретов: ни одного значения в Git (§42),
ротация токенов, минимальные scopes у runner.

---

## ADR-008: Registry и версионирование артефактов

- **Дата:** 2026-08-25
- **Статус:** принято

**Context.** §10–11, §43: versioned image, Docker Hub как registry, различение Git tag /
Docker tag / Helm revision.

**Decision.** Registry namespace: `docker.io/sulinivan/cloudshare`. Docker tag = SemVer
(`1.0.0`), Git tag = `v1.0.0`, `latest` не используется для deployment. Image tag передаётся в
Helm одним значением (`image.tag`).

**Consequences.** Полная прослеживаемость release: commit → tag → image → helm revision.

---

## ADR-009: Helm chart не создаёт credential secrets — ссылается на platform Secrets

- **Дата:** 2026-08-25
- **Статус:** принято

**Context.** README §40 показывает `secret.yaml` среди шаблонов chart. Если чарт сам создаёт
секреты из values, то креды протекают в release-историю (`helm get values --all`) и в
CI-переменные каждого деплоя.

**Decision.** Chart `cloudshare` ссылается на Kubernetes Secrets `cloudshare-db`,
`cloudshare-minio`, `cloudshare-app`, созданные вне Helm (scripts/apply-secrets.sh, фаза 4).
Шаблон secret.yaml отсутствует намеренно. Ingress-шаблона тоже нет: edge — nginx на хосте VM
(§14), ingress controller без эксплуатационной задачи не ставится (§49).

**Consequences.** `helm rollback`/переустановка релиза никогда не трогает credentials;
ротация секрета — platform-операция, не релизная.

---

## ADR-010: Удаление файлов в приложении — soft-delete; объект MinIO убирает purge-шедулер

- **Дата:** 2026-08-25
- **Статус:** принято

**Context.** Smoke-тест фазы 5 поймал расхождение с наивной моделью §22: после
`DELETE /api/v1/files/{id}` (HTTP 204) metadata исчезает сразу, но объект в MinIO остаётся.
Это документированное поведение приложения: soft-delete + `FilePurgeScheduler`
(cron `app.scheduler.file-purge.cron`, по умолчанию 02:00 UTC ежедневно) выполняет
`purgeSoftDeletedFile` — удаляет запись и S3-объект.

**Decision.** Smoke-тест проверяет строго metadata-сторону (204, отсутствие в list, 404 на
download) и фиксирует состояние объекта информационно. Полный цикл delete→purge проверяется
отдельным ops-упражнением (временный fast cron через helm values) в фазе 9.

**Consequences.** Сценарий §22 превращается в более богатый runbook: «удаление» и
«очистка» — разные точки отказа (объект мог не удалиться из MinIO при живой metadata-purge).
