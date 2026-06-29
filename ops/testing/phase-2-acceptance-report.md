# Peak Phase 2 Acceptance Report

Date: 2026-06-29  
Branch: `fix/phase2-acceptance-gate`  
Container image: `localhost/peak:phase2-acceptance` (`34d4714044a8`)  
Result: **PASSED**

## Executed Gates

```bash
./gradlew test bootJar
podman build -t localhost/peak:phase2-acceptance .
PHASE2_RESET=true ops/testing/run-phase2-acceptance.sh
```

- 189 automated tests passed, including Modulith boundaries and route-matrix coverage.
- All 23 Flyway migrations applied successfully to an empty PostgreSQL database through version 24.
- Production environment validation and Compose rendering passed.
- Keycloak issuer, audience, token, and DB-backed platform/tenant identity resolution passed.
- One-shot platform root bootstrap completed with a correlated audit event.
- Tenant registration, administrator provisioning, profile verification, and module setup used APIs only.
- Property setup and activation passed all readiness requirements.
- Verification delivery, consent, report recipient setup, and notification delivery passed through the bounded worker and HTTP provider adapter.
- SSE event delivery and authenticated STOMP subscription passed.
- Cross-tenant HTTP and STOMP access were denied.
- The denied STOMP subscription produced exactly one correlated tenant audit record.
- No manual application SQL was used for setup. SQL was used only by the gate to verify the security audit record.

Machine-readable evidence is written to `build/phase2-acceptance-evidence.json`. The build directory is intentionally ignored.

## Manual Verification

Import `Peak-Phase-2.postman_collection.json` into Postman for an operator-driven run. Use `phase-2-e2e-test-plan.md` for negative, replay, lifecycle, and recovery scenarios.

## Release Decision

The local Phase 2 production-acceptance gate is satisfied. Merge still requires the pull request CI and container checks to pass for the final commit.
