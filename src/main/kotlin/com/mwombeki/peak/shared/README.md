# Shared Kernel

The shared module contains platform primitives that are safe for every module to depend on. It should stay small, stable, and free of business workflows.

## Key Components

### Request Context

`shared.context` owns request identity resolution and request-local metadata:

- `RequestContextResolver` converts authenticated JWT subjects, explicitly enabled trusted identity claims, or test-only trusted headers into a typed `RequestIdentity`.
- `RequestContextInterceptor` binds the context for the request lifecycle and attaches logging metadata.
- Request context captures a sanitized remote address and user agent so audit
  records can preserve request provenance without trusting forwarded identity.
- `DatabaseSessionContext` binds PostgreSQL `app.current_*` settings inside transactions for RLS-aware database access.
- Public identities may bind a tenant only after the tenant/property has been resolved from trusted database state.

Production JWTs are resolved by issuer and subject through `identity_links`. Direct `peak_identity_mode` JWT claims are disabled by default and must stay disabled in production. There must be only one request identity path. Do not add tenant-specific thread locals or servlet filters beside this system.

### Security

`shared.security` owns HTTP security headers, CORS, JWT validation wiring, and method-security enablement. Route authorization lives in `usermanagement` and uses the request context plus `module_access_matrix`.

`shared.outbound` validates payment and fiscal destinations against an exact,
operator-controlled DNS host allowlist before an HTTP transport opens a
connection. Tenant configuration cannot widen this allowlist.

### Runtime Configuration

`shared.config` owns typed runtime properties and production readiness validation. A production runtime must fail fast if:

- JWT validation is disabled.
- JWT issuer or audience is missing.
- CORS origins are not explicit.
- trusted header identity is enabled.
- trusted direct JWT identity claims are enabled.
- SpringDoc API docs or Swagger UI are enabled.
- the API or worker uses the migration database login.
- Flyway is enabled outside the dedicated migration runtime, or disabled in the migration runtime.
- the API runtime starts outbox workers.
- the worker or migration runtime exposes HTTP.
- the migration runtime starts outbox workers.
- the local communication delivery provider is enabled.
- route authorization or deny-unregistered route behavior is disabled.
- the envelope encryption key is not an environment-backed 256-bit key.
- a worker invitation URL is not HTTPS.
- the provider outbound-host allowlist is empty or contains unsafe hostnames.

### Utilities And Errors

`shared.util`, `shared.dto`, and `shared.exception` contain small cross-module utilities and error response helpers. Avoid putting module behavior here.

## Related Modules

Reliability primitives are not in `shared`. Use the published ports from `reliability::api`:

- `IdempotencyPort`
- `OutboxPort`
- `OutboxWorkerPort`

Audit primitives are not in `shared`. Use the published ports from `audit::api`.

## Rules

1. Keep shared code generic.
2. Prefer typed request identity over raw JWT claim access.
3. Bind database session settings only through `DatabaseSessionContext`.
4. Do not add idempotency, outbox, audit, or tenant workflow implementations to `shared`.
5. Keep production validation strict; unsafe local defaults belong only in `dev` and `test`.
6. Keep Flyway execution separated from API and worker runtimes.
7. Tune datasource pools per runtime profile; API, worker, and migration have different concurrency budgets.
