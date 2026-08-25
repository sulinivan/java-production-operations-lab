# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [3.2.0] - 2026-08-24

### Added

- Added `.github/workflows/security-scans.yml` implementing automated security scans for the repository. This contains two jobs:
  - **OWASP Dependency-Check (SCA)**: runs on pushes, pull requests, and a weekly schedule (Mondays 06:00 UTC) to scan the Maven dependency tree.
  - **Trivy Container Scan**: builds the Docker image locally and scans for high and critical OS/library vulnerabilities.
- Integrated dynamic handling of `NVD_API_KEY` within the OWASP Dependency-Check CI job to gracefully fall back and unset the key if the repository secret is empty, preventing build failures on external pull requests.

### Changed

- Upgraded `okhttp` dependency configuration in `pom.xml`, replacing the `com.squareup.okhttp3:okhttp` dependency (`4.12.0`) with `com.squareup.okhttp3:okhttp-jvm` (`5.5.0`) to ensure Maven compatibility.
- Upgraded `org.bouncycastle:bcprov-jdk18on` from `1.85` to `1.85.2`.
- Upgraded `tools.jackson:jackson-bom` from `3.2.1` to `3.2.2`.
- Upgraded `com.fasterxml.jackson:jackson-bom` from `2.22.1` to `2.22.2`.
- Upgraded GitHub Action `actions/setup-python` from `v5` to `v7` in the API test workflow (`api-tests.yml`).

### Docs

- Updated `docs/system-design/data-lifecycle.md` to clarify the GDPR audit log lifecycle: note that archived audit log files (exported as gzip-compressed CSV to object storage during retirement) are currently retained indefinitely, as `v3.1.0` did not implement an automated archive-expiry policy.

## [3.1.0] - 2026-08-08

### Added

- `AuditPartitionScheduler` now retires `audit_logs` partitions past a
  configurable retention window (`app.scheduler.audit-partition.retention-months`,
  default 6 months), closing the gap flagged during the `v3.0.0` follow-up
  work where partitions were only ever created, never removed, and would
  have accumulated indefinitely.
- Retirement archives each expiring partition to `StorageService` as a
  gzip-compressed CSV (via the Postgres JDBC driver's `CopyManager`) **before**
  detaching and dropping it — controlled by `app.scheduler.audit-partition.archive-enabled`
  (default `true`). A failed archive export always blocks the drop for that
  partition and is retried on the next scheduled run; it never proceeds on a
  "best effort" basis that could silently lose audit data. Archive location
  is configurable via `app.scheduler.audit-partition.archive-path-prefix`
  (default `audit-archive`), reusing the app's existing storage
  abstraction (MinIO or local, per `storage.provider`) rather than adding new
  storage infrastructure.
- Added a Postgres session-level advisory lock (`pg_try_advisory_lock`)
  guarding the entire `maintainPartitions()` job. The app scales horizontally
  (`docs/system-design/architecture.md`); partition *creation* was already
  race-safe on its own (`CREATE TABLE IF NOT EXISTS` is idempotent), but the
  new `ALTER TABLE ... DETACH PARTITION` retirement step is not — a second
  replica detaching an already-detached partition fails outright. The lock
  ensures only one replica performs maintenance per scheduled run; any others
  log and skip cleanly. Held on its own dedicated connection for the job's
  duration and explicitly released in a `finally` block.
- Added `test_audit_partition_retention` to `tests/api_test.py`: a full
  black-box API test exercising the retirement path through the existing
  `POST /api/v1/admin/audit-logs/partitions` trigger endpoint (the same one
  `test_audit_partition_maintenance` already uses). Seeds a real row into a
  partition well past the retention cutoff, triggers maintenance over HTTP,
  and asserts both that the partition is actually gone from `pg_inherits`
  afterward *and* that its data landed in MinIO first, via a new `_mc()` test
  helper that re-authenticates the `storage` container's `local` `mc` alias
  with its own `MINIO_ROOT_USER`/`MINIO_ROOT_PASSWORD` before each call —
  `mc ready local` (the healthcheck) works without real credentials, but
  `mc ls`/`mc rm` don't, and a real local run of the suite caught this
  (`Access Denied`) before merge. Proves archive-before-drop end-to-end, not
  just that an old partition eventually disappears.

### Notes

- Retention period was raised from an initial 3-month default to 6 months
  (3 months was flagged as too short for hot retention of a security audit
  trail). 6 months is still on the shorter side relative to some compliance
  regimes (e.g. a 1-year minimum), so the archive-before-drop behavior above
  is what actually makes any window here safe, not the duration alone — data
  isn't gone at 6 months, just moved off the hot partition. Re-evaluate
  against actual compliance requirements before relying on this for
  production audit data.
- Confirmed the two `Locale.ROOT` call sites in `AuditPartitionScheduler`
  (flagged as future work in earlier release notes) were already fixed in
  `v2.3.0` (commit `e8480d9`) — no changes needed here. Closing that item out
  explicitly since it had been carried forward inaccurately.

## [3.0.0] - 2026-08-08

### Changed

- Upgraded core application framework to **Spring Boot 4.1.0** and migrated to Tomcat 11 managed-coordinates.
- Upgraded managed dependency `io.minio:minio` from `8.6.0` to `9.0.3` (major-line upgrade) and fixed the method signature mismatch (`PutObjectArgs#stream` parameter `int` -> `long` type).
- Upgraded managed dependency `org.bouncycastle:bcprov-jdk18on` from `1.84` to `1.85`.
- Upgraded managed dependency `net.logstash.logback:logstash-logback-encoder` from `8.0` to `9.0`.
- Upgraded managed dependency `org.jacoco:jacoco-maven-plugin` from `0.8.12` to `0.8.15`.
- Upgraded managed dependency `org.owasp:dependency-check-maven` from `12.2.2` to `13.0.0`.
- Upgraded managed dependency `io.gatling:gatling-maven-plugin` from `4.9.x` to `4.21.10`.
- Upgraded managed dependency `io.gatling.highcharts:gatling-charts-highcharts` from `3.11.x` to `3.15.1`.
- Upgraded GitHub Actions versions: `actions/setup-java` to `v5`, `actions/checkout` to `v7`, `actions/setup-node` to `v7`, `actions/cache` to `v6`, and `actions/upload-artifact` to `v7`.
- Refactored the ClamAV concurrency E2E test (`clamav-concurrency.spec.ts`) to use parallel fetch API requests inside the page context instead of browser file input UI interactions, achieving 100% concurrent request execution and eliminating flakiness.

### Security

- Upgraded managed dependency `tools.jackson:jackson-bom` (Jackson 3.x) from `3.2.0` to `3.2.1` to resolve security vulnerabilities (CVE-2026-29062).
- Pinned Tomcat embedded server dependency `<tomcat.version>` to `11.0.24` to resolve five vulnerabilities (CVE-2026-53434, CVE-2026-55276, CVE-2026-59083, CVE-2026-59084, and CVE-2026-53404) present in the default Spring Boot 4.1 Tomcat dependency.
- Pinned classic `com.fasterxml.jackson:jackson-bom` version to `2.22.1` in dependency management to resolve CVE-2026-54515 (case-insensitive deserialization exclusion bypass in jackson-databind).

## [2.4.0] - 2026-08-07

### Security

- **[Critical]** Upgraded transitive dependency `org.bouncycastle:bcprov-jdk18on` from `1.81` to
  `1.84` in `pom.xml` to address CVE-2025-14813 — a critical GOST block count bypass vulnerability
  that could allow attackers to bypass specific security properties in cryptographic libraries.
- Upgraded managed dependency `org.postgresql:postgresql` from `42.7.11` to `42.7.12` to address
  CVE-2026-54291 — a vulnerability where man-in-the-middle attackers could trigger a downgrade in
  SCRAM-SHA-256-PLUS authentication, bypassing MITM protection.
- Upgraded managed dependency `io.netty:netty-codec` (and all other Netty dependencies) from
  `4.1.135.Final` to `4.2.16.Final` to address both CVE-2026-59901 (infinite loop in bzip2 decompression handler)
  and CVE-2026-56816 (HTTP/3 frame codec memory exhaustion DoS).
- Upgraded Tomcat embed libraries from `10.1.55` to `10.1.57` to address multiple security vulnerabilities,
  including CVE-2026-55276 (high/critical request parsing vulnerability).
- Suppressed Tomcat `tomcat-embed-*` CVE-2026-66299 in `.owasp/suppressions.xml` because it only affects the Tomcat
  WebSocket chat example web application, which is completely absent from Spring Boot's embedded server deployments.
- Upgraded Log4j2 dependency from `2.24.3` to `2.26.1` to mitigate CVE-2026-34479 (XML layout character escaping vulnerability).
- Suppressed `kotlin-stdlib` CVEs (CVE-2026-53914, CVE-2020-29582) in `.owasp/suppressions.xml` as build-time issues
  not applicable to our runtime classpath (with Kotlin version bumped to `2.0.21` to maintain modern tooling alignment).

### Added

- Added `.github/workflows/security-scans.yml`: a real, implemented SCA/CVE
  scanning job, replacing the commented-out design stub that previously lived
  in `docs/system-design/infrastructure-cicd.md` with no corresponding
  workflow file. Two jobs, both running on push/PR to `main` and weekly
  (Mondays 06:00 UTC) to catch newly-disclosed CVEs against unchanged code,
  not just CVEs introduced by new commits:
  - `dependency-check` — OWASP `dependency-check-maven` against the full
    Maven dependency tree, gated at CVSS ≥ 7. This is exactly the class of
    tool that would have caught `CVE-2025-66516` (tika-core) automatically
    before it reached `main`, instead of relying on periodic manual review.
  - `container-scan` — Trivy against the `Dockerfile`-built image (built
    locally in the runner, not pushed to a registry), covering base-image /
    OS-package CVEs that dependency-only SCA doesn't see.
- Added a `security-scan` Maven profile (opt-in, not bound to the default
  `verify` lifecycle) wrapping the `dependency-check-maven` plugin, activated
  in CI via `-Psecurity-scan`. Kept out of the default build because the NVD
  database sync is slow and network-dependent — local `mvn verify` shouldn't
  pay that cost on every run.
- Added `.owasp/suppressions.xml` as the documented home for any future
  dependency-check false-positive suppressions, currently empty.
- Added `.github/dependabot.yml` enabling native Dependabot alerts for the
  `maven` and `github-actions` ecosystems (weekly cadence for routine
  version bumps; security PRs open immediately regardless of schedule).

### Requires manual follow-up (not part of this release's automated changes)

- An `NVD_API_KEY` repository secret needs to be added under Settings →
  Secrets and variables → Actions for the `dependency-check` job to run at a
  practical speed in CI (free key: nvd.nist.gov/developers/request-an-api-key).
  Without it, NVD database updates fall back to a heavily rate-limited public
  endpoint.
- Branch protection on `main` should be updated to require the new
  `security-scans` checks once they've run cleanly a few times — a workflow
  file can't configure this itself. While auditing branch protection for
  this, also check for any stale `security-scans` required-check entry left
  over from before this job existed (see the known-gap note carried in
  earlier releases).

### Docs

- Corrected `docs/system-design/infrastructure-cicd.md`: replaced the
  commented-out `security-scans` stub with a pointer to the real
  implementation above, and added an explicit note that the composite
  pipeline YAML shown in that section is illustrative — the actual pipeline
  is several separate workflow files, and the `publish-images` job
  (GHCR push + registry-image Trivy scan) documented there is still
  design-only with no corresponding workflow file. That's a separate,
  not-yet-scoped gap, not something this release closes.

## [2.3.0] - 2026-08-07

### Security

- Bumped `io.minio:minio` `8.5.12` → `8.6.0`, fixing CVE-2025-59952
  (GHSA-h7rh-xfpj-hpcm) — an information-disclosure flaw where the XML serializer
  substituted `${...}` references in response tag values with system property /
  environment variable values. A malicious or compromised S3-compatible endpoint
  could use this to exfiltrate JVM system properties or environment variables
  (credentials, paths) through client-side XML response parsing. MinIO fronts
  this project's encryption and object-storage pipeline, so client-side handling
  of storage-backend responses is directly relevant attack surface. Pinned to
  `8.6.0` rather than the latest `9.0.3`; `9.0.0` was an explicit breaking API
  refactor and is tracked separately as a future major-version initiative, same
  as the Spring Boot 4.x note below.
- Added an explicit `com.squareup.okhttp3:okhttp` pin at `4.12.0`. MinIO
  `8.6.0`'s own transitive `okhttp` default moved to `5.1.0`, which ships as
  split JVM/Android artifacts and breaks compilation (missing `okhttp3.HttpUrl`
  and related classes — see upstream `minio/minio-java#1670`/`#1681`). `4.12.0`
  is what `8.5.12` already resolved to, so this is a no-op for the actual
  runtime dependency graph, not a downgrade.

### Added

- Added `Referrer-Policy: strict-origin-when-cross-origin` and a `Permissions-Policy`
  (denying geolocation, camera, microphone, payment, USB, and FLoC) to
  `nginx/nginx.conf`, rounding out the existing header set (CSP, HSTS,
  X-Frame-Options, X-Content-Type-Options).
- Added a root `SECURITY.md` with a supported-versions table and vulnerability
  disclosure policy, surfaced by GitHub's Security tab. Two follow-ups are
  called out inline rather than blocking this release: the maintainer contact
  email is a placeholder, and GitHub's private vulnerability reporting needs to
  be manually enabled under repo Settings → Security → Vulnerability reporting.

### Fixed

- Closed out the last 4 call sites from the locale-folding sweep flagged in
  v2.1.0's own CHANGELOG: `AuditPartitionScheduler.java` (database product name
  check, partition name normalization) and the Content-Type sniffing in
  `FileController`/`ShareController` ahead of the inline-HTML-injection guard.
  Same latent-bug class as the real defect previously fixed in
  `RateLimitingFilter` — none of these 4 sites had an observed failure, but
  case folding under non-English default locales can silently change ASCII
  string comparisons, so they're closed out for consistency.

## [2.2.0] - 2026-08-06

### Security

- **[Critical]** Bumped `tika-core` `3.0.0` → `3.3.2`, fixing CVE-2025-66516 — a critical
  XXE vulnerability (CVSS 10.0 per NVD/GitHub Advisory, 8.4 per some vendor scoring) present
  in `tika-core` versions 1.13–3.2.1. Exploitable via a crafted XFA structure embedded in a
  PDF; this codebase's `FileService.uploadFile()` calls `Tika#detect()` directly against
  every uploaded file's magic bytes, including PDFs, making this live attack surface on the
  upload pipeline (info disclosure, SSRF, DoS). Fixed upstream in `tika-core` ≥3.2.2; pinned
  to the latest stable release rather than the minimum fixed version.
- Dropped the deprecated `X-XSS-Protection` header from `nginx/nginx.conf`. It's ignored by
  all modern browsers, and its legacy heuristic mode was itself an XSS vector in old
  IE/Edge. The existing CSP already provides the real protection here.

### Changed

- Bumped Spring Boot parent `3.5.0` → `3.5.16`, the final OSS release of the 3.5.x line
  (3.5 reached end-of-life 2026-06-30; no further OSS patches will land on this branch).
  Patch-level bump only — no Spring Security/Jackson/Java-baseline changes are introduced
  by this release line. A move to Spring Boot 4.x (Spring Framework 7, Java 21 baseline,
  Spring Security 7 default changes) is tracked separately as its own future major-version
  initiative, not folded into this release.
- Bumped `jjwt` (`jjwt-api`/`jjwt-impl`/`jjwt-jackson`) `0.12.6` → `0.13.0`. Upstream this
  release contains a single change (a previously-private constructor made `public`) with no
  impact on this codebase's usage.

### Fixed

- Reconciled `security-scans` documentation drift: `README.md`'s CI/CD table and
  `docs/system-design/infrastructure-cicd.md` both referenced a `security-scans` required
  check that has never existed as an actual workflow file — only `maven.yml`,
  `api-tests.yml`, `e2e-tests.yml`, `load-tests.yml`, and `release.yml` are live under
  `.github/workflows/`. Removed the false claim from the README table and relabeled the
  job spec in the infra doc as planned/not-yet-implemented, preserving the intended design
  (OWASP Dependency-Check + SpotBugs) without asserting it runs today. If `security-scans`
  is configured as a required status check in GitHub's branch-protection settings for
  `main`, it should be removed there too — verify directly in repo settings.
- Confirmed the JaCoCo `jacoco:check` gate added in v2.1.0 against a real measured
  baseline: actual instruction coverage is 69%. The 60% minimum is kept as an intentional
  buffer below that baseline rather than raised to match it, so routine coverage drift
  doesn't fail `verify` on unrelated PRs.

### Removed

- Removed the tracked, 0-byte `implementation_phase_plan.md` from the repo root. Its
  original 5-phase plan content is superseded by `docs/system-design/`; a breadcrumb note
  was added to `docs/README.md` for anyone who finds the old filename in git history.

## [2.1.0] - 2026-08-05

### Security

- Fixed locale-sensitive case folding in two remaining locations, completing the sweep
  deferred from the v1.2.2 review (which fixed the pattern in `FileService.java` only):
  `ShareService`'s permission-type parsing (`toUpperCase()` → `toUpperCase(Locale.ROOT)`) and,
  more significantly, `RateLimitingFilter`'s account-based rate-limit key hashing
  (`toLowerCase()` → `toLowerCase(Locale.ROOT)`). The latter is the more security-relevant of
  the two: on a non-default-locale JVM, inconsistent case folding here could split rate-limit
  buckets for the same account, silently weakening the rate limiter without ever raising an
  exception to surface it. Three other occurrences of the same pattern remain, still out of
  scope (`AuditPartitionScheduler.java` ×2, and the `text/html` Content-Type checks in
  `FileController`/`ShareController`) — all lower-risk since none of the strings involved
  contain characters affected by the relevant locale-specific case rules.

### Added

- Added a `jacoco:check` execution to the `verify` phase, enforcing a minimum coverage floor
  on top of the report-only JaCoCo setup shipped in v1.3.0. The threshold was raised from
  this change's original 30% placeholder to **60% instruction coverage** before merge. Whether
  60% reflects a real, measured baseline for this repo — as opposed to another unverified
  number — hasn't been confirmed by any coding-agent session working on this codebase; none
  have had local Maven/Maven Central access to run a real build, or `gh`/API access to pull a
  past CI run's artifact. If 60% wasn't set from an actual `mvn clean verify` run or the
  `jacoco-coverage-report` CI artifact, this gate can fail unexpectedly the next time `verify`
  runs — confirm against a real coverage report and adjust if needed.

## [2.0.0] - 2026-08-04

### Changed

- **[Major/Internal]** Split the monolithic `frontend/js/app.js` (1,262 lines) into 10 ES
  modules: `state.js` (the single shared state object, exported once as a true singleton),
  `shared.js` (pure UI helpers with no state/API dependency), `session.js` (session lifecycle:
  JWT parsing, active-session check, shell setup/teardown), `router.js` (the SPA router),
  `views/{auth,dashboard,sharing,mfa,admin}.js` (one file per feature area), and a slim
  bootstrap `app.js` that only wires up `DOMContentLoaded` and event listeners. No behavior
  change — verified via a full function-name inventory diff (all 42 original top-level
  functions present, none missing, none added), a byte-identical diff of the `state` object
  definition, and a Node.js ESM import-resolution harness confirming every module's imports
  resolve against real exports (this caught one real bug before merge: `bindGlobalEvents`'s
  admin pagination/filter handlers weren't importing `loadAdminUsers`/`loadAdminLogs`).
  `index.html` required no changes — it already loads `app.js` as `type="module"`, and the app
  has zero inline `onclick="..."` HTML attributes. This is a breaking internal restructure
  (import paths, module boundaries) but touches no external/public contract — no HTTP endpoint,
  request/response shape, or user-facing behavior changed.
- **[Major/Internal]** Extracted `PermissionCacheService` out of `FileService` and `ShareService`,
  removing duplicated Redis permission-cache logic. The near-identical eviction-with-bypass-marker
  pattern previously existed independently in `FileService.deleteFile` and
  `ShareService.evictPermissionsCache`; the cache-read-with-self-heal pattern existed only in
  `FileService.verifyFileAccess`. The new service owns only the Redis mechanics (cache-aside
  read/write, eviction, bypass-marker self-healing) — it does not query repositories directly;
  `FileService.verifyFileAccess` keeps owning the database-fallback orchestration. Both services'
  constructors now take `PermissionCacheService` instead of a directly-qualified
  `StringRedisTemplate`. Test coverage: `FileServiceTest`/`ShareServiceTest` mocks rewritten (37
  prior Redis-mock references across both files) to mock the new service and verify correct
  delegation; a new `PermissionCacheServiceTest` is now the authoritative coverage for the
  extracted cache-aside/eviction/self-healing mechanics themselves. No API/endpoint contract
  change.

## [1.3.0] - 2026-08-02

### Added

- Added `healthcheck:` blocks and `restart: unless-stopped` to every service in
  `docker-compose.yml`, and upgraded `app`'s `depends_on` to the long-form
  `condition: service_healthy` syntax. Previously `depends_on` only guaranteed
  container *start* order, not readiness — Postgres/Redis/MinIO could still be
  unready when the app started. `db` uses `pg_isready`; the three Redis instances use
  `redis-cli ping`; `storage` (MinIO) uses `mc ready local` (the currently-recommended
  check — the official `minio/minio` image dropped both `curl` and `wget` in late
  2023, so the commonly-referenced `curl .../minio/health/live` example no longer
  works on current image tags); `clamav` needs no new healthcheck since the official
  `clamav/clamav` image already ships its own (`clamdcheck.sh`).
- Added JaCoCo coverage reporting (`jacoco-maven-plugin`, report-only — no enforced
  `jacoco:check` threshold yet, since this repo's actual coverage baseline hasn't
  been measured from a real build). The `verify` CI job now uploads the generated
  HTML/XML report as a build artifact for visibility. An enforced minimum can follow
  once a real baseline number is available.
- Added `@Min(1)`/`@Max(200)` constraints directly to `AdminController`'s
  `clamav/limit` and `downloads/limit` endpoint parameters, mirroring the bounds
  already enforced in `ClamAvService`/`DownloadConcurrencyLimiter`. Uses Spring
  Framework 6.1+'s native method-validation support (no class-level `@Validated`,
  which would route through the older, discouraged-for-this-version AOP-proxy path).
  A new `GlobalExceptionHandler` handler for `HandlerMethodValidationException`
  returns the same `VALIDATION_FAILED` response shape already used for request-body
  validation failures, instead of a bad value only surfacing one layer down.
- Added explicit, environment-overridable connection pooling and command timeouts
  for all three Redis `LettuceConnectionFactory` beans (cache-aside, security,
  rate-limit), previously running on library defaults. Timeout defaults are chosen
  per instance's actual failure semantics: the security instance (fail-closed on
  step-up token checks) keeps a more forgiving 2000ms default so transient latency
  doesn't turn into incorrectly rejected legitimate step-up attempts, while the
  rate-limit instance (fails open by design in `RateLimiterService`) uses a
  deliberately shorter 500ms default, since there's no security benefit to waiting
  longer before falling through to "allow."

## [1.2.2] - 2026-08-02

### Added

- Added a container-level `HEALTHCHECK` to the production `Dockerfile`, polling the Actuator
  liveness probe (`/actuator/health/liveness`, already exposed unauthenticated via
  `SecurityConfig` and `management.endpoint.health.probes.enabled`). Gives `docker ps` and any
  orchestrator visibility into application health without requiring compose-level tooling.

### Security

- Expanded the upload pipeline's dangerous-MIME deny-list (`isDangerousMimeType`) to also reject
  `application/x-executable`, `application/x-elf`, `text/x-python`, and `application/x-httpd-php`,
  closing a few common executable/script MIME variants that were previously absent from the
  secondary content-based deny-list (the primary extension allow-list already blocks the
  associated file extensions independently).

- Fixed locale-sensitive case folding in `FileService`: `String#toLowerCase()`/`toUpperCase()`
  called with no `Locale` argument use the JVM's default locale, which can silently corrupt
  ASCII-only string matching under certain locales (e.g. Turkish, where `I`/`i` fold differently).
  Switched to `toLowerCase(Locale.ROOT)` in `containsDangerousMarkup` (polyglot markup scan),
  `isDangerousMimeType` (MIME deny-list check), and `isDisallowedExtension` (extension allow-list
  check) to make case folding deterministic regardless of server locale. Caught in PR review.

### Changed

- Reduced redundant work in the polyglot-file markup scanner (`containsDangerousMarkup`): the
  sliding content window is now maintained pre-lowercased and updated incrementally per chunk,
  instead of re-lowercasing the entire (up to 5000-character) window from scratch on every 8KB
  read. The window size, trim threshold, and boundary-overlap behavior are unchanged, so detection
  coverage for markers split across chunk reads is unaffected — this is a constant-factor
  efficiency improvement, not a change in scanning behavior.
- Split the permission-cache failure log tag in `FileService.verifyFileAccess` into two distinct
  markers: `[PERMISSION_CACHE_EVICTION_FAILED]` (unchanged, used only when a permission-cache
  *eviction* fails after a share/ownership change — a real stale-permission risk) and the new
  `[PERMISSION_CACHE_READ_FAILED]` (used when a cache *read* fails and the code safely falls back
  to the database as source of truth — not a security risk). Previously both conditions were
  unlabeled/conflated, which would have caused false-positive alerting on the benign path if
  either tag is used for monitoring.

## [1.2.1] - 2026-08-02

### Security

- Fixed a TOTP anti-replay bypass where the single-use guard was keyed on the server's current
  time-step rather than the submitted code's value. Because the code verifier accepts a +/-1
  step discrepancy window by default, a code could be replayed once in an adjacent 30s window
  since each window computed a different Redis claim key. The guard is now keyed on a hash of
  the code itself, closing the window regardless of which step it validated under.
- Removed logging of raw refresh-token values at `DEBUG` level in `RefreshTokenService`, and
  changed the default `com.cloudshare` logging level from `DEBUG` to `INFO` in both
  `application.yml` and the non-dev `logback-spring.xml` profile, so this class of sensitive
  data-in-logs mistake isn't shipped as the default in any environment.
- Added authentication (`requirepass`) to all three Redis instances (`cache-aside`,
  `cache-security`, `cache-ratelimit`), which previously relied solely on Docker network
  isolation. `SecretsStartupValidator` now fails closed at boot if a Redis password is missing,
  unless explicitly overridden for local development.
- Added a per-account (hashed-identifier) rate limit on `/api/v1/auth/login`, layered on top of
  the existing per-IP limit, to reduce exposure to credential-stuffing attempts distributed
  across many source IPs against a single target account.
- Added audit logging (`STEP_UP_GRANTED` / `STEP_UP_FAILED`) for MFA step-up token issuance,
  closing a gap where every other sensitive action had an audit trail except this one.
- Client-facing authentication error responses no longer echo raw `UsernameNotFoundException`
  messages (which could include internal identifiers); a generic message is returned instead
  while the detail is still captured server-side in logs.

### Fixed

- Public share-link creation (`expiresInSeconds`) now enforces an upper bound (30 days) in
  addition to the existing lower bound, preventing effectively-permanent share links and a
  potential `Instant` overflow on extreme input values.
- Admin-tunable ClamAV scan and download concurrency limits now enforce an upper sanity bound
  (200) in addition to the existing lower bound, preventing accidental resource exhaustion from
  a misconfigured value.

### Changed

- File-upload extension filtering switched from a deny-list of known-dangerous extensions to an
  allow-list of permitted extensions, closing the inherent gap where novel or uncommon
  executable/script extensions could bypass a fixed blocklist.
- Removed unreachable fallback branches in `JwtAuthenticationFilter` and `RateLimitingFilter`
  that handled a `null` return from `JwtTokenProvider#resolveToken`, which never actually
  returns `null` (code clarity only, no behavior change).

## [1.2.0] - 2026-07-27

### Security

- Enforced runtime parity between `SecretsStartupValidator`'s fail-closed KEK shape validation
  and `EncryptionService`'s runtime KEK resolution — a non-32-byte KEK is now rejected at
  runtime (not just at startup) unless `crypto.kek.allow-raw-passphrase` is explicitly set (§1.3).
- Added Redis-backed anti-replay tracking for TOTP codes — a valid MFA code can no longer be
  presented more than once within its validity window across `/mfa/verify` and `/mfa/step-up` (§1.4, §3.5).
- Isolated rate-limiting Redis capacity from security-critical token-blacklist and refresh-token
  tracking by moving rate-limit keys to a dedicated `allkeys-lru` Redis instance (`cache-ratelimit`),
  preventing rate-limiter write volume from ever triggering OOM-driven failures in unrelated
  security enforcement paths. Refresh-token-family tracking was also migrated to a Redis sorted
  set with time-based pruning, bounding unbounded growth without weakening RTR breach-detection
  guarantees within the full refresh-token lifetime (§3.4).
- Public share-link downloads now return an identical response for "share code does not exist,"
  "share code exists but is expired," and "share code exists but the password is missing or
  incorrect," closing a minor enumeration vector. A new `/api/v1/shares/link/{code}/info`
  endpoint supports the password-prompt UX without reintroducing the same signal on the download
  path itself (§1.5).

### Fixed

- Capped previously-unbounded page-size requests on the admin audit-log listing endpoint and
  added a global page-size ceiling across all paginated API endpoints (§1.6).
- Removed a brittle hardcoded string-length assumption in public-link rate-limit key parsing
  that could have caused request failures on any future route rename (§2.4).
- File-purge and share-link-cleanup scheduled jobs now emit success/failure metrics instead of
  silently logging per-item failures with no aggregate visibility (§2.5).
- Automated creation of future monthly `audit_logs` table partitions via a new
  `AuditPartitionScheduler`, removing a previously manual maintenance dependency whose neglect
  could have taken down all audit-logged write operations (upload, download, share, delete)
  app-wide. A new admin endpoint (`POST /api/v1/admin/audit-logs/partitions`) also allows
  triggering partition maintenance on demand as an operational fallback (§2.6).
- Bounded global concurrent ClamAV scan throughput independent of per-user upload rate limits,
  protecting the single ClamAV daemon sidecar from being saturated by distributed
  low-and-slow uploads. Runtime-tunable via a new admin endpoint
  (`POST /api/v1/admin/clamav/limit`) (§3.3).
- Bounded concurrent decrypt-to-temporary-file operations across both authenticated and
  public-link downloads, preventing a burst of concurrent large-file downloads from exhausting
  shared container temp storage and disrupting unrelated users' downloads. Runtime-tunable via
  a new admin endpoint (`POST /api/v1/admin/downloads/limit`) (§3.6).

### Documentation

- Added an operational runbook for audit-log partition maintenance
  (`docs/runbooks/audit-partition-maintenance.md`), including manual fallback procedures.

## [1.1.1] - 2026-07-21

### Security

- Fixed a race condition in MFA step-up token single-use enforcement by replacing check-then-set logic with an atomic `setIfAbsent` claim in Redis (§1.1).
- Enforced fail-closed behavior (HTTP 503 Service Unavailable) when Redis security store is unavailable during step-up token validation (§1.2).

### Fixed

- Fixed a TOCTOU race condition in public share link downloads by replacing application-level read-check-increment with an atomic conditional database update (§2.1, §3.1).

## [1.1.0] - 2026-05-24

### Added

- Implemented OAuth 2.0 Client Credentials Flow for machine-to-machine authentication and service account authorization (§1.4).
- Added `/api/v1/oauth/token` endpoint for client credential issuance and token exchange (§1.4.1).
- Implemented global request rate limiting using Redis for brute force protection and denial-of-service prevention (§1.5).
- Added `/api/v1/admin/rate-limits` endpoint for administrative view of current rate limit usage (§1.5.3).
- Implemented per-second public download rate limiting per share link to prevent abuse (§3.2.1).
- Added upload concurrency controls with sliding window rate limiting to prevent resource exhaustion (§3.3.1).
- Added configurable ClamAV AV scan concurrency limit and job queueing in storage service (§2.5).

### Security

- Enforced OAuth 2.0 client authentication and PKCE validation for all authenticated API requests (§1.4).
- Added granular access control for rate limiting admin endpoints with tenant and role restrictions (§1.5.3).
- Implemented atomic download counting with conditional checks to prevent TOCTOU race conditions (§3.2.1).
- Added request size validation and X-Body-Length header enforcement to prevent request smuggling (§1.6).
- Implemented max upload file size enforcement per tenant and global configuration (§2.3).
- Enforced tenant isolation for rate limit tracking and configuration data storage (§1.5.3, §3.2.1).
- Added validation for share expiration date to prevent time-based bypasses (§3.1.3).

### Fixed

- Fixed issue where share deletion did not cascade to audit log purge jobs, causing unbounded growth (§2.2.2, §5.3.3).
- Fixed database query for file listing to handle special characters in folder names correctly (§2.2.3).
- Fixed issue where share access checks did not correctly enforce download limits for public links (§3.2.1).
- Fixed pagination query to handle large limit values without performance degradation (§4.3.2).

## [1.0.0] - 2026-06-01

### Added

- **Initial Production Release of CloudShare Application**:
  - Spring Boot 3.5 core REST API architecture.
  - PostgreSQL 17 database schema with range-partitioned audit logs.
  - Dual-Redis architecture (Cache-Aside & Security instance split).
  - AES-256-GCM envelope encryption with per-file FEK wrapping.
  - ClamAV container sidecar antivirus scanning.
  - Refresh Token Rotation (RTR) authentication.
  - Dark glassmorphic Vanilla JS SPA dashboard.
  - Nginx edge gateway with SSL/TLS 1.3 termination.
