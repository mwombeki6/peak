# Night Audit Module

Owns Phase 3 property close checks and blocking issue capture.

## Responsibilities

- Run property night-audit checks for a business date.
- Record blocking and warning issues in `night_audit_issues`.
- Report open unpaid folios, missing invoices, missing accepted fiscal receipts,
  pending payments, open POS sessions, and overdue stays.
- Emit audit and outbox events for completed audit runs.

## API

- `POST /api/v1/properties/{propertyId}/night-audit`
- `GET /api/v1/properties/{propertyId}/night-audit`
- `GET /api/v1/properties/{propertyId}/night-audit/{runId}`

## Status Semantics

Runs complete with `completed` when no blocking issues exist. Runs complete with
`failed` when blocking operational or financial issues require resolution. A
fiscal override checkout still leaves a night-audit issue until fiscal recovery
accepts the invoice.
