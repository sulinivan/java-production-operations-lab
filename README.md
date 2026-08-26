# Java Production Operations Lab

Production-like учебно-портфельный проект по эксплуатации, доставке, наблюдаемости и восстановлению готового Java microservice.

> **Статус: проект завершён.** Definition of Done выполнен на **53/55** пунктов;
> два пункта я пропустил осознанно
> (см. [docs/dod-checklist.md](docs/dod-checklist.md)). Репозиторий:
> `github.com/sulinivan/java-production-operations-lab` (публичный).

## 1. Цель проекта

Цель проекта — получить практический опыт полного жизненного цикла Java-приложения в production-like инфраструктуре:

```text
source code
    ↓
GitHub
    ↓
CI
    ↓
Docker image
    ↓
Docker Hub
    ↓
CD
    ↓
Kubernetes / kind
    ↓
Java service
    ↓
PostgreSQL + S3/MinIO
    ↓
metrics + logs + health
    ↓
Prometheus + Loki + Grafana
    ↓
incident investigation
    ↓
backup / restore / rollback
```

Java-разработка не является целью проекта.

Java microservice используется как **готовый объект эксплуатации**.

Основной навык, который демонстрирует проект:

> Умение доставить, развернуть, наблюдать, диагностировать, обновить и восстановить Java-приложение в production-like инфраструктуре.

---

# 2. Основной workload

В качестве application workload используется готовый Java/Spring Boot сервис **CloudShare**
(secure file sharing, upstream: `Dhruv0306/cloudshare-app`, MIT; поглощён монорепо — ADR-001).

Приложение разворачивается **как есть**, со всеми своими зависимостями (ADR-002):

```text
Document
   │
   ├── metadata ──────► PostgreSQL (Flyway миграции)
   │
   └── content ───────► S3 API / MinIO (зашифровано на стороне приложения)
```

Фактический состав зависимостей шире минимальной пары из исходной спецификации:

| Зависимость | Роль | Поведение при отказе |
|---|---|---|
| PostgreSQL 17 | authoritative metadata store | приложение деградирует |
| MinIO (S3 API) | содержимое файлов, bucket `documents` | upload/download отказывают |
| Redis cache-aside | кэш, `allkeys-lru` | потеря безопасна |
| Redis security-instance | blacklist токенов, step-up, `noeviction` | **fail-closed**: login ломается |
| Redis rate-limit | счётчики окон, короткий timeout | fail-open |
| ClamAV | сканирование загружаемых файлов | upload невозможен |

Аутентификация — JWT (+ опциональная MFA); пароли всех трёх Redis и остальные
секреты жёстко валидируются при старте (`SecretsStartupValidator`, fail-closed).

---

# 3. Что доказано проектом

Полный чек-лист DoD с фактическими доказательствами — [docs/dod-checklist.md](docs/dod-checklist.md).

Кратко: инфраструктура поднимается автоматически (Terraform → Lima → Ansible → kind);
CI/CD прогоняет цикл от git-тега до smoke-теста через Docker Hub и self-hosted runner;
наблюдаемость собирает метрики трёх слоёв и логи восьми контейнеров; выполнен полный
DR-цикл с потерей базы и восстановлением, проверенным через REST API; отработаны
rollback'и двух видов. Два инцидентных сценария — MinIO-failure и
resource-pressure — я пропустил осознанно; runbooks обоих готовы.

---

# 4. Архитектурный принцип

Каждая технология существует только потому, что решает конкретную задачу.
Ни одного компонента «для демонстрации инструмента» не добавлено — принцип
закреплён и применялся через архитектурные решения [docs/decisions.md](docs/decisions.md).

Основные связи:

```text
Terraform (guidoiaquinti/lima ~> 0.1.0)
    ↓
Lima VM java-prod-ops-lab (ubuntu 24.04, vz, aarch64, 6 CPU / 12GiB / 80GiB)
    ↓
Ansible
    ↓
Linux host: Docker, kind 'lab', kubectl, Helm, Nginx, GitHub Actions runner
    ↓
kind Kubernetes
    ↓
Helm → Java application
    ├──────────────► PostgreSQL ── backups ──► MinIO/postgres-backups
    ├──────────────► S3 API → MinIO/documents
    ├── Redis ×3, ClamAV
    │
    ├── health ────────► Kubernetes probes
    ├── metrics ───────► Prometheus ← kube-state-metrics, node-exporter,
    │                                    postgres-exporter
    └── logs ──────────► Alloy → Loki → MinIO/loki

Prometheus ──────► Grafana ◄───── Loki

Client ──► Nginx (VM edge) ──► kind NodePort ──► Java Pod
```

---

# 5. Архитектурные границы

## 5.1 Infrastructure layer

Terraform отвечает только за создание инфраструктурной boundary:

```text
Terraform (provider guidoiaquinti/lima ~> 0.1.0, patch-pin)
    ↓
Lima VM
    ↓
Linux VM
```

Terraform не управляет релизами приложения (это Helm), не конфигурирует хост
(это Ansible). State хостится локально; провайдер до 1.0 запинен на patch-range.

## 5.2 Host configuration layer

Ansible приводит созданную Lima VM в состояние пригодное для эксплуатации.
Фактические роли: `common` (базовые пакеты, inotify sysctls), `docker`
(apt-репозиторий, ротация json-file логов), `kind`+`helm` (инструменты),
`nginx` (edge), `runner` (self-hosted GitHub Actions runner + CD-kubeconfig),
`cluster` (создание kind + платформенные зависимости), `app` (сборка образа +
деплой + smoke), `monitoring` (стек наблюдаемости).

Модель:

```text
Terraform:  «Создай мне машину»
Ansible:    «Сделай эту машину пригодной для эксплуатации»
```

---

# 6. Lima

Lima предоставляет Linux environment всей лаборатории. Фактические параметры
(`terraform/variables.tf`, применены): Ubuntu 24.04 LTS, backend vz, aarch64,
6 vCPU / 12 GiB RAM / 80 GiB disk. Домашний каталог хоста **не** пробрасывается
в VM (`mounts: null`) — deployment target изолирован от developer machine.
Проброшены порты 80/443 для edge Nginx.

```text
Developer machine (macOS, Apple M1)
       │  terraform apply + ansible over ssh
       ▼
Lima VM java-prod-ops-lab
       ├── Docker 29.7.2
       ├── kind cluster 'lab' (k8s v1.36.4)
       ├── kubectl v1.36.4, Helm v4.2.4
       ├── Nginx 1.24 (edge)
       └── self-hosted GitHub Actions runner
```

Инструментарий резолвится как latest stable в момент provisioning; обновление =
пересоздание VM (`scripts/bootstrap.sh` с нуля ~30 минут).

---

# 7. GitHub

Репозиторий: `github.com/sulinivan/java-production-operations-lab`, публичный
(моё решение; риски self-hosted runner компенсированы — см.
[docs/security.md](docs/security.md)). Приложение поглощено каталогом без вложенного
`.git`; история upstream сохранена (ADR-001).

Работа с приложением идёт через Git:

```text
branch → commit → pull request → CI → merge → tag → release → CD
```

---

# 8. GitHub Actions

Три workflow в `.github/workflows/`:

## CI — `ci.yml`

Триггеры: `pull_request` и `push` в `main`. Выполняется на **GitHub-hosted**
runner (ubuntu-latest) — PR-контекст никогда не касается self-hosted машины:

```text
checkout
   ↓
mvn verify (unit + JaCoCo coverage gate)
   ↓
docker build (валидация сборки)
   ↓
Trivy scan CRITICAL/HIGH = fail (security gate)
```

## Docker — `docker.yml`

Триггер: тег `vMAJOR.MINOR.PATCH`. Выполняется на **self-hosted runner**
(нативный linux/arm64, та же архитектура, что у kind-ноды):

```text
checkout → docker login (GitHub Secrets) → build → push
docker.io/sulinivan/cloudshare:<semver>
```

## CD — `cd.yml`

Триггер: `workflow_run` после успешного Docker. Self-hosted runner внутри Lima VM
имеет прямой доступ к кластеру через выделенный ServiceAccount
(`/opt/lab/kubeconfig-cd`, не копия admin-конфига):

```text
checkout head_sha → версия из git describe --tags
   ↓
helm upgrade --install --atomic (--set image.repository/tag, serviceMonitor.enabled)
   ↓
kubectl rollout status
   ↓
bash scripts/smoke-test.sh
```

Неудачный rollout или smoke = failed pipeline; `--atomic` откатывает релиз
автоматически (проверено: revision 24 failed → 25 rollback).

---

# 9. Self-hosted GitHub Actions Runner

Runner является частью deployment environment. Факт:

- имя `lima-lab-runner`, labels `self-hosted / Linux / ARM64`;
- выделенный непривилегированный пользователь `github-runner` (группа docker);
- systemd-юнит `actions.runner.sulinivan-java-production-operations-lab.lima-lab-runner.service`;
- регистрация одноразовым токеном через Ansible с `no_log` (токен нигде не сохраняется);
- CD-задачи работают через ServiceAccount `cd-deployer` (kubeconfig
  `/opt/lab/kubeconfig-cd`, root:github-runner 0640) — отзыв доступа = удаление SA.

Security implications публичного репозитория + self-hosted runner задокументированы
и компенсированы ([docs/security.md](docs/security.md)): fork-PR требуют approval,
CD триггерится только push/tag, секреты недоступны из fork-контекста.

---

# 10. Docker

Приложение упаковано в versioned multiarch-образ (linux/arm64 собран нативно):

```text
sulinivan/cloudshare:0.1.0
sulinivan/cloudshare:0.1.1
```

`latest` не используется как deployment identifier; тег обязателен в шаблоне
(`required "image.tag"`). Базовый образ runtime — `eclipse-temurin:17-jre-jammy`
(multiarch; alpine-варианты amd64-only — единственное изменение приложения,
ADR-005). Non-root user, HEALTHCHECK на actuator liveness сохранены.

---

# 11. Docker Hub

Registry: `docker.io/sulinivan/cloudshare`.

```text
git tag v0.1.1 → Docker workflow (self-hosted) → Docker Hub
             → CD workflow → Kubernetes imagePull
```

Kubernetes не зависит от локальных образов: финальные деплои получают image из
Hub (в ручном dev-цикле возможен interim `kind load` — помечен в коде как
не-final путь).

---

# 12. Kubernetes / kind

Однонодовый kind-кластер `lab` (k8s v1.36.4). Осознанное ограничение лаборатории,
production-grade HA не заявляется. Демонстрировано: deployment, service discovery,
probes, resource limits, ConfigMap/Secret, rollout/rollback, logs, metrics.

Stateful-зависимости — **Deployment + PVC** (local-path), не StatefulSet —
осознанное решение ADR-003 согласно этому разделу исходной спецификации.
Размещение зафиксировано и не менялось между этапами.

Базовые ресурсы приложения: Namespace `cloudshare`, Deployment, Service (NodePort
30080), ConfigMap (checksum-аннотация → rolling restart), Secret (только ссылки —
ADR-009), ServiceMonitor. Edge-интеграция — Nginx хоста VM вместо Ingress-контроллера.

---

# 13. Helm

Helm — application release layer. Фактическая структура:

```text
helm/cloudshare/
├── Chart.yaml                 # appVersion перезаписывается --set image.tag
├── values.yaml                # конфиг по умолчанию
├── values-dev.yaml            # диагностический профиль (plaintext DEBUG логи)
└── templates/
    ├── _helpers.tpl
    ├── configmap.yaml         # checksum-аннотация → rolling update
    ├── deployment.yaml        # probes, env/envFrom, resources
    ├── service.yaml           # NodePort 30080
    └── servicemonitor.yaml    # включается флагом
```

Шаблоны `secret.yaml` и `ingress.yaml` отсутствуют **намеренно** (ADR-009):
chart ссылается на platform Secrets `cloudshare-db/-minio/-app`, поэтому
`helm rollback` и переустановка никогда не трогают credentials; edge обслуживает
nginx хоста VM, ingress controller без эксплуатационной задачи не ставился.

Продемонстрировано на живых ревизиях: `helm install/upgrade/history/rollback`
(revision history 23–28, включая failed upgrade и оба вида отката).

---

# 14. Nginx

Nginx — edge reverse proxy на хосте Lima VM; не заменяет Kubernetes Service.

```text
api.lab.local     → 127.0.0.1:8080 → kind mapping :8080 → NodePort 30080 → Pod
grafana.lab.local → 127.0.0.1:3000 → kind mapping :3000 → NodePort 30300 → Grafana
```

Реализовано: reverse proxy, access/error логи per-vhost, upstream error handling
(502 при недоступных апстримах — наблюдалось до подъёма кластера), client_max_body_size
под multipart-лимиты приложения, WebSocket upgrade для Grafana Live. TLS termination
не реализован (опциональный пункт исходной спецификации).

---

# 15. Java Application

CloudShare — главный workload проекта. Приложение не изменялось без необходимости;
единственное изменение за весь проект — замена базового образа Dockerfile на
multiarch (ADR-005, ~3 строки). Изучается эксплуатация, а не разработка.

---

# 16. REST API

API используется для smoke tests, verification деплоя, incident reproduction.
Фактические операции (полная спека приложения — `cloudshare-app/docs/system-design/api-spec.md`):

```text
POST   /api/v1/auth/register          # уникальный пользователь для каждого прогона
POST   /api/v1/auth/login             # → accessToken (JWT)
POST   /api/v1/files/upload           # multipart `file`; Tika MIME check + ClamAV
GET    /api/v1/files?page=&size=      # metadata list
GET    /api/v1/files/{id}/download    # расшифровка на лету
DELETE /api/v1/files/{id}             # soft-delete
```

Smoke-цикл (`scripts/smoke-test.sh`): health → register → login →
upload → list → download (byte-compare) → delete → проверка обеих сторон хранилища.
Для upload используется валидный PNG: magic-byte MIME check отбраковывает случайные байты.

```text
deployment → curl API → HTTP 200/201/204 → deployment successful
```

---

# 17. PostgreSQL

PostgreSQL хранит file metadata, пользователей, sharing, audit (партиционирован
приложением), relational state. As-built: `postgres:17-alpine`, Deployment + PVC 5Gi
(Recreate), БД `cloudshare`, application user `cloudshare_user` (владелец схемы,
пароль из Secret `cloudshare-db`), экспортёру выдан `GRANT pg_monitor`.

Отработано администрирование: создание пользователя/БД, роли, active sessions
(`pg_stat_activity`), database size, connection pool Hikari (лимит понижен до
лабораторного значения через chart values), логи, восстановление.

---

# 18. PostgreSQL backup

```text
PostgreSQL → pg_dump → gzip → mc pipe → MinIO s3://postgres-backups/
```

`postgres/scripts/backup.sh`: имя файла несёт timestamp + identifier + pg-version —

```text
cloudshare-20260825-221336-pg17.11.sql.gz
```

Backup считается невалидным без проверки восстановления. В bucket лежат
два артефакта реальных прогонов.

---

# 19. PostgreSQL restore

Обязательный сценарий выполнен полностью:

```text
working database (пользователь + файл)
       ↓
backup → MinIO ✓
       ↓
DROP DATABASE ... WITH (FORCE)   # намеренная потеря
       ↓
CONFIRM=yes restore.sh <backup>  # guard от случайного запуска
       ↓
database recreated → dump applied → app restarted
       ↓
verify-restore.sh drill2@lab.local → «файлов у пользователя: 1» ✓
```

Недостаточно `pg_restore completed successfully` — доказано через REST API:
Java переподключилась, известный пользователь видит свои данные. Попутно
подтверждена точность point-in-time: попытка verify пользователя, созданного
ПОСЛЕ backup, честно вернула 401.

---

# 20. MinIO

S3-compatible object storage, Deployment + PVC 20Gi. Buckets (создаются Job
`minio-bucket-init`, idempotent `--ignore-existing`):

```text
documents          # содержимое файлов приложения
postgres-backups   # дампы PostgreSQL
loki               # chunks + TSDB index бэкенда логов
```

Каждый bucket имеет отдельную ответственность; связь Loki→MinIO даёт вторую
независимую нагрузку на object storage.

---

# 21. S3 API

Приложение работает с S3-compatible API через MinIO client; концептуально backend
заменяем на AWS S3 без изменения бизнес-модели. Credentials приложения передаются
через Kubernetes Secret `cloudshare-minio`.

---

# 22. Object lifecycle

Upload/download/delete проверяются smoke-тестом с обеих сторон хранилища:

```text
upload  → metadata created AND объект появился в documents (счётчик объектов)
download → содержимое совпадает побайтово (расшифровка on-the-fly)
delete  → metadata исчезла из list/download немедленно
```

Важное уточнение к исходной модели: удаление в приложении — **soft-delete**.
После `DELETE /files/{id}` (HTTP 204) объект MinIO остаётся до прогона
`FilePurgeScheduler` (`app.scheduler.file-purge.cron`, по умолчанию 02:00 UTC
ежедневно) — это документированное поведение приложения, а не рассинхрон
(ADR-010). Smoke-тест поэтому проверяет metadata-сторону строго (204 → нет в
list → download 404), а состояние объекта фиксирует информационно; полный цикл
delete→purge — отдельный ops-эксперимент с временным fast cron.

---

# 23. Health checks

Actuator probes включены приложением; Kubernetes использует их так
(`helm/cloudshare/values.yaml`):

```text
startupProbe   → /actuator/health/liveness   period 5s × 24 (JVM + Flyway)
livenessProbe  → /actuator/health/liveness   ТОЛЬКО livenessState
readinessProbe → /actuator/health/readiness  группа включает db
```

Readiness включает состояние БД явно: `MANAGEMENT_ENDPOINT_HEALTH_GROUP_READINESS_INCLUDE=readinessState,db`.
Нюанс Boot 4: вариант `additional-paths` не связался — явный `include` надёжен.
Liveness сознательно НЕ зависит от БД: временный отказ PostgreSQL не рестартует pod.

Проверено контролируемой потерей БД: readiness DOWN → pod снят с эндпоинтов
Service (`notReadyAddresses`), liveness UP, restarts = 0.

---

# 24. Metrics

```text
Java:            Micrometer /actuator/prometheus (JVM memory/GC/threads, HTTP rate/errors/latency)
Kubernetes:      kube-state-metrics + node-exporter
PostgreSQL:      postgres-exporter (v0.15.0; DATA_SOURCE_NAME из Secret, роль pg_monitor)
Pipeline:        ServiceMonitor → Prometheus → Grafana
```

Все targets up; живые проверки: `sum(jvm_memory_used_bytes)` и `count(kube_pod_info)`
возвращают значения, счётчик запросов приложения растёт со smoke-трафиком.

---

# 25. Prometheus

Backend — Prometheus из `kube-prometheus-stack` **88.5.4** (Operator + Alertmanager +
Grafana + kube-state-metrics + node-exporter), namespace `cloudshare`.
Скрейпы несуществующих в kind control-plane компонентов (etcd/scheduler/cm/proxy)
отключены — иначе вечные ложные алерты. Селекторы `*NilUsesHelmValues: false`,
чтобы оператор видел ServiceMonitor/PrometheusRule приложения.

Alert rules (`monitoring/prometheus/rules/cloudshare-alerts.yaml`) покрывают все
шесть требований: CloudshareAppUnavailable, HighHttpErrorRate (>5% 5m), HighLatencyP95
(>2s 10m), PodRestartStorm (>3 за 30m), ContainerMemoryNearLimit (>85% limit 10m),
PostgresUnavailable. Живое подтверждение конвейера: PodRestartStorm переходил в firing
на реальных рестартах exporter'а.

---

# 26. Logging

Логи приложения идут через stdout/stderr (JSON-энкодер logstash уже встроен в
non-dev профиль приложения — менять ничего не потребовалось). Collector — Alloy
DaemonSet: читает `/var/log/pods` ноды kind (ключевым оказался mount varlog),
labels namespace/pod/container, push → Loki.

---

# 27. Loki

Loki 7.3 (app 3.6.12), режим SingleBinary, schema v13 / store tsdb /
object_store s3. Backend — MinIO bucket `loki`: в бакете лежат реальные chunk-
файлы и TSDB-индексы. retention 168h. Три связи MinIO подтверждены:

```text
MinIO: documents / postgres-backups / loki
```

---

# 28. Grafana

Общая operational UI (NodePort 30300 + edge vhost `grafana.lab.local`). Дашборды
провижинены через sidecar из ConfigMap `grafana-dashboards-cloudshare`:

- **CloudShare / Application & JVM** — request rate, error rate, p50/p95 latency, heap, GC, threads
- **CloudShare / Kubernetes** — pod CPU/memory vs limit, restarts, pod phases
- **CloudShare / PostgreSQL** — pg_up, connections, db size, transactions

Datasources: Prometheus (из stack) + Loki (additionalDataSources). Поиск логов
работает: `{namespace="cloudshare"}` возвращает потоки восьми контейнеров.

---

# 29. Observability workflow

Workflow закреплён и использовался в реальных разборах:

```text
Alert → Metrics → affected service → Logs → error → Health checks
     → Fix / rollback / restore → Verify metrics → Verify API
```

Пример из практики проекта: PodRestartStorm (metrics) → рестарты exporter
(k8s events) → причина найдена в логах → фикс образа → alert resolved.

---

# 30–36. Incident scenarios — фактические статусы

Runbooks всех семи сценариев написаны по единому шаблону (`docs/incidents/`).
Исполнение:

| Сценарий | Статус | Как доказан |
|---|---|---|
| Broken release | ✅ выполнен | CD run c несуществующим `image.tag=main`: rollout timeout → pipeline FAILED → авто-откат `--atomic` (rev 25) |
| PostgreSQL unavailable | ✅ выполнен | DROP DATABASE: readiness DOWN, pod снят с трафика, liveness UP, restarts 0 |
| PostgreSQL credentials broken | ◐ частично | механику покрывают смежные прогоны (рассинхрон секретов ловится валидатором старта); отдельного сценария смены пароля не проводилось |
| MinIO unavailable | ❌ пропущен мной | runbook готов |
| Data loss → restore | ✅ выполнен | полный DR-цикл с known-user проверкой через API |
| Bad configuration | ✅ выполнен | неверное configuration value через Helm values → отказ → диагностика → исправление конфигурацией; плюс фикс readiness-группы с наблюдением probes |
| Resource pressure | ❌ пропущен мной | runbook готов |

---

# 37. CI/CD acceptance criteria

Подтверждено полным циклом релизов v0.1.0 и v0.1.1:

```text
git push → CI passes → image created → pushed to Docker Hub
        → CD starts (workflow_run) → Helm upgrade → rollout
        → readiness successful → REST smoke test OK
```

Pipeline завершается ошибкой при неудаче любого шага — доказано реальным failed
run (rollout timeout на несуществующем теге).

---

# 38. Deployment verification

`scripts/smoke-test.sh` выполняется автоматически после каждого деплоя (внутри
CD job) и вручную:

```text
health (liveness+readiness UP)
    ↓ create unique user → login
    ↓ upload (valid PNG) → object counter in MinIO +1
    ↓ list содержит файл
    ↓ download → byte-compare
    ↓ delete → 204, отсутствует в list, download 404
```

---

# 39. Rollback acceptance criteria

Два независимых доказательства:

1. **Автоматический** — `--atomic`: неудачный upgrade (rev 24) откатился сам
   (rev 25), сервис остался доступен.
2. **Ручной** — `helm rollback` после осознанного изменения конфигурации
   (rev 27 → 28), rollout успешен, readiness UP.

---

# 40. Repository structure

Фактическая структура репозитория:

```text
java-production-operations-lab/
│
├── README.md                      # этот документ (as-built)
├── .env.lab.example               # формат локальных секретов (реальные — вне Git)
│
├── docs/
│   ├── architecture.md            # размещение компонентов, версии стека
│   ├── decisions.md               # 10 ADR — все ключевые решения
│   ├── dod-checklist.md           # итоговая верификация DoD 48/50
│   ├── deployment.md              # процедуры bootstrap/deploy/backup/destroy
│   ├── operations.md              # регламент эксплуатации
│   ├── security.md                # модель секретов, риски public repo + runner
│   ├── troubleshooting.md         # симптом → проверка → runbook
│   ├── backup-restore.md          # политика и процедуры DR
│   ├── evidence/README.md         # реестр доказательств (без скриншотов)
│   └── incidents/                 # 7 runbooks по единому шаблону
│
├── terraform/                     # main/variables/outputs/versions.tf + lock
├── ansible/
│   ├── ansible.cfg
│   ├── inventory/hosts.ini.example
│   ├── playbooks/                 # site, common, docker, kubernetes, nginx,
│   │                              # runner, cluster, app, monitoring
│   └── roles/                     # common docker kind helm nginx runner
│                                  # cluster app monitoring (+templates)
├── kind/cluster.yaml              # extraPortMappings 8080→30080, 3000→30300
│
├── helm/cloudshare/               # Chart, values, values-dev, templates/*
│
├── kubernetes/                    # platform deps: namespace, postgres,
│                                  # minio(+bucket-init), redis×3, clamav
│
├── postgres/scripts/              # backup.sh, restore.sh, verify-restore.sh
├── monitoring/                    # prometheus/(values,rules), loki/, alloy/,
│                                  # grafana/dashboards/, postgres-exporter.yaml
├── scripts/                       # bootstrap, gen-inventory, gen-secrets,
│                                  # apply-secrets, deploy, smoke-test, destroy
├── .github/workflows/             # ci.yml, docker.yml, cd.yml
│
└── cloudshare-app/                # готовый workload (Spring Boot 4, MIT);
                                   # src, migrations, frontend, tests, docs
```

---

# 41. Environment model

```text
developer machine (macOS)
        ▼
Lima VM java-prod-ops-lab
        ├── kind cluster 'lab'
        ├── Nginx edge (host 80/443)
        └── self-hosted runner
```

Внутри Kubernetes — один namespace `cloudshare`: Java, PostgreSQL, MinIO,
Redis ×3, ClamAV, Prometheus stack, Grafana, Loki, Alloy, postgres-exporter.

Таблица портов:

| Уровень | Порт | Назначение |
|---|---|---|
| macOS host | 80/443 | forwarded → VM nginx |
| VM loopback | 8080 / 3000 | kind extraPortMappings |
| kind node | 30080 / 30300 | NodePort cloudshare / Grafana |

Размещение stateful-зависимостей зафиксировано (ADR-003) и не менялось между этапами.

---

# 42. Secrets

Ни один реальный credential не хранится в Git. Механизм: `scripts/gen-secrets.sh`
генерирует `.env.lab` (chmod 600, gitignored) → `scripts/apply-secrets.sh`
создаёт Kubernetes Secrets → chart только ссылается на них (ADR-009).

Фактический набор:

| Секрет | Хранение | Потребитель |
|---|---|---|
| POSTGRES_USERNAME/PASSWORD | `.env.lab` | Secret `cloudshare-db` |
| MINIO_ACCESS_KEY/SECRET_KEY | `.env.lab` | Secret `cloudshare-minio` |
| JWT_SECRET, CRYPTO_MASTER_KEK | `.env.lab` | Secret `cloudshare-app` |
| REDIS_*_PASSWORD ×3 | `.env.lab` | Secret `cloudshare-app` |
| GRAFANA_ADMIN_PASSWORD | `.env.lab` | helm --set-string при установке |
| DOCKERHUB_USERNAME/TOKEN | GitHub Secrets | docker.yml push |
| GITHUB_RUNNER_TOKEN | одноразовый, env при установке | Ansible, не сохраняется |

Приложение дополнительно валидирует форму секретов на старте (fail-closed).

---

# 43. Versioning

Три различаемых понятия на живых примерах:

```text
Git tag:          v0.1.0, v0.1.1
Docker tag:       0.1.0, 0.1.1   (= тег без префикса v)
Helm revision:    23…28          (инкремент каждого upgrade/rollback)
```

`latest` не используется для deployment; пустой `image.tag` в шаблоне запрещён.

---

# 44. Definition of Done

Итог верификации: **53 из 55 пунктов выполнены**, каждый подтверждён командой
или прогоном pipeline. Детальная таблица с доказательствами —
[docs/dod-checklist.md](docs/dod-checklist.md).

Резюме по слоям:

```text
Infrastructure  4/4     CI             6/6
CD              6/6     Application    8/8
PostgreSQL      6/6     Kubernetes    10/10
Observability   8/8     Operations     5/7*
```

\* MinIO-failure и resource-pressure я пропустил;
runbooks обоих сценариев написаны и готовы к исполнению.

---

# 45. Portfolio evidence

Фазу скриншотов-доказательств я пропустил.
Существует реестр ожидаемых артефактов с требованием формата
Problem/Action/Expected/Actual/Evidence — `docs/evidence/README.md`;
доказательства зафиксированы текстово в `docs/dod-checklist.md`.

---

# 46. Runbook format

Каждый operational scenario имеет runbook по шаблону (Symptoms → Impact →
Detection → Investigation → Root cause → Recovery → Verification → Prevention):

```text
docs/incidents/
├── broken-release.md          # ✅ исполнен
├── postgres-failure.md        # ✅ исполнен
├── postgres-credentials.md    # ◐ частично
├── minio-failure.md           # ❌ пропущен (runbook готов)
├── postgres-data-loss.md      # ✅ исполнен (главный DR)
├── bad-configuration.md       # ✅ исполнен
└── resource-pressure.md       # ❌ пропущен (runbook готов)
```

---

# 47. Final end-to-end scenario

Главный сценарий проекта выполнен дважды (v0.1.0, v0.1.1):

```text
git tag v0.1.1 → push
    → CI (mvn verify, Trivy gate)
    → Docker workflow (arm64 build на self-hosted runner)
    → Docker Hub: docker.io/sulinivan/cloudshare:0.1.1
    → CD workflow: helm upgrade --atomic на self-hosted runner
    → Kubernetes rollout → SMOKE OK
    → в кластере крутится образ из Docker Hub
```

Затем намеренно ломался релиз:

```text
bad release (image.tag=main) → rollout timeout → pipeline FAILED
    → auto-rollback (--atomic) → сервис жив
    → fix версии из git describe → повторный тег → deployed
```

И моделировалась потеря данных:

```text
known user + file → backup → MinIO
    → DROP DATABASE → readiness DOWN, restarts 0
    → restore из MinIO → Java reconnect
    → REST verification: известный пользователь видит свои файлы
```

Это и есть главный результат проекта.

---

# 48. Final project statement

Проект демонстрирует не знание отдельных инструментов, а способность построить
и эксплуатировать связанный delivery/runtime environment:

```text
Infrastructure → Configuration → CI → Artifact → CD → Runtime
→ Dependencies → Observability → Incident response → Recovery
```

Каждый слой зависит от предыдущего и предоставляет основу следующему — вся цепочка
проверена на практике.

Главная цель достигнута:

> **Take a ready-made Java service from source control to a reproducible
> production-like environment, operate it under normal and failure conditions,
> observe it, diagnose incidents, roll back releases, and recover persistent data.**

---

# 49. Принцип проекта

Если компонент нельзя объяснить фразой:

> **«Без этого компонента конкретная эксплуатационная задача проекта не решается»**

— компонент не добавляется.

Принцип соблюдён на протяжении всего проекта: ни Redis/Kafka/ArgoCD/Istio/Vault,
ни StatefulSet, ни ingress-controller, ни secret.yaml в chart не появились
«ради списка технологий». Каждое решение зафиксировано с обоснованием в
[docs/decisions.md](docs/decisions.md) (10 ADR), включая отказы от компонентов.
