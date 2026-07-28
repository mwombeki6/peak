# Engineering Readiness Record

This record consolidates the six independently releasable hardening slices for
the existing V1 backend. Applied migrations `V1–V73` remain immutable. The
additive `V74` migration introduces authenticated frontend session contracts;
`V71`–`V73` introduced the hospitality control plane, tenant-continuity lock and
audit IP-address alignment.

## Slice closure

| Slice | Implemented closure | Authoritative gate |
|---|---|---|
| Architecture and boundaries | Canonical 22-module inventory, 230-table ownership catalog, SQL mutation ownership enforcement, named tenant lifecycle/module mutation and property staff-directory ports, audit bootstrap port, generated Modulith canvases | `ModulithArchitectureTests`, `ModulithDocumentationTests`, `DatabaseOwnershipArchitectureTests` |
| Real-hotel acceptance | Product-named tenant/property, stay/finance, department operations, API-security/DAST, concurrent financial writes, mixed-load, and close/reporting runners; separate department identities; API/worker-only business transitions; deliberate failure paths; Daily Control Brief; signed simulators; deterministic evidence | `run-real-hotel-acceptance.sh` |
| Data integrity and migrations | Immutable `V1–V73`, additive V74, clean/current validation, populated V49/V53/V67 paths, PostgreSQL 16/18 CI matrix, isolated runtime roles, RLS, quota locking, financial-concurrency, generated economic invariants, 64-way numbering, and 125,000-row plan gates | migration/database CI matrix and integration tests |
| Operational readiness | Separate migration/tenant-API/platform-API/worker/bootstrap topology, pinned production images, domain dashboards/alerts, fail-fast populated PostgreSQL/Keycloak restore, worker/identity/API/storage/database recovery injection, recovery/degradation/rotation procedures, and weekly/manual soak | operations CI job, resilience-soak workflow, and `run-backup-restore-drill.sh` |
| API and contracts | Checked-in OpenAPI V1 baseline, additive compatibility test, authenticated session bootstrap, runtime route isolation, effective bearer/webhook security assertions, linting, isolated platform and hospitality TypeScript clients, RFC 9457 problem correlation and redaction | architecture/contract CI job |
| Release and supply chain | Parallel required CI jobs, Node 24-compatible actions, direct Podman GHCR login, Trivy filesystem/JAR/OS/config/secret gates, SBOM, provenance attestation, digest equality checks, semantic `v1.x.y` releases | required release gate and container release workflow |

## Finding closure

- Critical: direct cross-owner tenant/platform/audit mutations were removed;
  runtime operations now use the owning module's named API. No open critical
  finding remains in the reviewed local scope.
- High: populated migration upgrades, runtime role boundaries, outbox/replay,
  recovery, financial-control recurrence, and canonical acceptance gates are release requirements. No open
  high finding remains in the reviewed local scope.
- Medium: module/table documentation, API lint/client generation, problem-detail
  sanitization, domain observability, product naming, and test gaps were closed.
  No open medium finding remains in the reviewed local scope.

Protected ClickPesa credentials are an external environment dependency, not a
local release blocker. Deterministic signed simulator evidence is mandatory in
CI; the protected sandbox workflow records live-provider evidence when its
environment owner supplies credentials.

## Evidence contract

Every release run must retain the OpenAPI document/client, Modulith output,
test results, migration matrix, acceptance JSON/PDF hashes, SBOM, provenance,
workflow URL, merge commit, and GHCR manifest digest. PR/merge identifiers and
the published digest are populated by GitHub after these local changes are
committed, reviewed, merged, and published; they are intentionally not
fabricated in this local record.

The complete code-level baseline is enforced by the authoritative Gradle gate,
including the hospitality platform control-plane journey. The bounded
production topology additionally probes 296 V1 operations with 658 hostile
requests, 40 cash-settled concurrent POS orders, and 800 mixed departmental
requests before close, outage recovery, and populated restore.

The Daily Control Brief deliberately reports certified revenue, collections,
variance, leakage exposure, and recorded recovery/protection outcomes. It sets
`actualProfitCalculated=false` until complete operating-cost coverage can
support an honest profit calculation.
