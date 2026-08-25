# CloudShare Documentation

This is the index for all design, security, and operational documentation. Start here.

> The original `implementation_phase_plan.md` (root-level, 5-phase build plan) has been
> removed — it was empty and superseded. Its content now lives across the docs indexed
> below, primarily [Architecture](system-design/architecture.md) and this index.

## System Design
- [Architecture](system-design/architecture.md) — topology, component breakdown, functional/non-functional requirements
- [Data Flows](system-design/data-flows.md) — sequence diagrams for auth, upload, download, sharing
- [API Specification](system-design/api-spec.md) — full REST contract
- [Database Design](system-design/database.md) — schema, partitioning, indexes
- [Caching Strategy](system-design/caching-strategy.md) — triple-Redis roles and invalidation rules
- [Security](system-design/security.md) — auth, encryption, hardening controls
- [Threat Model](system-design/threat-model.md) — STRIDE-style analysis of attack surfaces
- [Secrets & Key Management](system-design/secrets-key-management.md) — KEK/FEK envelope encryption, rotation
- [Infrastructure & CI/CD](system-design/infrastructure-cicd.md) — pipeline, required checks, branch protection
- [Observability](system-design/observability.md) — metrics, logging, tracing
- [Disaster Recovery](system-design/disaster-recovery.md) — RPO/RTO, backup strategy
- [Testing Strategy](system-design/testing-strategy.md) — unit/E2E/load test layers
- [Data Lifecycle](system-design/data-lifecycle.md) — retention, soft-delete, purge scheduling

## Operations
- [Staging Environment](staging-environment.md) — sizing, deployment, KPI re-baselining
- [Runbook: Audit Partition Maintenance](runbooks/audit-partition-maintenance.md)

## Audits
- [Adversarial Audit Report](audits/CloudShare_Adversarial_Audit_Report.md)

## Contributing
See [CONTRIBUTING.md](../CONTRIBUTING.md) at the repo root for branch naming, PR discipline, and required CI checks.
