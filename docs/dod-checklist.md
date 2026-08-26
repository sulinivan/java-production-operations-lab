# DoD Checklist — финальная верификация (README §44)

> Проверено фактически 2026-08-25/26. Каждый пункт подтверждён командой или
> запуском pipeline. Пропуски помечены явно с указанием причины.

## Infrastructure

- [x] Lima VM создаётся автоматически — `terraform apply` (фаза 2), VM `java-prod-ops-lab`
      создана за 4m52s, ssh-доступ через outputs Terraform
- [x] Infrastructure configuration в Terraform — `terraform/{main,variables,outputs,versions}.tf`
- [x] Linux host конфигурируется Ansible — `ansible/playbooks/site.yml`, идемпотентен
      (`changed=0 failed=0` на повторном прогоне)
- [x] Инструменты устанавливаются автоматически — Docker 29.7.2, kind v0.32.0,
      kubectl v1.36.4, helm v4.2.4, nginx 1.24, self-hosted runner (online)

## CI

- [x] GitHub Actions запускается на Pull Request — PR #1 → run 32909339115 на ubuntu-latest
      (PR никогда не касаются self-hosted runner — docs/security.md)
- [x] Tests выполняются автоматически — job `Maven test` (56s), `mvn verify` + JaCoCo gate
- [x] Integration tests выполняются автоматически — `mvn verify` включает Spring-context
      тесты приложения; отдельный e2e-workflow upstream не переносился (объект проекта —
      эксплуатация, не разработка приложения)
- [x] Security check выполняется автоматически — job `Docker build + security gate`,
      Trivy CRITICAL/HIGH = fail (v0.36.0)
- [x] Docker image создаётся автоматически — job `build-push` (5m20s, arm64 native)
- [x] Image публикуется в Docker Hub — `docker.io/sulinivan/cloudshare:0.1.1`

## CD

- [x] Deployment выполняется автоматически — workflow_run после Docker, run 32908903973
- [x] Kubernetes получает image из Docker Hub — running image:
      `docker.io/sulinivan/cloudshare:0.1.1` (не kind-load)
- [x] Helm управляет release — release `cloudshare`, revision history 23–28
- [x] Rollout проверяется — `kubectl rollout status --timeout=300s` в cd.yml
- [x] REST smoke test выполняется после deployment — `scripts/smoke-test.sh` внутри CD job,
      `SMOKE OK`
- [x] Неудачный deployment приводит к failed pipeline — run 32908340278 FAILED
      (image.tag=main не существует → rollout timeout)

## Application

- [x] REST API работает — smoke: register/login/upload/list/download/delete
- [x] Java подключается к PostgreSQL — Hikari pool, Flyway миграции при старте
- [x] Java использует S3 API — MinIO client, bucket `documents`
- [x] Файл можно загрузить / получить / удалить — HTTP 201/200/204 в smoke
- [x] Metadata хранится в PostgreSQL — list/download после рестарта pod
- [x] Content хранится в MinIO — счётчик объектов `mc ls --recursive` растёт на upload
      (§22: обе стороны проверяются smoke-тестом; soft-delete задокументирован в ADR-010)

## PostgreSQL

- [x] Создан application user — `cloudshare_user` (владелец БД, пароль из Secret)
- [x] Настроены permissions — владелец схемы; `GRANT pg_monitor` для экспортёра
- [x] Выполняется backup — `postgres/scripts/backup.sh`: pg_dump | gzip | mc pipe
- [x] Backup сохраняется в MinIO — `postgres-backups/cloudshare-20260825-{220134,221336}-pg17.11.sql.gz`
- [x] Выполняется restore — `CONFIRM=yes restore.sh` (DROP WITH FORCE → дамп → restart app)
- [x] Restore проверяется через application API — `verify-restore.sh drill2@lab.local`:
      login OK, «файлов у пользователя: 1» (README §19)

## Kubernetes

- [x] Deployment работает / Service работает — NodePort 30080, все поды Running
- [x] ConfigMap используется — `cloudshare-config` + checksum-аннотация для rolling update
- [x] Secret используется — `cloudshare-db/-minio/-app` (chart их не создаёт — ADR-009)
- [x] Startup probe настроена — `/actuator/health/liveness`, period 5s × 24
- [x] Readiness probe настроена — `/actuator/health/readiness`, группа включает `db`
      (проверено: DROP DATABASE → readiness DOWN, pod снят с эндпоинтов)
- [x] Liveness probe настроена — только livenessState: отказ БД НЕ рестартует pod
      (restarts=0 во время drill)
- [x] Resources requests/limits настроены — requests 250m/768Mi, limits 1cpu/1Gi
- [x] Rollout работает — ревизии helm 26–28
- [x] Rollback протестирован — два доказательства: авто-rollback `--atomic`
      (rev 24 fail → 25 откат) и ручной `helm rollback` (rev 27 → 28, readiness UP)

## Observability

- [x] Java metrics видны в Prometheus — target `cloudshare | up`;
      `sum(jvm_memory_used_bytes)` = 846292544
- [x] Kubernetes metrics видны — kube-state-metrics/node-exporter targets up;
      `count(kube_pod_info)` = 29
- [x] Application logs собираются — Alloy DaemonSet читает /var/log/pods ноды kind
- [x] Logs доступны через Loki — запрос `{namespace="cloudshare"}` возвращает потоки
      8 контейнеров: cloudshare, postgres, loki, grafana-sc-dashboard/datasources,
      loki-sc-rules, exporter, alloy
- [x] Loki использует MinIO object storage — bucket `loki` содержит чанки и
      TSDB-индексные файлы (schema v13, store tsdb, object_store s3)
- [x] Grafana отображает metrics — дашборды CloudShare App/K8s/PostgreSQL +
      datasource Prometheus (API search подтверждён)
- [x] Grafana позволяет искать logs — datasource Loki добавлен через additionalDataSources
- [x] Есть alerts для основных отказов — PrometheusRule `cloudshare-alerts`:
      CloudshareAppUnavailable, HighHttpErrorRate, HighLatencyP95, PodRestartStorm,
      ContainerMemoryNearLimit, PostgresUnavailable.
      Живое срабатывание: PodRestartStorm firing на реальных рестартах exporter

## Operations

- [x] PostgreSQL failure воспроизведён — DROP DATABASE: readiness DOWN,
      pod снят с эндпоинтов, liveness UP, restarts 0 (§31/§23)
- [-] **MinIO failure воспроизведён — ПРОПУЩЕН** (я пропустил инцидентную фазу;
      runbook готов в docs/incidents/minio-failure.md)
- [x] Broken deployment воспроизведён — CD run 32908340278: несуществующий тег образа
      → rollout timeout → failed pipeline → авто-откат релиза
- [x] Bad configuration воспроизведён — тот же инцидент является сценарием §35:
      неверное configuration value через Helm values, диагностика по events/logs,
      исправление конфигурацией (commit fix(cd)); плюс drill readiness-группы
      (`additional-paths` → явный `include`) как config-fix с наблюдением probes
- [-] **Resource pressure воспроизведён — ПРОПУЩЕН** (я пропустил эту фазу;
      runbook готов в docs/incidents/resource-pressure.md)
- [x] PostgreSQL restore выполнен — полный DR-цикл с известным пользователем и файлом
- [x] Application rollback выполнен — см. Kubernetes/Rollback выше

---

## Итог

Выполнено **53 из 55** пунктов DoD. Два пункта я пропустил осознанно
(инцидентные сценарии MinIO-failure и resource-pressure); runbooks для обоих
написаны и готовы к исполнению.

Пропущенные фазы: incident scenarios (бывшая фаза 9), portfolio evidence
(скриншоты) — эти фазы я не исполнял.
