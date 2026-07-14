# Peak Release Checklist

## Build Evidence

- [ ] Release commit is reviewed and protected-branch CI is green.
- [ ] Container scan has no unaccepted critical/high findings.
- [ ] Deploy image is identified by commit SHA or registry digest.
- [ ] `./gradlew test` report is retained.
- [ ] Warning-free Kotlin compilation and the full required test suite pass with no skips.
- [ ] Flyway validates from empty PostgreSQL 16 and 18 databases through the expected version.
- [ ] Populated V49, V53, and V67 upgrades preserve tenant and financial invariants.
- [ ] V40 payment data upgrades to the V41 canonical lifecycle.
- [ ] Populated V44 department data upgrades through the V49 canonical
      operations contracts.
- [ ] Modulith dependency verification and required integration tests pass with
      no skipped results.
- [ ] `podman compose ... config` and `validate-production-env.sh` pass.
- [ ] The OpenAPI baseline is backward compatible, lint passes, and the generated TypeScript client compiles.
- [ ] OCI/JAR/filesystem/configuration/secret scans, SBOM, and provenance are retained.

## Security Evidence

- [ ] Keycloak realm verification passes for issuer, JWKS, audience mapper,
      authorization code flow, and PKCE client.
- [ ] Header and trusted-claim identity modes are disabled.
- [ ] API, worker, migrator, and support database credentials are distinct.
- [ ] Envelope, guest identity, provider, webhook, database, and Keycloak
      secrets are loaded from the approved secret store.
- [ ] Active payment/fiscal mocks and fiscal simulators are absent.
- [ ] Provider endpoints use certified hosts present in the exact outbound
      allowlist, and the host egress policy permits only required destinations.
- [ ] API and Keycloak bind only to loopback; the edge proxy terminates TLS and
      overwrites forwarding headers.
- [ ] Route matrix coverage and cross-tenant/property denial tests pass.

## Operational Readiness

- [ ] Database and Keycloak backups completed.
- [ ] Restore drill passed against a disposable environment.
- [ ] Rollback image and migration recovery owner are recorded.
- [ ] Keycloak realm export is retained before an upgrade.
- [ ] API readiness reports database, Keycloak, worker heartbeat, and realtime
      journal `UP`.
- [ ] Outbox, audit, payment, fiscal, communication, realtime, DB pool, auth,
      and NIDA/manual-fallback alerts are enabled.

## Provider Readiness

- [ ] Communication HTTP gateway contract is certified.
- [ ] Mobile-money canonical gateway and signed webhook contract are certified.
- [ ] Protected ClickPesa sandbox evidence covers USSD push, status query,
      checksum callback, duplicate callback, and statement reconciliation.
- [ ] Fiscal gateway is approved for the selected TRA/EFD/VFD integration.
- [ ] Provider timeouts, retry policy, idempotency, and reconciliation procedure
      are accepted by operations and finance.
- [ ] NIDA mode remains disabled until the approved private CIG contract is
      implemented; physical-document fallback controls are staffed.

## Acceptance And Rollout

- [ ] Security/runtime and database-role regression gates pass.
- [ ] Tenant onboarding and property administration acceptance passes.
- [ ] Core hospitality journey and concurrency acceptance passes.
- [ ] Podman evidence uses Keycloak plus separate API/worker roles and the
      signed fiscal simulator.
- [ ] Department operations acceptance covers housekeeping inspection, maintenance
      release-to-dirty, weighted-average stock, partial receiving, replay-safe
      kitchen consumption, and authenticated WebSocket/KDS access.
- [ ] Close-reporting acceptance verifies close snapshots, deterministic PDFs,
      private storage, signed-link expiry, consent, delivery retry and cleanup.
- [ ] ClickPesa protected sandbox evidence is tracked as a non-blocking external
      follow-up; cash, room-charge, and simulated mobile-money acceptance pass.
- [ ] Release image is deployed to staging and `smoke-test.sh` passes.
- [ ] Production migration exits successfully before API/worker rollout.
- [ ] First production readiness and smoke checks pass.
- [ ] Image SHA, migration version, test evidence, operator, and rollout time
      are recorded.
- [ ] Accepted commit SHA and immutable image digest are recorded after the
      worktree is clean.
- [ ] Merge-SHA, `latest`, and semantic `v1.x.y` tags resolve to the same manifest digest.
