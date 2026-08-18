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
  source. A PIN till searches in-house stays through
  `GET .../pos/room-charge-candidates` (`pos.order.settle`, operational). That
  response is stay, room, display name, and posting eligibility only — not a
  folio, reservation, or identity dump. GET /rooms and GET /reservations stay
  STRONG. Settle re-validates in-house status, room assignment, and an open
  folio so a candidate found before checkout cannot be charged after it.
- `servedBy` on an order is the staff member who created it, not the cashier
  who opened the drawer. Any operational staff with `pos.order.manage` may
  create, add, send, and settle against an **open** till. Close and opening
  float remain the opening cashier.
- A non-zero cash variance requires approval by a different user.
- Create-order, add-item, and kitchen-send commands require both HTTP
  idempotency and a stable `clientOperationId`.
- Kitchen sends snapshot recipes and consume stock through `inventory::api`.
  Post-send voids require exact `RETURN_TO_STOCK` compensation or audited
  `WASTE`; KDS changes publish on the authenticated property stream.

## Routes

| Method | Route | Permission |
|---|---|---|
| `POST` | `/api/v1/properties/{propertyId}/pos-config/outlets` | `pos.configure` |
| `POST` | `/api/v1/properties/{propertyId}/pos-config/menu-categories` | `pos.configure` |
| `GET` | `/api/v1/properties/{propertyId}/pos-config/menu-categories?outletId=` | `pos.view` |
| `POST` | `/api/v1/properties/{propertyId}/pos-config/menu-items` | `pos.configure` |
| `GET` | `/api/v1/properties/{propertyId}/pos-config/menu-items?outletId=` | `pos.view` |
| `GET` | `/api/v1/properties/{propertyId}/pos/room-charge-candidates?query=` | `pos.order.settle` |
| `POST` | `/api/v1/properties/{propertyId}/pos-sessions/open` | `pos.session.manage` |
| `GET` | `/api/v1/properties/{propertyId}/pos-sessions/{sessionId}` | `pos.view` |
| `POST` | `/api/v1/properties/{propertyId}/pos-sessions/{sessionId}/close` | `pos.session.manage` |
| `POST` | `/api/v1/properties/{propertyId}/pos-sessions/{sessionId}/variance-approve` | `pos.variance.approve` |
| `POST` | `/api/v1/properties/{propertyId}/pos-orders` | `pos.order.manage` |
| `GET` | `/api/v1/properties/{propertyId}/pos-orders/{orderId}` | `pos.view` |
| `POST` | `/api/v1/properties/{propertyId}/pos-orders/{orderId}/items` | `pos.order.manage` |
| `POST` | `/api/v1/properties/{propertyId}/pos-orders/{orderId}/settle` | `pos.order.settle` |
| `POST` | `/api/v1/properties/{propertyId}/pos-orders/{orderId}/send` | `pos.order.manage` |
| `POST` | `/api/v1/properties/{propertyId}/pos-orders/{orderId}/items/{itemId}/void` | `pos.item.void` |
| `GET/POST` | `/api/v1/properties/{propertyId}/kitchen-tickets/**` | `pos.kitchen.*` |
| `GET` | `/api/v1/properties/{propertyId}/pos-print-jobs` | `pos.view` |
| `POST` | `/api/v1/properties/{propertyId}/pos-print-jobs/{jobId}/claim` | `pos.print.manage` |
| `POST` | `/api/v1/properties/{propertyId}/pos-print-jobs/{jobId}/printed` | `pos.print.manage` |
| `POST` | `/api/v1/properties/{propertyId}/pos-print-jobs/{jobId}/failed` | `pos.print.manage` |
| `POST` | `/api/v1/properties/{propertyId}/pos-print-jobs/{jobId}/reclaim` | `pos.print.manage` |
| `POST` | `/api/v1/properties/{propertyId}/pos-print-jobs/{jobId}/reprint` | `pos.print.manage` |

The database route matrix is authoritative. Static application roles are not
used for POS authorization; tenant roles receive these permissions dynamically.

## Settlement

- `cash`: no provider fields; the server uses the order total.
- `mobile_money`: requires `providerAccountId` and a Tanzanian E.164 phone
  number. The order closes only after the signed callback is processed.
- `room_charge`: requires `stayId` (from the candidate search) or `folioId`
  plus `roomNumber`. The billing module re-validates the in-house stay and
  posts an `F&B` charge.

Night audit blocks while any POS session remains open or awaits variance
approval.
