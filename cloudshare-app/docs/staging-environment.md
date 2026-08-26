# Staging Environment & Latency KPI Re-baselining

This document describes the design, deployment, data lifecycle maintenance, and load-testing procedures for the dedicated CloudShare staging environment (introduced in Phase 13).

---

## 1. Staging Sizing & Resource Constraints

To provide trustworthy performance numbers (like the p95 latency SLAs), the staging environment runs with production-like resource limits defined in [docker-compose.staging.yml](file:///d:/github/cloudshare-app/docker-compose.staging.yml).

| Service | CPU limit | Memory limit | Memory reservation / sizing |
| :--- | :--- | :--- | :--- |
| **`gateway` (Nginx)** | `0.5` | `256M` | N/A |
| **`app` (Spring Boot)** | `1.0` | `1024M` | `512M` |
| **`db` (PostgreSQL)** | `2.0` | `2048M` | `1024M` |
| **`cache-aside` (Redis)** | `1.0` | `512M` | maxmemory: `512mb` (allkeys-lru) |
| **`cache-security` (Redis)**| `1.0` | `512M` | maxmemory: `512mb` (noeviction) |
| **`cache-ratelimit` (Redis)**| N/A (Inherited) | N/A (Inherited) | maxmemory: `128mb` (allkeys-lru) |
| **`clamav` (Virus Scan)** | `2.0` | `2048M` | N/A |
| **`storage` (MinIO S3)** | `1.0` | `1024M` | N/A |

---

## 2. Deploying Staging

Staging is deployed using the default `docker-compose.yml` merged with the staging override:

```bash
# Generate SSL certificates if not already present
bash nginx/ssl/generate-certs.sh

# Run docker-compose with staging override and staging environment variables
# Copy tests/.env.staging.example to tests/.env.staging first and customize it.
docker compose -f docker-compose.yml -f docker-compose.staging.yml --env-file tests/.env.staging up -d --build
```

> [!IMPORTANT]
> **Environment File Safeguards**
> The `tests/.env.staging` and `.env.staging` files containing actual staging passwords/keys are explicitly added to `.gitignore`. They must **NEVER** be committed to Git. Always use `tests/.env.staging.example` as a template reference.

> [!IMPORTANT]
> **Cloud Network Security Lists & Firewalls (Out-of-band configuration)**
> When deploying the staging environment to Oracle Cloud Infrastructure (OCI) or another cloud host, ensure that the cloud's security lists or network security groups (NSGs) are configured to strictly permit inbound traffic on ports **80** and **443** only. Direct access to all other backend service ports (such as database port 5432, Redis ports, storage ports, and application port 8080) must be blocked externally, as they are now fully secured within the internal Docker bridge network.

---

## 3. Data Lifecycle Maintenance

The Gatling load tests register 100 fresh users per run (`load_user_<suffix>`) and upload 10MB binary files, which will accumulate and bloat database metadata tables (like `users` and `audit_logs`) and storage buckets over time.

While background schedulers (`FilePurgeScheduler` and `LinkCleanupScheduler` from Phase 6) clean up soft-deleted files and expired links, they do not prune registered user accounts or their corresponding audit trails.

### The Primary Staging Reset Mechanism
To maintain a consistent performance baseline, a full environment volume purge is established as the primary maintenance action. We run `bash scripts/reset-staging.sh` to fully teardown all containers and wipe databases/storage volumes, ensuring a completely clean, migrations-re-seeded slate:

```bash
# Triggers an interactive confirmation warning
bash scripts/reset-staging.sh

# Or bypass the interactive prompt in non-interactive/CI scripts:
bash scripts/reset-staging.sh --force
```

---

## 4. Running Gatling Load Tests

The `Gatling Load Tests` GitHub Actions workflow ([load-tests.yml](file:///.github/workflows/load-tests.yml)) supports targeting either the local Docker Compose stack on the runner or a remote staging host.

### Local Runner Mode vs. Remote Staging Mode
- **Local mode**: Triggered when the `target_url` workflow input contains `"localhost"` (e.g., `https://localhost`). The workflow automatically spins up the local Docker compose stack, waits for it to become healthy, runs Gatling, and shuts down the stack.
- **Remote Staging mode**: Triggered when the `target_url` workflow input does **NOT** contain `"localhost"` (e.g., `https://staging.cloudshare.internal`). The workflow skips all local container setups, runs Gatling against the remote host directly, and records the latency metrics.

### Custom Latency SLAs / Thresholds
The p95 latency thresholds are parameterized to allow staging-specific performance baselining without hard-coding:
- **`p95_api_ms`** (default: `200`): SLA in milliseconds for REST API calls (Register, Login, List Files).
- **`p95_stream_ms`** (default: `1500`): SLA in milliseconds for 10MB file stream uploads/downloads.

These can be adjusted directly from the GitHub Actions dispatch UI when running the workflow:

```
Run workflow ->
  Target Base URL: https://staging.cloudshare.internal
  Gatling p95 API Latency SLA (ms): 300
  Gatling p95 Streaming Latency SLA (ms): 2000
```
