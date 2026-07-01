# Peak Phase 1 Regression Test Plan

This is the maintained Phase 1 security/runtime regression gate. Business-flow
acceptance belongs to the Phase 2 and Phase 3 plans.

## Runtime Gate

1. Validate `ops/production/.env` with
   `ops/scripts/validate-production-env.sh`.
2. Start PostgreSQL and Keycloak with Podman.
3. Run `bootstrap-db-roles.sh`, then the one-shot migration runtime.
4. Run the platform bootstrap once on a new environment.
5. Start API and worker as separate services.
6. Run `ops/scripts/verify-keycloak-realm.sh`.
7. Run `ops/scripts/smoke-test.sh`.

Expected:

- API liveness and readiness are `UP`.
- Readiness includes database, Keycloak discovery, worker heartbeat, and
  realtime journal.
- API uses `peak_app`, worker uses `peak_worker`, and only migration/bootstrap
  use the migrator login.
- Worker and migration runtimes expose no HTTP server.
- SpringDoc is unavailable in production.

## Authentication And Authorization

1. Use a Keycloak authorization-code/PKCE access token with `aud=peak-api`.
2. Verify unknown issuer, missing audience, expired, and unsigned tokens are
   rejected.
3. Verify an unlinked valid OIDC subject has public identity only.
4. Link the subject through an administration API and verify DB-backed
   platform/tenant resolution.
5. Lock, disable, and revoke the identity link in turn; access must stop on the
   next request.
6. Verify anonymous, platform, tenant, cross-tenant, and cross-property
   boundaries against real controller routes.
7. Verify every `/api/**` route is registered in `module_access_matrix` unless
   explicitly public/system.

Production must keep:

```text
PEAK_ALLOW_HEADER_IDENTITY=false
PEAK_ALLOW_TRUSTED_JWT_IDENTITY_CLAIMS=false
PEAK_SECURITY_JWT_AUDIENCE=peak-api
```

## Permission Model

- System roles cannot be edited by tenant self-service.
- Dynamic roles cannot contain permissions the acting user does not hold.
- Users cannot assign or revoke their own roles.
- Revoked assignments take effect on the next request.
- Platform users do not receive tenant/property access implicitly.
- Public routes cannot derive tenant/property scope from trusted headers.

## Reliability

For every unsafe administration command:

1. Omit `Idempotency-Key`; expect a client error.
2. Send one key/payload twice; expect one mutation and a replayed result.
3. Reuse the key with another payload; expect conflict.
4. Verify mutation, audit, idempotency result, and outbox event commit
   atomically.
5. Run two workers and verify `SKIP LOCKED` claim behavior prevents concurrent
   delivery.
6. Verify retry, stale-lock reclaim, dead-letter, and reference-safe
   idempotency cleanup.

## Public Surface

The legacy V1 public booking and public payment integration controllers are
disabled and must return no routable API. Payment provider callbacks use only:

```text
POST /api/v1/payments/webhooks/{providerAccountId}
```

Callback scope is resolved from the database provider account. Signatures cover
`timestamp + "." + rawBody`, have a five-minute window, and use unique provider
event ids.

## Evidence

Retain:

- tested image SHA;
- complete Gradle test report;
- CI and container scan results;
- Keycloak verification output;
- migration version;
- liveness/readiness output;
- representative correlation ids for authorization, audit, outbox, and safe
  error responses.
