# Incident: MinIO unavailable (§33)

> Status: сценарий определён, валидируется в фазе 9.

## Symptoms

Операции с содержимым файлов отказывают; metadata-операции могут продолжать работать.

## Impact

Upload/download ломаются (content → S3). GET metadata может работать — проверяем, какие
операции деградировали, а какие нет (суть сценария §33).

## Detection

- Логи Java: ошибки MinIO/S3 SDK
- Alert на 5xx rate; метрики MinIO (bucket objects, errors)

## Investigation

```bash
kubectl -n cloudshare get pods | grep minio
kubectl -n cloudshare logs deploy/minio --tail=50
```
Матрица проверки через API: `GET /files` (metadata), `GET /files/{id}/download`,
`POST /files` — что работает, что нет.

## Root cause

TODO по факту прогона.

## Recovery

Восстановить MinIO pod/PVC.

## Verification

Upload нового файла → download → совпадение содержимого; объект присутствует в bucket.

## Prevention

Раздельные метрики ошибок по зависимостям; smoke test проверяет обе стороны
(metadata + object) — §22.
