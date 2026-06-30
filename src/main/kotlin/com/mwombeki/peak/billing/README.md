# Billing Module

Owns Phase 3 folio, charge, payment-posting, invoice, and checkout validation
contracts.

## Responsibilities

- Open guest folios for reservations.
- Post room and manual charges.
- Reverse posted charges without deleting financial rows.
- Post confirmed cash and mobile-money payments.
- Recalculate folio totals through database financial functions.
- Issue immutable invoice snapshots with document sequencing.
- Publish fiscal outbox events for issued invoices.
- Provide checkout financial state to frontdesk.

## API

- `GET /api/v1/properties/{propertyId}/folios`
- `GET /api/v1/properties/{propertyId}/folios/{folioId}`
- `POST /api/v1/properties/{propertyId}/folios/{folioId}/charges`
- `POST /api/v1/properties/{propertyId}/folios/{folioId}/charges/{chargeId}/reverse`
- `POST /api/v1/properties/{propertyId}/folios/{folioId}/payments`
- `POST /api/v1/properties/{propertyId}/folios/{folioId}/invoice`
- `GET /api/v1/properties/{propertyId}/invoices`
- `GET /api/v1/properties/{propertyId}/invoices/{invoiceId}`

## Engineer B Contract

Provider payment modules must call `BillingPort.postConfirmedPayment` after a
cash or mobile-money transaction is confirmed. Provider calls must not write
directly to billing tables.

## Financial Safety

The module relies on PostgreSQL financial guard functions for totals,
immutability, and document-number allocation. New Phase 3 writes are restricted
to `cash` and `mobile_money`.
