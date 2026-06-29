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

The authoritative automated gate starts from empty PostgreSQL and Keycloak volumes and performs all normal application setup through APIs:

```bash
./gradlew test bootJar
podman build -t localhost/peak:phase2-acceptance .
PHASE2_RESET=true ops/testing/run-phase2-acceptance.sh
```

The command writes machine-readable evidence to `build/phase2-acceptance-evidence.json`. It is destructive only to the isolated `peak-phase2-acceptance` Compose project. Do not point it at shared or production volumes.

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
deliveryRequestId=
sseLastEventId=
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

Before the first login, create the platform root once with:

```bash
ops/scripts/bootstrap-platform.sh
```

The bootstrap runtime is non-web, requires a real Keycloak issuer/subject, and refuses to create another root after platform administration exists.

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

8. Register a tenant, provision its first administrator, and verify its reviewed profile.

```http
POST {{baseUrl}}/api/v1/platform/tenants/{{tenantId}}/administrators
Idempotency-Key: tenant-admin-provision-001

{
  "fullName": "Tenant Administrator",
  "email": "tenant.admin@example.com",
  "issuer": "{{keycloakUrl}}/realms/{{realm}}",
  "subject": "{{tenantKeycloakSubject}}"
}
```

```http
POST {{baseUrl}}/api/v1/platform/tenants/{{tenantId}}/profile/verify
Idempotency-Key: tenant-profile-verify-001
```

Expected: the administrator, immutable system role, permissions, module enablement, and OIDC link are created atomically; the tenant token resolves without SQL.

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

## Property Management Flow

Use a tenant token whose user has `module.manage`, `property.view`, `property.manage`, `property.lifecycle`, and `communications.manage`. First enable the tenant modules required for setup:

```http
POST {{baseUrl}}/api/v1/tenants/{{tenantId}}/modules
Idempotency-Key: tenant-module-enable-booking-engine-001

{ "moduleId": "booking_engine" }
```

```http
POST {{baseUrl}}/api/v1/tenants/{{tenantId}}/modules
Idempotency-Key: tenant-module-enable-communications-001

{ "moduleId": "communications" }
```

1. Create a property.

```http
POST {{baseUrl}}/api/v1/properties
Idempotency-Key: property-create-001

{
  "name": "Phase 2 Test Hotel",
  "location": "Arusha",
  "code": "P2H001",
  "type": "HOTEL"
}
```

Expected: response has `propertyId`, `status=draft`, `changed=true`, `replayed=false`. Replay with the same idempotency key returns the same `propertyId` with `replayed=true`.

2. Enable the booking engine for the property.

```http
POST {{baseUrl}}/api/v1/properties/{{propertyId}}/modules
Idempotency-Key: property-module-booking-engine-001

{ "moduleId": "booking_engine" }
```

Expected: `enabled=true`. Disabling the core `property` module must return `400`.

3. Create the structural setup.

```http
POST {{baseUrl}}/api/v1/properties/{{propertyId}}/buildings
Idempotency-Key: property-building-create-001

{
  "name": "Main Building",
  "description": "Reception and guest rooms"
}
```

```http
POST {{baseUrl}}/api/v1/properties/{{propertyId}}/floors
Idempotency-Key: property-floor-create-001

{
  "buildingId": "{{buildingId}}",
  "floorNumber": 1,
  "name": "Ground Floor",
  "capacity": 30
}
```

```http
POST {{baseUrl}}/api/v1/properties/{{propertyId}}/room-types
Idempotency-Key: property-room-type-create-001

{
  "name": "Deluxe King",
  "code": "DLX",
  "basePrice": 0,
  "maxAdults": 2,
  "maxChildren": 1,
  "maxOccupancy": 3
}
```

```http
POST {{baseUrl}}/api/v1/properties/{{propertyId}}/rooms
Idempotency-Key: property-room-create-001

{
  "buildingId": "{{buildingId}}",
  "roomNumber": "101",
  "roomTypeId": "{{roomTypeId}}",
  "floorNumber": 1
}
```

Expected: each command returns `resourceId`, writes audit/outbox records, and replays by idempotency key.

4. Configure operational and financial setup.

```http
POST {{baseUrl}}/api/v1/properties/{{propertyId}}/revenue-centers
Idempotency-Key: property-revenue-center-create-001

{
  "name": "Rooms Revenue",
  "code": "ROOMS",
  "centerType": "rooms",
  "isRoomsRevenue": true,
  "displayOrder": 1
}
```

```http
POST {{baseUrl}}/api/v1/properties/{{propertyId}}/departments
Idempotency-Key: property-department-create-001

{
  "name": "Front Office",
  "code": "FO"
}
```

```http
POST {{baseUrl}}/api/v1/properties/taxes
Idempotency-Key: property-tax-create-001

{
  "name": "VAT",
  "code": "VAT18",
  "rate": 0.18,
  "taxType": "vat"
}
```

```http
POST {{baseUrl}}/api/v1/properties/{{propertyId}}/rates
Idempotency-Key: property-base-rate-001

{
  "roomTypeId": "{{roomTypeId}}",
  "amount": 250000,
  "currency": "TZS"
}
```

5. Create and verify a business contact through the communication flow below. Property readiness requires at least one active verified contact channel.

6. Check readiness and activate.

```http
GET {{baseUrl}}/api/v1/properties/{{propertyId}}/readiness
```

Expected before all setup: `isReady=false` with clear missing requirements. Expected after setup: `isReady=true`, `missingRequirements=[]`.

```http
POST {{baseUrl}}/api/v1/properties/{{propertyId}}/activate
Idempotency-Key: property-activate-001
```

Expected: response remains ready, and `GET /api/v1/properties/{{propertyId}}` returns `status=active`, `isActive=true`.

7. Verify isolation.

Use a second tenant user without `user_property_roles` for `{{propertyId}}` and call:

```http
GET {{baseUrl}}/api/v1/properties/{{propertyId}}
```

Expected: `403`. A tenant user from another tenant must also receive `403` or `404` without leaking property details.

## Communication Flow

Use a tenant token whose user has `communications.view`, `communications.manage`, and `communications.send`. The tenant must have the `communications` module enabled.

1. Create a contact with channels.

```http
POST {{baseUrl}}/api/v1/communication/contacts
Idempotency-Key: communication-contact-create-001

{
  "fullName": "Operations Manager",
  "jobTitle": "Operations",
  "email": "ops@example.com",
  "phone": "+255712345678",
  "whatsapp": "+255712345678"
}
```

Expected: response has `contactId`, `channelIds`, and `replayed=false`. Replay with the same idempotency key must return the same `contactId` with `replayed=true`.

2. List contacts.

```http
GET {{baseUrl}}/api/v1/communication/contacts
```

Expected: the contact appears with channel ids and verification statuses.

3. Request channel verification.

```http
POST {{baseUrl}}/api/v1/communication/channels/{{channelId}}/request-verification
Idempotency-Key: communication-channel-request-001
```

Expected: `202 Accepted`, `notificationEventId` and `deliveryRequestId` are returned, channel status becomes `pending`, and no raw token is returned by the API. The token is staged for delivery through the outbox.

4. Verify a channel with the delivered token.

```http
POST {{baseUrl}}/api/v1/communication/channels/{{channelId}}/verify
Idempotency-Key: communication-channel-verify-001

{
  "token": "TOKEN_FROM_DELIVERY"
}
```

Expected: `verified=true`, channel status becomes `verified`, and replay with the same idempotency key returns `replayed=true`.

5. Create a template.

```http
POST {{baseUrl}}/api/v1/communication/templates
Idempotency-Key: communication-template-create-001

{
  "name": "Arrival Alert",
  "subject": "Arrival alert",
  "content": "Guest {{guestName}} has arrived.",
  "type": "EMAIL"
}
```

Expected: response has `templateId`; audit and platform outbox events are written.

6. Enqueue a notification.

```http
POST {{baseUrl}}/api/v1/communication/notifications
Idempotency-Key: communication-notification-001

{
  "propertyId": "{{propertyId}}",
  "channel": "EMAIL",
  "recipient": "ops@example.com",
  "subject": "Operational alert",
  "content": "A test alert was emitted."
}
```

Expected: response has `eventId` and `deliveryRequestId`; the notification outbox event is tenant/property scoped. Audit must contain a recipient fingerprint, not the raw recipient or message body.

7. Check delivery request status.

```http
GET {{baseUrl}}/api/v1/communication/delivery-requests/{{deliveryRequestId}}
```

Expected: status starts as `queued` or `sending`, then becomes `delivered` after the worker processes the notification destination. The response must show `recipientFingerprint`, not raw message content.

8. List provider attempts.

```http
GET {{baseUrl}}/api/v1/communication/delivery-requests/{{deliveryRequestId}}/attempts
```

Expected: at least one attempt appears with `provider`, `attemptNumber`, `status`, and provider message/error fields.

9. Retry a failed delivery.

Use this only after forcing or observing a `failed` or `dead_letter` delivery request.

```http
POST {{baseUrl}}/api/v1/communication/delivery-requests/{{deliveryRequestId}}/retry
Idempotency-Key: communication-delivery-retry-001
```

Expected: response has a new `eventId`, the delivery request returns to `queued`, and replaying the same idempotency key returns `replayed=true`.

## Realtime Flow

These routes require a tenant token with `realtime.stream` for the target property and the `realtime` module enabled for that property.

1. Open an SSE stream.

```bash
curl -N \
  -H "Authorization: Bearer ${tenantToken}" \
  -H "X-Correlation-Id: phase2-realtime-sse-001" \
  "${baseUrl}/api/v1/realtime/tenants/${tenantId}/properties/${propertyId}/stream"
```

Expected: the stream starts with `connection-established`.

2. Trigger a property event from another terminal or Postman tab.

```http
PUT {{baseUrl}}/api/v1/properties/{{propertyId}}/rooms/{{roomId}}/status
Idempotency-Key: realtime-room-status-001

{
  "status": "maintenance"
}
```

Expected: the SSE stream receives a `property.room.status_changed` event with an `id:` line. Save that id as `sseLastEventId`.

3. Reconnect with resume.

```bash
curl -N \
  -H "Authorization: Bearer ${tenantToken}" \
  -H "X-Correlation-Id: phase2-realtime-sse-resume-001" \
  -H "Last-Event-ID: ${sseLastEventId}" \
  "${baseUrl}/api/v1/realtime/tenants/${tenantId}/properties/${propertyId}/stream"
```

Expected: only events newer than `sseLastEventId` are replayed. Invalid `Last-Event-ID` values return `400`.

4. Verify isolation.

Use a token from another tenant or a property where the user lacks `realtime.stream`.

Expected: the stream is denied with `403`, and `realtime.security.violations` increases for WebSocket subscription denials.

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

## Phase 2 Completion Notes

Normal Phase 2 verification should not require manual SQL. SQL is reserved for emergency inspection, forced failure simulation, or release triage.
