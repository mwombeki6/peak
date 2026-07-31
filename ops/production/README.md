# Production Operations

This directory contains the Podman Compose deployment baseline for Peak.

## Files

- `compose.yaml`: PostgreSQL, Keycloak, migration, one-shot platform bootstrap, API, and worker services.
- `.env.example`: required environment variables without real secrets.
- `role-bootstrap.sql`: creates production login roles and grants them membership in Flyway-managed no-login roles.
- `../keycloak/peak-platform-realm.json`: isolated platform-operator realm bootstrap template.
- `../keycloak/peak-hospitality-realm.json`: hotel staff, tenant administrator and POS realm bootstrap template.
- `../scripts/reconcile-keycloak-realms.py`: idempotently applies Peak-owned policy and client drift to existing realms.
- `../scripts/migrate-keycloak-legacy-realm.sh`: guarded, backup-first migration from the former `peak` realm.
- `../scripts/smoke-test.sh`: post-deploy API, Keycloak, SpringDoc, and anonymous-access smoke checks.

## Rollout Order

1. Build and push an image through CI, or build locally with `podman build -t ghcr.io/mwombeki6/peak/peak:<tag> .`.
2. Create `ops/production/.env` from `.env.example` and replace every placeholder secret.
3. Validate the env file: `ops/scripts/validate-production-env.sh ops/production/.env`.
4. Start PostgreSQL and Keycloak: `podman compose --env-file ops/production/.env -f ops/production/compose.yaml up -d postgres keycloak-db keycloak`.
5. Reconcile and verify both Keycloak realms. Override both URLs, not only the
   public one: `set -a; . ops/production/.env; set +a; KEYCLOAK_BASE_URL=http://localhost:8081 KEYCLOAK_ADMIN_BASE_URL=http://localhost:8081 python3 ops/scripts/reconcile-keycloak-realms.py && KEYCLOAK_BASE_URL=http://localhost:8081 KEYCLOAK_ADMIN_BASE_URL=http://localhost:8081 ops/scripts/verify-keycloak-realms.sh`.
   Administrative calls read `KEYCLOAK_ADMIN_BASE_URL`, which the env file points
   at the public administrative hostname. Overriding only `KEYCLOAK_BASE_URL`
   sends those calls back through the reverse proxy while the token comes from
   the container port, which fails, or silently addresses a different Keycloak
   than the one being reconciled.

   On a first installation no service account exists yet to authenticate with,
   so set `KEYCLOAK_ALLOW_BOOTSTRAP_ADMIN=true` for this step alone and start
   Keycloak in step 4 with `-f ops/production/compose.bootstrap-admin.yaml`
   layered in. Return it to `false` afterwards; production validation rejects it.

   Reconciliation creates a `peak-realm-reconciler` client in each realm whose
   secret Keycloak generates, because committing one to the realm templates
   would publish it. Read each secret from the admin console under Clients,
   `peak-realm-reconciler`, Credentials, and record it as
   `KEYCLOAK_RECONCILER_SECRET` so later runs authenticate as the service
   account rather than the bootstrap administrator. Each client also needs
   `manage-clients`, `view-clients` and `manage-authentication` from that realm's
   `realm-management` client, granted once per realm. Do not grant `realm-admin`.
6. Bootstrap production login roles: `ops/scripts/bootstrap-db-roles.sh`.
7. Start PostgreSQL and wait for its health check, then run Flyway through the migration profile: `podman compose --env-file ops/production/.env -f ops/production/compose.yaml --profile migration run --rm --no-deps peak-migration`. The `--no-deps` flag is required with Podman Compose so a one-shot migration does not reconcile or replace already-running services.
8. On the first installation only, create **two** emergency administrator
   custodians in Keycloak. Production requires both: appointing or revoking a
   root needs approval from two distinct custodians, so provisioning one would
   leave a window in which a single account could appoint another root
   unilaterally. They must have distinct emails and distinct Keycloak subjects.

   Set `PEAK_PLATFORM_BOOTSTRAP_ENABLED=true` and all eight identity values —
   `PEAK_PLATFORM_BOOTSTRAP_FULL_NAME`, `_EMAIL`, `_ISSUER`, `_SUBJECT` and
   `PEAK_PLATFORM_BOOTSTRAP_SECOND_FULL_NAME`, `_SECOND_EMAIL`,
   `_SECOND_ISSUER`, `_SECOND_SUBJECT` — then run
   `ops/scripts/bootstrap-platform.sh`. Both custodians are created in one
   transaction. Immediately set the flag back to `false` and clear all eight
   values. The command refuses to create a different root after platform
   initialization, and production readiness validation refuses to start the
   bootstrap runtime unless the four `second-*` values are present.
9. If every platform root has lost effective access, use the audited offline
   recovery procedure. Configure **two** replacement Keycloak identities, not
   one: recovery provisions custodians on the same terms as a first
   installation, so restoring a single root would recreate the window dual
   control exists to close. Set all eight identity values as in step 8, set both
   `PEAK_PLATFORM_BOOTSTRAP_ENABLED=true` and
   `PEAK_PLATFORM_RECOVERY_ENABLED=true`, then run
   `ops/scripts/recover-platform-root.sh`. The script refuses to start if any of
   the eight is missing, and recovery itself refuses to run if any effective root
   remains. Immediately disable both flags and clear all eight values after
   success.
10. Start tenant API, isolated platform API, and worker: `podman compose --env-file ops/production/.env -f ops/production/compose.yaml up -d peak-api peak-platform peak-worker`.
11. Configure a host reverse proxy to terminate TLS for the tenant API,
    platform API, `KEYCLOAK_HOSTNAME` and `KEYCLOAK_ADMIN_HOSTNAME`. Forward
    only to the corresponding loopback listeners. Preserve
    `X-Forwarded-For`, `X-Forwarded-Proto`, and `Host`, overwrite any
    client-supplied forwarding headers, and restrict direct listener access.
    Expose the Keycloak administration hostname only to the operator network.
    Never proxy the Keycloak management port `9000` publicly.
12. Verify the deployment: `ops/scripts/smoke-test.sh http://localhost:8080 http://localhost:8081 http://localhost:8082`.

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
- Import, reconcile and verify both Keycloak realms before allowing users to authenticate.
- Keep `PEAK_SECURITY_JWT_ISSUER_URI` on `peak-hospitality`,
  `PEAK_PLATFORM_JWT_ISSUER_URI` on `peak-platform`, and
  `PEAK_SECURITY_JWT_AUDIENCE=peak-api`. The isolated API runtimes must never
  trust both issuers.
- Keep platform TOTP enrollment mandatory. Passkeys remain an additional
  phishing-resistant option; do not weaken user verification or discoverable
  credential requirements.
- Keep browser/native clients public, authorization-code-only and PKCE S256.
  Never introduce a public-client secret, direct grant or wildcard redirect.
- Keycloak authenticates identities only. Keep tenants, properties, roles,
  permissions, entitlements, support access and RLS authoritative in Peak's
  database.
- Configure authenticated Keycloak SMTP with exactly one of STARTTLS or
  implicit SSL. Test verification and password-recovery mail before enabling
  users; do not route identity mail through tenant-configurable guest messaging.
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
- Keycloak database pool defaults: initial `5`, minimum `5`, maximum `20`.
- Keycloak rejects above `KEYCLOAK_HTTP_MAX_QUEUED_REQUESTS=1000` rather than
  accepting unbounded work, uses a graceful shutdown delay, and discovers
  cluster peers with the `jdbc-ping` cache stack.

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
   idempotently recreates the non-superuser runtime roles and the dedicated
   non-login tenant-continuity function owner before applying the dump, then
   aborts on the first SQL error.
4. Restore the Keycloak backup with
   `ops/scripts/restore-keycloak.sh backups/<keycloak-backup>.sql.gz` against a
   disposable Keycloak database when identity recovery is in scope.
5. Run `ops/scripts/verify-keycloak-realms.sh` and `ops/scripts/smoke-test.sh`
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

The baseline is Keycloak `26.7.0`, pinned by immutable multi-architecture image
digest. Read every intervening official upgrading guide before changing it.

1. Run the backup/restore drill and verify both realms in staging.
2. Update the image tag and digest together. Review realm policy, client IDs,
   redirects, audience mappers, required actions and release security notes.
3. If the former `peak` realm contains users, schedule a maintenance window and
   run the guarded migration before changing API issuers:

   ```sh
   KEYCLOAK_LEGACY_REALM_MIGRATION_APPROVED=true \
     ops/scripts/migrate-keycloak-legacy-realm.sh
   ```

   The command backs up both databases, stops authentication and application
   runtimes, exports the old realm offline, preserves subject identifiers and
   portable credentials, partitions users into the platform and hospitality
   realms using Peak's active identity links, and verifies the target users
   before changing any application data. The issuer cutover and its audit
   records commit atomically. The old realm is then disabled and a real smoke
   check must pass before the command succeeds. Temporary files containing
   credentials are mode-restricted and deleted on every exit. The command also
   refuses to disable the old realm while it contains an unmapped user or Peak
   references a subject absent from the export.

   Keycloak service accounts and federated identities intentionally stop the
   automated migration. Recreate service accounts as workload identities and
   migrate identity-provider/broker configuration explicitly before retrying.
4. Run the guarded upgrade:

   ```sh
   KEYCLOAK_UPGRADE_APPROVED=true ops/scripts/upgrade-keycloak.sh
   ```

5. The command takes a database backup, pulls the reviewed digest, starts the
   new version, reconciles both realms and verifies their live contracts. It
   deliberately does not start an older binary after a failed schema upgrade;
   use a forward fix or restore the recorded backup into a clean database.

The upgrade guard does not accept a manual confirmation bypass. It checks that
the old realm is disabled when legacy users remain and independently queries
Peak for zero active links using the old issuer.

Startup `--import-realm` creates missing realms but skips existing realms.
Never treat a successful restart as proof that policy changes were applied;
the reconciler and live verifier are required release gates.

Podman Compose is the single-host production-like baseline. A real production
deployment should run at least two Keycloak instances across failure domains
behind a health-aware load balancer, use the shared Keycloak PostgreSQL
database and `jdbc-ping`, drain nodes during rolling upgrades, and keep port
`9000` available only to internal health/metrics collectors.

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
