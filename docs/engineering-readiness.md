# Engineering Readiness Record

This record consolidates the six independently releasable hardening slices for
the existing V1 backend. Applied migrations `V1–V67` were not modified. No
unverified database change was introduced; future schema changes begin at
`V68`.

## Slice closure

| Slice | Implemented closure | Authoritative gate |
|---|---|---|
| Architecture and boundaries | Canonical 22-module inventory, 227-table ownership catalog, SQL mutation ownership enforcement, named tenant lifecycle/module mutation ports, audit bootstrap port, generated Modulith canvases | `ModulithArchitectureTests`, `ModulithDocumentationTests`, `DatabaseOwnershipArchitectureTests` |
| Core hospitality journey | Product-named tenant/property, stay/finance, core-hospitality, and close/reporting runners; API/worker-only business transitions; signed simulators; deterministic evidence | `run-core-hospitality-journey.sh`, `run-close-reporting-acceptance.sh` |
| Data integrity and migrations | Immutable `V1–V67`, clean/current validation, populated V49/V53/V67 paths, PostgreSQL 16/18 CI matrix, runtime-role and RLS gates | migration/database CI matrix and integration tests |
| Operational readiness | Separate migration/API/worker/bootstrap topology, pinned production images, domain dashboards/alerts, backup/restore drill, recovery/degradation/rotation procedures | operations CI job and `run-backup-restore-drill.sh` |
| API and contracts | Checked-in OpenAPI V1 baseline, additive compatibility test, effective bearer/webhook security assertions, linting, generated TypeScript client, RFC 9457 problem correlation and redaction | architecture/contract CI job |
| Release and supply chain | Parallel required CI jobs, Node 24-compatible actions, direct Podman GHCR login, Trivy filesystem/JAR/OS/config/secret gates, SBOM, provenance attestation, digest equality checks, semantic `v1.x.y` releases | required release gate and container release workflow |

## Finding closure

- Critical: direct cross-owner tenant/platform/audit mutations were removed;
  runtime operations now use the owning module's named API. No open critical
  finding remains in the reviewed local scope.
- High: populated migration upgrades, runtime role boundaries, outbox/replay,
  recovery, and canonical acceptance gates are release requirements. No open
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
