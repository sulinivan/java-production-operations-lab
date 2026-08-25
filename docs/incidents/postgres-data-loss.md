# Incident: PostgreSQL data loss — disaster recovery (§34)

> Status: главный DR-сценарий проекта. Валидируется в фазе 9 после фазы 7 (backup/restore).

## Symptoms

Metadata в PostgreSQL потеряна намеренно (controlled data loss): таблицы удалены/база пересоздана.

## Impact

Приложение не имеет данных: login невозможен, документы отсутствуют.

## Detection

REST API отдаёт ошибки/пустые данные; это DR-упражнение, а не внезапный инцидент.

## Investigation

Зафиксировать состояние до потери: список документов через API (эталон для сверки).
Найти последний валидный backup:

```bash
mc ls local/postgres-backups/
```

## Root cause

Смоделированная потеря (для учений). В реальности — human error / hardware / bug.

## Recovery

```bash
postgres/scripts/restore.sh <backup-file>    # restore из MinIO
kubectl -n cloudshare rollout restart deploy/cloudshare
```

## Verification

Обязателен application-level proof (§19):

1. Java переподключилась (логи Hikari/Flyway)
2. `scripts/smoke-test.sh` зелёный
3. Ранее загруженный документ находится через API, metadata совпадает с эталоном до потери

## Prevention

Регулярный backup по расписанию; периодический restore drill; backup без проверки
восстановлением не считается готовым.
