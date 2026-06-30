# Payments Module

The Payments module is responsible for the complete lifecycle of financial collection and reconciliation as defined in Phase . It manages cash collections, manual mobile money references, and integrated USSD push payments via ClickPesa.

## Features

- **Cash Payments**: Direct recording of physical cash collections at POS or Front Desk.
- **Manual Mobile Money**: Recording of M-Pesa/Tigo-Pesa transactions using customer-provided transaction IDs.
- **ClickPesa Integration**: Automated USSD push initiation and asynchronous webhook handling for mobile money collections.
- **Transaction Ledger**: Strict, immutable directional ledger of all financial movements.
- **Reconciliation**: Matching internal records against external provider statements to ensure financial integrity.

## Security

- **Isolation**: Multi-tenant and property-scoped data protection.
- **Webhook Safety**: (Planned) HMAC-SHA256 signature verification for ClickPesa callbacks.
- **Idempotency**: Strict checks on provider references to prevent double-posting of mobile money transactions.

## Main Routes

| Method | Route | Description |
|---|---|---|
| `POST` | `/api/v1/properties/{propertyId}/payments/cash` | Record a cash collection |
| `POST` | `/api/v1/properties/{propertyId}/payments/manual-mobile-money` | Record a manual M-Pesa reference |
| `POST` | `/api/v1/properties/{propertyId}/payments/clickpesa/initiate` | Start an automated ClickPesa USSD push |
| `POST` | `/api/v1/properties/{propertyId}/payments/clickpesa/webhook` | Public endpoint for ClickPesa callbacks |
| `GET`  | `/api/v1/properties/{propertyId}/payments/{id}` | Get transaction status |

## Persistence

All transactions are stored in the `payment_transactions` table with canonical states:
`CREATED` -> `INITIATED` -> `PENDING` -> `POSTED` -> `FAILED` -> `RECONCILED`.
