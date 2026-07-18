# Production Operations

This directory contains the Podman Compose deployment baseline for Peak.

## Files

- `compose.yaml`: PostgreSQL, Keycloak, migration, one-shot platform bootstrap, API, and worker services.
- `.env.example`: required environment variables without real secrets.
- `role-bootstrap.sql`: creates production login roles and grants them membership in Flyway-managed no-login roles.
- `../keycloak/peak-realm.json`: Keycloak realm export imported by the Keycloak container on first startup.
- `../scripts/smoke-test.sh`: post-deploy API, Keycloak, SpringDoc, and anonymous-access smoke checks.

## Rollout Order

1. Build and push an image through CI, or build locally with `podman build -t ghcr.io/mwombeki6/peak/peak:<tag> .`.
2. Create `ops/production/.env` from `.env.example` and replace every placeholder secret.
3. Validate the env file: `ops/scripts/validate-production-env.sh ops/production/.env`.
4. Start PostgreSQL and Keycloak: `podman compose --env-file ops/production/.env -f ops/production/compose.yaml up -d postgres keycloak-db keycloak`.
5. Verify the imported Keycloak realm: `set -a; . ops/production/.env; set +a; KEYCLOAK_BASE_URL=http://localhost:8081 ops/scripts/verify-keycloak-realm.sh`.
6. Bootstrap production login roles: `ops/scripts/bootstrap-db-roles.sh`.
7. Start PostgreSQL and wait for its health check, then run Flyway through the migration profile: `podman compose --env-file ops/production/.env -f ops/production/compose.yaml --profile migration run --rm --no-deps peak-migration`. The `--no-deps` flag is required with Podman Compose so a one-shot migration does not reconcile or replace already-running services.
8. On the first installation only, create the initial operator in Keycloak, set `PEAK_PLATFORM_BOOTSTRAP_ENABLED=true` plus the operator name, email, exact issuer, and Keycloak subject, then run `ops/scripts/bootstrap-platform.sh`. Immediately set the flag back to `false` and clear the four bootstrap identity values. The command refuses to create a different root after platform initialization.
9. If every platform root has lost effective access, use the audited offline recovery procedure: configure the replacement Keycloak identity, set both `PEAK_PLATFORM_BOOTSTRAP_ENABLED=true` and `PEAK_PLATFORM_RECOVERY_ENABLED=true`, then run `ops/scripts/recover-platform-root.sh`. Recovery refuses to run if any effective root remains. Immediately disable both flags and clear the identity values after success.
10. Start tenant API, isolated platform API, and worker: `podman compose --env-file ops/production/.env -f ops/production/compose.yaml up -d peak-api peak-platform peak-worker`.
11. Configure a host reverse proxy to terminate TLS for `PEAK_PUBLIC_HOST` and
    `KEYCLOAK_HOSTNAME`, forwarding only to `127.0.0.1:8080` and
    `127.0.0.1:8081`. Preserve `X-Forwarded-For`, `X-Forwarded-Proto`, and
    `Host`, overwrite any client-supplied forwarding headers, and restrict
    direct access to both loopback listeners.
12. Verify the deployment: `ops/scripts/smoke-test.sh http://localhost:8080 http://localhost:8081`.

The standard deploy script performs steps 3, 7, 8, and 10:

```sh
ops/scripts/deploy.sh
```

## Security Requirements

- Never run API or worker as the migration login.
- Keep `PEAK_API_BIND_ADDRESS` and `KEYCLOAK_BIND_ADDRESS` on `127.0.0.1`.
  Public traffic must enter through an HTTPS reverse proxy; do not expose the
  application or Keycloak plaintext ports directly.
- Keep Flyway disabled for API and worker; only `peak-migration` runs migrations.
- Keep the one-shot `peak-bootstrap` service disabled after the initial platform operator is linked. Routine operator, tenant, and tenant-admin provisioning must use authenticated APIs.
- Keep `PEAK_ALLOW_HEADER_IDENTITY=false` in production.
- Keep `PEAK_ALLOW_TRUSTED_JWT_IDENTITY_CLAIMS=false` in production.
- Keep `PEAK_COMMUNICATION_DELIVERY_LOCAL_PROVIDER_ENABLED=false` in production.
- Configure the worker HTTP communication gateway with an HTTPS base URL and a
  secret API key. The provider contract is `POST /v1/messages`.
- Generate a random 32-byte envelope key, encode it as base64, store it in
  `PEAK_ENVELOPE_KEY_BASE64`, and keep
  `PEAK_ENVELOPE_KEY_REFERENCE=env:PEAK_ENVELOPE_KEY_BASE64`.
- During rotation, move the old key to
  `PEAK_ENVELOPE_PREVIOUS_KEY_BASE64` and set
  `PEAK_ENVELOPE_PREVIOUS_KEY_REFERENCE=env:PEAK_ENVELOPE_PREVIOUS_KEY_BASE64`.
  Remove it only after every invitation encrypted with the old key has expired.
- Set `PEAK_INVITATION_ACCEPTANCE_BASE_URL` to the public HTTPS application
  route that consumes invitation tokens.
- Keep SpringDoc disabled in production.
- Keep CORS origins explicit.
- Keep realtime WebSocket origins explicit and free of wildcards.
- Import and verify the Keycloak realm before allowing tenant users to authenticate.
- Keep `PEAK_SECURITY_JWT_ISSUER_URI` equal to the Keycloak realm issuer and `PEAK_SECURITY_JWT_AUDIENCE=peak-api`.
- Resolve tenant and platform users through active OIDC `identity_links`; do not rely on client-editable user attributes for authorization.
- Keep payment and fiscal credentials out of checked-in YAML. Production payment
  accounts use `providerCode=clickpesa`,
  `apiKeySecretRef=env:PEAK_CLICKPESA_API_KEY`, and
  `checksumKeySecretRef=env:PEAK_CLICKPESA_CHECKSUM_KEY`.
- Set `PEAK_OUTBOUND_PROVIDER_ALLOWED_HOSTS` to the exact comma-separated DNS
  hosts certified for payment and fiscal traffic. Wildcards, URLs, localhost,
  and IP literals are forbidden; tenant-configured endpoints outside this
  operator allowlist are rejected before any network request.
- Enforce the same restriction at the host/network egress layer using the
  provider-published destination ranges. The application allowlist does not
  replace DNS and firewall controls.
- Set `PEAK_PAYMENT_PRODUCTION_APPROVED_PROVIDER_CODES=clickpesa` only after the
  protected sandbox workflow passes and certification evidence is recorded.
- Keep `PEAK_FISCAL_PRODUCTION_APPROVED_PROVIDER_CODES` empty until a TRA vendor
  adapter is selected and certified. Contract mocks and the signed simulator
  cannot run under the production profile.
- Reject placeholder secrets and short passwords with `ops/scripts/validate-production-env.sh`.
- Store production secrets outside Git; `.env` is ignored and is only a local operator input file.
- Generate `PEAK_GUEST_IDENTITY_HASH_KEY` from at least 32 cryptographically
  random characters and retain its `PEAK_GUEST_IDENTITY_HASH_KEY_VERSION`
  during key rotation.
- Rotate by moving the former key/version into
  `PEAK_GUEST_IDENTITY_PREVIOUS_HASH_KEY` and
  `PEAK_GUEST_IDENTITY_PREVIOUS_HASH_KEY_VERSION`. Successful re-verification
  rewrites the fingerprint with the current key; remove the previous key only
  after outstanding documents have been re-verified.
- Keep `PEAK_NIDA_MODE=disabled` until NIDA supplies and approves the CIG
  sandbox contract. `simulator` and the incomplete `cig` mode fail production
  validation.
- While NIDA is disabled, only users with
  `guests.identity.manual_verify` may attest a physical document. Monitor
  `peak.guest.identity.verification` and the `nida` health contributor.

## Database Roles

- `peak_migrator`: Flyway migrations only.
- `peak_app`: tenant/public API runtime, member only of `pms_app`.
- `peak_platform`: isolated control-plane API runtime, member only of `pms_platform`.
- `peak_worker`: outbox worker runtime, member of `pms_worker`.
- `peak_platform_support`: optional readonly support login, member of `pms_readonly_support`.

The schema grants are owned by Flyway migrations. Add privilege changes as migrations, not manual grants.
Runtime grants and role-scoped RLS policies are validated by `RuntimeDatabaseRoleIntegrationTests`.

PostgreSQL 18 data volumes are mounted at `/var/lib/postgresql`, which preserves
the image's major-version-specific data layout. Upgrades across PostgreSQL major
versions require an explicit `pg_upgrade` or backup/restore procedure.

## Runtime Sizing

- API pool defaults: `PEAK_DB_POOL_MAX_SIZE=20`, `PEAK_DB_POOL_MIN_IDLE=5`.
- Hikari timeout values are milliseconds: connection `5000`, validation `2000`,
  idle `600000`, and max lifetime `1800000`.
- Worker pool defaults: `PEAK_WORKER_DB_POOL_MAX_SIZE=10`, `PEAK_WORKER_DB_POOL_MIN_IDLE=2`.
- Migration pool defaults: `PEAK_MIGRATION_DB_POOL_MAX_SIZE=2`, `PEAK_MIGRATION_DB_POOL_MIN_IDLE=0`.
- Outbox worker defaults: `PEAK_OUTBOX_WORKER_BATCH_SIZE=50`, `PEAK_OUTBOX_WORKER_MAX_PARALLELISM=4`.

Keep worker DB pool size greater than or equal to worker parallelism only when handlers require database access. Otherwise tune pool size to the database budget and provider latency.

## Backup And Restore Drill

Create a backup:

```sh
ops/scripts/backup-postgres.sh
ops/scripts/backup-keycloak.sh
```

Dry-run restore into a disposable environment before every production restore:

1. Start a disposable PostgreSQL container or a separate Podman Compose project.
2. Set `COMPOSE_FILE`, `ENV_FILE`, and `POSTGRES_DB` to the disposable target.
3. Run `ops/scripts/restore-postgres.sh backups/<backup>.sql.gz`. The restore
   idempotently recreates the non-superuser runtime roles before applying the
   dump and aborts on the first SQL error.
4. Restore the Keycloak backup with
   `ops/scripts/restore-keycloak.sh backups/<keycloak-backup>.sql.gz` against a
   disposable Keycloak database when identity recovery is in scope.
5. Run `ops/scripts/verify-keycloak-realm.sh` and `ops/scripts/smoke-test.sh`
   against the disposable environment after migrations are applied.

Never test restore against the live production database.

## Rollback

1. Set `PEAK_IMAGE` in `ops/production/.env` to the previous known-good SHA or release tag.
2. Run `ops/scripts/validate-production-env.sh ops/production/.env`.
3. If the failed release already ran irreversible migrations, stop and follow the migration failure procedure before starting old application code.
4. Run `podman compose --env-file ops/production/.env -f ops/production/compose.yaml up -d peak-api peak-platform peak-worker`.
5. Run `ops/scripts/smoke-test.sh`.

Prefer forward fixes for schema changes. Restore from backup only when data integrity is compromised and the business accepts the recovery point.

## Migration Failure Recovery

1. Keep API and worker stopped while the migration failure is investigated.
2. Inspect Flyway state: connect as `POSTGRES_MIGRATOR_USER` and query `flyway_schema_history` ordered by `installed_rank desc`.
3. If a migration failed before committing, fix the migration or environment and rerun `peak-migration`.
4. If a migration partially changed data outside a transaction, write an explicit repair migration; do not edit applied migration files.
5. Take a fresh backup before rerunning a repaired migration.
6. Start API and worker only after `peak-migration` exits successfully and `ops/scripts/smoke-test.sh` passes.

## Keycloak Realm Upgrade

1. Export the current realm from production and keep it with the release evidence.
2. Update `ops/keycloak/peak-realm.json` in a branch and review client IDs, redirect URIs, audience mapper, and required actions.
3. Validate JSON in CI and run `ops/scripts/verify-keycloak-realm.sh` against a staging Keycloak instance.
4. Deploy Keycloak changes before API changes that depend on new token claims.
5. Re-run `ops/scripts/verify-keycloak-realm.sh` after production rollout.

## Observability

The outbox worker emits counters tagged by destination:

- `peak.outbox.worker.claimed`
- `peak.outbox.worker.delivered`
- `peak.outbox.worker.failed`
- `peak.outbox.worker.dead_lettered`
- `peak.outbox.worker.reclaimed`

Guest identity emits:

- `peak.guest.identity.verification`, tagged by provider and result.
- `peak.guest.identity.provider.latency`, tagged by provider.
- `peak.frontdesk.checkin.identity`, tagged `ready` or `blocked`.
- The `nida` health contributor with mode and fallback state.

Alert on sustained delivery failures, any dead-letter growth, health-check
failures, database connection saturation, and Keycloak discovery/token
validation errors. For identity operations, alert when provider
`unavailable` outcomes persist for five minutes, manual fallback exceeds the
normal property baseline, blocked check-ins exceed ten in five minutes, or
provider p95 latency exceeds the configured read timeout.

Realtime alerts should include journal polling stalls, replay growth,
connection-limit rejections, send failures, and abrupt disconnect spikes.
The product dashboard, domain alert rules, and incident procedures are in
`ops/observability`. Payment/fiscal alerts include ClickPesa token failures,
poll backlog, checksum/webhook failures, reconciliation backlog, fiscal
corrections, POS variance, and night-audit blockers.
