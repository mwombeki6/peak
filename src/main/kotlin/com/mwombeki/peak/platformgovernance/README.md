# Platform Governance Module

Platform governance owns platform-operator actions against tenant accounts. It does not own tenant profile persistence; it coordinates lifecycle state transitions for tenant accounts that already exist.

## Web API

- `POST /api/v1/platform/tenants/{id}/approve`
- `POST /api/v1/platform/tenants/{id}/suspend`

These routes have exact `module_access_matrix` contracts with a named `tenantId` after API version normalization. The broader tenant-management fallback does not decide their authorization.

## Security

Route authorization is enforced by the shared request context plus the user-management route guard. The named governance API independently requires `platform.tenants.manage` through the User Management API. A support identity additionally needs the exact active, approved break-glass session for the target tenant; it cannot use global platform routes or a different session belonging to the same operator.

The shared platform access port binds `DatabaseSessionContext` before the service reads or writes RLS-protected tables.

## Database Tables

- `tenants`: canonical tenant account state.
- `tenant_lifecycle_events`: canonical lifecycle audit stream for tenant account transitions.

Do not write to `tenant_lifecycle_logs`; it was an obsolete duplicate table removed by migration.
