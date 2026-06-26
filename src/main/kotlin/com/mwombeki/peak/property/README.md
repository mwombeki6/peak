# Property Management Module

Property management owns hotel property setup, readiness, and lifecycle control. It lets tenant admins configure a real property through secured APIs without normal manual SQL.

## Lifecycle

New properties start as `draft` and `isActive=false`. A property becomes publicly/operationally usable only after `POST /api/v1/properties/{propertyId}/activate` passes readiness and sets `status=active`.

Supported states are `draft`, `active`, `suspended`, `frozen`, `archived`, and `terminated`.

## Security

- Routes are covered by `module_access_matrix`.
- Tenant-scoped routes use tenant permissions such as `property.manage`.
- Property-scoped routes require property permission through `user_property_roles`.
- Property creation automatically assigns the creator a system `Property Administrator` property role for the new property so setup can continue by API.
- Every mutating route requires `Idempotency-Key`.
- Mutations record tenant audit events and outbox events.
- RLS is bound inside the service transaction through `DatabaseSessionContext`.

## Main Routes

| Method | Route | Permission | Scope |
| --- | --- | --- | --- |
| `POST` | `/api/v1/properties` | `property.manage` | tenant |
| `GET` | `/api/v1/properties` | `property.view` | tenant |
| `GET` | `/api/v1/properties/{propertyId}` | `property.view` | property |
| `PUT` | `/api/v1/properties/{propertyId}` | `property.manage` | property |
| `DELETE` | `/api/v1/properties/{propertyId}` | `property.lifecycle` | property |
| `POST` | `/api/v1/properties/{propertyId}/activate` | `property.lifecycle` | property |
| `POST` | `/api/v1/properties/{propertyId}/suspend` | `property.lifecycle` | property |
| `POST` | `/api/v1/properties/{propertyId}/archive` | `property.lifecycle` | property |
| `GET` | `/api/v1/properties/{propertyId}/readiness` | `property.view` | property |

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

Activation is denied until all readiness checks pass:

- Tenant is `trial` or `active`.
- Property profile exists and is not archived or terminated.
- At least one active building and floor exist.
- At least one active room type exists.
- At least one active room exists.
- At least one active revenue center exists.
- At least one active tax rate exists.
- Every active room type has a positive base rate.
- Required tenant and property modules are enabled: `property`, `booking_engine`.
- At least one active verified business contact channel exists.

## Operational Notes

- Disable of the core `property` module is blocked to avoid locking admins out of property setup.
- Enabling a property module requires the same tenant module to already be enabled.
- Public booking access remains blocked until the property is active and the `booking_engine` tenant/property modules are enabled.
