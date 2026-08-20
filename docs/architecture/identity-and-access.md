# Identity and Access Architecture

Peak uses Keycloak for authentication and Peak's database for authorization.
This boundary follows the Safari platform blueprint while retaining Peak's
existing tenant, property, role, entitlement, audit and RLS model.

## Responsibility boundary

The identity layer owns **who** a person is and **how** they proved it. Peak owns
**where** they may act and **what** they may do.

| Keycloak owns | Peak owns |
|---|---|
| Credentials, password policy and reset | Tenant membership and lifecycle |
| TOTP, WebAuthn/passkeys and login assurance | Platform, tenant and property roles |
| Browser/native OIDC sessions and logout | Permission codes and module access |
| Email verification and brute-force protection | Plan entitlements and limits |
| Identity brokering and future federation | Support approval, break-glass and RLS |
| Token signing, issuer and audience | Business audit and authorization decisions |
| The identifier a person types to sign in | Staff numbers and property-scoped operations |

Keycloak realm roles, groups, organizations and client roles are not business
authorization inputs. A validated token identifies an external subject. Peak
then resolves the token's exact `(issuer, subject)` through `identity_links` and
evaluates current database state. Disabling a user, tenant, property assignment
or permission in Peak therefore takes effect without waiting for a token claim
to change.

## Identifier model

Email is optional. Most Peak operators are reached by mobile number, and a
staff member with neither address nor phone is an ordinary hotel employee
(`V113`, `V122`). Nothing in authentication may require an email address.

| Identifier | Owner | Mutable | Purpose |
|---|---|---|---|
| `sub` | Identity layer | Never | The immutable identity subject |
| Canonical username | Identity layer | Never | Opaque internal handle. Never shown, never typed |
| Verified phone (E.164) | Identity layer | Yes | The primary identifier a person types |
| Email | Identity layer | Yes | Optional verified secondary identifier |
| `staff_number` | Peak | Yes | Property-scoped operational identifier |

The canonical username is opaque and immutable precisely because phone numbers
are not: they get reassigned by networks and people change them. Changing a
phone number updates an attribute. It must never rename or recreate the
identity, and must never disturb business memberships or audit history.

A staff number must never become a username in the identity layer. It is
property-scoped, reused across properties, and belongs to Peak's operational
model — not to the person's identity.

The branded sign-in page accepts a mobile number or a username and resolves it
to the identity **inside the identity layer**, through its identifier form.
Peak does not perform that lookup: doing so would make Peak an enumeration
oracle, split the sign-in journey across two systems, and mean that replacing
the identity layer later would change Hospitality Web's login experience.

A POS till has no browser session. Its operator signs in on the terminal with a
device-scoped **PIN** that Peak stores and verifies (hashed, never the same PIN
across two terminals, never usable for the branded web sign-in). The PIN unlocks
the till for a short working shift; the terminal itself still authenticates to
Peak with a machine credential scoped to the property, so a stolen PIN without
the terminal gains nothing. PIN is an unlock mechanism, not an identity-layer
credential.

## Identity principal and memberships

One human is one identity principal. That principal may hold many memberships:
a person can be an owner of one hotel company and a property manager in
another, and signs in once.

```
Identity principal            (sub, canonical username, verified phone, email?)
  └── tenant membership       (tenant, tenant roles, staff number, status)
        └── property membership
```

`identity_links (issuer, subject)` stays unique. It is the one canonical mapping
from an identity-layer subject to a principal. Multi-tenant humans are **not**
modelled by allowing that pair to repeat and point at several independently
modelled users — that would duplicate the human. Membership is what is plural.

After authentication Peak resolves memberships, then establishes business
context:

```
authenticate the human
  → resolve tenant memberships
  → one workspace: enter it
  → several: choose a workspace
  → establish tenant and property context
```

## Assurance levels

SMS is a restricted authenticator and is not phishing-resistant. It is
indispensable for a Tanzania-first operation and is used heavily — but it must
never be treated as equivalent to a hardware security key.

| Level | Established by | Permits |
|---|---|---|
| `ACTIVATION` | Phone OTP against a Peak activation context | Only completing credential setup |
| `RECOVERY` | Phone OTP in the restricted recovery flow | Only re-establishing a credential |
| `STANDARD` | Password, or SMS OTP where explicitly permitted | Lower-risk operational access |
| `STRONG` | Password plus TOTP | Everything, including the operations below |

These operations require `STRONG`, and an SMS-only session must step up before
it can reach them:

- changing a settlement or payout account
- creating or replacing a tenant administrator
- viewing sensitive KYB material
- changing multi-factor enrolment
- security administration

Assurance is derived from the token's authentication claims, not asserted by any
frontend. It is a distinct dimension from route scope and guard mode.

## Activation

An invited operator has no credential yet, so activation cannot begin with a
password. It begins with the phone number Peak already holds and verified during
KYB (`onboarding_applications.representative_phone`).

```
FBC approves the tenant
  → Peak provisions the identity: opaque username, verified phone, no credential
  → Peak sends an activation link by SMS
  → the branded identity flow proves phone possession
  → the identity is authenticated for activation only
  → required actions: create a password; register TOTP if policy demands
  → normal authorization code + PKCE
  → Peak BFF session
```

Credential setup is a **required action in the identity layer**, reached through
a custom authenticator in the browser flow. Peak does not mint action-token
links of its own: required actions and the authentication SPI already exist for
exactly this, and inventing a parallel mechanism would put credential material
back on Peak's side of the boundary.

The consequence is the point: a person's password never passes through
Hospitality Web or the Peak backend, at any stage, including the first one.

## Identity verification gateway

The identity layer needs to send and check one-time codes. Peak's `verification`
module already does that, with rate limiting, HMAC-stored codes and Beem
delivery — so it backs the gateway today.

```
identity-layer authenticator
  → IdentityVerificationGateway
  → narrow verification contract  (issue code, confirm code)
  → Peak verification module → Beem
```

Later the gateway points at FBC Identity Verification instead, and nothing in
the browser flow changes.

The contract is deliberately narrow. The identity layer must never reach
reservation, tenant or any other hospitality business API. Identity
infrastructure depending on hotel business infrastructure is the coupling this
boundary exists to prevent.

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
- The browser flow is two steps: an identifier form that accepts a mobile number
  or username, then a credential form offering a password, then a conditional
  multi-factor step where policy demands one.
- Platform operators must enroll TOTP. Both realms support SHA-256 TOTP with
  time-step and skew tolerances tuned for field conditions.
- New-user self-registration is disabled. Password reset, login/admin event
  recording and brute-force protection are enabled. Email verification applies
  only where an address exists; it is never a precondition for signing in.
- Platform passwords have a 15-character minimum across four character classes.
  Hospitality passwords require ten characters with a digit and a lowercase
  letter, and are checked against username and email — an operator on a shared
  back-office machine has to be able to type it. Peak does not force
  calendar-based rotation; credentials are reset after compromise or recovery.
- Verified-email and password-recovery flows use authenticated SMTP with one
  explicit TLS mode. Identity email is separate from hotel guest messaging.

## Frontend integration contract

Each frontend must use only its assigned issuer and client ID. It requests an
access token for audience `peak-api`, sends it as `Authorization: Bearer`, and
never supplies tenant, property, role or permission claims as authority.

Hospitality Web and Platform Console hold no tokens in the browser at all. Their
Next.js backend-for-frontend performs the authorization code exchange, keeps the
tokens server-side, and gives the browser only an HttpOnly session cookie; the
BFF attaches the bearer token to Peak on the browser's behalf. Neither app ever
collects or forwards a plaintext password — credentials are only ever entered on
the branded identity pages. The POS must
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
