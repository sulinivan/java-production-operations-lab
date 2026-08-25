# Incident: Resource pressure (§36)

> Status: сценарий определён, валидируется в фазе 9.

## Symptoms

Под controlled load: рост latency, рост GC-пауз, OOMKill / restart контейнера при
заниженных limits.

## Impact

Деградация API; при OOMKill — кратковременная недоступность на время рестарта.

## Detection

- Grafana: pod memory vs limit, JVM heap, GC time, latency p95
- Alert `HighMemoryUsage` / `PodRestart`
- `kubectl -n cloudshare describe pod` → `OOMKilled`, `last state`

## Investigation

```bash
kubectl -n cloudshare top pods
kubectl -n cloudshare describe pod <pod> | grep -A5 "Last State"
```
Grafana JVM dashboard: heap после GC, threads. Correlation: load ↑ → memory ↑ → kill.

## Root cause

Memory/CPU limits ниже реальной потребности workload под нагрузкой (JVM heap + metaspace +
direct buffers).

## Recovery

Вернуть адекватные resources requests/limits через Helm values; pod перезапускается и
выходит из деградации.

## Verification

Latency вернулась к baseline; restarts не растут; нагрузка держится без OOM.

## Prevention

Limits выставлять по измерению (этот сценарий и есть измерение); `-XX:MaxRAMPercentage=75`
уже согласован с контейнерными limits; alert на memory > 85% limit.
