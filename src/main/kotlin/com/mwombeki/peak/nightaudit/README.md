# Night Audit Module

Owns Phase 3 property close checks and blocking issue capture.

## Responsibilities

- Run property night-audit checks for a business date.
- Record blocking and warning issues in `night_audit_issues`.
- Report open unpaid folios, missing invoices, missing accepted fiscal receipts,
  pending payments, open POS sessions, and overdue stays.
- Emit audit and outbox events for completed audit runs.

## API

- `POST /api/v1/properties/{propertyId}/night-audit`: Runs the audit for the current business date.
- `GET /api/v1/properties/{propertyId}/night-audit`: Lists recent audit runs.
- `GET /api/v1/properties/{propertyId}/night-audit/{runId}`: Gets details of a specific run and its issues.

## Operations

1. **Room Charge Posting**: During each audit run, the system automatically identifies all `checked_in` guests and posts their recurring room charges for the audit date.
2. **Blocker Checks**: The audit checks for open POS sessions, pending payments, missing invoices, and missing fiscal receipts.
3. **Business Date Transition**: Upon a successful run (no blocking issues), the property's `business_date` is automatically incremented.

## Status Semantics

Runs complete with `completed` when no blocking issues exist. Runs complete with
`failed` when blocking operational or financial issues require resolution. A
fiscal override checkout still leaves a night-audit issue until fiscal recovery
accepts the invoice.
