# Incident: PostgreSQL unavailable (§31)

> Status: сценарий определён, валидируется в фазе 9.

## Symptoms

HTTP 5xx на операциях с metadata; в логах Java — connection errors; pod остаётся Running.

## Impact

Login, upload, list, download metadata — деградируют/отказывают. Приложение живо —
умерла зависимость.

## Detection

- Alert `PostgresDown` / `DatabaseUnavailable` (postgres-exporter)
- Метрики: активные соединения → 0, ошибки JDBC в логах
- Readiness приложения не должен убивать pod (§23): readiness зависит от БД осознанно,
  liveness — нет

## Investigation

```bash
kubectl -n cloudshare get pods          # postgres pod status
kubectl -n cloudshare logs deploy/postgres --tail=50
psql: SELECT count(*) FROM pg_stat_activity;  # когда восстановится
```
Определить: Java причина или PostgreSQL причина (по метрикам обеих сторон).

## Root cause

TODO по факту прогона (scale to 0 / crash / PVC).

## Recovery

Восстановить PostgreSQL pod (`kubectl scale`/fix), дождаться reconnect Java-пула Hikari.

## Verification

REST API отвечает 200; smoke test; метрики соединений восстановились.

## Prevention

Readiness/liveness разведены (§23); alert на DB down раньше, чем заметят пользователи.
