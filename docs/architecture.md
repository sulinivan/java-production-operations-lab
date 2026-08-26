# Architecture

> Status: scaffold — заполняется по мере реализации (фазы 2–6).
> Принцип проекта (README §49): компонент существует, только если решает конкретную
> эксплуатационную задачу.

## Обзор

Финальная схема — README §47. Здесь фиксируется фактическая реализация: компоненты,
их размещение и связи.

## Размещение компонентов

| Компонент | Где работает | Ответственность |
|---|---|---|
| Lima VM | macOS host (Terraform → provider lima) | Linux environment всей лаборатории |
| Docker, kind, kubectl, Helm | внутри Lima VM (Ansible) | инструментарий deployment target |
| Nginx | Lima VM host | edge reverse proxy (`api.lab.local`, `grafana.lab.local`) |
| Self-hosted runner | Lima VM | выполнение CD-заданий рядом с кластером |
| cloudshare (Java) | namespace `cloudshare` | application workload |
| PostgreSQL | namespace `cloudshare` | authoritative metadata store (ADR-003) |
| MinIO | namespace `cloudshare` | S3 API: documents, postgres-backups, loki |
| Redis ×3 | namespace `cloudshare` | cache / security (fail-closed) / rate-limit (ADR-002) |
| ClamAV | namespace `cloudshare` | сканирование upload (ADR-002) |
| Prometheus stack | monitoring | метрики + алерты |
| Loki + Alloy | monitoring | логи stdout → Loki → MinIO |

## Установленные версии observability (фаза 6)

| Release | Chart | App |
|---|---|---|
| prometheus | kube-prometheus-stack-88.5.4 | v0.93.1 |
| loki | loki-7.3.0 (SingleBinary, S3→MinIO `loki`) | 3.6.12 |
| alloy | alloy-1.12.0 (DaemonSet, /var/log/pods → Loki) | v1.19.0 |
| postgres-exporter | plain manifest (v0.15.0) | — |

Версии резолвятся при установке; обновление стека = пересоздание VM.

## Потоки

- TODO: traffic flow (client → nginx → svc → pod) — фаза 4
- TODO: data flow (metadata → PostgreSQL, content → MinIO) — фаза 5
- TODO: observability flow (metrics/logs/health) — фаза 6
- TODO: delivery flow (git → CI → Docker Hub → CD → kind) — фаза 8
