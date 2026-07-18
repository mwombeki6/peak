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

Support tickets have customer/internal notes and an immutable event timeline.
Privileged access requires:

1. an open ticket for the exact tenant;
2. an exact target permission already held by the operator;
3. active MFA;
4. approval by a different platform operator;
5. a bounded duration and use count;
6. activation by the requesting operator; and
7. an authenticated platform token plus exact session and tenant selector
   headers on every supported request.

Revocation is immediate. Every request, approval, activation, use decision and
revocation is auditable.

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

Keycloak is configured for verified email, short access tokens, refresh-token
revocation, brute-force protection, SHA-256 TOTP, stronger passwords,
organization support, security events and detailed admin events. Direct grants
and implicit flow remain disabled; the browser client uses authorization code
with PKCE S256.

Production has five explicit runtime modes: `migration`, `bootstrap`, `api`,
`platform` and `worker`. Startup validation rejects the wrong database login,
Flyway mode, web/worker topology, unsafe identity headers, token exposure and
local secret/provider defaults.
