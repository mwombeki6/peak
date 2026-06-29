# Tenant Management Module

Tenant management owns platform-led tenant onboarding, tenant account reads, tenant module enablement, and tenant readiness checks. It persists to the canonical foundation schema instead of maintaining module-specific tenant tables.

## Platform Web API

These routes require a platform identity and the corresponding platform tenant permissions:

| Method | Route | Purpose |
| --- | --- | --- |
| `POST` | `/api/v1/platform/tenants` | Register a tenant and canonical business profile. |
| `GET` | `/api/v1/platform/tenants/{id}` | View tenant account and profile state. |
| `PATCH` | `/api/v1/platform/tenants/{id}/status` | Change tenant lifecycle status. |
| `POST` | `/api/v1/platform/tenants/{id}/administrators` | Provision the first tenant administrator and OIDC identity. |
| `POST` | `/api/v1/platform/tenants/{id}/profile/verify` | Mark a reviewed business profile as verified. |

The platform routes are covered by the canonical `module_access_matrix` pattern `/api/platform/tenants*` after API version normalization.

Tenant registration and administrator provisioning are separate idempotent operations. Provisioning creates the active tenant user, immutable `tenant_admin` role, complete tenant permission grant, role assignment, `tenant_admin` module enablement, and DB-backed OIDC link atomically.

## Tenant Administration API

These routes require a tenant identity and route-guard permission coverage:

| Method | Route | Permission | Purpose |
| --- | --- | --- | --- |
| `GET` | `/api/v1/tenants/{tenantId}/modules` | `module.manage` | List tenant-level module enablement state. |
| `POST` | `/api/v1/tenants/{tenantId}/modules` | `module.manage` | Enable an active tenant-visible module. |
| `DELETE` | `/api/v1/tenants/{tenantId}/modules/{moduleId}` | `module.manage` | Disable a tenant-level module without deleting configuration history. |
| `GET` | `/api/v1/tenants/{tenantId}/readiness` | `tenant.profile.view` | Return readiness status and missing requirements. |

Mutating module routes require `Idempotency-Key`. Successful changes write tenant audit entries, enqueue platform outbox events, and emit `peak.tenant.module.command{operation,result}` metrics.

## Readiness Rules

Tenant readiness is computed from real state. A tenant is ready only when:

- the tenant account is `trial` or `active`;
- the business profile is verified and has business phone and email;
- an active owner/managing director, authorized signatory, or primary contact exists;
- an enabled operational report recipient has active consent on a verified channel;
- the `tenant_admin` module is enabled.

## Security

The controllers rely on the request-context and route-guard pipeline. Platform services require a platform or support identity. Tenant administration services require a tenant identity whose tenant id matches the route, then bind `DatabaseSessionContext` before RLS-protected database access.

Tenant module changes are idempotent, audited, and outbox-backed. Module ids are validated against active `module_catalog` rows, and disabled or mismatched tenant identities are rejected before mutation.

Normal onboarding requires no SQL. A platform operator registers the tenant, provisions its administrator, verifies the reviewed profile, and the tenant administrator completes modules, contacts, consent, report recipients, and property setup through APIs.

## Database Tables

- `tenants`: tenant account, slug, status, schema name, and subscription plan.
- `tenant_profiles`: legal/business profile for the tenant.
- `tenant_modules`: tenant-level module enablement and configuration state.
- `tenant_lifecycle_events`: onboarding lifecycle event emitted when a tenant is created or status changes.
- `tenant_contacts`, `tenant_contact_roles`, `contact_channels`, `communication_consents`: readiness contact and consent inputs.
- `report_subscriptions`, `report_subscription_recipients`: readiness operational report recipient inputs.

Supported tenant status values come from the baseline schema: `trial`, `active`, `suspended`, `frozen`, `archived`, `terminated`, and `cancelled`.
