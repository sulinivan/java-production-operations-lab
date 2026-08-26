# Operations

> Status: scaffold — заполняется в фазах 6–7, 9.

## Регулярные операции

- TODO: backup PostgreSQL по расписанию → MinIO `postgres-backups` (§18) — фаза 7
- TODO: проверка пригодности backup (restore drill) — фаза 7
- TODO: обновление релиза через helm upgrade — фаза 8

## Observability workflow (§29)

```text
Alert → Metrics → affected service → Logs → error → Health checks
     → Fix / rollback / restore → Verify metrics → Verify API
```

- TODO: как искать логи в Loki, какие дашборды открывать — фаза 6

## Runbooks

Сценарии отказов — в [incidents/](incidents/), формат — README §46.
