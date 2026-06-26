# Peak Phase 2 End-to-End Test Plan

Audience: Engineer A and Engineer B

Purpose: verify Phase 2 production APIs for platform administration, tenant administration, property setup, communication, realtime streams, and cross-cutting security controls.

## Exit Target

Phase 2 passes when a platform operator and tenant admin can configure a hotel property, users, permissions, identity links, communication channels, and realtime event streams through APIs without normal manual SQL.

## Required Tools

- Podman and Podman Compose
- Postman or `curl`
- `jq`
- `psql` for verification and emergency inspection only

## Environment

Use the production-like local runtime from `ops/production/README.md`.

Minimum checks before API testing:

```bash
./gradlew test bootJar
ops/scripts/validate-production-env.sh ops/production/.env
KEYCLOAK_BASE_URL=http://localhost:8081 ops/scripts/verify-keycloak-realm.sh
ops/scripts/smoke-test.sh http://localhost:8080 http://localhost:8081
```

## Postman Environment

Variables:

```text
baseUrl=http://localhost:8080
keycloakUrl=http://localhost:8081
realm=peak
platformToken=
tenantToken=
platformUserId=
platformTargetUserId=
platformRoleId=
platformIdentityLinkId=
tenantId=
tenantUserId=
tenantRoleId=
propertyId=
buildingId=
floorId=
roomTypeId=
roomId=
contactId=
channelId=
templateId=
correlationId=phase2-manual-001
idempotencyKey=phase2-idem-001
```

Default headers:

```text
Content-Type: application/json
X-Correlation-Id: {{correlationId}}
Authorization: Bearer {{platformToken}}
```

Unsafe commands must also send:

```text
Idempotency-Key: {{idempotencyKey}}
```

## Platform Administration Flow

These routes require a platform token linked to an active `platform_users` row with `platform.security.manage`.

1. List permissions.

```http
GET {{baseUrl}}/api/v1/platform/permissions
```

Expected: `platform.security.manage` appears.

2. Create a platform user.

```http
POST {{baseUrl}}/api/v1/platform/users
Idempotency-Key: platform-user-create-001

{
  "fullName": "Phase 2 Operator",
  "email": "phase2.operator@example.com",
  "status": "active"
}
```

Expected: response has `platformUserId`, `changed=true`, `replayed=false`.

3. Replay the same request with the same idempotency key.

Expected: same `platformUserId`, `replayed=true`.

4. Create a dynamic platform role.

```http
POST {{baseUrl}}/api/v1/platform/roles
Idempotency-Key: platform-role-create-001

{
  "code": "phase2_security_observer",
  "name": "Phase 2 Security Observer",
  "description": "Can inspect audit and monitoring state",
  "permissionCodes": [
    "platform.audit.view",
    "platform.monitoring.view"
  ]
}
```

5. Assign and revoke the role.

```http
POST {{baseUrl}}/api/v1/platform/users/{{platformTargetUserId}}/roles/{{platformRoleId}}/assign
POST {{baseUrl}}/api/v1/platform/users/{{platformTargetUserId}}/roles/{{platformRoleId}}/revoke
```

Expected: both commands are idempotent and write audit/outbox records.

6. Link and revoke OIDC identity.

```http
POST {{baseUrl}}/api/v1/platform/users/{{platformTargetUserId}}/identity-links
Idempotency-Key: platform-identity-link-001

{
  "issuer": "http://localhost:8081/realms/peak",
  "subject": "keycloak-user-subject",
  "email": "phase2.operator@example.com"
}
```

```http
POST {{baseUrl}}/api/v1/platform/users/{{platformTargetUserId}}/identity-links/{{platformIdentityLinkId}}/revoke
Idempotency-Key: platform-identity-revoke-001
```

Expected: revoked links stop resolving immediately.

7. Lock, disable, and reactivate a platform user.

```http
POST {{baseUrl}}/api/v1/platform/users/{{platformTargetUserId}}/lock
POST {{baseUrl}}/api/v1/platform/users/{{platformTargetUserId}}/disable
POST {{baseUrl}}/api/v1/platform/users/{{platformTargetUserId}}/reactivate
```

Expected: locked/disabled users cannot authorize platform routes.

## Tenant Administration Flow

Switch the default `Authorization` header to a tenant token linked to an active `users` row in `identity_links`. The tenant user must have `tenant.users.manage`, `module.manage`, and `tenant.profile.view` through tenant roles.

1. List tenant permissions and roles.

```http
GET {{baseUrl}}/api/v1/tenants/{{tenantId}}/permissions
GET {{baseUrl}}/api/v1/tenants/{{tenantId}}/roles
```

Expected: `tenant.users.manage`, `module.manage`, and `tenant.profile.view` are visible in the permission list, and the current admin role is visible in roles.

2. Create a dynamic tenant role.

```http
POST {{baseUrl}}/api/v1/tenants/{{tenantId}}/roles
Idempotency-Key: tenant-role-create-001

{
  "code": "phase2_front_office_lead",
  "name": "Phase 2 Front Office Lead",
  "description": "Can supervise front office setup checks",
  "permissionCodes": [
    "tenant.profile.view"
  ]
}
```

Expected: response has `tenantRoleId`, `changed=true`, `replayed=false`.

3. Replay the same role create request.

Expected: same `tenantRoleId`, `replayed=true`, and no duplicate audit or outbox records.

4. Update then deactivate the dynamic tenant role.

```http
PUT {{baseUrl}}/api/v1/tenants/{{tenantId}}/roles/{{tenantRoleId}}
Idempotency-Key: tenant-role-update-001

{
  "name": "Phase 2 Front Office Supervisor",
  "permissionCodes": [
    "tenant.profile.view",
    "property.view"
  ]
}
```

```http
DELETE {{baseUrl}}/api/v1/tenants/{{tenantId}}/roles/{{tenantRoleId}}
Idempotency-Key: tenant-role-delete-001
```

Expected: only dynamic roles can be changed. Attempts to update or deactivate system roles must return `400` and must not mutate assignments.

5. Enable and list a tenant module.

```http
POST {{baseUrl}}/api/v1/tenants/{{tenantId}}/modules
Idempotency-Key: tenant-module-enable-property-001

{
  "moduleId": "property"
}
```

```http
GET {{baseUrl}}/api/v1/tenants/{{tenantId}}/modules
```

Expected: `property` appears with `isEnabled=true`.

6. Check tenant readiness.

```http
GET {{baseUrl}}/api/v1/tenants/{{tenantId}}/readiness
```

Expected before full setup: `isReady=false` and `missingRequirements` lists the remaining business profile, contact, consent, report recipient, or module requirements. Expected after profile/contact/report setup: `isReady=true` and `missingRequirements=[]`.

7. Disable the tenant module when testing rollback behavior.

```http
DELETE {{baseUrl}}/api/v1/tenants/{{tenantId}}/modules/property
Idempotency-Key: tenant-module-disable-property-001
```

Expected: response has `enabled=false`, and the command is audited and outbox-backed.

## Cross-Cutting Checks

- Every `/api/**` route must resolve through `module_access_matrix`.
- Tenant identities must be denied on platform routes.
- Platform identities must be denied on tenant/property routes unless explicitly supported.
- Production runtime must reject trusted identity headers.
- JWT issuer and audience validation must remain enabled.
- Mutating admin commands must require idempotency.
- Security-sensitive changes must create audit records.
- Downstream-relevant changes must enqueue outbox events.
- Correlation ID must appear in audit, outbox, logs, and problem responses where applicable.
- Realtime subscriptions must be tenant/property scoped.

## Verification Queries

Use these only to verify API side effects:

```sql
SELECT action, entity_type, entity_id, correlation_id
FROM platform_audit_logs
ORDER BY created_at DESC
LIMIT 20;

SELECT event_type, aggregate_type, aggregate_id, correlation_id, status
FROM outbox_events
ORDER BY created_at DESC
LIMIT 20;
```

## Remaining Phase 2 Flows

The next E2E expansions must cover full property child-resource CRUD, communication provider delivery status, and realtime reconnect/backpressure behavior.
