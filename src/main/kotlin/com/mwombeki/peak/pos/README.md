# POS (Point of Sale) Module

The POS module manages financial transactions for property outlets such as restaurants, bars, and gift shops. It handles the complete order lifecycle from session opening to final settlement and provides integration with the billing module for folio transfers.

## Features

- **Session Management**: Tracks cashier shifts, starting floats, expected revenue, and variance approval.
- **Order Lifecycle**: Supports creating orders, adding multiple items, and tracking order status (`OPEN`, `PAID`, `CANCELLED`).
- **Settlement**: Supports `CASH` and `MOBILE_MONEY` payments as per Phase 3 requirements.
- **Folio Transfer**: Allows routing charges from POS outlets directly to a guest's room bill (Folio).
- **Revenue Attribution**: All orders are attributed to specific Revenue Centers for financial reporting.

## Security

- **Tenant & Property Isolation**: All POS data is strictly isolated using `tenant_id` and `property_id`.
- **Role-Based Access**: Routes are protected using `ROLE_CASHIER`, `ROLE_PROPERTY_MANAGER`, and `ROLE_TENANT_ADMIN`.
- **Dual Authorization**: Significant cash variances require approval by a supervisor/manager.
- **Idempotency**: All mutating operations are guarded against duplicate execution.

## Main Routes

| Method | Route | Permission | Scope |
|---|---|---|---|
| `POST` | `/api/v1/properties/{propertyId}/pos/sessions` | `pos.sessions` | property |
| `POST` | `/api/v1/properties/{propertyId}/pos/sessions/{id}/close` | `pos.sessions` | property |
| `POST` | `/api/v1/properties/{propertyId}/pos/sessions/{id}/approve-variance` | `pos.sessions` | property |
| `POST` | `/api/v1/properties/{propertyId}/pos-orders` | `pos.orders` | property |
| `GET`  | `/api/v1/properties/{propertyId}/pos-orders/{id}` | `pos.orders` | property |
| `POST` | `/api/v1/properties/{propertyId}/pos-orders/{id}/items` | `pos.orders` | property |
| `POST` | `/api/v1/properties/{propertyId}/pos-orders/{id}/settle` | `pos.orders` | property |
| `POST` | `/api/v1/properties/{propertyId}/pos/sessions/{id}/transfer-folio` | `pos.orders` | property |

## Integration

The POS module interacts with:
- **Billing API**: For posting manual charges when a "Transfer to Folio" is requested.
- **Payments API**: (Upcoming) For verifying mobile money transactions via aggregators like ClickPesa.
- **Night Audit**: Provides status of open sessions to prevent business date closing while cashiers are active.
