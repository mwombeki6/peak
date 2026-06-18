# Shared Kernel

The shared module contains platform primitives that are safe for every module to depend on. It should stay small, stable, and free of business workflows.

## Key Components

### Request Context

`shared.context` owns request identity resolution and request-local metadata:

- `RequestContextResolver` converts trusted headers or JWT claims into a typed `RequestIdentity`.
- `RequestContextInterceptor` binds the context for the request lifecycle and attaches logging metadata.
- `DatabaseSessionContext` binds PostgreSQL `app.current_*` settings inside transactions for RLS-aware database access.

There must be only one request identity path. Do not add tenant-specific thread locals or servlet filters beside this system.

### Security

`shared.security` owns HTTP security headers, CORS, JWT validation wiring, and method-security enablement. Route authorization lives in `usermanagement` and uses the request context plus `module_access_matrix`.

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
