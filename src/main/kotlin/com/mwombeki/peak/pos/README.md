# Point of Sale Module

Owns property outlet cashier sessions and POS orders.

## Guarantees

- Every query is tenant and property scoped; PostgreSQL RLS remains the final
  isolation layer.
- Item names, prices, and tax rules are loaded from the active outlet menu.
  Clients cannot supply prices or settlement amounts.
- Order and session mutations lock the aggregate row and use idempotency,
  audit, outbox events, and command metrics.
- Cash settlement creates a posted payment transaction and increments the
  session's expected cash atomically.
- Mobile-money settlement remains pending until a checksum-verified provider webhook
  confirms it. A POS outbox handler then closes the order.
- Room charges use the billing API and preserve the POS order as the charge
  source.
- A non-zero cash variance requires approval by a different user.

## Routes

| Method | Route | Permission |
|---|---|---|
| `POST` | `/api/v1/properties/{propertyId}/pos-config/outlets` | `pos.configure` |
| `POST` | `/api/v1/properties/{propertyId}/pos-config/menu-categories` | `pos.configure` |
| `POST` | `/api/v1/properties/{propertyId}/pos-config/menu-items` | `pos.configure` |
| `POST` | `/api/v1/properties/{propertyId}/pos-sessions/open` | `pos.session.manage` |
| `GET` | `/api/v1/properties/{propertyId}/pos-sessions/{sessionId}` | `pos.view` |
| `POST` | `/api/v1/properties/{propertyId}/pos-sessions/{sessionId}/close` | `pos.session.manage` |
| `POST` | `/api/v1/properties/{propertyId}/pos-sessions/{sessionId}/variance-approve` | `pos.variance.approve` |
| `POST` | `/api/v1/properties/{propertyId}/pos-orders` | `pos.order.manage` |
| `GET` | `/api/v1/properties/{propertyId}/pos-orders/{orderId}` | `pos.view` |
| `POST` | `/api/v1/properties/{propertyId}/pos-orders/{orderId}/items` | `pos.order.manage` |
| `POST` | `/api/v1/properties/{propertyId}/pos-orders/{orderId}/settle` | `pos.order.settle` |

The database route matrix is authoritative. Static application roles are not
used for POS authorization; tenant roles receive these permissions dynamically.

## Settlement

- `cash`: no provider fields; the server uses the order total.
- `mobile_money`: requires `providerAccountId` and a Tanzanian E.164 phone
  number. The order closes only after the signed callback is processed.
- `room_charge`: requires `folioId`; the billing module validates the open
  folio and posts an `F&B` charge.

Night audit blocks while any POS session remains open or awaits variance
approval.
