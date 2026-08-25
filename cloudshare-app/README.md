# CloudShare

**A production-grade, security-first file-sharing platform built with Spring Boot 3.5.**

CloudShare is a portfolio-grade demonstration of enterprise backend engineering: envelope-encrypted file storage, zero-trust authentication with step-up MFA, a defense-in-depth caching architecture, ClamAV-scanned uploads, and full runtime observability — all running behind a hardened Nginx edge.

[![Java CI with Maven](https://github.com/Dhruv0306/cloudshare-app/actions/workflows/maven.yml/badge.svg)](https://github.com/Dhruv0306/cloudshare-app/actions/workflows/maven.yml)
![GitHub release](https://img.shields.io/github/v/release/Dhruv0306/cloudshare-app)
![Last commit](https://img.shields.io/github/last-commit/Dhruv0306/cloudshare-app)

---

## Table of Contents
- [Tech Stack](#tech-stack)
- [Architecture](#architecture)
- [Prerequisites](#prerequisites)
- [Local Setup / Installation](#local-setup--installation)
- [Usage](#usage)
- [Environment Variables](#environment-variables)
- [Testing](#testing)
- [Versioning](#versioning)
- [Contributing](#contributing)
- [Documentation](#documentation)

---

## Tech Stack

| Layer | Technology |
|---|---|
| **Backend** | Java 17, Spring Boot 3.5.16 (Web, Security, Data JPA, Validation, Actuator) |
| **Auth** | JJWT 0.13.0, Refresh Token Rotation, TOTP-based MFA, step-up admin tokens |
| **Database** | PostgreSQL 17, Flyway migrations, monthly range-partitioned audit log table |
| **Caching / Security State** | Triple-Redis architecture: `cache-aside` (LRU app cache), `cache-security` (no-eviction token blacklist/replay defense), `cache-ratelimit` (LRU sliding-window rate limiting) |
| **Object Storage** | Pluggable — local filesystem (dev) or MinIO / S3-compatible (`io.minio`) |
| **Encryption** | AES-256-GCM per-file keys, wrapped with a versioned Key Encrypting Key via AESWrap (RFC 3394) |
| **Anti-malware** | ClamAV daemon, streamed scan on upload |
| **MIME Validation** | Apache Tika (magic-byte inspection, not extension trust) |
| **Edge / Gateway** | Nginx (TLS termination, sole ingress, static SPA hosting) |
| **Frontend** | Vanilla JS SPA, native Fetch client, dark glassmorphic UI |
| **Observability** | Micrometer + Prometheus, structured JSON logging (Logback + Logstash encoder) |
| **Testing** | JUnit 5 / Mockito (unit), Playwright (E2E), Python `requests`-based API suite, Gatling (load) |
| **CI/CD** | GitHub Actions (`verify`, `api-tests` required checks on protected `main`) |

---

## Architecture

- **Backend:** Spring Boot (REST APIs)
- **Database:** PostgreSQL
- **Containerization:** Docker
- **Design:** Layered architecture (Controller → Service → Repository)

---

## Prerequisites

- **Java 17+ JDK**
- **Maven 3.8+**
- **Docker & Docker Compose**
- **Python 3.x** (for the API integration test suite)
- OpenSSL or equivalent (for local TLS certificate generation)

---

## Local Setup / Installation

### 1. Clone and configure environment
```bash
git clone https://github.com/Dhruv0306/cloudshare-app.git
cd cloudshare-app
cp .env.example .env
```
Fill in `.env` with real values — at minimum, generate:
- A 32-byte value for `CRYPTO_MASTER_KEK`
- A 64+ character (512-bit) secret for `JWT_SECRET`
- Strong `SPRING_DATASOURCE_*` and `MINIO_ROOT_*` credentials

### 2. Generate local TLS certificates
Nginx requires certificates before it will start.

**Linux/macOS:**
```bash
chmod +x nginx/ssl/generate-certs.sh
./nginx/ssl/generate-certs.sh
```
**Windows (PowerShell):**
```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
.\nginx\ssl\generate-certs.ps1
```

### 3. Start the infrastructure
```bash
docker compose up -d
```
This brings up 8 containers: `gateway` (Nginx, :80/:443), `app` (Spring Boot), `db` (Postgres :5432), `cache-aside` (Redis, internal), `cache-security` (Redis, internal), `cache-ratelimit` (Redis, internal), `clamav` (:3310 internal), and `storage` (MinIO, :9000 API / :9001 console). Only the gateway ports are published to the host.

### 4. Run the backend (if not using the `app` container directly)
```bash
mvn spring-boot:run
```
or build and run the jar:
```bash
mvn clean package -DskipTests
java -jar target/cloudshare-2.2.0.jar
```

### 5. Open the app
Navigate to **`https://localhost`**.

---

## Usage

Once running, CloudShare exposes a REST API under `/api/v1` and serves the SPA at `/`:

| Area | Base path | Key operations |
|---|---|---|
| Auth | `/api/v1/auth` | register, login, refresh, logout, MFA setup/verify, admin step-up |
| Files | `/api/v1/files` | upload, list (owned), list (shared with me), download, delete |
| Sharing | `/api/v1/shares` | internal user-to-user shares, password-protected public links, link info/revoke |
| Admin | `/api/v1/admin` | ClamAV & download concurrency tuning, audit log partitions, user/audit views |

See [`docs/system-design/api-spec.md`](docs/system-design/api-spec.md) for the full request/response contract.

**Key Rotation:** to migrate to a new KEK version without downtime:
```bash
java -jar target/cloudshare-2.2.0.jar \
  --spring.profiles.active=rekey-job \
  --rekey.oldVersion=1 \
  --rekey.newVersion=2
```
Full details: [`docs/system-design/secrets-key-management.md`](docs/system-design/secrets-key-management.md).

---

## Environment Variables

All variables are documented with defaults in [`.env.example`](.env.example). Highlights:

| Variable | Purpose |
|---|---|
| `SPRING_DATASOURCE_URL/USERNAME/PASSWORD` | PostgreSQL connection |
| `SPRING_DATA_REDIS_HOST/PORT` | `cache-aside` Redis instance |
| `SECURITY_REDIS_HOST/PORT` | `cache-security` Redis instance (token blacklist, MFA replay defense) |
| `RATE_LIMIT_REDIS_HOST/PORT` | `cache-ratelimit` Redis instance (sliding-window counters) |
| `JWT_SECRET` / `JWT_EXPIRATION` / `JWT_REFRESH_EXPIRATION` | Token signing and TTLs |
| `JWT_STEP_UP_SESSION_MAX` | Absolute cap (seconds) on an MFA step-up session, regardless of token rotation |
| `CRYPTO_MASTER_KEK` | 32-byte Key Encrypting Key for envelope encryption |
| `MINIO_ROOT_USER/PASSWORD` | Object storage credentials |
| `RATE_LIMIT_AUTH/MFA/UPLOAD/LINK/LINK_GLOBAL/GENERAL` | Per-route rate limit thresholds |
| `PASSWORD_BREACH_CHECK_ENABLED/TIMEOUT_MS` | HIBP-style breached-password check |
| `CLAMAV_MAX_CONCURRENT_SCANS/TIMEOUT_SECONDS` | ClamAV scan concurrency limiter |
| `STORAGE_MAX_CONCURRENT_DECRYPT_DOWNLOADS/TIMEOUT_SECONDS` | Decrypt-on-download concurrency limiter |

---

## Testing

```bash
# Unit & integration tests (JUnit 5 + Mockito + H2)
mvn clean test

# End-to-end API tests (requires running stack)
pip install requests
python tests/api_test.py

# E2E browser tests
npx playwright test   # see tests/e2e

# Load tests (Gatling — validates p95 < 200ms, error rate < 0.1%)
mvn verify -Pperformance
```

---

## Versioning

This project follows semantic versioning.  
Multiple releases have been published with incremental improvements and bug fixes.

Check all versions here: https://github.com/Dhruv0306/cloudshare-app/releases

---

## Contributing

Contributions are welcome!  
Feel free to fork the repo and submit a PR.

For major changes, please open an issue first to discuss what you would like to change.

---

## Documentation

Full system design docs live in [`docs/`](docs/) — see [`docs/README.md`](docs/README.md) for the index, including architecture, data flows, threat model, secrets/key management, disaster recovery, and CI/CD.
