# Peak Phase 1 End-to-End Test Plan

Date: 2026-06-20 morning session

Audience: Engineer A and Engineer B

Purpose: verify that Phase 1 meets its intended goals across tenant governance, user authorization, public booking/payment integration, audit, idempotency, outbox reliability, Keycloak-backed authentication, and production runtime hardening.

## Testing Goals

Phase 1 passes when all of these are true:

- The API, database, Keycloak, migration runtime, and worker runtime start cleanly with Podman.
- Flyway migrations apply cleanly and are repeat-safe.
- Public health endpoints are reachable, while secured API routes deny anonymous users.
- Production runtime does not accept trusted identity headers.
- Keycloak tokens are validated for issuer and audience.
- OIDC identities resolve through database identity links.
- Platform users can use only platform routes.
- Tenant users can use only their tenant routes and only with required permissions.
- Public booking/payment routes resolve tenant and property from database state, not trusted headers or request bodies.
- Unsafe commands enforce idempotency.
- Audit, idempotency, and outbox records are created for security-sensitive state changes.
- Worker/runtime split is respected: API serves HTTP; worker processes outbox and should not expose HTTP.
- Failures return safe problem responses with correlation or trace identifiers.

## Tools

Required:

- Podman and Podman Compose
- Postman
- `curl`
- `psql` or database client
- `jq` optional, useful for shell checks

Useful app URLs:

- API: `http://localhost:8080`
- Keycloak: `http://localhost:8081`
- Keycloak realm discovery: `http://localhost:8081/realms/peak/.well-known/openid-configuration`
- Health: `http://localhost:8080/actuator/health`

## Recommended Test Runtime

Use a local production-like runtime for the main session. It exercises the same security assumptions as production:

- JWT enabled
- header identity disabled
- API and worker separated
- Flyway isolated to migration runtime
- Keycloak realm imported

Build a local image:

```bash
./gradlew test bootJar
podman build -f Containerfile -t localhost/peak:phase1-test .
```

Prepare local production env:

```bash
cp ops/production/.env.example ops/production/.env
```

For local testing, edit `ops/production/.env`:

```text
PEAK_IMAGE=localhost/peak:phase1-test
PEAK_PUBLIC_HOST=localhost
PEAK_APP_ORIGIN=http://localhost:5173
KEYCLOAK_HOSTNAME=localhost
PEAK_SECURITY_JWT_ISSUER_URI=http://localhost:8081/realms/peak
PEAK_SECURITY_JWT_AUDIENCE=peak-api
PEAK_CORS_ALLOWED_ORIGINS=http://localhost:5173
PEAK_ALLOW_HEADER_IDENTITY=false
PEAK_ALLOW_TRUSTED_JWT_IDENTITY_CLAIMS=false
PEAK_PAYMENT_VODACOM_MPESA_URL=https://payments.example.com/vodacom-mpesa
```

Use non-production passwords in local only. Do not commit `ops/production/.env`.

Start infrastructure:

```bash
podman compose --env-file ops/production/.env -f ops/production/compose.yaml up -d postgres keycloak-db keycloak
```

Validate env and Keycloak:

```bash
ops/scripts/validate-production-env.sh ops/production/.env
set -a; . ops/production/.env; set +a
KEYCLOAK_BASE_URL=http://localhost:8081 ops/scripts/verify-keycloak-realm.sh
```

Bootstrap runtime database roles:

```bash
ops/scripts/bootstrap-db-roles.sh
```

Run migrations:

```bash
podman compose --env-file ops/production/.env -f ops/production/compose.yaml --profile migration run --rm peak-migration
```

Start API and worker:

```bash
podman compose --env-file ops/production/.env -f ops/production/compose.yaml up -d peak-api peak-worker
```

Smoke test:

```bash
ops/scripts/smoke-test.sh http://localhost:8080 http://localhost:8081
```

Expected:

- health check passes
- Keycloak issuer is `http://localhost:8081/realms/peak`
- Swagger UI is not exposed in prod
- anonymous secured API route returns `401` or `403`

## Postman Environment

Create a Postman environment named `Peak Phase 1 Local`.

Variables:

```text
baseUrl=http://localhost:8080
keycloakUrl=http://localhost:8081
realm=peak
issuer=http://localhost:8081/realms/peak
audience=peak-api
platformToken=
tenantToken=
publicToken=
platformUserId=
platformRoleId=
planId=
tenantId=
tenantUserId=
tenantActorRoleId=
tenantTargetUserId=
tenantTargetRoleId=
identityLinkId=
propertyId=
roomTypeId=
bookingSessionId=
invitationToken=
correlationId=phase1-manual-001
idempotencyKey=phase1-idem-001
```

Use these default headers on API requests:

```text
Content-Type: application/json
X-Correlation-Id: {{correlationId}}
```

For unsafe commands, also send:

```text
Idempotency-Key: {{idempotencyKey}}
```

For authenticated routes:

```text
Authorization: Bearer {{platformToken}}
```

or:

```text
Authorization: Bearer {{tenantToken}}
```

## Keycloak Tokens For Manual Testing

The committed realm uses secure defaults:

- `peak-api` is bearer-only.
- `peak-web` uses authorization code with PKCE.
- direct access grants are disabled.

For a local manual Postman session, use one of these approaches:

1. Preferred production-like approach: use a browser authorization-code flow through a local test frontend callback and copy the access token into Postman.
2. Faster local-only approach: temporarily create a Keycloak client named `peak-postman`, enable direct access grants, add an audience mapper for `peak-api`, create test users with verified emails, then delete this client after testing.

Do not commit the temporary `peak-postman` client to `ops/keycloak/peak-realm.json`.

Password grant token request for the temporary local-only client:

```bash
curl -sS -X POST "http://localhost:8081/realms/peak/protocol/openid-connect/token" \
  -H "Content-Type: application/x-www-form-urlencoded" \
  --data-urlencode "grant_type=password" \
  --data-urlencode "client_id=peak-postman" \
  --data-urlencode "username=platform-admin@example.com" \
  --data-urlencode "password=ChangeMe123!" \
  | jq -r '.access_token'
```

Token acceptance criteria:

- `iss` equals `http://localhost:8081/realms/peak`.
- `aud` includes `peak-api`.
- `email_verified` is `true`.
- API rejects the token if issuer or audience is wrong.

## Database Access

Open a psql shell:

```bash
set -a; . ops/production/.env; set +a
podman compose --env-file ops/production/.env -f ops/production/compose.yaml exec postgres \
  psql -U "$POSTGRES_MIGRATOR_USER" -d "$POSTGRES_DB"
```

Use UUIDs generated by Postman, `uuidgen`, or psql:

```sql
SELECT gen_random_uuid();
```

## Test Data Setup

Phase 1 does not yet expose every setup endpoint. Seed only the test records needed for manual verification.

Use a fresh local database for the cleanest session. If you rerun against the same database, reuse the same UUID variables or clear the previous `phase1-*` test records first.

Create plan and platform operator:

```sql
\set plan_id 'replace-with-plan-uuid'
\set platform_user_id 'replace-with-platform-user-uuid'
\set platform_role_id 'replace-with-platform-role-uuid'

INSERT INTO plans (id, name, code)
VALUES (:'plan_id', 'Phase 1 Test Plan', 'phase1-test')
ON CONFLICT (code) DO UPDATE SET name = EXCLUDED.name;

INSERT INTO platform_users (id, full_name, email, status)
VALUES (:'platform_user_id', 'Phase 1 Platform Operator', 'platform-admin@example.com', 'active')
ON CONFLICT (id) DO NOTHING;

INSERT INTO platform_roles (id, name, code)
VALUES (:'platform_role_id', 'Phase 1 Platform Admin', 'phase1-platform-admin')
ON CONFLICT (id) DO NOTHING;

INSERT INTO platform_role_permissions (platform_role_id, platform_permission_id)
SELECT :'platform_role_id', id
FROM platform_permissions
WHERE code IN ('platform.tenants.view', 'platform.tenants.manage')
ON CONFLICT DO NOTHING;

INSERT INTO platform_user_roles (platform_user_id, platform_role_id)
VALUES (:'platform_user_id', :'platform_role_id')
ON CONFLICT DO NOTHING;
```

After creating a Keycloak platform test user, link it:

```sql
\set platform_identity_link_id 'replace-with-identity-link-uuid'
\set platform_subject 'replace-with-keycloak-platform-user-sub'

INSERT INTO identity_links (
  id,
  identity_mode,
  provider,
  issuer,
  subject,
  platform_user_id,
  email
)
VALUES (
  :'platform_identity_link_id',
  'platform',
  'oidc',
  'http://localhost:8081/realms/peak',
  :'platform_subject',
  :'platform_user_id',
  'platform-admin@example.com'
)
ON CONFLICT DO NOTHING;
```

## Test Suite A: Runtime And Smoke

1. Health endpoint:

```http
GET {{baseUrl}}/actuator/health
```

Expected: `200`.

2. Swagger disabled in prod:

```http
GET {{baseUrl}}/swagger-ui.html
```

Expected: not `200`.

3. Anonymous secured API denied:

```http
GET {{baseUrl}}/api/v1/platform/tenants/00000000-0000-0000-0000-000000000000
```

Expected: `401` or `403`.

4. Trusted identity headers rejected in prod:

```http
GET {{baseUrl}}/api/v1/platform/tenants/00000000-0000-0000-0000-000000000000
X-Peak-Platform-User-Id: {{platformUserId}}
```

Expected: `400`, `401`, or `403`. It must not return successful data.

5. Worker should not expose HTTP:

```bash
podman compose --env-file ops/production/.env -f ops/production/compose.yaml ps
```

Expected: `peak-api` maps port `8080`; `peak-worker` does not map an HTTP port.

## Test Suite B: Platform Tenant Governance

Use `Authorization: Bearer {{platformToken}}`.

1. Register tenant:

```http
POST {{baseUrl}}/api/v1/platform/tenants
Idempotency-Key: phase1-register-tenant-001

{
  "name": "Phase 1 Zanzibar Hotel",
  "slug": "phase1-zanzibar-hotel",
  "planId": "{{planId}}",
  "legalName": "Phase 1 Zanzibar Hotel Limited",
  "tradingName": "Phase 1 Zanzibar",
  "entityType": "limited_company",
  "businessRegistrationNumber": "BRELA-PHASE1-001",
  "businessEmail": "ops@phase1-zanzibar.example",
  "businessPhone": "+255712345678",
  "registeredAddress": {
    "line1": "Stone Town",
    "city": "Zanzibar",
    "countryCode": "TZ"
  },
  "countryCode": "TZ",
  "currencyCode": "TZS"
}
```

Expected: `201`, response has `id`, `status=TRIAL`, lowercased email.

Save `id` to `tenantId`.

2. Get tenant:

```http
GET {{baseUrl}}/api/v1/platform/tenants/{{tenantId}}
```

Expected: `200`, same tenant.

3. Approve tenant:

```http
POST {{baseUrl}}/api/v1/platform/tenants/{{tenantId}}/approve
Idempotency-Key: phase1-approve-tenant-001

{
  "reason": "Manual Phase 1 verification completed"
}
```

Expected: `200`, previous status `trial`, new status `active`.

4. Suspend tenant:

```http
POST {{baseUrl}}/api/v1/platform/tenants/{{tenantId}}/suspend
Idempotency-Key: phase1-suspend-tenant-001

{
  "reason": "Manual Phase 1 suspension test"
}
```

Expected: `200`, new status `suspended`.

5. Re-approve tenant before continuing:

```http
POST {{baseUrl}}/api/v1/platform/tenants/{{tenantId}}/approve
Idempotency-Key: phase1-reactivate-tenant-001

{
  "reason": "Reactivate after suspension test"
}
```

Expected: `200`, new status `active`.

Evidence queries:

```sql
SELECT id, slug, status, schema_name, plan_id
FROM tenants
WHERE id = :'tenant_id';

SELECT event_type, reason, performed_by_platform_user_id, created_at
FROM tenant_lifecycle_events
WHERE tenant_id = :'tenant_id'
ORDER BY created_at;
```

Expected: `created`, `activated`, `suspended`, `activated` lifecycle evidence is present as applicable.

## Test Suite C: Tenant User Management Setup

Seed tenant admin actor, target user, roles, and permission:

```sql
\set tenant_id 'replace-with-tenant-id'
\set tenant_actor_user_id 'replace-with-tenant-actor-user-uuid'
\set tenant_target_user_id 'replace-with-tenant-target-user-uuid'
\set tenant_actor_role_id 'replace-with-tenant-actor-role-uuid'
\set tenant_target_role_id 'replace-with-tenant-target-role-uuid'
\set tenant_permission_id 'replace-with-permission-uuid'

INSERT INTO tenant_modules (tenant_id, module_id, is_enabled, is_configured)
VALUES (:'tenant_id', 'tenant_admin', true, true)
ON CONFLICT (tenant_id, module_id) DO UPDATE SET
  is_enabled = true,
  is_configured = true;

INSERT INTO users (id, tenant_id, full_name, email, status, is_active)
VALUES
  (:'tenant_actor_user_id', :'tenant_id', 'Phase 1 Tenant Admin', 'tenant-admin@example.com', 'active', true),
  (:'tenant_target_user_id', :'tenant_id', 'Phase 1 Target User', 'target-user@example.com', 'active', true)
ON CONFLICT (id) DO NOTHING;

INSERT INTO tenant_roles (id, tenant_id, name, code, is_system, is_active)
VALUES
  (:'tenant_actor_role_id', :'tenant_id', 'Phase 1 Tenant Admin', 'phase1-tenant-admin', false, true),
  (:'tenant_target_role_id', :'tenant_id', 'Phase 1 Reports Viewer', 'phase1-reports-viewer', false, true)
ON CONFLICT (tenant_id, code) DO NOTHING;

INSERT INTO permissions (id, tenant_id, code, description)
VALUES (:'tenant_permission_id', :'tenant_id', 'tenant.users.manage', 'Manage tenant users')
ON CONFLICT (tenant_id, code) DO NOTHING;

INSERT INTO tenant_role_permissions (tenant_role_id, permission_id)
VALUES (:'tenant_actor_role_id', :'tenant_permission_id')
ON CONFLICT DO NOTHING;

INSERT INTO user_tenant_roles (user_id, tenant_id, tenant_role_id)
VALUES (:'tenant_actor_user_id', :'tenant_id', :'tenant_actor_role_id')
ON CONFLICT DO NOTHING;
```

Create a Keycloak tenant test user and link it:

```sql
\set tenant_identity_link_id 'replace-with-tenant-identity-link-uuid'
\set tenant_subject 'replace-with-keycloak-tenant-user-sub'

INSERT INTO identity_links (
  id,
  identity_mode,
  provider,
  issuer,
  subject,
  tenant_id,
  user_id,
  email
)
VALUES (
  :'tenant_identity_link_id',
  'tenant',
  'oidc',
  'http://localhost:8081/realms/peak',
  :'tenant_subject',
  :'tenant_id',
  :'tenant_actor_user_id',
  'tenant-admin@example.com'
)
ON CONFLICT DO NOTHING;
```

Use `Authorization: Bearer {{tenantToken}}`.

## Test Suite D: Tenant User Role And Permission Boundaries

1. List roles:

```http
GET {{baseUrl}}/api/v1/tenants/{{tenantId}}/roles
```

Expected: `200`, includes actor and target roles.

2. List permissions:

```http
GET {{baseUrl}}/api/v1/tenants/{{tenantId}}/permissions
```

Expected: `200`, includes `tenant.users.manage`.

3. Assign role:

```http
POST {{baseUrl}}/api/v1/tenants/{{tenantId}}/users/{{tenantTargetUserId}}/roles/{{tenantTargetRoleId}}/assign
Idempotency-Key: phase1-role-assign-001
```

Expected: `200`, `assigned=true`, `changed=true`, `replayed=false`.

4. Replay same assignment:

```http
POST {{baseUrl}}/api/v1/tenants/{{tenantId}}/users/{{tenantTargetUserId}}/roles/{{tenantTargetRoleId}}/assign
Idempotency-Key: phase1-role-assign-001
```

Expected: `200`, `replayed=true` or `changed=false`. It must not duplicate the assignment.

5. Revoke role:

```http
POST {{baseUrl}}/api/v1/tenants/{{tenantId}}/users/{{tenantTargetUserId}}/roles/{{tenantTargetRoleId}}/revoke
Idempotency-Key: phase1-role-revoke-001
```

Expected: `200`, role assignment removed.

Evidence:

```sql
SELECT count(*)
FROM user_tenant_roles
WHERE tenant_id = :'tenant_id'
  AND user_id = :'tenant_target_user_id'
  AND tenant_role_id = :'tenant_target_role_id';
```

Expected: `0` after revoke.

Negative checks:

- Tenant token on platform route must return `403`.
- Platform token on tenant route must return `403`.
- Tenant token for a different tenant ID must return `403`.
- Tenant user without `tenant.users.manage` must return `403`.
- Unknown `/api/v1/...` route must return `403` because API route guard is deny-by-default.

## Test Suite E: Tenant Invitation And OIDC Linking

1. Invite user:

```http
POST {{baseUrl}}/api/v1/tenants/{{tenantId}}/users/invitations
Authorization: Bearer {{tenantToken}}
Idempotency-Key: phase1-invite-001

{
  "email": "invitee@example.com",
  "fullName": "Phase 1 Invitee",
  "tenantRoleId": "{{tenantTargetRoleId}}",
  "expiresInHours": 24,
  "metadata": {
    "source": "phase1-manual-test"
  }
}
```

Expected: `201`, response includes `invitationToken`, `replayed=false`.

Save `invitationToken`.

2. Replay invitation:

```http
POST {{baseUrl}}/api/v1/tenants/{{tenantId}}/users/invitations
Authorization: Bearer {{tenantToken}}
Idempotency-Key: phase1-invite-001

{
  "email": "invitee@example.com",
  "fullName": "Phase 1 Invitee",
  "tenantRoleId": "{{tenantTargetRoleId}}",
  "expiresInHours": 24,
  "metadata": {
    "source": "phase1-manual-test"
  }
}
```

Expected: no duplicate pending invitation; replay response must not leak a second raw token.

3. Accept invitation with invitee Keycloak token:

```http
POST {{baseUrl}}/api/v1/invitations/accept
Authorization: Bearer {{publicToken}}
Idempotency-Key: phase1-invite-accept-001

{
  "invitationToken": "{{invitationToken}}",
  "fullName": "Phase 1 Invitee"
}
```

Expected: `200`, response has `userId`, `identityLinkId`, `tenantRoleId`, `replayed=false`.

4. Invalid token:

```http
POST {{baseUrl}}/api/v1/invitations/accept
Authorization: Bearer {{publicToken}}
Idempotency-Key: phase1-invalid-invite-001

{
  "invitationToken": "not-a-valid-token"
}
```

Expected: `400`, safe problem response, no raw SQL error.

5. No JWT:

```http
POST {{baseUrl}}/api/v1/invitations/accept
Idempotency-Key: phase1-invite-no-jwt-001

{
  "invitationToken": "{{invitationToken}}"
}
```

Expected: `401`.

Evidence:

```sql
SELECT id, tenant_id, email, status, token_hash, accepted_at
FROM tenant_user_invitations
WHERE tenant_id = :'tenant_id'
ORDER BY created_at DESC;

SELECT id, issuer, subject, tenant_id, user_id, email, revoked_at
FROM identity_links
WHERE tenant_id = :'tenant_id'
ORDER BY created_at DESC;
```

Expected:

- invitation token hash stored, raw token not stored
- accepted invitation creates or reuses tenant user
- identity link uses JWT issuer, subject, and verified email, not forged body fields

## Test Suite F: Tenant User Lifecycle

Use `Authorization: Bearer {{tenantToken}}`.

1. Disable user:

```http
POST {{baseUrl}}/api/v1/tenants/{{tenantId}}/users/{{tenantTargetUserId}}/disable
Idempotency-Key: phase1-user-disable-001
```

Expected: `200`, `status=disabled`, `isActive=false`.

2. Reactivate user:

```http
POST {{baseUrl}}/api/v1/tenants/{{tenantId}}/users/{{tenantTargetUserId}}/reactivate
Idempotency-Key: phase1-user-reactivate-001
```

Expected: `200`, active again.

3. Lock user:

```http
POST {{baseUrl}}/api/v1/tenants/{{tenantId}}/users/{{tenantTargetUserId}}/lock
Idempotency-Key: phase1-user-lock-001
```

Expected: `200`, locked state visible.

4. Unlock user:

```http
POST {{baseUrl}}/api/v1/tenants/{{tenantId}}/users/{{tenantTargetUserId}}/unlock
Idempotency-Key: phase1-user-unlock-001
```

Expected: `200`, lock removed.

5. Revoke identity link:

```http
POST {{baseUrl}}/api/v1/tenants/{{tenantId}}/users/{{tenantTargetUserId}}/identity-links/{{identityLinkId}}/revoke
Idempotency-Key: phase1-identity-revoke-001
```

Expected: `200`, `revokedAt` present.

Negative checks:

- revoked identity token must no longer authorize secured tenant routes
- disabled or locked user token must no longer authorize secured tenant routes
- user must not be able to disable, lock, or assign roles to themselves

Evidence:

```sql
SELECT id, status, is_active, locked_until
FROM users
WHERE tenant_id = :'tenant_id'
ORDER BY created_at DESC;

SELECT id, revoked_at
FROM identity_links
WHERE tenant_id = :'tenant_id';
```

## Test Suite G: Public Booking And Payment Integration

Seed property and booking engine module:

```sql
\set tenant_id 'replace-with-active-tenant-id'
\set property_id 'replace-with-property-uuid'
\set room_type_id 'replace-with-room-type-uuid'

INSERT INTO properties (id, tenant_id, name, code, status, is_active)
VALUES (:'property_id', :'tenant_id', 'Phase 1 Test Property', 'P1TEST', 'active', true)
ON CONFLICT (id) DO NOTHING;

INSERT INTO room_types (id, tenant_id, property_id, name, code, base_price)
VALUES (:'room_type_id', :'tenant_id', :'property_id', 'Standard Room', 'STD-P1', 100000)
ON CONFLICT (id) DO NOTHING;

INSERT INTO tenant_modules (tenant_id, module_id, is_enabled, is_configured)
VALUES (:'tenant_id', 'booking_engine', true, true)
ON CONFLICT (tenant_id, module_id) DO UPDATE SET
  is_enabled = true,
  is_configured = true;

INSERT INTO property_modules (tenant_id, property_id, module_id, is_enabled, is_configured)
VALUES (:'tenant_id', :'property_id', 'booking_engine', true, true)
ON CONFLICT (tenant_id, property_id, module_id) DO UPDATE SET
  is_enabled = true,
  is_configured = true;
```

These routes are public. Do not send tenant or platform identity headers.

1. Create booking session:

```http
POST {{baseUrl}}/api/v1/public/properties/{{propertyId}}/booking-engine/sessions

{
  "roomTypeId": "{{roomTypeId}}",
  "checkInDate": "2030-01-01",
  "checkOutDate": "2030-01-03",
  "guestName": "Public Guest",
  "guestEmail": "public@example.com"
}
```

Expected: `200`, `status=payment_pending`, response has `sessionId`.

Save `sessionId` to `bookingSessionId`.

2. Initiate payment:

```http
POST {{baseUrl}}/api/v1/public/properties/{{propertyId}}/booking-engine/payments/initiate
Idempotency-Key: phase1-public-payment-001

{
  "sessionId": "{{bookingSessionId}}",
  "provider": "VODACOM_MPESA",
  "paymentMethod": "MOBILE_MONEY",
  "phoneNumber": "+255700000000",
  "accountNumber": null,
  "amount": 100000.00
}
```

Expected: `200`, `status=PENDING`, response has `referenceId`.

3. Replay payment:

Send the exact same payment request with the same idempotency key.

Expected: same `referenceId`; database has one payment attempt.

4. Missing idempotency:

Same payment request without `Idempotency-Key`.

Expected: `400`, message says `Idempotency-Key header is required`.

5. Disabled module denial:

```sql
UPDATE property_modules
SET is_enabled = false
WHERE tenant_id = :'tenant_id'
  AND property_id = :'property_id'
  AND module_id = 'booking_engine';
```

Retry booking session creation.

Expected: `403`, public module inaccessible.

Restore:

```sql
UPDATE property_modules
SET is_enabled = true
WHERE tenant_id = :'tenant_id'
  AND property_id = :'property_id'
  AND module_id = 'booking_engine';
```

Evidence:

```sql
SELECT id, tenant_id, property_id, status, guest_email, expires_at
FROM booking_sessions
WHERE id = :'booking_session_id';

SELECT provider, provider_payment_id, idempotency_key, amount, status
FROM booking_payment_attempts
WHERE session_id = :'booking_session_id';
```

## Test Suite H: Audit, Idempotency, And Outbox Evidence

Run after tenant/user/payment flows.

Correlation evidence:

```sql
SELECT correlation_id, actor_type, action, outcome, created_at
FROM audit_events
WHERE correlation_id LIKE 'phase1%'
ORDER BY created_at DESC
LIMIT 50;
```

Idempotency evidence:

```sql
SELECT operation_type, idempotency_key, status, response_code, created_at, updated_at
FROM idempotency_keys
WHERE idempotency_key LIKE 'phase1%'
ORDER BY created_at DESC;
```

Outbox evidence:

```sql
SELECT event_type, destination, status, correlation_id, attempt_count, next_attempt_at, created_at
FROM outbox_events
WHERE correlation_id LIKE 'phase1%'
ORDER BY created_at DESC
LIMIT 50;
```

Expected:

- unsafe commands have idempotency rows
- security-sensitive changes have audit rows
- side-effect events are queued in outbox
- worker does not corrupt rows under retry or missing handlers

Worker logs:

```bash
podman compose --env-file ops/production/.env -f ops/production/compose.yaml logs --tail=200 peak-worker
```

Expected:

- no startup failure
- no unbounded retry loop
- no unexpected database permission failure

## Test Suite I: Security Negative Matrix

Run these deliberately and record the response code.

| Case | Request | Expected |
| --- | --- | --- |
| Anonymous platform route | `GET /api/v1/platform/tenants/{{tenantId}}` without token | `401` or `403` |
| Anonymous tenant route | `GET /api/v1/tenants/{{tenantId}}/roles` without token | `401` or `403` |
| Tenant token on platform route | tenant token calls `/api/v1/platform/tenants/{{tenantId}}` | `403` |
| Platform token on tenant route | platform token calls `/api/v1/tenants/{{tenantId}}/roles` | `403` |
| Wrong tenant | tenant token calls another tenant ID | `403` |
| Revoked identity link | revoke link, retry route | `403` |
| Locked user | lock user, retry route | `403` |
| Missing permission | remove `tenant.users.manage`, retry route | `403` |
| Unknown API route | `GET /api/v1/not-registered` | `403` |
| Trusted header in prod | send `X-Peak-Tenant-Id` without token | denied |
| Public payment no idempotency | omit `Idempotency-Key` | `400` |
| Bad payment amount | amount `0` | `400` |
| Disabled public property module | disable `property_modules` | `403` |

Response body expectations:

- uses `application/problem+json` or safe structured error body
- no SQL stack traces
- no secret values
- has trace/correlation information where handled by global exception handling

## Test Suite J: Production Config And Ops Scripts

Run:

```bash
podman compose --env-file ops/production/.env -f ops/production/compose.yaml config >/tmp/peak-compose-check.yaml
ops/scripts/validate-production-env.sh ops/production/.env
ops/scripts/healthcheck.sh http://localhost:8080/actuator/health
```

Backup dry run:

```bash
ops/scripts/backup-postgres.sh
ls -lh backups/
```

Do not run restore into the active test database unless both engineers agree to reset the session. Restore should be validated against a disposable database.

## Pass/Fail Record

Use this table during the session.

| Area | Owner | Result | Evidence |
| --- | --- | --- | --- |
| Runtime startup | A/B |  | health output, podman ps |
| Migrations | A |  | Flyway logs |
| Keycloak realm | A |  | verify script output |
| Platform governance | A |  | Postman responses, lifecycle SQL |
| Tenant user invite/accept | A |  | Postman responses, identity SQL |
| Tenant roles/lifecycle | A |  | Postman responses, user SQL |
| Public booking | B |  | Postman responses, booking SQL |
| Public payment | B |  | Postman responses, payment SQL |
| Idempotency replay | A/B |  | idempotency SQL |
| Audit evidence | A |  | audit SQL |
| Outbox/worker | A/B |  | outbox SQL, worker logs |
| Security negative matrix | A/B |  | status code list |
| Backup script | A |  | backup file |

## Session Rules

- Test on latest `master`.
- Pull before starting: `git checkout master && git pull --ff-only origin master`.
- Keep Postman requests and SQL evidence from the session.
- Any failed expected behavior becomes a GitHub issue or fix branch.
- Do not weaken production security settings just to make a request pass.
- Temporary Keycloak/Postman clients are local test helpers only and must not be committed.
- Do not call Phase 1 accepted until the negative security matrix passes, not only the happy path.
