# Hospitality Platform Control Plane

Peak's platform side is the operating control plane for a multi-tenant
hospitality service. It is deliberately separate from property operations: the
tenant API runs as `peak_app`; control-plane traffic runs as `peak_platform`.
Neither login inherits the other's grant role.

## 1. Tenant control

The tenant catalog exposes cursor pagination and filters over lifecycle,
verification, subscription and service state. Tenant 360 combines profile,
subscription, usage, module, workflow, support, alert and configuration-drift
signals. Lifecycle mutations use optimistic versions and durable workflows.

Restriction and offboarding preserve operational continuity. Archive or final
offboarding is rejected while an in-house stay or active legal hold exists.
Desired and actual configuration versions are reconciled explicitly.

## 2. Commercial control

Plans, subscriptions, plan entitlements and time-bound overrides are distinct
records. Effective entitlements prefer an active override, then the current
plan. Property, room, user and outlet limits are asserted inside the owning
write transaction under a tenant/entitlement advisory lock.

Tenant administrators can inspect their subscription, effective entitlements
and usage. Only billing operators can change commercial state. A subscription
state never silently aborts an active guest journey.

## 3. Trust, privacy and enterprise identity

Verification cases hold evidence metadata and hashes, explicit review states,
risk, expiry and reviewer attribution. Privacy requests support identity
verification, bounded exports to private object storage, completion/rejection,
and legal holds. Enterprise OIDC/SAML/LDAP/SCIM connections keep secret
references—not plaintext credentials—and require platform verification before
activation.

The initial tenant administrator can be invited without a pre-known identity
provider subject. The subject is bound only when the invitation is accepted.

## 4. Support and privileged access

Peak does not impersonate tenant users. It grants a scoped platform session that
passes tenant-bound authorization, which is why the permissions are
`platform.support.access.*` and why Keycloak's native impersonation role is
never granted to Peak support. `platform.support.impersonate` is deprecated and
authorizes nothing; it is retained only so historical grants and audit records
stay interpretable.

Support tickets have customer/internal notes and an immutable event timeline.
Privileged access requires:

1. an open ticket for the exact tenant;
2. an exact operation, resolved through `privileged_operation_policies` rather
   than a permission code alone, because one permission can guard several routes
   whose risk differs by method;
3. authentication assurance proven by the validated token. `acr`, `amr` and
   `auth_time` are compared against the operation's required level and freshness
   window. `platform_users.mfa_enabled` records only that an operator once
   enrolled a factor and no longer authorizes anything;
4. an independent approval quorum. Policies declare seats, each naming a
   permission and a number of distinct approvers. The requester cannot approve,
   one person cannot occupy two seats regardless of how many roles they hold,
   and `platform.admin.all` does not satisfy a seat, so an under-staffed quorum
   leaves the operation unavailable rather than silently shrinking;
5. a bounded duration and use count, enforced atomically by
   `consume_privileged_access`. Ceilings are database constraints per access
   class, so the catalog cannot declare a limit looser than its class permits,
   and destructive operations are barred from eligibility by constraint;
6. activation by the requesting operator, which starts the window; and
7. an authenticated platform token plus exact session and tenant selector
   headers on every supported request.

Approvals bind to a canonical hash and version of the exact request. Changing
the tenant, operation, reason, duration, use limit or ticket bumps the version
and invalidates every prior approval automatically.

One server request consumes at most one use. Deduplication uses a
server-generated execution identifier rather than the caller-supplied
correlation id, which a client can pin across requests. A denied authorization
records evidence but consumes nothing. Consuming the final use exhausts the
grant, which is distinct evidence from time expiry.

Revocation is immediate. Requests, approvals, activation, every consumed or
denied use, exhaustion, expiry and revocation are recorded in append-only tables
that reject `UPDATE` and `DELETE`. Business outcomes are appended separately, so
a crash after consumption records an unknown outcome and never refunds the use.

Tenants are told. Activation enqueues a security notice in the same transaction
as the state change, so it cannot be lost by a later failure and inherits retry
and per-attempt delivery evidence from the notification worker. That notice uses
a legitimate-interest delivery basis: a recipient cannot suppress notification
that their data was accessed by withholding consent, though the channel must
still be verified and active. `GET /api/v1/tenants/{tenantId}/privileged-access`
returns the tenant's own timeline, scoped by the bound database session rather
than by the path parameter.

### Emergency administration

`platform_root` is retained as the database role code but means Platform
Emergency Administrator. It is not a daily workspace. Appointment and revocation
both require a request approved by two distinct security custodians, plus a
fresh phishing-resistant step-up by the operator applying it. Quorum is
re-evaluated at apply time rather than trusted from the request status, because
an approver disabled or stripped of the seat permission after approving must
stop counting.

Production bootstrap provisions two custodians in one transaction, with distinct
emails and distinct identity subjects, so dual control holds from the first
minute and no window exists in which a single account can unilaterally appoint
another root. Development may bootstrap one custodian; production readiness
validation rejects that configuration. Offline zero-root recovery remains the
exceptional path and is refused while any effective root can sign in.

### Known limits

Stated because a control described more strongly than it is enforced is worse
than an absent one.

- When trusted header identity is enabled, privileged operations skip the
  step-up check, since such a runtime carries no token. This is safe only
  because production readiness validation rejects header identity under `prod`.
  That coupling is asserted: `PrivilegedStepUpPolicyTests` proves the carve-out
  is unreachable when header identity is disabled, and the production validator
  has its own test rejecting header identity under `prod`. Relaxing either
  breaks a test that names the dependency. Support access and emergency
  administration share one implementation of this rule, so they cannot diverge
  under identical configuration.
- Realm reconciliation authenticates as the per-realm `peak-realm-reconciler`
  service account using client credentials. Each realm's client can administer
  only that realm, so a compromised reconciliation credential cannot administer
  the server. The master-realm password grant remains only for first
  installation, before the service account exists to authenticate with, and is
  refused unless `KEYCLOAK_ALLOW_BOOTSTRAP_ADMIN=true`; production validation
  rejects that switch.

  One step is not automated and must be performed once per realm by an operator:
  granting the service account its roles. It needs `manage-clients` and
  `view-clients` from that realm's `realm-management` client, plus
  `manage-authentication` for required actions. It must not receive
  `realm-admin`, which would restore the broad authority this replaces. The
  grant is not expressed in the realm templates because those drive a partial
  client reconciliation rather than a full realm import, so a role assignment
  written there would be silently ignored rather than applied.
- The reverse proxy that must block `/admin/**` and `/realms/master/**` on the
  public hostname is not configured in this repository, so nothing here proves
  that isolation. Environment validation asserts the configuration that makes
  the block possible, not the block itself.

## 5. Fleet, release and feature control

Fleet state includes services, timestamped health, jobs/runs, degradation
alerts and incident state machines. A degraded/down health observation opens a
deduplicated alert; recovery resolves it.

Releases use semantic `v1.x.y` versions and immutable SHA-256 image digests.
The creator cannot approve the release. Approved releases move through canary,
rollout, stable, pause or recall states, with per-channel/tenant assignments and
explicit rollback evidence.

Feature flags resolve in property → tenant → platform order. Percentage rollout
uses a stable SHA-256 bucket so the same property never oscillates between
cohorts.

## 6. Hospitality portfolio control

Organization units model portfolios, brands, regions, hubs and management
groups using cycle-safe materialized paths and optimistic versions. Properties
may belong to multiple groups but have at most one primary membership.

Configuration templates have append-only, canonical JSONB SHA-256 revisions.
Active revisions can be scheduled, canaried, applied, failed or rolled back for
a property or inherited organization unit. Effective property configuration
prefers a property assignment, then the deepest matching organization unit.

## 7. Identity and runtime security

Keycloak uses separate `peak-platform` and `peak-hospitality` realms, verified
email, short access tokens, refresh-token rotation, brute-force protection,
SHA-256 TOTP, WebAuthn/passkeys, stronger passwords, security events and
detailed admin events. Platform MFA enrollment is mandatory. Direct grants and
implicit flow remain disabled; browser and native clients use authorization
code with PKCE S256 and exact redirects. Keycloak organizations and realm roles
do not model Peak tenants or permissions; database identity links and RBAC
remain authoritative. The complete client and responsibility matrix is in
`identity-and-access.md`.

Production has five explicit runtime modes: `migration`, `bootstrap`, `api`,
`platform` and `worker`. Startup validation rejects the wrong database login,
Flyway mode, web/worker topology, unsafe identity headers, token exposure and
local secret/provider defaults.
