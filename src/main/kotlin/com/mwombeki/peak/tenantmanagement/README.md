# Tenant Management Module

Tenant management owns platform-led tenant onboarding and tenant account reads. It persists to the canonical foundation schema instead of maintaining module-specific tenant tables.

## Web API

- `POST /api/v1/platform/tenants`
- `GET /api/v1/platform/tenants/{id}`
- `PATCH /api/v1/platform/tenants/{id}/status`

These routes are covered by the canonical `module_access_matrix` pattern `/api/platform/tenants*` after API version normalization.

## Security

The controller relies on the request-context and route-guard pipeline. The service requires a platform or support identity and binds `DatabaseSessionContext` before RLS-protected database access.

## Database Tables

- `tenants`: tenant account, slug, status, schema name, and subscription plan.
- `tenant_profiles`: legal/business profile for the tenant.
- `tenant_lifecycle_events`: onboarding lifecycle event emitted when a tenant is created or status changes.

Supported tenant status values come from the baseline schema: `trial`, `active`, `suspended`, `frozen`, `archived`, `terminated`, and `cancelled`.
