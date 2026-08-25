# Incident: Broken application release (§30)

> Status: сценарий определён, валидируется в фазе 9. Релиз v1.1.0 ломается через Helm values (ADR-006).

## Symptoms

После `helm upgrade` до v1.1.0-broken: pod не проходит readiness, deployment unavailable,
CD-pipeline падает на шаге rollout wait.

## Impact

API недоступен; предыдущая версия продолжает жить в helm history.

## Detection

- Alert `ApplicationUnavailable` / `HighHttpErrorRate` (фаза 6)
- `kubectl get pods` — READY 0/1
- CD job failed

## Investigation

```bash
kubectl -n cloudshare get pods
kubectl -n cloudshare describe pod <pod>   # события probe
kubectl -n cloudshare logs deploy/cloudshare --previous
```
Grafana: error rate, pod status. Loki: логи readiness failures.

## Root cause

Некорректная конфигурация релиза (readiness path / зависимость) в values v1.1.0.

## Recovery

```bash
helm -n cloudshare history cloudshare
helm -n cloudshare rollback cloudshare <previous-revision>
kubectl -n cloudshare rollout status deploy/cloudshare
```

## Verification

`scripts/smoke-test.sh` зелёный; метрики вернулись к baseline; alert resolved.

## Prevention

CD не должен завершаться успехом при нерабочем rollout (§37); smoke test обязателен после
каждого деплоя (§38).
