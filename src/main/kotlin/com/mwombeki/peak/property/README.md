# Property Management Module

Property management owns hotel property setup, readiness, and lifecycle control. It lets tenant admins configure a real property through secured APIs without normal manual SQL.

Housekeeping and maintenance change room readiness only through
`property::api`. This boundary owns clean/dirty, occupancy, maintenance-block,
and release-to-dirty transitions and records each source.

## Lifecycle

New properties start as `draft` and `isActive=false`. A property becomes publicly/operationally usable only after `POST /api/v1/properties/{propertyId}/activate` passes readiness and sets `status=active`.

Supported states are `draft`, `active`, `suspended`, `frozen`, `archived`, and `terminated`.

## Security

- Routes are covered by `module_access_matrix`.
- Tenant-scoped routes use tenant permissions such as `property.manage`.
- Property-scoped routes require property permission through `user_property_roles`.
- Property creation calls `usermanagement::api` to assign the creator a system `Property Administrator` property role for the new property so setup can continue by API.
- Tenants manage property access through `/api/v1/tenants/{tenantId}/properties/{propertyId}/...` user-management APIs; property role definitions are tenant-owned templates and assignments are property-scoped.
- Tenant governors with `tenant.properties.administrators.manage` can appoint a replacement administrator and then revoke the departed administrator. User management serializes this handover and blocks role removal, lock/disable, or final identity-link revocation if it would orphan the property.
- Every mutating route requires `Idempotency-Key`.
- Mutations record tenant audit events and outbox events.
- RLS is bound inside the service transaction through `DatabaseSessionContext`.

## Main Routes

| Method | Route | Permission | Scope |
| --- | --- | --- | --- |
| `POST` | `/api/v1/properties` | `property.manage` | tenant |
| `POST` | `/api/v1/properties/bootstrap` | `property.manage` | tenant |
| `GET` | `/api/v1/properties` | `property.view` | tenant |
| `GET` | `/api/v1/properties/{propertyId}` | `property.view` | property |
| `PUT` | `/api/v1/properties/{propertyId}` | `property.manage` | property |
| `DELETE` | `/api/v1/properties/{propertyId}` | `property.lifecycle` | property |
| `POST` | `/api/v1/properties/{propertyId}/activate` | `property.lifecycle` | property |
| `POST` | `/api/v1/properties/{propertyId}/suspend` | `property.lifecycle` | property |
| `POST` | `/api/v1/properties/{propertyId}/archive` | `property.lifecycle` | property |
| `GET` | `/api/v1/properties/{propertyId}/readiness` | `property.view` | property |
| `GET` | `/api/v1/properties/{propertyId}/onboarding` | `property.view` | property |

## Setup Resources

All setup resources support create, list, get, update, and delete unless noted.

| Resource | Base Route |
| --- | --- |
| Buildings | `/api/v1/properties/{propertyId}/buildings` |
| Floors | `/api/v1/properties/{propertyId}/floors` |
| Room types | `/api/v1/properties/{propertyId}/room-types` |
| Rooms | `/api/v1/properties/{propertyId}/rooms` |
| Room status | `PUT /api/v1/properties/{propertyId}/rooms/{roomId}/status` |
| Revenue centers | `/api/v1/properties/{propertyId}/revenue-centers` |
| Departments | `/api/v1/properties/{propertyId}/departments` |
| Base rates | `POST /api/v1/properties/{propertyId}/rates` |
| Tax rates | `/api/v1/properties/taxes` |
| Property modules | `/api/v1/properties/{propertyId}/modules` |

## Readiness

Activation is denied until the property step machine has no required blockers:

- Property exists and is distinct from the tenant.
- At least one STRONG (Keycloak) Property Administrator is assigned.
- Hotel inventory is in place: building, floor, room type, room, revenue center, tax, positive base rates, `property` module, verified business contact.
- If POS, front desk, or reservations is enabled: a frontline path exists (phone-first staff or an equivalent operational property role). Frontline staff do not need email. POS cashier PIN login is for tills (`POST /staff/sessions`), not go-live.
- Guest mobile money is **not** a go-live blocker. Collecting guest USSD is a later CONFIGURE/ENABLE on the hotel's own Snippe merchant (`payment_provider_accounts`). Hotels do not onboard Snippe as a second product.
- Fiscal/NIDA are not go-live blockers.
- SMS routing is not a hotel activate blocker. WhatsApp is optional. Inbound WhatsApp is out of scope.

`GET /api/v1/properties/{propertyId}/onboarding` and `GET /api/v1/properties/{propertyId}/readiness` return persisted steps, remaining hotel blockers, a single `nextAction` `{ step, title, why, method, path, bodyHint }`, and optional `operatorBlocker`. Evidence is recomputed on every read.

`POST /api/v1/properties/bootstrap` creates a distinct property, attaches the acting STRONG manager as Property Administrator, seeds no rooms, and returns that `nextAction` (usually inventory).

Activate is the last hotel step. A 409 names the same `nextAction`, not a generic refusal.

`sms_routable` is Peak deployment config (`PEAK_COMMUNICATION_ROUTING_SMS`). It is not a hotel task and does not block activate. When frontline is in scope and SMS is not routed, the response sets `operatorBlocker` so Peak ops can route Beem. Bootstrap does not auto-ENABLE Snippe, auto-pair tills, or skip rooms/rates. Guest collection is optional after activate.

## Operational Notes

- Disable of the core `property` module is blocked to avoid locking admins out of property setup.
- Enabling a property module requires the same tenant module to already be enabled.
- The legacy public booking engine is disabled in this release. Property activation does not imply that a public booking channel is available.
- Property-scoped platform outbox events are mirrored into the realtime journal
  by the database in the same transaction as the mutation.
