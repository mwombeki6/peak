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
| `peak-hospitality` | `peak-web` | Public OIDC + PKCE | Front office and hotel operations web |
| `peak-hospitality` | `peak-tenant-admin` | Public OIDC + PKCE | Tenant owner/administrator web |
| `peak-hospitality` | `peak-pos-desktop` | Public native OIDC + PKCE | Tauri/desktop POS |

There are four interactive frontend clients and two realm-local resource-server
registrations. The repeated `peak-api` audience is safe because API runtimes
also require their exact, distinct issuer. CI creates a direct-grant
`peak-acceptance` client dynamically in disposable realms; it is not part of
production configuration.

The platform API trusts only `peak-platform`. The hotel API and worker trust
only `peak-hospitality`. A token from one realm must not authenticate to the
other runtime even when its audience text is `peak-api`.

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

The API remains the source of current user, property, module and permission
state. Frontends may hide unavailable controls for usability, but must treat API
authorization responses as final.

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
