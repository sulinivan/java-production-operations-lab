# Incident: Broken configuration (§35)

> Status: сценарий определён, валидируется в фазе 9.

## Symptoms

Pod стартует и сразу падает или не проходит probes после helm upgrade с неверным
configuration value (wrong S3 endpoint / wrong DB URL / wrong credentials).

## Impact

Релиз недоступен; предыдущая ревизия остаётся в history.

## Detection

- `CrashLoopBackOff` / readiness failure
- Логи при старте: `SecretsStartupValidator` падает с явным именем секрета;
  connection refused на неверный endpoint

## Investigation

```bash
kubectl -n cloudshare logs deploy/cloudshare --previous | head -50
helm -n cloudshare get values cloudshare --all   # что реально применилось
```

Приложение помогает диагностике: fail-closed валидатор называет конкретный секрет/значение.

## Root cause

Ошибка в Helm values (опечатка, неверный host, непрокинутый secret key).

## Recovery

`helm upgrade` с исправленными values; либо `helm rollback`, если исправление не готово.

## Verification

Pod Ready; smoke test; в Loki нет startup errors.

## Prevention

values-dev.yaml как проверенный профиль; lint chart (`helm lint`) в CI; secrets через
единый механизм, а не ручные правки.
