# Production Operations

This directory contains the Podman Compose deployment baseline for Peak phase 1.

## Files

- `compose.yaml`: PostgreSQL, Keycloak, migration, API, and worker services.
- `.env.example`: required environment variables without real secrets.
- `role-bootstrap.sql`: creates production login roles and grants them membership in Flyway-managed no-login roles.
- `../keycloak/peak-realm.json`: Keycloak realm export imported by the Keycloak container on first startup.

## Rollout Order

1. Build and push an image through CI, or build locally with `podman build -t ghcr.io/mwombeki6/peak/peak:<tag> .`.
2. Create `ops/production/.env` from `.env.example` and replace every placeholder secret.
3. Start PostgreSQL and Keycloak: `podman compose --env-file ops/production/.env -f ops/production/compose.yaml up -d postgres keycloak-db keycloak`.
4. Verify the imported Keycloak realm: `set -a; . ops/production/.env; set +a; KEYCLOAK_BASE_URL=http://localhost:8081 ops/scripts/verify-keycloak-realm.sh`.
5. Bootstrap production login roles: `ops/scripts/bootstrap-db-roles.sh`.
6. Run Flyway through the migration profile: `podman compose --env-file ops/production/.env -f ops/production/compose.yaml --profile migration run --rm peak-migration`.
7. Start API and worker: `podman compose --env-file ops/production/.env -f ops/production/compose.yaml up -d peak-api peak-worker`.
8. Verify health: `ops/scripts/healthcheck.sh http://localhost:8080/actuator/health`.

## Security Requirements

- Never run API or worker as the migration login.
- Keep Flyway disabled for API and worker; only `peak-migration` runs migrations.
- Keep `PEAK_ALLOW_HEADER_IDENTITY=false` in production.
- Keep SpringDoc disabled in production.
- Keep CORS origins explicit.
- Import and verify the Keycloak realm before allowing tenant users to authenticate.
- Keep `PEAK_SECURITY_JWT_ISSUER_URI` equal to the Keycloak realm issuer and `PEAK_SECURITY_JWT_AUDIENCE=peak-api`.
- Store production secrets outside Git; `.env` is ignored and is only a local operator input file.

## Database Roles

- `peak_migrator`: Flyway migrations only.
- `peak_app`: API runtime, member of `pms_app` and `pms_platform`.
- `peak_worker`: outbox worker runtime, member of `pms_worker`.
- `peak_platform_support`: optional readonly support login, member of `pms_readonly_support`.

The schema grants are owned by Flyway migrations. Add privilege changes as migrations, not manual grants.
Runtime grants and role-scoped RLS policies are validated by `RuntimeDatabaseRoleIntegrationTests`.
