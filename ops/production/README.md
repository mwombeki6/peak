# Production Operations

This directory contains the Podman Compose deployment baseline for Peak phase 1.

## Files

- `compose.yaml`: PostgreSQL, Keycloak, migration, API, and worker services.
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
7. Run Flyway through the migration profile: `podman compose --env-file ops/production/.env -f ops/production/compose.yaml --profile migration run --rm peak-migration`.
8. Start API and worker: `podman compose --env-file ops/production/.env -f ops/production/compose.yaml up -d peak-api peak-worker`.
9. Verify the deployment: `ops/scripts/smoke-test.sh http://localhost:8080 http://localhost:8081`.

The standard deploy script performs steps 3, 7, 8, and 9:

```sh
ops/scripts/deploy.sh
```

## Security Requirements

- Never run API or worker as the migration login.
- Keep Flyway disabled for API and worker; only `peak-migration` runs migrations.
- Keep `PEAK_ALLOW_HEADER_IDENTITY=false` in production.
- Keep `PEAK_ALLOW_TRUSTED_JWT_IDENTITY_CLAIMS=false` in production.
- Keep SpringDoc disabled in production.
- Keep CORS origins explicit.
- Import and verify the Keycloak realm before allowing tenant users to authenticate.
- Keep `PEAK_SECURITY_JWT_ISSUER_URI` equal to the Keycloak realm issuer and `PEAK_SECURITY_JWT_AUDIENCE=peak-api`.
- Resolve tenant and platform users through active OIDC `identity_links`; do not rely on client-editable user attributes for authorization.
- Keep payment provider URLs and credentials out of checked-in YAML. Production payment providers are supplied through environment variables and must use HTTPS.
- Reject placeholder secrets and short passwords with `ops/scripts/validate-production-env.sh`.
- Store production secrets outside Git; `.env` is ignored and is only a local operator input file.

## Database Roles

- `peak_migrator`: Flyway migrations only.
- `peak_app`: API runtime, member of `pms_app` and `pms_platform`.
- `peak_worker`: outbox worker runtime, member of `pms_worker`.
- `peak_platform_support`: optional readonly support login, member of `pms_readonly_support`.

The schema grants are owned by Flyway migrations. Add privilege changes as migrations, not manual grants.
Runtime grants and role-scoped RLS policies are validated by `RuntimeDatabaseRoleIntegrationTests`.

## Runtime Sizing

- API pool defaults: `PEAK_DB_POOL_MAX_SIZE=20`, `PEAK_DB_POOL_MIN_IDLE=5`.
- Worker pool defaults: `PEAK_WORKER_DB_POOL_MAX_SIZE=10`, `PEAK_WORKER_DB_POOL_MIN_IDLE=2`.
- Migration pool defaults: `PEAK_MIGRATION_DB_POOL_MAX_SIZE=2`, `PEAK_MIGRATION_DB_POOL_MIN_IDLE=0`.
- Outbox worker defaults: `PEAK_OUTBOX_WORKER_BATCH_SIZE=50`, `PEAK_OUTBOX_WORKER_MAX_PARALLELISM=4`.

Keep worker DB pool size greater than or equal to worker parallelism only when handlers require database access. Otherwise tune pool size to the database budget and provider latency.

## Backup And Restore Drill

Create a backup:

```sh
ops/scripts/backup-postgres.sh
```

Dry-run restore into a disposable environment before every production restore:

1. Start a disposable PostgreSQL container or a separate Podman Compose project.
2. Set `COMPOSE_FILE`, `ENV_FILE`, and `POSTGRES_DB` to the disposable target.
3. Run `ops/scripts/restore-postgres.sh backups/<backup>.sql.gz`.
4. Run `ops/scripts/smoke-test.sh` against the disposable API after migrations are applied.

Never test restore against the live production database.

## Rollback

1. Set `PEAK_IMAGE` in `ops/production/.env` to the previous known-good SHA or release tag.
2. Run `ops/scripts/validate-production-env.sh ops/production/.env`.
3. If the failed release already ran irreversible migrations, stop and follow the migration failure procedure before starting old application code.
4. Run `podman compose --env-file ops/production/.env -f ops/production/compose.yaml up -d peak-api peak-worker`.
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

Alert on sustained delivery failures, any dead-letter growth, health-check failures, database connection saturation, and Keycloak discovery/token validation errors.
