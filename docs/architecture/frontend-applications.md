# Frontend Application Architecture

Peak currently has no implemented frontend. The production target is three
deployable applications backed by two API trust domains. This is the canonical
boundary for the first product release.

## Deployable applications

| Application | Primary users | OIDC client | API runtime | SDK |
|---|---|---|---|---|
| Peak Platform Console | Peak platform operators | `peak-platform-web` | `platform` | `@peak/platform-api-client` |
| Peak Hospitality Web | Tenant owners, property managers and hotel departments | `peak-hospitality-web` | `api` | `@peak/hospitality-api-client` |
| Peak POS Desktop | Cashiers and POS supervisors | `peak-pos-desktop` | `api` | `@peak/hospitality-api-client` |

Platform Console is a separate trust boundary. It has its own Keycloak realm,
origin, issuer, database login, runtime mode and route surface. The platform
runtime returns `404` for non-platform API routes. The hospitality runtime
returns `404` for platform API routes.

Hospitality Web contains these permission-controlled workspaces:

```text
Hospitality Web
├── Owner and tenant administration
├── Property administration
├── Front office
├── Finance and night audit
├── Housekeeping
├── Maintenance
├── Inventory and procurement
└── Reports and Daily Control
```

Tenant administration is not a fourth application. Tenant and property users
share the same realm, organisation data and operating workflows. Navigation is
derived from current server authority; hiding a screen never replaces API
authorization.

POS remains separate because its system-browser OIDC flow, loopback callback,
local device integration, resilient order queue, printers, cash drawer and
desktop release cycle differ materially from a browser application.

## Frontend repository

The intended frontend repository shape is:

```text
peak-frontend/
├── apps/
│   ├── platform-console/
│   ├── hospitality-web/
│   └── pos-desktop/
└── packages/
    ├── auth/
    ├── permissions/
    ├── ui/
    ├── money/
    ├── observability/
    └── test-support/
```

The generated API packages are consumed as build artifacts; they are not copied
or edited in the frontend repository. CI produces:

```text
peak-platform-api-client-<version>.tgz
peak-hospitality-api-client-<version>.tgz
```

One SDK per frontend would be the wrong boundary. Platform Console uses the
platform SDK. Hospitality Web and POS use the hospitality SDK, then add their
own application services and, for POS, an offline synchronisation layer.

## Login and application bootstrap

Browser and desktop clients use authorization code flow with PKCE S256. They
validate issuer, state and nonce and request audience `peak-api`. Browser tokens
remain in memory. POS uses the system browser and the exact RFC 8252 loopback
callback `http://127.0.0.1`; it never embeds a client secret.

After login, each application calls its trust-domain bootstrap route:

| Application | Bootstrap route | Returned authority |
|---|---|---|
| Hospitality Web and POS | `GET /api/v1/session` | Tenant user, tenant roles and permissions, assigned properties, property roles and permissions, enabled modules |
| Platform Console | `GET /api/v1/platform/session` | Platform operator and effective platform permissions |

These responses are derived from the active database identity link and current
RBAC state. The frontend must not infer tenant, property, role or permission
authority from token claims. A recommended startup flow is:

```text
OIDC login
→ call current-session endpoint
→ reject an unavailable or wrong-scope identity
→ select an assigned property when required
→ construct navigation from permissions and enabled modules
→ fetch workspace data through the assigned SDK
```

`tenant.admin.all`, `admin.all` and `platform.admin.all` are wildcard authority
codes. All API responses remain authoritative when cached frontend access state
becomes stale.

## Shared frontend contracts

All three applications must:

- attach `Authorization: Bearer <access-token>`;
- create a stable `X-Correlation-ID` per user action and retain the response
  value for support;
- create and reuse one `Idempotency-Key` for every retry of the same command;
- parse `application/problem+json` without displaying internal details;
- treat `401` as authentication loss, `403` as current authorization denial,
  `404` as absent or deliberately hidden resource and `409` as a state conflict;
- render money from decimal strings with the response currency, never binary
  floating-point arithmetic;
- treat business dates separately from instants and display instants in the
  property timezone;
- implement bounded retries only for safe reads or replay-protected commands;
- clear cached tenant/property data on logout, identity change or permission
  refresh failure; and
- emit release, route, correlation and failure metadata without tokens,
  credentials, guest secrets or signed URLs.

Hospitality Web should be responsive for reception, management and lightweight
department work. It is not the offline staff application. POS must persist a
local append-only command queue, preserve idempotency keys across restarts and
surface conflicts for human resolution rather than silently overwriting server
state.

## Delivery order

The implementation order is:

1. Hospitality Web: login, session bootstrap, property selection, front
   office and the reservation-to-reconciliation journey.
2. POS Desktop: cashier session, orders, room charge, payment, fiscal receipt,
   peripheral health and offline replay.
3. Minimal Platform Console: tenant onboarding, administrator invitation,
   lifecycle, commercial controls, fleet health and support access.

Each application is production-ready only when its own route-level authorization,
error, accessibility, browser/device, contract, end-to-end and hostile-input
tests pass against the production-shaped API topology.

## Deferred clients and split triggers

Staff Mobile and Guest Web are future applications, not current placeholders.
Staff Mobile becomes justified by offline field work, camera evidence, push
notifications and background synchronisation. Guest Web becomes justified by
direct booking, pre-arrival, payment and guest-service journeys. A separate
Portfolio Console is justified only when cross-tenant or large hotel-group
governance has a distinct release and experience boundary.

No new frontend is created merely because a new role or navigation section
exists. A split requires a different trust boundary, runtime, offline/device
model, release cycle or materially different user journey.
