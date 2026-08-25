# Java Production Operations Lab

Production-like учебно-портфельный проект по эксплуатации, доставке, наблюдаемости и восстановлению готового Java microservice.

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

Основной навык, который должен демонстрировать проект:

> Умение доставить, развернуть, наблюдать, диагностировать, обновить и восстановить Java-приложение в production-like инфраструктуре.

---

# 2. Основной workload

В качестве application workload используется готовый Java/Spring Boot сервис **CloudShare**.

Сервис предоставляет функциональность защищённого обмена файлами.

Основная модель данных:

```text
Document
   │
   ├── metadata ──────► PostgreSQL
   │
   └── content ───────► S3 API / MinIO
```

Таким образом PostgreSQL и object storage решают разные задачи.

PostgreSQL является authoritative store для metadata/application state.

MinIO предоставляет S3-compatible object storage для содержимого файлов.

Приложение не должно хранить содержимое файлов непосредственно в PostgreSQL.

---

# 3. Что должно быть доказано этим проектом

После завершения проекта необходимо уметь продемонстрировать:

* создание Linux infrastructure environment;
* автоматическое provisioning инфраструктуры;
* configuration management;
* создание и публикацию Docker image;
* CI через GitHub Actions;
* CD через GitHub Actions;
* получение image из Docker Hub;
* deployment Java application в Kubernetes;
* управление deployment через Helm;
* rollback неудачного release;
* подключение Java к PostgreSQL;
* базовое PostgreSQL administration;
* PostgreSQL backup;
* PostgreSQL restore;
* подключение Java к S3-compatible storage;
* использование MinIO;
* Kubernetes health probes;
* application metrics;
* Kubernetes metrics;
* centralized logs;
* Grafana dashboards;
* investigation реального incident;
* диагностику проблем через metrics + logs + health;
* восстановление сервиса после отказа;
* безопасное управление credentials;
* reproducible deployment.

---

# 4. Архитектурный принцип

Каждая технология должна существовать только потому, что решает конкретную задачу.

Нельзя добавлять технологию только ради демонстрации знания инструмента.

Основные связи:

```text
Terraform
    ↓
Провайдер guidoiaquinti/lima для Terraform
    ↓
Lima VM
    ↓
Ansible
    ↓
Linux host
    ↓
self-hosted GitHub Actions runner
    ↓
kind Kubernetes cluster
    ↓
Helm
    ↓
Java application
    ├──────────────► PostgreSQL
    │
    └──────────────► S3 API
                         ↓
                       MinIO

Java
    ├── health ────────► Kubernetes probes
    ├── metrics ───────► Prometheus
    └── logs ──────────► log collector
                              ↓
                            Loki
                              ↓
                            MinIO

Prometheus ──────► Grafana
Loki ────────────► Grafana

External client
    ↓
Nginx
    ↓
Kubernetes Service
    ↓
Java application
```

---

# 5. Архитектурные границы

## 5.1 Infrastructure layer

### Terraform

Terraform отвечает только за создание инфраструктурной boundary.

В нашем случае:

```text
Terraform
    ↓
Lima
    ↓
Linux VM
```

Terraform не должен управлять Java application release.

Terraform не должен заменять Helm.

Terraform не должен заменять Ansible.

---

## 5.2 Host configuration layer

### Ansible

Ansible приводит созданную Lima VM в необходимое состояние.

Он устанавливает и конфигурирует:

* Docker;
* kubectl;
* kind;
* Helm;
* GitHub Actions self-hosted runner;
* Nginx;
* необходимые системные пакеты;
* каталоги;
* пользователей;
* permissions;
* базовую конфигурацию Linux.

Модель:

```text
Terraform:
    "Создай мне машину"

Ansible:
    "Сделай эту машину пригодной для эксплуатации"
```

---

# 6. Lima

Lima предоставляет Linux environment для всей локальной production-like лаборатории.

Целевая структура:

```text
Developer machine
       │
       ▼
     Lima
       │
       ▼
    Linux VM
       │
       ├── Docker
       ├── kind
       ├── kubectl
       ├── Helm
       ├── Nginx
       └── GitHub Actions runner
```

Lima используется намеренно:

* Kubernetes запускается в Linux environment;
* Ansible работает с реальным Linux host;
* deployment target отделён от основной developer environment;
* infrastructure можно уничтожить и создать заново.

---

# 7. GitHub

GitHub является центральной точкой source control и automation.

Repository содержит:

```text
application reference
infrastructure
Ansible
Terraform
Helm
Kubernetes configuration
monitoring
scripts
documentation
runbooks
GitHub Actions
```

Работа с application должна идти через Git:

```text
branch
   ↓
commit
   ↓
pull request
   ↓
CI
   ↓
merge
   ↓
release
```

---

# 8. GitHub Actions

GitHub Actions отвечает за CI/CD.

Pipeline должен иметь минимум две логические части.

## CI

```text
checkout
   ↓
validate
   ↓
test
   ↓
integration test
   ↓
security scan
   ↓
Docker build
   ↓
image validation
   ↓
push image
```

## CD

```text
new image
   ↓
deploy to kind
   ↓
Helm upgrade
   ↓
wait for rollout
   ↓
health verification
   ↓
smoke test
```

Deployment должен выполняться self-hosted runner, находящимся в Lima VM.

Причина:

```text
GitHub-hosted runner
        X
        │
        │ no direct access
        ▼
local kind cluster

self-hosted runner
        │
        ▼
Lima VM
        │
        ▼
kind
```

---

# 9. Self-hosted GitHub Actions Runner

Runner является частью deployment environment.

Он устанавливается и конфигурируется Ansible.

Модель:

```text
GitHub
   │
   │ Actions job
   ▼
Self-hosted runner
   │
   ├── docker
   ├── kubectl
   └── helm
          │
          ▼
        kind
```

Repository должен быть private во время разработки этого варианта deployment architecture.

Необходимо отдельно документировать security implications self-hosted runners.

---

# 10. Docker

Docker используется для создания переносимого application artifact.

Java application должна быть упакована в versioned image:

```text
document-service:1.0.0
document-service:1.1.0
document-service:1.2.0
```

`latest` не используется как основной deployment identifier.

Deployment должен использовать конкретный version/tag.

---

# 11. Docker Hub

Docker Hub является image registry.

Pipeline:

```text
GitHub
    ↓
GitHub Actions
    ↓
Docker build
    ↓
Docker image
    ↓
Docker Hub
    ↓
Kubernetes imagePull
```

Kubernetes не должен зависеть от локального image, созданного на developer machine.

Это необходимо для демонстрации реального artifact delivery lifecycle.

---

# 12. Kubernetes / kind

Используется однонодовый kind cluster.

Это осознанное ограничение локальной лаборатории.

Проект НЕ заявляет production-grade Kubernetes HA.

Он демонстрирует:

* Kubernetes workload management;
* deployment;
* service discovery;
* health probes;
* resource limits;
* configuration;
* secrets;
* rollout;
* rollback;
* logs;
* metrics.

Базовые Kubernetes resources:

```text
Namespace

Deployment
Service

ConfigMap
Secret

Ingress / edge integration

PersistentVolumeClaim
```

Stateful infrastructure не должна автоматически переноситься в Kubernetes только ради демонстрации StatefulSet.

---

# 13. Helm

Helm является application release layer.

Структура:

```text
helm/
└── cloudshare/
    ├── Chart.yaml
    ├── values.yaml
    ├── values-dev.yaml
    └── templates/
        ├── deployment.yaml
        ├── service.yaml
        ├── configmap.yaml
        ├── secret.yaml
        ├── ingress.yaml
        └── servicemonitor.yaml
```

Helm отвечает за:

* application configuration;
* image version;
* resource configuration;
* probes;
* environment variables;
* Kubernetes resources;
* release lifecycle.

Необходимо продемонстрировать:

```text
helm install
helm upgrade
helm history
helm rollback
```

---

# 14. Nginx

Nginx является edge reverse proxy.

Он не является заменой Kubernetes Service.

Traffic:

```text
Client
   ↓
Nginx
   ↓
Kubernetes Service
   ↓
Java Pod
```

Nginx отвечает за:

* внешний HTTP endpoint;
* reverse proxy;
* access logs;
* upstream error handling;
* optional TLS termination.

Пример логических endpoints:

```text
api.lab.local
grafana.lab.local
```

---

# 15. Java Application

Java application является главным workload проекта.

Приложение не изменяется без необходимости.

Мы не изучаем:

* Spring Boot development;
* Java design patterns;
* application architecture;
* Java coding.

Мы изучаем эксплуатацию уже существующего сервиса.

---

# 16. REST API

REST API является способом взаимодействия с workload.

Минимальные операции:

```text
POST   /api/v1/files
GET    /api/v1/files
GET    /api/v1/files/{id}
DELETE /api/v1/files/{id}

POST   /api/v1/shares
```

API используется не только для демонстрации.

Через него выполняются:

* smoke tests;
* health verification;
* incident reproduction;
* performance checks;
* deployment verification.

Пример:

```text
deployment
    ↓
curl API
    ↓
HTTP 200/expected response
    ↓
deployment successful
```

---

# 17. PostgreSQL

PostgreSQL хранит:

* file metadata;
* users/application state;
* sharing metadata;
* audit information;
* relational state.

Java dependency:

```text
Java
  │
  │ JDBC
  ▼
PostgreSQL
```

Необходимо отработать базовое administration:

```text
database creation
user creation
roles
permissions
connections
active sessions
locks
database size
basic configuration
logs
```

---

# 18. PostgreSQL backup

Backup является частью production lifecycle.

Минимальный сценарий:

```text
PostgreSQL
    ↓
pg_dump
    ↓
backup file
    ↓
MinIO
    ↓
postgres-backups bucket
```

Backup должен содержать:

* timestamp;
* application/database identifier;
* version/format information.

Необходимо проверить не только создание backup, но и его пригодность для восстановления.

---

# 19. PostgreSQL restore

Обязательный scenario:

```text
working database
       ↓
backup
       ↓
data loss / simulated failure
       ↓
restore
       ↓
PostgreSQL
       ↓
application verification
```

После restore необходимо проверить application-level behavior через REST API.

Недостаточно получить:

```text
pg_restore completed successfully
```

Нужно доказать:

```text
database restored
       ↓
Java reconnects
       ↓
REST API works
       ↓
expected document metadata exists
```

---

# 20. MinIO

MinIO используется как S3-compatible object storage.

Основной application flow:

```text
POST /files
     ↓
Java
     ├── metadata → PostgreSQL
     │
     └── content → S3 API → MinIO
```

Buckets:

```text
documents
postgres-backups
loki
```

Каждый bucket имеет отдельную ответственность.

---

# 21. S3 API

Java application не должна быть концептуально привязана к MinIO.

Она работает с S3-compatible API.

Поэтому:

```text
Application
    ↓
S3 API
    ↓
MinIO
```

MinIO является текущей implementation.

В дальнейшем backend theoretically можно заменить на AWS S3 без изменения бизнес-модели приложения.

---

# 22. Object lifecycle

Необходимо продемонстрировать:

```text
upload
    ↓
object exists

download
    ↓
object returned

delete
    ↓
object removed
```

И проверить обе стороны:

```text
PostgreSQL metadata
+
MinIO object
```

Например, нельзя считать удаление успешным, если:

```text
PostgreSQL metadata deleted
BUT
MinIO object remains
```

Это должно стать отдельным troubleshooting scenario.

---

# 23. Health checks

Java Actuator предоставляет application health.

Kubernetes использует:

```text
startupProbe
readinessProbe
livenessProbe
```

Модель:

```text
startup
   ↓
application initialization

readiness
   ↓
can receive traffic?

liveness
   ↓
is application process alive?
```

Readiness и liveness не должны бездумно означать одно и то же.

Особенно важно не превращать временный отказ PostgreSQL в бесконтрольный restart всех Java pods.

---

# 24. Metrics

Application metrics:

```text
HTTP request count
HTTP error count
HTTP latency

JVM memory
JVM CPU
GC
threads
```

Infrastructure/Kubernetes metrics:

```text
pod CPU
pod memory
restarts
container status
```

PostgreSQL metrics:

```text
connections
database size
transactions
locks
```

Pipeline:

```text
Java
  ↓
Micrometer
  ↓
Prometheus
  ↓
Grafana
```

---

# 25. Prometheus

Prometheus является metrics backend.

Он должен собирать:

* Java application metrics;
* Kubernetes metrics;
* relevant infrastructure metrics;
* PostgreSQL metrics.

Необходимо создать alert rules минимум для:

```text
application unavailable
high HTTP error rate
high latency
pod restart
high memory usage
database unavailable
```

---

# 26. Logging

Application logs должны идти через container stdout/stderr.

Не нужно писать application logs непосредственно в файлы внутри container.

Pipeline:

```text
Java
  ↓
stdout/stderr
  ↓
container runtime
  ↓
log collector
  ↓
Loki
```

Structured JSON logging должен сохранять:

```text
timestamp
level
logger
message
trace/request identifier
service
environment
```

---

# 27. Loki

Loki является centralized log backend.

Он хранит application/container logs.

Object storage:

```text
Loki
   ↓
S3-compatible storage
   ↓
MinIO
   ↓
loki bucket
```

Это создаёт вторую важную связь MinIO:

```text
MinIO
  ├── documents
  ├── postgres-backups
  └── loki
```

---

# 28. Grafana

Grafana является общей operational UI.

Она должна показывать:

### Application dashboard

```text
request rate
error rate
latency
JVM memory
GC
threads
```

### Kubernetes dashboard

```text
pod status
CPU
memory
restarts
deployment status
```

### PostgreSQL dashboard

```text
connections
database size
transactions
locks
```

### Logs

Через Loki:

```text
application errors
stack traces
request identifiers
deployment events
```

---

# 29. Observability workflow

Grafana должна использоваться не только как красивый dashboard.

Основной workflow:

```text
Alert
  ↓
Metrics
  ↓
Identify affected service
  ↓
Logs
  ↓
Identify error
  ↓
Health checks
  ↓
Confirm dependency
  ↓
Fix / rollback / restore
  ↓
Verify metrics
  ↓
Verify API
```


---

# 30. CI/CD acceptance criteria

Pipeline считается готовым, если:

```text
git push
    ↓
CI passes
    ↓
Docker image created
    ↓
image pushed to Docker Hub
    ↓
CD starts
    ↓
Helm upgrade
    ↓
Kubernetes rollout
    ↓
readiness successful
    ↓
REST smoke test successful
```

Pipeline должен завершаться ошибкой, если:

* tests fail;
* image build fails;
* security gate fails;
* deployment fails;
* readiness does not become healthy;
* smoke test fails.

---

# 31. Deployment verification

После каждого deployment необходимо автоматически проверить:

```text
deployment exists
pods ready
service exists
health endpoint
REST endpoint
database connectivity
object storage connectivity
```

Минимальный smoke test:

```text
health
    ↓
create/upload
    ↓
list
    ↓
download
    ↓
delete
```

---

# 32. Rollback acceptance criteria

Нужно иметь две версии:

```text
v1.0.0
v1.1.0
```

`v1.1.0` должен быть намеренно сломан в controlled scenario.

После deployment:

```text
v1.1.0
    ↓
failure
```

необходимо выполнить:

```text
helm rollback
```

и доказать:

```text
previous release
    ↓
healthy
    ↓
REST API works
```

---

# 33. Repository structure

Итоговая структура repository:

```text
java-production-operations-lab/
│
├── README.md
│
├── docs/
│   ├── architecture.md
│   ├── decisions.md
│   ├── deployment.md
│   ├── operations.md
│   ├── troubleshooting.md
│   ├── backup-restore.md
│   ├── security.md
│   └── incidents/
│       ├── broken-release.md
│       ├── postgres-failure.md
│       ├── minio-failure.md
│       ├── bad-configuration.md
│       └── resource-pressure.md
│
├── terraform/
│   ├── main.tf
│   ├── variables.tf
│   ├── outputs.tf
│   └── versions.tf
│
├── ansible/
│   ├── inventory/
│   ├── playbooks/
│   │   ├── site.yml
│   │   ├── docker.yml
│   │   ├── kubernetes.yml
│   │   ├── runner.yml
│   │   └── nginx.yml
│   │
│   └── roles/
│       ├── docker/
│       ├── kind/
│       ├── helm/
│       ├── runner/
│       └── nginx/
│
├── kind/
│   └── cluster.yaml
│
├── helm/
│   └── cloudshare/
│       ├── Chart.yaml
│       ├── values.yaml
│       ├── values-dev.yaml
│       └── templates/
│
├── kubernetes/
│   └── namespace.yaml
│
├── postgres/
│   ├── scripts/
│   │   ├── backup.sh
│   │   ├── restore.sh
│   │   └── verify-restore.sh
│   └── README.md
│
├── minio/
│   ├── buckets/
│   └── README.md
│
├── monitoring/
│   ├── prometheus/
│   │   ├── rules/
│   │   └── dashboards/
│   │
│   ├── loki/
│   ├── alloy/
│   └── grafana/
│       └── dashboards/
│
├── nginx/
│   └── conf.d/
│
├── scripts/
│   ├── bootstrap.sh
│   ├── deploy.sh
│   ├── smoke-test.sh
│   ├── backup.sh
│   ├── restore.sh
│   └── destroy.sh
│
└── .github/
    └── workflows/
        ├── ci.yml
        ├── docker.yml
        └── cd.yml
```

---

# 34. Environment model

Локальная лаборатория должна иметь минимум:

```text
developer machine
        │
        ▼
     Lima VM
        │
        ├── kind
        │
        ├── Nginx
        │
        └── self-hosted runner
```

Внутри Kubernetes:

```text
namespace: cloudshare

Java
Prometheus
Grafana
Loki
Alloy
```

Stateful dependencies:

```text
PostgreSQL
MinIO
```

Их размещение должно быть зафиксировано в architecture documentation и не меняться случайно между этапами проекта.

---

# 35. Secrets

Никогда не хранить реальные credentials в Git.

Минимальные secrets:

```text
POSTGRES_USERNAME
POSTGRES_PASSWORD

S3_ACCESS_KEY
S3_SECRET_KEY

DOCKERHUB_USERNAME
DOCKERHUB_TOKEN

GITHUB_RUNNER_TOKEN
```

Для Kubernetes application secrets должны передаваться через Kubernetes Secret/Helm values mechanism.

Для GitHub Actions credentials должны находиться в GitHub Secrets.

---

# 36. Versioning

Application image version:

```text
MAJOR.MINOR.PATCH
```

Пример:

```text
1.0.0
1.1.0
1.1.1
```

Git tag:

```text
v1.0.0
```

Docker tag:

```text
1.0.0
```

Helm release revision:

```text
1
2
3
```

Эти три понятия должны быть различимы в документации.

---

# 37. Definition of Done

Проект считается завершённым только если выполнены все условия.

## Infrastructure

* [ ] Lima VM создаётся автоматически.
* [ ] Infrastructure configuration находится в Terraform.
* [ ] Linux host конфигурируется Ansible.
* [ ] Все необходимые инструменты устанавливаются автоматически.

## CI

* [ ] GitHub Actions запускается на Pull Request.
* [ ] Tests выполняются автоматически.
* [ ] Integration tests выполняются автоматически.
* [ ] Security check выполняется автоматически.
* [ ] Docker image создаётся автоматически.
* [ ] Image публикуется в Docker Hub.

## CD

* [ ] Deployment выполняется автоматически.
* [ ] Kubernetes получает image из Docker Hub.
* [ ] Helm управляет release.
* [ ] Rollout проверяется.
* [ ] REST smoke test выполняется после deployment.
* [ ] Неудачный deployment приводит к failed pipeline.

## Application

* [ ] REST API работает.
* [ ] Java подключается к PostgreSQL.
* [ ] Java использует S3 API.
* [ ] Файл можно загрузить.
* [ ] Файл можно получить.
* [ ] Файл можно удалить.
* [ ] Metadata хранится в PostgreSQL.
* [ ] Content хранится в MinIO.

## PostgreSQL

* [ ] Создан application user.
* [ ] Настроены permissions.
* [ ] Выполняется backup.
* [ ] Backup сохраняется в MinIO.
* [ ] Выполняется restore.
* [ ] Restore проверяется через application API.

## Kubernetes

* [ ] Deployment работает.
* [ ] Service работает.
* [ ] ConfigMap используется.
* [ ] Secret используется.
* [ ] Startup probe настроена.
* [ ] Readiness probe настроена.
* [ ] Liveness probe настроена.
* [ ] Resources requests/limits настроены.
* [ ] Rollout работает.
* [ ] Rollback протестирован.

## Observability

* [ ] Java metrics видны в Prometheus.
* [ ] Kubernetes metrics видны.
* [ ] Application logs собираются.
* [ ] Logs доступны через Loki.
* [ ] Loki использует MinIO object storage.
* [ ] Grafana отображает metrics.
* [ ] Grafana позволяет искать logs.
* [ ] Есть alerts для основных отказов.

## Operations

* [ ] PostgreSQL failure воспроизведён.
* [ ] MinIO failure воспроизведён.
* [ ] Broken deployment воспроизведён.
* [ ] Bad configuration воспроизведён.
* [ ] Resource pressure воспроизведён.
* [ ] PostgreSQL restore выполнен.
* [ ] Application rollback выполнен.

---

# 38. Portfolio evidence

В repository должны быть не только YAML-файлы.

Необходимо сохранить доказательства эксплуатации.

Например:

```text
docs/evidence/
├── ci-success.png
├── docker-image.png
├── kubernetes-rollout.png
├── helm-history.png
├── helm-rollback.png
├── postgres-backup.png
├── postgres-restore.png
├── minio-buckets.png
├── prometheus.png
├── grafana.png
├── loki.png
└── incident-recovery.png
```

Каждый screenshot должен сопровождаться коротким описанием:

```text
Problem
Action
Expected result
Actual result
Evidence
```

---

# 39. Runbook format

Каждый operational scenario должен иметь runbook.

Шаблон:

```text
# Incident: PostgreSQL unavailable

## Symptoms

What the operator observes.

## Impact

What functionality is affected.

## Detection

Which metric / alert / log detects the problem.

## Investigation

Commands and dashboards to inspect.

## Root cause

What caused the failure.

## Recovery

Exact recovery procedure.

## Verification

How to prove that service is healthy again.

## Prevention

What can be changed to reduce recurrence.
```

---

# 40. Final end-to-end scenario

Главная демонстрация проекта должна выглядеть так:

```text
Developer changes application version
            │
            ▼
         GitHub
            │
            ▼
     GitHub Actions CI
            │
      ┌─────┼─────┐
      │     │     │
    test  scan  build
      │     │     │
      └─────┼─────┘
            │
            ▼
       Docker image
            │
            ▼
        Docker Hub
            │
            ▼
    Self-hosted runner
            │
            ▼
          Helm
            │
            ▼
       Kubernetes
            │
            ▼
       Java service
          /    \
         /      \
        ▼        ▼
 PostgreSQL    MinIO
    │             │
    │             └── documents
    │
    └── backups ─────► MinIO

Java
 ├── metrics ───────► Prometheus
 ├── logs ──────────► Alloy → Loki → MinIO
 └── health ────────► Kubernetes

Prometheus ─────────► Grafana
Loki ───────────────► Grafana

Client ─────────────► Nginx ─────► Java
```

Затем намеренно ломается release:

```text
bad release
    ↓
Kubernetes rollout
    ↓
readiness failure
    ↓
Prometheus alert
    ↓
Grafana
    ↓
Loki
    ↓
root cause
    ↓
Helm rollback
    ↓
REST smoke test
    ↓
service recovered
```

После этого моделируется потеря данных:

```text
PostgreSQL
    ↓
data loss
    ↓
backup from MinIO
    ↓
restore
    ↓
Java reconnect
    ↓
REST verification
```

Это и есть главный результат проекта.

---

# 41. Final project statement

Этот проект демонстрирует не знание отдельных инструментов.

Он демонстрирует способность построить и эксплуатировать связанный delivery/runtime environment:

```text
Infrastructure
    ↓
Configuration
    ↓
CI
    ↓
Artifact
    ↓
CD
    ↓
Runtime
    ↓
Dependencies
    ↓
Observability
    ↓
Incident response
    ↓
Recovery
```

Каждый слой зависит от предыдущего и предоставляет основу следующему.

Главная цель:

> **Take a ready-made Java service from source control to a reproducible production-like environment, operate it under normal and failure conditions, observe it, diagnose incidents, roll back releases, and recover persistent data.**

---

# 42. Принцип проекта

Если компонент нельзя объяснить фразой:

> **«Без этого компонента конкретная эксплуатационная задача проекта не решается»**

— компонент не добавляется.

Это правило распространяется на все будущие технологии.

Не добавлять:

```text
Redis
Kafka
Argo CD
Istio
Terraform Cloud
AWS
EKS
Service Mesh
Vault
Keycloak
```

только ради расширения списка технологий.

Новая технология появляется только после формулировки:

```text
Operational problem
        ↓
Why existing stack cannot solve it
        ↓
New technology
        ↓
Concrete responsibility
        ↓
Failure scenario
        ↓
Operational evidence
```

Именно этот принцип должен сохраняться на протяжении всего проекта.
