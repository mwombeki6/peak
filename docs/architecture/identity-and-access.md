# Identity and Access Architecture

Peak uses Keycloak for authentication and Peak's database for authorization.
This boundary follows the Safari platform blueprint while retaining Peak's
existing tenant, property, role, entitlement, audit and RLS model.

## Responsibility boundary

| Keycloak owns | Peak owns |
|---|---|
| Credentials, password policy and reset | Tenant membership and lifecycle |
| TOTP, WebAuthn/passkeys and login assurance | Platform, tenant and property roles |
| Browser/native OIDC sessions and logout | Permission codes and module access |
| Email verification and brute-force protection | Plan entitlements and limits |
| Identity brokering and future federation | Support approval, break-glass and RLS |
| Token signing, issuer and audience | Business audit and authorization decisions |

Keycloak realm roles, groups, organizations and client roles are not business
authorization inputs. A validated token identifies an external subject. Peak
then resolves the token's exact `(issuer, subject)` through `identity_links` and
evaluates current database state. Disabling a user, tenant, property assignment
or permission in Peak therefore takes effect without waiting for a token claim
to change.

## Realms and clients

| Realm | Client | Type | Consumer |
|---|---|---|---|
| `peak-platform` | `peak-api` | Bearer-only resource server | Isolated platform API |
| `peak-platform` | `peak-platform-web` | Public OIDC + PKCE | Peak platform administration web |
| `peak-hospitality` | `peak-api` | Bearer-only resource server | Hotel operations API |
| `peak-hospitality` | `peak-hospitality-web` | Public OIDC + PKCE | Tenant, property and hotel operations web |
| `peak-hospitality` | `peak-pos-desktop` | Public native OIDC + PKCE | Tauri/desktop POS |

There are three interactive frontend clients and two realm-local resource-server
registrations. The repeated `peak-api` audience is safe because API runtimes
also require their exact, distinct issuer. CI creates a direct-grant
`peak-acceptance` client dynamically in disposable realms; it is not part of
production configuration.

The platform API trusts only `peak-platform`. The hotel API and worker trust
only `peak-hospitality`. A token from one realm must not authenticate to the
other runtime even when its audience text is `peak-api`.

## Frontend and SDK boundaries

Peak publishes two generated TypeScript clients from the canonical V1 OpenAPI
contract. SDK boundaries follow resource-server and issuer boundaries, not the
number of screens or administrator titles.

| Frontend | Realm | Generated client | Authority evaluated by Peak |
|---|---|---|---|
| Platform administration web | `peak-platform` | `@peak/platform-api-client` | Platform roles and permissions |
| Hospitality web | `peak-hospitality` | `@peak/hospitality-api-client` | Tenant and property roles, permissions and assignments |
| POS desktop | `peak-hospitality` | `@peak/hospitality-api-client` | Property roles and permissions |

The platform client contains only `/api/v1/platform/**` operations. The
hospitality client contains every other V1 operation and no platform operation.
Their generated path sets are disjoint and must exactly reconstruct the
canonical V1 path set; CI fails if a route is missing, duplicated or placed
across the issuer boundary.

Possessing an SDK type never grants access. Each application still authenticates
through its assigned OIDC client, and each request is authorized from current
database state.

Hospitality Web is one deployment with role-specific workspaces for tenant
administration, property administration, front office, finance, housekeeping,
maintenance, inventory and reporting. A role is not an application boundary.
The legacy `peak-web` and `peak-tenant-admin` registrations are retired during
realm reconciliation after the unified client is installed.

## Administrator scope and delegation

Platform, tenant and property administrator are independent authority scopes:

| Administrator | Identity and authority | Scope | Relationship |
|---|---|---|---|
| Platform administrator | `peak-platform`; `platform_users` and `platform_user_roles` | Operates Peak's SaaS control plane | Has no implicit tenant or property authority |
| Tenant administrator | `peak-hospitality`; `users` and `user_tenant_roles` | Governs one tenant business | May appoint property administrators with `tenant.properties.administrators.manage` |
| Property administrator | `peak-hospitality`; `users` and `user_property_roles` | Operates an assigned property | Receives no authority over sibling properties or the tenant control plane |

A person can hold more than one scope, but each assignment is explicit and
evaluated independently. Platform support access to hotel data remains a
ticket-bound, time-limited, audited break-glass workflow; platform employment or
the `platform.admin.all` permission never silently creates a hospitality user.

Tenant and property administrators share the hospitality identity realm and SDK
because they act on the same hotel system. They are separated by route scope,
tenant/property binding, role assignment, RLS and permission checks. Tenant
administrator continuity uses `tenant.administrators.manage`; property
administrator continuity uses the narrower
`tenant.properties.administrators.manage`. Revocation cannot orphan the
corresponding tenant or property.

## Authentication posture

- All browser and desktop clients use authorization code flow with PKCE S256.
  Implicit flow, direct grants, client secrets in public clients and service
  accounts are disabled.
- Browser redirect URIs and web origins are exact. Wildcard browser redirect
  URIs are forbidden. POS uses the RFC 8252 loopback redirect
  `http://127.0.0.1` through the system browser.
- Access tokens expire after five minutes. Refresh-token rotation has zero
  reuse tolerance and sessions have finite idle and maximum lifetimes.
- Platform operators must enroll TOTP. Both realms support SHA-256 TOTP and
  WebAuthn/passkeys with user verification, discoverable credentials and
  conditional mediation.
- New-user self-registration is disabled. Email verification, password reset,
  login/admin event recording and brute-force protection are enabled.
- Passwords have a 15-character minimum and are checked against username,
  email and recent history. Peak does not force calendar-based password
  rotation; administrators reset credentials after compromise or recovery.
- Verified-email and password-recovery flows use authenticated SMTP with one
  explicit TLS mode. Identity email is separate from hotel guest messaging.

## Frontend integration contract

Each frontend must use only its assigned issuer and client ID. It requests an
access token for audience `peak-api`, sends it as `Authorization: Bearer`, and
never supplies tenant, property, role or permission claims as authority.

Browser clients should keep tokens in memory, avoid local/session storage,
perform OIDC state/nonce validation through a maintained adapter, refresh
before expiry and clear local state on refresh failure or logout. The POS must
open the system browser, bind a loopback listener only for the callback, verify
state and PKCE, then close that listener.

After OIDC login, Hospitality Web and POS bootstrap database-authoritative
identity and access state from `GET /api/v1/session`. Platform Console uses
`GET /api/v1/platform/session`. The responses include only the caller's current
scope and never accept tenant, property, role or permission authority from the
frontend.

Frontends may hide unavailable controls for usability, but must treat API
authorization responses as final. The complete deployable-application and
integration contract is in `frontend-applications.md`.

## Configuration lifecycle

The realm JSON files are bootstrap templates. Keycloak intentionally skips a
startup import when a realm already exists. After first boot and after every
identity configuration change, operators run:

```sh
set -a
. ops/production/.env
set +a
python3 ops/scripts/reconcile-keycloak-realms.py
ops/scripts/verify-keycloak-realms.sh
```

The reconciler updates only Peak-owned realm policy, required actions, clients
and dedicated protocol mappers. It does not manage users, credentials,
federated identities or arbitrary third-party clients.

Existing users in the former `peak` realm cannot silently change issuer. Peak's
maintenance-window migration performs an offline Keycloak export, partitions
users by the authoritative `identity_links.identity_mode`, and uses Keycloak's
partial-import path to preserve subject identifiers and credential hashes. It
does not copy Keycloak groups, realm roles, client roles, service accounts or
federated identities. The latter two require an explicit operator-managed
migration because they are not portable user credentials.

After every imported subject and credential type is verified, the migration
atomically rewrites only the issuer of each active Peak identity link, records
append-only tenant/platform audit entries, disables the legacy realm and starts
the isolated runtimes. API and worker processes remain stopped if any stage
fails. The upgrade command independently refuses to proceed while the legacy
realm is enabled with users or any active legacy issuer links remain.
