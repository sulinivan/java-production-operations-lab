# Infrastructure, Containerization & CI/CD

This document details the configuration for local containerized development, production deployments, and the CI/CD pipeline.

---

## 1. Multi-Stage Dockerfile (Backend)

The `Dockerfile` is optimized for security and performance. It utilizes a multi-stage build to ensure the runtime image contains no build-time utilities or raw source code, and runs under a restricted non-root user.

```dockerfile
# ==========================================
# Stage 1: Build the Application
# ==========================================
FROM maven:3.9.6-eclipse-temurin-17-alpine AS builder
WORKDIR /build

# Copy Maven descriptor and resolve dependencies (enables layer caching)
COPY pom.xml .
RUN mvn dependency:go-offline -B

# Copy source code and build jar
COPY src ./src
RUN mvn clean package -DskipTests -B

# ==========================================
# Stage 2: Testing
# ==========================================
FROM builder AS tester
RUN mvn test -B

# ==========================================
# Stage 3: Production Runtime
# ==========================================
FROM eclipse-temurin:17-jre-alpine AS runner
WORKDIR /app

# Install security patches and create a non-root system group & user
RUN apk update && apk upgrade && \
    addgroup -g 10001 -S spring && \
    adduser -u 10001 -S spring -G spring

# Ensure test stage runs successfully before creating runner image
COPY --from=tester /build/pom.xml /tmp/dummy_test_check

# Copy the built jar file from the builder stage
COPY --from=builder /build/target/cloudshare-*.jar ./app.jar

# Adjust ownership to the non-root user
RUN chown -R spring:spring /app
USER spring:spring

# Expose backend API port
EXPOSE 8080

# Container-level health check. Hits the Actuator liveness probe (exposed via
# management.endpoint.health.probes.enabled=true) and is permitted without
# authentication in SecurityConfig (/actuator/health/**). Uses wget since the
# eclipse-temurin:17-jre-alpine base includes it via busybox; no curl is present.
HEALTHCHECK --interval=30s --timeout=3s --start-period=30s --retries=3 \
    CMD wget -qO- http://localhost:8080/actuator/health/liveness || exit 1

# Environment-agnostic JVM tuning parameters
ENV JAVA_OPTS="-XX:+UseG1GC \
               -XX:MaxRAMPercentage=75.0 \
               -XX:MinRAMPercentage=50.0 \
               -XX:+ExitOnOutOfMemoryError"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
```

---

## 2. Local Docker-Compose Environment (`docker-compose.yml`)

The following compose configuration builds a local copy of CloudShare side-by-side with PostgreSQL, Dual-Redis, MinIO, ClamAV, and Nginx.

> [!IMPORTANT]
> **Internal-Network-Only Topology:** Notice that **no service except `gateway` publishes a host port**.
> Backing services (`app`, `db`, `cache-aside`, `cache-security`, `clamav`, `storage`) run strictly on the internal bridge network. This enforces edge ingress control through Nginx (`gateway`), preventing direct external access to internal ports (e.g. bypassing rate limiting or spoofing client IP headers).

```yaml
services:
  # Nginx Gateway & Static Asset Router (Edge reverse proxy for Phase 5)
  # IMPORTANT PRECONDITION: Before starting this service, developers MUST run the SSL certificate
  # generation script: `nginx/ssl/generate-certs.sh` or `nginx/ssl/generate-certs.ps1`.
  # If certificates are missing, container initialization will fail.
  gateway:
    image: nginx:1.25-alpine
    restart: unless-stopped
    ports:
      - "80:80"
      - "443:443"
    volumes:
      - ./nginx/nginx.conf:/etc/nginx/nginx.conf:ro
      - ./nginx/ssl:/etc/nginx/ssl:ro
      - ./frontend:/usr/share/nginx/html:ro
    depends_on:
      app:
        condition: service_healthy
    healthcheck:
      # nginx:alpine includes wget via busybox (no curl). Plain HTTP check against
      # the static frontend root is enough to confirm the process is serving traffic.
      test: ["CMD", "wget", "--spider", "-q", "http://localhost:80/"]
      interval: 30s
      timeout: 3s
      start_period: 10s
      retries: 3

  # Spring Boot Stateless App (Scaffolded for later phases)
  app:
    build:
      context: .
      dockerfile: Dockerfile
    restart: unless-stopped
    environment:
      - SPRING_PROFILES_ACTIVE=dev
      - SPRING_DATASOURCE_URL=jdbc:postgresql://db:5432/cloudshare
      - SPRING_DATASOURCE_USERNAME=${SPRING_DATASOURCE_USERNAME}
      - SPRING_DATASOURCE_PASSWORD=${SPRING_DATASOURCE_PASSWORD}
      - SPRING_DATA_REDIS_HOST=cache-aside
      - SPRING_DATA_REDIS_PORT=6379
      - SPRING_DATA_REDIS_PASSWORD=${REDIS_CACHE_PASSWORD}
      - SECURITY_REDIS_HOST=cache-security
      - SECURITY_REDIS_PORT=6379
      - SECURITY_REDIS_PASSWORD=${REDIS_SECURITY_PASSWORD}
      - RATE_LIMIT_REDIS_HOST=cache-ratelimit
      - RATE_LIMIT_REDIS_PORT=6379
      - RATE_LIMIT_REDIS_PASSWORD=${REDIS_RATELIMIT_PASSWORD}
      - CLAMAV_HOST=clamav
      - CLAMAV_PORT=3310
      - STORAGE_PROVIDER=MINIO
      - MINIO_ENDPOINT=http://storage:9000
      - MINIO_ACCESS_KEY=${MINIO_ROOT_USER}
      - MINIO_SECRET_KEY=${MINIO_ROOT_PASSWORD}
      - MINIO_BUCKET_NAME=cloudshare-bucket
      - CRYPTO_MASTER_KEK=${CRYPTO_MASTER_KEK}
      - CRYPTO_KEK_ALLOW_RAW_PASSPHRASE=${CRYPTO_KEK_ALLOW_RAW_PASSPHRASE:-false}
      - JWT_SECRET=${JWT_SECRET}
      - JWT_STEP_UP_SESSION_MAX=${JWT_STEP_UP_SESSION_MAX:-900}
      - RATE_LIMIT_AUTH=${RATE_LIMIT_AUTH:-5}
      - RATE_LIMIT_UPLOAD=${RATE_LIMIT_UPLOAD:-10}
      - RATE_LIMIT_LINK=${RATE_LIMIT_LINK:-30}
      - RATE_LIMIT_LINK_GLOBAL=${RATE_LIMIT_LINK_GLOBAL:-100}
      - RATE_LIMIT_GENERAL=${RATE_LIMIT_GENERAL:-100}
      - RATE_LIMIT_ENABLED=${RATE_LIMIT_ENABLED:-true}
      - CLAMAV_MAX_CONCURRENT_SCANS=${CLAMAV_MAX_CONCURRENT_SCANS:-8}
      - CLAMAV_CONCURRENCY_TIMEOUT_SECONDS=${CLAMAV_CONCURRENCY_TIMEOUT_SECONDS:-30}
      - STORAGE_MAX_CONCURRENT_DECRYPT_DOWNLOADS=${STORAGE_MAX_CONCURRENT_DECRYPT_DOWNLOADS:-20}
      - STORAGE_DECRYPT_ACQUIRE_TIMEOUT_SECONDS=${STORAGE_DECRYPT_ACQUIRE_TIMEOUT_SECONDS:-10}
    # No healthcheck: block needed here — the Dockerfile already declares a HEALTHCHECK
    # (added in v1.2.2) that Compose inherits automatically from the built image.
    depends_on:
      db:
        condition: service_healthy
      cache-aside:
        condition: service_healthy
      cache-security:
        condition: service_healthy
      cache-ratelimit:
        condition: service_healthy
      clamav:
        condition: service_healthy
      storage:
        condition: service_healthy

  # PostgreSQL Relational Metadata Database
  db:
    image: postgres:17-alpine
    restart: unless-stopped
    environment:
      - POSTGRES_DB=cloudshare
      - POSTGRES_USER=${SPRING_DATASOURCE_USERNAME}
      - POSTGRES_PASSWORD=${SPRING_DATASOURCE_PASSWORD}
    volumes:
      - pgdata:/var/lib/postgresql/data
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U ${SPRING_DATASOURCE_USERNAME} -d cloudshare"]
      interval: 10s
      timeout: 5s
      start_period: 10s
      retries: 5

  # Redis Cache-Aside Instance
  cache-aside:
    image: redis:7-alpine
    restart: unless-stopped
    command: redis-server --maxmemory 256mb --maxmemory-policy allkeys-lru --requirepass ${REDIS_CACHE_PASSWORD}
    volumes:
      - redisdata-aside:/data
    healthcheck:
      test: ["CMD-SHELL", "redis-cli -a \"$$REDIS_CACHE_PASSWORD\" --no-auth-warning ping | grep -q PONG"]
      interval: 10s
      timeout: 3s
      start_period: 5s
      retries: 5
    environment:
      - REDIS_CACHE_PASSWORD=${REDIS_CACHE_PASSWORD}

  # Redis Security Instance (Blacklists, Token Families)
  cache-security:
    image: redis:7-alpine
    restart: unless-stopped
    command: redis-server --maxmemory 256mb --maxmemory-policy noeviction --requirepass ${REDIS_SECURITY_PASSWORD}
    volumes:
      - redisdata-security:/data
    healthcheck:
      test: ["CMD-SHELL", "redis-cli -a \"$$REDIS_SECURITY_PASSWORD\" --no-auth-warning ping | grep -q PONG"]
      interval: 10s
      timeout: 3s
      start_period: 5s
      retries: 5
    environment:
      - REDIS_SECURITY_PASSWORD=${REDIS_SECURITY_PASSWORD}

  # Redis Rate Limiting Instance (Ephemeral sliding windows)
  cache-ratelimit:
    image: redis:7-alpine
    restart: unless-stopped
    command: redis-server --maxmemory 128mb --maxmemory-policy allkeys-lru --requirepass ${REDIS_RATELIMIT_PASSWORD}
    healthcheck:
      test: ["CMD-SHELL", "redis-cli -a \"$$REDIS_RATELIMIT_PASSWORD\" --no-auth-warning ping | grep -q PONG"]
      interval: 10s
      timeout: 3s
      start_period: 5s
      retries: 5
    environment:
      - REDIS_RATELIMIT_PASSWORD=${REDIS_RATELIMIT_PASSWORD}

  # ClamAV Virus Scanner Daemon
  clamav:
    image: clamav/clamav:latest
    restart: unless-stopped
    # No healthcheck: block needed — the official clamav/clamav image ships its own
    # HEALTHCHECK (clamdcheck.sh, 6-minute start period to allow signature DB load)
    # which Compose inherits automatically. depends_on below relies on it.

  # MinIO S3-Compatible Object Storage (Free local S3)
  storage:
    image: minio/minio:latest
    restart: unless-stopped
    command: server /data
    environment:
      - MINIO_ROOT_USER=${MINIO_ROOT_USER}
      - MINIO_ROOT_PASSWORD=${MINIO_ROOT_PASSWORD}
      - MINIO_BROWSER=off
    volumes:
      - miniadata:/data
    healthcheck:
      # The minio/minio image dropped curl and wget in late 2023; the bundled `mc`
      # client's "ready" subcommand is MinIO's own currently-recommended healthcheck
      # (see minio/minio's official docker-compose.yaml) and needs no credentials.
      test: ["CMD", "mc", "ready", "local"]
      interval: 10s
      timeout: 5s
      start_period: 10s
      retries: 5

volumes:
  pgdata:
  redisdata-aside:
  redisdata-security:
  miniadata:
```

> [!NOTE]
> **MinIO Console Lockdown & Operational Administration**:
> The MinIO Web Console UI (`:9001`) is disabled by default (`MINIO_BROWSER=off`, `--console-address` omitted) to minimize container attack surface. The S3 API port (9000) remains active internally.
> For ad-hoc object/bucket administration in staging/production, administrators should run the MinIO Client CLI (`mc`) directly inside the internal network:
> ```bash
> docker run --rm --net cloudshare_default minio/mc alias set local http://storage:9000 minioadmin minioadmin
> docker run --rm --net cloudshare_default minio/mc ls local/cloudshare-bucket
> ```
> Alternatively, temporary port-forwarding or SSH tunneling to port 9000 can be established for S3 API client tools.

---

## 3. Production Deployment (Kubernetes Manifest Spec)

For production deployments, the application runs on a Kubernetes cluster. Below is an example Kubernetes configuration specifying resource allocation limits and readiness/liveness checks using Spring Boot Actuator.

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: cloudshare-backend
  namespace: cloudshare
  labels:
    app: cloudshare-backend
spec:
  replicas: 3
  selector:
    matchLabels:
      app: cloudshare-backend
  template:
    metadata:
      labels:
        app: cloudshare-backend
    spec:
      containers:
        - name: app
          image: cloudshare/backend:v2.2.0
          ports:
            - containerPort: 8080
          resources:
            requests:
              memory: "512Mi"
              cpu: "250m"
            limits:
              memory: "1024Mi"
              cpu: "1000m"
          readinessProbe:
            httpGet:
              path: /actuator/health/readiness
              port: 8080
            initialDelaySeconds: 15
            periodSeconds: 10
          livenessProbe:
            httpGet:
              path: /actuator/health/liveness
              port: 8080
            initialDelaySeconds: 20
            periodSeconds: 15
          envFrom:
            - secretRef:
                name: cloudshare-secrets
            - configMapRef:
                name: cloudshare-config
---
apiVersion: v1
kind: Service
metadata:
  name: cloudshare-backend-service
  namespace: cloudshare
spec:
  selector:
    app: cloudshare-backend
  ports:
    - protocol: TCP
      port: 80
      targetPort: 8080
  type: ClusterIP
```

---

## 4. CI/CD Pipeline Configuration (GitHub Actions)

> **Note on this section:** the YAML below is an illustrative composite of the pipeline's
> intended shape, not a literal copy of any single workflow file. The actual, live
> workflows are `.github/workflows/maven.yml`, `api-tests.yml`, `e2e-tests.yml`,
> `load-tests.yml`, `release.yml`, and (as of v2.4.0) `security-scans.yml` — each a
> separate file, none triggered on a `release/*` branch pattern (branch protection is
> `main`-only today). Job 1 (`verify`) and Job 2 (`security-scans`) below correspond to
> real, live jobs. **Job 3 (`publish-images`) does not exist as an implemented workflow**
> — no GHCR push, no image-build step, and no registry-image Trivy scan run in CI today.
> It's left here as the intended design; treat it the same way the security-scans job
> was treated before v2.4.0 — a documented plan, not a running check. If you need image
> publishing enforced, that's a separate, not-yet-scoped piece of work.

This pipeline triggers automatically on commits to the `main` or `release/*` branches. It validates the code, runs automated tests using JUnit and Mockito, performs security vulnerability scans (OWASP Dependency Check, Trivy), builds the docker image, and deploys.

```yaml
name: CloudShare CI/CD Pipeline

on:
  push:
    branches: [ main, "release/*" ]
  pull_request:
    branches: [ main ]

jobs:
  # Job 1: Build & Verify Code Base
  verify:
    runs-on: ubuntu-latest
    steps:
      - name: Checkout Source Code
        uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '17'
          cache: 'maven'

      - name: Compile and Run Unit/Integration Tests
        run: mvn clean verify -B

      - name: Publish Test Report
        uses: scacap/action-surefire-report@v1
        if: always()

      - name: Upload JaCoCo coverage report
        if: always()
        uses: actions/upload-artifact@v4
        with:
          name: jacoco-coverage-report
          path: target/site/jacoco/
          retention-days: 14

  # Job 2: Static Security Scanning — IMPLEMENTED as of v2.4.0.
  # Lives in its own file, .github/workflows/security-scans.yml, rather than as a job
  # in this composite — it runs on its own schedule (weekly, plus push/PR to main) and
  # doesn't share a `needs: verify` dependency with the jobs above. It has two jobs:
  #   - dependency-check: OWASP dependency-check-maven (SCA), gated at CVSS >= 7,
  #     activated via the `security-scan` Maven profile in pom.xml. Requires an
  #     NVD_API_KEY repo secret (https://nvd.nist.gov/developers/request-an-api-key) —
  #     without one, NVD database updates are rate-limited to the point of
  #     impracticality in CI.
  #   - container-scan: Trivy against the Dockerfile-built image (built locally in the
  #     runner, not pushed — see the Job 3 note above for why).
  # This closes the doc/reality gap that existed before v2.4.0: this job was previously
  # described here as a commented-out stub with no corresponding workflow file.
  # Note: as with any new required-status-check candidate, branch protection on `main`
  # needs to be updated manually in GitHub repo settings to require this check — that's
  # not something a workflow file itself can configure.

  # Job 3: Build & Publish Container Images
  publish-images:
    needs: verify
    if: github.event_name == 'push' && github.ref == 'refs/heads/main'
    runs-on: ubuntu-latest
    steps:
      - name: Checkout Source Code
        uses: actions/checkout@v4

      - name: Set up QEMU (Multi-platform builds)
        uses: docker/setup-qemu-action@v3

      - name: Set up Docker Buildx
        uses: docker/setup-buildx-action@v3

      - name: Log in to GitHub Container Registry (GHCR)
        uses: docker/login-action@v3
        with:
          registry: ghcr.io
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}

      - name: Build and Push Backend Container Image
        uses: docker/build-push-action@v5
        with:
          context: .
          file: ./Dockerfile
          push: true
          tags: |
            ghcr.io/${{ github.repository }}/backend:latest
            ghcr.io/${{ github.repository }}/backend:${{ github.sha }}

      - name: Run Trivy Vulnerability Scan on Built Image
        uses: aquasecurity/trivy-action@master
        with:
          image-ref: 'ghcr.io/${{ github.repository }}/backend:${{ github.sha }}'
          format: 'table'
          exit-code: '1' # Fails build if HIGH or CRITICAL issues are found
          ignore-unfixed: true
          vuln-type: 'os,library'
          severity: 'HIGH,CRITICAL'
```
