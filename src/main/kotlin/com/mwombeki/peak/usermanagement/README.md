# User Management Module

User management owns authorization, external identity resolution, tenant user invitations, tenant user lifecycle, and tenant role assignment. It is the permission boundary for platform and tenant-facing modules.

## Responsibilities

- Resolve external OIDC identities to active platform or tenant users.
- Enforce route authorization using `module_access_matrix`.
- Authorize static permissions through canonical role-permission tables.
- Support tenant invitations, identity links, role assignment, role revocation, lock, unlock, disable, reactivate, and identity revocation.

## Access Control Model

- JWT claims identify the external subject; database state decides whether the subject is allowed.
- Platform permissions are checked through platform roles and tenant access helpers.
- Tenant permissions are checked through tenant roles, role permissions, and active tenant user state.
- Public property routes are resolved through `resolve_public_property_scope`; public headers are not trusted.
- Route guard rules are cached briefly and refreshed from `module_access_matrix`.

## Production Rules

1. Keep JWT validation enabled in production and require issuer plus audience.
2. Require verified OIDC email before accepting invitation flows that bind an email.
3. Do not place permission decisions in controllers; use the authorization port or route guard.
4. Prefer database-backed role and permission data for business permissions, with code constants only for stable permission names.
5. Deny unregistered API routes by default in production.
