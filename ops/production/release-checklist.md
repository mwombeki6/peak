# Peak Release Checklist

## Build Evidence

- [ ] Release commit is reviewed and protected-branch CI is green.
- [ ] Container scan has no unaccepted critical/high findings.
- [ ] Deploy image is identified by commit SHA or registry digest.
- [ ] `./gradlew test` report is retained.
- [ ] Flyway validates from an empty PostgreSQL 18 database through the expected version.
- [ ] `podman compose ... config` and `validate-production-env.sh` pass.

## Security Evidence

- [ ] Keycloak realm verification passes for issuer, JWKS, audience mapper,
      authorization code flow, and PKCE client.
- [ ] Header and trusted-claim identity modes are disabled.
- [ ] API, worker, migrator, and support database credentials are distinct.
- [ ] Envelope, guest identity, provider, webhook, database, and Keycloak
      secrets are loaded from the approved secret store.
- [ ] Active payment/fiscal `contract_mock` configurations are absent.
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
- [ ] Fiscal gateway is approved for the selected TRA/EFD/VFD integration.
- [ ] Provider timeouts, retry policy, idempotency, and reconciliation procedure
      are accepted by operations and finance.
- [ ] NIDA mode remains disabled until the approved private CIG contract is
      implemented; physical-document fallback controls are staffed.

## Acceptance And Rollout

- [ ] Phase 1 security/runtime regression plan passes.
- [ ] Phase 2 property/administration acceptance passes.
- [ ] Phase 3 end-to-end and concurrency plan passes.
- [ ] Release image is deployed to staging and `smoke-test.sh` passes.
- [ ] Production migration exits successfully before API/worker rollout.
- [ ] First production readiness and smoke checks pass.
- [ ] Image SHA, migration version, test evidence, operator, and rollout time
      are recorded.
