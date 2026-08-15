# User Management Module

User management owns authorization, external identity resolution, platform administration, tenant user invitations, tenant user lifecycle, tenant role assignment, and tenant-managed property access administration. It is the permission boundary for platform and tenant-facing modules.

## Responsibilities

- Resolve external OIDC identities to active platform or tenant users.
- Enforce route authorization using `module_access_matrix`.
- Authorize static permissions through canonical role-permission tables.
- Manage platform users, platform roles, platform role assignments, platform permissions, and platform OIDC identity links through audited APIs.
- Support tenant invitations, identity links, dynamic tenant role CRUD, administrator succession, role assignment, role revocation, lock, unlock, disable, reactivate, and identity revocation.

## Access Control Model

- JWT claims identify the external subject; database state decides whether the subject is allowed.
- Normal Keycloak/OIDC tokens resolve through `identity_links` by issuer and subject; direct `peak_identity_mode` claims are disabled unless a trusted runtime explicitly enables them.
- Platform permissions are checked through platform roles and tenant access helpers.
- Tenant permissions are checked through tenant roles, role permissions, and active tenant user state.
- Tenant-created roles are dynamic but tenant-scoped; their catalog permissions must have `tenant` or `both` scope, and a tenant route cannot assign a role from another tenant.
- Tenant administrator succession uses the dedicated `tenant.administrators.manage` capability; the platform provisioning endpoint remains the supervised recovery path when no tenant administrator can sign in.
- Tenant admins manage ordinary property access with `tenant.properties.manage_access`; actual property operations still require a property-scoped role assignment.
- The tenant owns its properties and governs administrator continuity with the narrower, auditable `tenant.properties.administrators.manage` capability.
- Property roles are tenant-owned role templates in `roles`; `user_property_roles` scopes an assignment to one property.
- Dynamic role creation and update cannot grant permissions the acting user
  does not already hold. Mutating an existing role or user also requires the
  actor to hold the target's current effective permission set.
- System tenant roles are immutable through tenant self-service. Assignment, revocation, and invitation flows must reject `tenant_roles.is_system=true`.
- Administrator wildcards are reserved database invariants: `platform.admin.all`, `tenant.admin.all`, and `admin.all` may be granted only to their corresponding system roles. Dynamic roles cannot inherit or preserve them.
- Public property routes are resolved through `resolve_public_property_scope`; public headers are not trusted.
- Staff and platform guards bind database session context before permission checks; public module guards stay unbound. Named administration APIs repeat their required platform or tenant capability check so in-process callers cannot bypass the HTTP guard.
- Route guard rules are cached briefly and refreshed from `module_access_matrix`.
- Support identities are never treated as unrestricted platform identities. They may use only tenant-targeted platform routes whose `tenantId`, exact support-session id, permission, approval state, and active time window all match `platform_break_glass_access`.
- Equally specific route rows must resolve to one authorization contract. Conflicting permissions, scopes, modules, or guard modes fail startup validation and fail closed at request time.

## Platform Administration API

All routes below are platform-scoped and are covered by `module_access_matrix`. Narrow permissions such as `platform.users.view`, `platform.users.manage`, `platform.roles.view`, `platform.roles.manage`, `platform.permissions.view`, and `platform.identity_links.manage` are preferred. `platform.security.manage` remains a compatibility permission during the transition.

| Method | Route | Purpose |
| --- | --- | --- |
| `GET` | `/api/v1/platform/users` | List platform users, roles, and active identity-link counts. |
| `GET` | `/api/v1/platform/users/{platformUserId}` | View one platform user. |
| `GET` | `/api/v1/platform/administrators` | List system platform administrators and effective sign-in state. |
| `POST` | `/api/v1/platform/administrators/{platformUserId}/assign` | Appoint an active platform user as a platform administrator. |
| `POST` | `/api/v1/platform/administrators/{platformUserId}/revoke` | Revoke root access only after another effective administrator exists. |
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
| `POST` | `/api/v1/platform/tenants/{tenantId}/administrators` | Provision an initial or recovery administrator, immutable system role, permissions, and OIDC link. |
| `POST` | `/api/v1/platform/tenants/{tenantId}/profile/verify` | Verify the tenant business profile after platform review. |

Mutating platform administration routes require `Idempotency-Key`. Successful changes write a `platform_audit_logs` record and enqueue a platform outbox event. System platform roles cannot be modified or assigned through ordinary role routes, and an operator cannot lock, disable, assign roles to, revoke roles from, link identities for, or revoke identity links from themselves. Dynamic platform role creation, update, deactivation, assignment, and revocation cannot manage permissions above the actor's effective platform permission set. Platform user profile, lifecycle, role assignment, role revocation, identity-link creation, and identity-link revocation also require the actor to hold the target user's current effective platform permissions; `platform.admin.all` satisfies this hierarchy check.

Platform-root succession is the controlled exception for a system platform-role assignment. Dedicated routes require `platform.administrators.manage`, serialize all root-removal paths with a transaction-scoped PostgreSQL advisory lock, and reject revocation, disabling, locking, or final identity unlink when it would leave no other effective root. A root is effective only when the system role is active and the user is active, unlocked, undeleted, and has an unrevoked platform OIDC identity.

The initial platform root is created once with the non-web `bootstrap` runtime. It requires a real Keycloak issuer/subject, writes an audit event, and closes after a platform user exists. Run `ops/scripts/bootstrap-platform.sh`; never seed platform identities with application SQL. If every root is already ineffective, an operator with database and deployment authority may run `ops/scripts/recover-platform-root.sh` with both explicit flags enabled. Recovery refuses to run while any effective root can sign in, reactivates or creates the named operator, restores the immutable root role and current permissions, links the supplied OIDC identity, and records `platform.recovery.completed`.

Metrics:

- `peak.platform.admin.command{operation,result}` counts succeeded, conflicting, and in-progress platform administration commands.

## Tenant Role Administration API

Tenant role routes are tenant-scoped and covered by `module_access_matrix`. Reads require `tenant.roles.view`; mutations and user assignment changes require `tenant.users.manage`.

| Method | Route | Permission | Purpose |
| --- | --- | --- | --- |
| `GET` | `/api/v1/tenants/{tenantId}/roles` | `tenant.roles.view` | List tenant roles and their permissions. |
| `GET` | `/api/v1/tenants/{tenantId}/roles/{tenantRoleId}` | `tenant.roles.view` | View one tenant role. |
| `POST` | `/api/v1/tenants/{tenantId}/roles` | `tenant.users.manage` | Create a dynamic tenant role. |
| `PUT` | `/api/v1/tenants/{tenantId}/roles/{tenantRoleId}` | `tenant.users.manage` | Update a dynamic tenant role and its permission set. |
| `DELETE` | `/api/v1/tenants/{tenantId}/roles/{tenantRoleId}` | `tenant.users.manage` | Deactivate a dynamic tenant role and remove assignments. |
| `GET` | `/api/v1/tenants/{tenantId}/permissions` | `tenant.roles.view` | List tenant permission codes available for tenant roles. |
| `POST` | `/api/v1/tenants/{tenantId}/users/{userId}/roles/{tenantRoleId}/assign` | `tenant.users.manage` | Assign a dynamic tenant role to a tenant user. |
| `POST` | `/api/v1/tenants/{tenantId}/users/{userId}/roles/{tenantRoleId}/revoke` | `tenant.users.manage` | Revoke a dynamic tenant role from a tenant user. |
| `GET` | `/api/v1/tenants/{tenantId}/administrators` | `tenant.roles.view` | List system tenant administrators and their effective account/identity state. |
| `POST` | `/api/v1/tenants/{tenantId}/administrators/{userId}/assign` | `tenant.administrators.manage` | Appoint an active tenant user as a tenant administrator. |
| `POST` | `/api/v1/tenants/{tenantId}/administrators/{userId}/revoke` | `tenant.administrators.manage` | Revoke an administrator only after another effective administrator exists. |

Mutating tenant role routes require `Idempotency-Key`. Successful dynamic role definition changes write `audit_logs` entries and enqueue platform outbox events. Tenant system role definitions remain read-only through ordinary tenant role APIs, a tenant user cannot change their own ordinary role assignments, and delegated permissions cannot exceed the actor's effective permission set. Updating, deactivating, assigning, or revoking a role also requires the actor to hold the role's current permission set.

Tenant administrator assignment is the single controlled exception for a system tenant-role assignment. It has dedicated routes and permission checks, serializes succession on the tenant, prevents self-revocation, allows self-assignment only to an existing `tenant.admin.all` holder, and never permits removal of the last effective administrator. A replacement is effective only when the user is active, unlocked, undeleted, and has an unrevoked tenant OIDC identity link.

Tenant user lifecycle and identity-link revocation routes also require `tenant.users.manage`. The actor cannot disable, lock, reactivate, unlock, or revoke identity links for a user whose effective tenant or property permissions exceed the actor's own effective permissions, unless the actor holds `tenant.admin.all`. Disabling or locking a tenant/property administrator, or revoking that user's final active tenant identity link, is rejected when it would leave the tenant or any property without another active administrator who can sign in.

Tenant invitations only create new tenant users. They cannot reactivate or relink an existing active, invited, locked, or disabled account; existing accounts must use the lifecycle and identity-link administration routes. Acceptance revalidates that the invited dynamic role is still active and assignable.

Frontline staff are not invited through Keycloak. `POST /api/v1/tenants/{tenantId}/staff` (`tenant.users.manage`, strong session) creates a user with no email, allocates `staff_number`, assigns an operational property role, and issues a PIN activation. When `phoneNumber` is present the secret is enqueued as SMS (`staff.credential.activation.issued`); when it is absent the one-time secret is returned once so a manager can hand it over in person. `POST /api/v1/staff/credentials/activate` is public: staff number, secret, and the PIN the staff member chose. A role that contains any strong permission is refused — that person still needs an email invitation. PIN login (`POST /staff/sessions`) is for POS cashiers and other till/frontline staff. It is not used to create a tenant, pay Peak, or activate a property; owners stay on Keycloak.

## Tenant-Managed Property Access API

These routes are tenant-scoped and validate that the property belongs to the tenant. Reads require `tenant.properties.roles.view`; mutations and assignments require `tenant.properties.manage_access`.

| Method | Route | Permission | Purpose |
| --- | --- | --- | --- |
| `GET` | `/api/v1/tenants/{tenantId}/properties/{propertyId}/roles` | `tenant.properties.roles.view` | List tenant-owned property role templates. |
| `GET` | `/api/v1/tenants/{tenantId}/properties/{propertyId}/roles/{propertyRoleId}` | `tenant.properties.roles.view` | View one property role template. |
| `POST` | `/api/v1/tenants/{tenantId}/properties/{propertyId}/roles` | `tenant.properties.manage_access` | Create a mutable property role template. |
| `PUT` | `/api/v1/tenants/{tenantId}/properties/{propertyId}/roles/{propertyRoleId}` | `tenant.properties.manage_access` | Update a mutable property role template. |
| `DELETE` | `/api/v1/tenants/{tenantId}/properties/{propertyId}/roles/{propertyRoleId}` | `tenant.properties.manage_access` | Deactivate a mutable property role template and remove assignments. |
| `GET` | `/api/v1/tenants/{tenantId}/properties/{propertyId}/users/{userId}/roles` | `tenant.properties.roles.view` | List a user's roles for one property. |
| `POST` | `/api/v1/tenants/{tenantId}/properties/{propertyId}/users/{userId}/roles/{propertyRoleId}/assign` | `tenant.properties.manage_access` | Assign a property role to a tenant user for one property. |
| `POST` | `/api/v1/tenants/{tenantId}/properties/{propertyId}/users/{userId}/roles/{propertyRoleId}/revoke` | `tenant.properties.manage_access` | Revoke a property role from a tenant user for one property. |
| `GET` | `/api/v1/tenants/{tenantId}/properties/{propertyId}/administrators` | `tenant.properties.roles.view` | List system property administrators and their effective account/identity state. |
| `POST` | `/api/v1/tenants/{tenantId}/properties/{propertyId}/administrators/{userId}/assign` | `tenant.properties.administrators.manage` | Appoint an active tenant user as a property administrator. |
| `POST` | `/api/v1/tenants/{tenantId}/properties/{propertyId}/administrators/{userId}/revoke` | `tenant.properties.administrators.manage` | Revoke an administrator only after another effective administrator exists. |

Mutating property access routes require `Idempotency-Key`, write audit entries, and enqueue platform outbox events. Dynamic property role creation, update, deactivation, assignment, and revocation cannot delegate or manage permissions above the actor's effective tenant/property permission set. System property role definitions remain immutable through ordinary role APIs. Property creation uses an internal bootstrap port to assign the creator the system Property Administrator role; that port requires the caller's tenant identity to match the creator and independently verifies `property.manage`.

Administrator assignment is the single controlled exception for a system-role assignment. It has dedicated routes and permission checks, serializes changes on the property, prevents self-revocation, allows self-assignment only to `tenant.admin.all`, and never permits removal of the last effective administrator. A replacement counts as effective only when the user is active, unlocked, undeleted, and has an unrevoked tenant OIDC identity link.

## Production Rules

1. Keep JWT validation enabled in production and require issuer plus audience.
2. Keep trusted direct JWT identity claims disabled in production; use database-backed OIDC identity links.
3. Require verified OIDC email before accepting invitation flows that bind an email.
4. Do not place permission decisions in controllers; use the authorization port or route guard, and enforce the same base capability inside named administration APIs.
5. Prefer database-backed role and permission data for business permissions, with code constants only for stable permission names.
6. Deny unregistered API routes by default in production.
7. Keep platform RLS policies scoped to platform runtime roles instead of granting platform helpers to tenant API roles.
8. Treat disabled, locked, deleted, or revoked OIDC identities as immediately unauthorized; do not cache positive identity resolution outside request scope.
9. Require idempotency, audit, and outbox side effects for mutating platform and tenant role administration commands.
10. Keep platform OIDC identity links in `identity_links`; do not add provider-specific identity tables.
11. Never return invitation bearer tokens from production administration APIs;
    deliver the encrypted token only through the invitation outbox handler.
12. Keep one effective permission contract per HTTP method and route pattern; migrate grants before retiring a legacy permission row.
