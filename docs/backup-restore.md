# Backup & Restore

> Status: scaffold — реализуется в фазе 7, отрабатывается в фазе 9 (Incident 5, §34).

## Политика

- Что: PostgreSQL (metadata — authoritative state). Содержимое файлов — в MinIO `documents`
  и бэкапится отдельно (TODO: решение по object backup).
- Куда: `s3://postgres-backups/` (MinIO), имя файла:
  `cloudshare-YYYYMMDD-HHMMSS-<pg-version>.sql.gz` (timestamp + identifier + format, §18).
- Backup без проверки восстановления считается невалидным.

## Процедуры

```bash
postgres/scripts/backup.sh          # pg_dump → gzip → mc cp → postgres-backups
postgres/scripts/restore.sh         # restore из указанного backup
postgres/scripts/verify-restore.sh  # application-level проверка через REST API (§19)
```

- TODO: пошаговый restore runbook + критерии успеха — фаза 7
- TODO: RPO/RTO лаборатории — фаза 7

## Критерий готовности

Данные потеряны намеренно → backup из MinIO → restore → Java reconnect → REST API показывает
ожидаемые документы. Только это доказывает восстановление (§19, §34).
