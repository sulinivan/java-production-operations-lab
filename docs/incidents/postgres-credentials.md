# Incident: PostgreSQL credentials broken (§32)

> Status: сценарий определён, валидируется в фазе 9.

## Symptoms

Java не может подключиться к БД после смены credential; аутентификация падает в логах
(`password authentication failed`).

## Impact

Полная деградация API, зависящего от metadata.

## Detection

- Логи: `FATAL: password authentication failed for user`
- Alert на error rate / DB connectivity

## Investigation

```bash
kubectl -n cloudshare get secret cloudshare-db -o yaml   # сравнить значение с ожидаемым
kubectl -n cloudshare logs deploy/cloudshare | grep -i auth
```

## Root cause

Рассинхрон Kubernetes Secret (или env приложения) с паролем пользователя БД.

## Recovery

Исправить Secret → `kubectl rollout restart deploy/cloudshare`
(или helm upgrade) → Hikari переподключается.

## Verification

REST API 200; smoke test; логи без auth failures.

## Prevention

Единственный источник значения секрета — Helm values/Secret pipeline; ручные правки
`kubectl edit secret` запрещены.
