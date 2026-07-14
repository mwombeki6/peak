## Scope

- [ ] The change is limited to the stated module ownership.
- [ ] API, database, event, and provider contract changes are described.
- [ ] No secrets, raw identity numbers, tokens, or production `.env` values are included.

## Security And Data

- [ ] Every new `/api/**` route is registered in `module_access_matrix`.
- [ ] Tenant/property scope and permission boundaries have negative tests.
- [ ] Unsafe commands use idempotency, audit, and outbox where required.
- [ ] Migration is forward-only, preserves financial/audit history, and includes runtime-role grants.
- [ ] Logs, metrics, audit payloads, and errors avoid sensitive/high-cardinality values.

## Verification

- [ ] `./gradlew test` passes.
- [ ] Modulith boundary and route coverage tests pass.
- [ ] Clean Flyway migration validation passes.
- [ ] `git diff --check` passes.
- [ ] Podman Compose and production environment contract validate.
- [ ] Module inventory, OpenAPI baseline/client, API collection, and acceptance plan are updated.

## Rollout

- [ ] Backward compatibility and rollback impact are documented.
- [ ] Provider or Keycloak contract changes were validated in staging.
- [ ] Operational metrics, readiness, alerts, and smoke-test expectations are defined.
- [ ] The release uses a semantic `v1.x.y` product version and product-facing artifact names.
