# Troubleshooting

> Status: scaffold — наполняется по результатам инцидентных сценариев (фаза 9).

Формат записи: симптом → первая проверка → вероятная причина → runbook.

| Симптом | Первая проверка | Вероятная причина | Runbook |
|---|---|---|---|
| Pod `CrashLoopBackOff` | `kubectl logs` | bad configuration / secrets | incidents/bad-configuration.md |
| Pod `Running`, но not Ready | readiness probe / logs | PostgreSQL недоступен | incidents/postgres-failure.md |
| HTTP 5xx на API | metrics error rate + Loki | зависимость (DB/S3/Redis) | incidents/*.md |
| Upload падает | logs ClamAV/MinIO | отказ object storage или AV | incidents/minio-failure.md |
| Login падает | Redis security instance | fail-closed Redis | TODO фаза 9 |
| Данные пропали | backup bucket | data loss → restore | incidents/postgres-data-loss.md |

- TODO: команды диагностики по слоям (pod/node/dependency) — фаза 9
