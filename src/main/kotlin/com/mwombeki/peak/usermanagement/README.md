# User Management Module

User management owns authorization, external identity resolution, platform administration, tenant user invitations, tenant user lifecycle, tenant role assignment, and tenant-managed property access administration. It is the permission boundary for platform and tenant-facing modules.

## Responsibilities

- Resolve external OIDC identities to active platform or tenant users.
- Enforce route authorization using `module_access_matrix`.
- Authorize static permissions through canonical role-permission tables.
- Manage platform users, platform roles, platform role assignments, platform permissions, and platform OIDC identity links through audited APIs.
- Support tenant invitations, identity links, dynamic tenant role CRUD, role assignment, role revocation, lock, unlock, disable, reactivate, and identity revocation.

## Access Control Model

- JWT claims identify the external subject; database state decides whether the subject is allowed.
- Normal Keycloak/OIDC tokens resolve through `identity_links` by issuer and subject; direct `peak_identity_mode` claims are disabled unless a trusted runtime explicitly enables them.
- Platform permissions are checked through platform roles and tenant access helpers.
- Tenant permissions are checked through tenant roles, role permissions, and active tenant user state.
- Tenant-created roles are dynamic but tenant-scoped; a tenant route cannot assign a role from another tenant.
- Tenant admins manage property access with `tenant.properties.manage_access`; actual property operations still require a property-scoped role assignment.
- Property roles are tenant-owned role templates in `roles`; `user_property_roles` scopes an assignment to one property.
- Dynamic role creation and update cannot grant permissions the acting user
  does not already hold.
- System tenant roles are immutable through tenant self-service. Assignment, revocation, and invitation flows must reject `tenant_roles.is_system=true`.
- Public property routes are resolved through `resolve_public_property_scope`; public headers are not trusted.
- Staff and platform guards bind database session context before permission checks; public module guards stay unbound.
- Route guard rules are cached briefly and refreshed from `module_access_matrix`.

## Platform Administration API

All routes below are platform-scoped and are covered by `module_access_matrix`. Narrow permissions such as `platform.users.view`, `platform.users.manage`, `platform.roles.view`, `platform.roles.manage`, `platform.permissions.view`, and `platform.identity_links.manage` are preferred. `platform.security.manage` remains a compatibility permission during the transition.

| Method | Route | Purpose |
| --- | --- | --- |
| `GET` | `/api/v1/platform/users` | List platform users, roles, and active identity-link counts. |
| `GET` | `/api/v1/platform/users/{platformUserId}` | View one platform user. |
| `POST` | `/api/v1/platform/users` | Create an invited or active platform user. |
| `PUT` | `/api/v1/platform/users/{platformUserId}` | Update platform user profile fields. |
| `POST` | `/api/v1/platform/users/{platformUserId}/lock` | Lock a platform user. |
| `POST` | `/api/v1/platform/users/{platformUserId}/disable` | Disable a platform user. |
| `POST` | `/api/v1/platform/users/{platformUserId}/reactivate` | Reactivate a platform user. |
| `POST` | `/api/v1/platform/users/{platformUserId}/roles/{platformRoleId}/assign` | Assign a platform role. |
| `POST` | `/api/v1/platform/users/{platformUserId}/roles/{platformRoleId}/revoke` | Revoke a platform role. |
| `POST` | `/api/v1/platform/users/{platformUserId}/identity-links` | Link an OIDC issuer/subject to a platform user. |
| `POST` | `/api/v1/platform/users/{platformUserId}/identity-links/{identityLinkId}/revoke` | Revoke a platform OIDC identity link. |
| `GET` | `/api/v1/platform/roles` | List platform roles and permissions. |
| `GET` | `/api/v1/platform/roles/{platformRoleId}` | View one platform role. |
| `POST` | `/api/v1/platform/roles` | Create a dynamic platform role. |
| `PUT` | `/api/v1/platform/roles/{platformRoleId}` | Update a dynamic platform role and its permissions. |
| `DELETE` | `/api/v1/platform/roles/{platformRoleId}` | Deactivate a dynamic platform role. |
| `GET` | `/api/v1/platform/permissions` | List immutable platform permissions. |
| `POST` | `/api/v1/platform/tenants/{tenantId}/administrators` | Provision the tenant's first administrator, immutable system role, permissions, and OIDC link. |
| `POST` | `/api/v1/platform/tenants/{tenantId}/profile/verify` | Verify the tenant business profile after platform review. |

Mutating platform administration routes require `Idempotency-Key`. Successful changes write a `platform_audit_logs` record and enqueue a platform outbox event. System platform roles cannot be modified, and an operator cannot lock, disable, assign roles to, or revoke roles from themselves.

The initial platform root is created once with the non-web `bootstrap` runtime. It requires a real Keycloak issuer/subject, writes an audit event, and closes after a platform user exists. Run `ops/scripts/bootstrap-platform.sh`; never seed platform identities with application SQL.

Metrics:

- `peak.platform.admin.command{operation,result}` counts succeeded, conflicting, and in-progress platform administration commands.

## Tenant Role Administration API

All tenant role routes are tenant-scoped, require `tenant.users.manage`, and are covered by `module_access_matrix`.

| Method | Route | Purpose |
| --- | --- | --- |
| `GET` | `/api/v1/tenants/{tenantId}/roles` | List tenant roles and their permissions. |
| `GET` | `/api/v1/tenants/{tenantId}/roles/{tenantRoleId}` | View one tenant role. |
| `POST` | `/api/v1/tenants/{tenantId}/roles` | Create a dynamic tenant role. |
| `PUT` | `/api/v1/tenants/{tenantId}/roles/{tenantRoleId}` | Update a dynamic tenant role and its permission set. |
| `DELETE` | `/api/v1/tenants/{tenantId}/roles/{tenantRoleId}` | Deactivate a dynamic tenant role and remove assignments. |
| `GET` | `/api/v1/tenants/{tenantId}/permissions` | List tenant permission codes available for tenant roles. |
| `POST` | `/api/v1/tenants/{tenantId}/users/{userId}/roles/{tenantRoleId}/assign` | Assign a dynamic tenant role to a tenant user. |
| `POST` | `/api/v1/tenants/{tenantId}/users/{userId}/roles/{tenantRoleId}/revoke` | Revoke a dynamic tenant role from a tenant user. |

Mutating tenant role routes require `Idempotency-Key`. Successful dynamic role definition changes write `audit_logs` entries and enqueue platform outbox events. Tenant system roles are read-only through tenant self-service, a tenant user cannot change their own role assignments, and delegated permissions cannot exceed the actor's effective permission set.

## Tenant-Managed Property Access API

These routes are tenant-scoped, require `tenant.properties.manage_access`, and validate that the property belongs to the tenant.

| Method | Route | Purpose |
| --- | --- | --- |
| `GET` | `/api/v1/tenants/{tenantId}/properties/{propertyId}/roles` | List tenant-owned property role templates. |
| `GET` | `/api/v1/tenants/{tenantId}/properties/{propertyId}/roles/{propertyRoleId}` | View one property role template. |
| `POST` | `/api/v1/tenants/{tenantId}/properties/{propertyId}/roles` | Create a mutable property role template. |
| `PUT` | `/api/v1/tenants/{tenantId}/properties/{propertyId}/roles/{propertyRoleId}` | Update a mutable property role template. |
| `DELETE` | `/api/v1/tenants/{tenantId}/properties/{propertyId}/roles/{propertyRoleId}` | Deactivate a mutable property role template and remove assignments. |
| `GET` | `/api/v1/tenants/{tenantId}/properties/{propertyId}/users/{userId}/roles` | List a user's roles for one property. |
| `POST` | `/api/v1/tenants/{tenantId}/properties/{propertyId}/users/{userId}/roles/{propertyRoleId}/assign` | Assign a property role to a tenant user for one property. |
| `POST` | `/api/v1/tenants/{tenantId}/properties/{propertyId}/users/{userId}/roles/{propertyRoleId}/revoke` | Revoke a property role from a tenant user for one property. |

Mutating property access routes require `Idempotency-Key`, write audit entries, and enqueue platform outbox events. System property roles are immutable through these APIs; property creation uses an internal bootstrap port to assign the creator the system Property Administrator role.

## Production Rules

1. Keep JWT validation enabled in production and require issuer plus audience.
2. Keep trusted direct JWT identity claims disabled in production; use database-backed OIDC identity links.
3. Require verified OIDC email before accepting invitation flows that bind an email.
4. Do not place permission decisions in controllers; use the authorization port or route guard.
5. Prefer database-backed role and permission data for business permissions, with code constants only for stable permission names.
6. Deny unregistered API routes by default in production.
7. Keep platform RLS policies scoped to platform runtime roles instead of granting platform helpers to tenant API roles.
8. Treat disabled, locked, deleted, or revoked OIDC identities as immediately unauthorized; do not cache positive identity resolution outside request scope.
9. Require idempotency, audit, and outbox side effects for mutating platform and tenant role administration commands.
10. Keep platform OIDC identity links in `identity_links`; do not add provider-specific identity tables.
11. Never return invitation bearer tokens from production administration APIs;
    deliver the encrypted token only through the invitation outbox handler.
