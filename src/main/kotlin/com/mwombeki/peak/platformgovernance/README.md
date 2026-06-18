# Platform Governance Module

Platform governance owns platform-operator actions against tenant accounts. It does not own tenant profile persistence; it coordinates lifecycle state transitions for tenant accounts that already exist.

## Web API

- `POST /api/v1/platform/tenants/{id}/approve`
- `POST /api/v1/platform/tenants/{id}/suspend`

These routes are covered by the canonical `module_access_matrix` pattern `/api/platform/tenants*` after API version normalization.

## Security

Route authorization is enforced by the shared request context plus the user-management route guard. Platform governance requires a platform or support identity with the `platform.tenants.manage` permission.

The service binds `DatabaseSessionContext` before reading or writing RLS-protected tables.

## Database Tables

- `tenants`: canonical tenant account state.
- `tenant_lifecycle_events`: canonical lifecycle audit stream for tenant account transitions.

Do not write to `tenant_lifecycle_logs`; it was an obsolete duplicate table removed by migration.
