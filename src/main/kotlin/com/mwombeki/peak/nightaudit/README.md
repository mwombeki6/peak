# Night Audit Module

Owns Phase 3 property close checks and blocking issue capture.

## Responsibilities

- Run property night-audit checks for a business date.
- Record blocking and warning issues in `night_audit_issues`.
- Report open unpaid folios, missing invoices, missing accepted fiscal receipts,
  pending payments, open POS sessions, and overdue stays.
- Emit audit and outbox events for completed audit runs.
- Derive the business date from the property's IANA timezone and configured
  business-day offset.

## API

- `POST /api/v1/properties/{propertyId}/night-audit`: Runs the audit for the current business date.
- `GET /api/v1/properties/{propertyId}/night-audit`: Lists recent audit runs.
- `GET /api/v1/properties/{propertyId}/night-audit/{runId}`: Gets details of a specific run and its issues.

## Operations

1. **Blocker Checks**: The audit checks for open POS sessions, pending payments,
   unpaid folios, missing invoices, missing fiscal receipts, and overdue stays.
2. **Immutable Attempts**: Each attempt is retained under a property and
   business date; a property/date advisory lock serializes concurrent runs.
3. **Business Date Control**: Business date is read from the property close
   configuration. Room-charge posting remains an explicit billing operation and
   is not silently retried or swallowed by night audit.

## Status Semantics

Runs complete with `completed` when no blocking issues exist. Runs complete with
`failed` when blocking operational or financial issues require resolution. A
fiscal override checkout still leaves a night-audit issue until fiscal recovery
accepts the invoice.

Every attempt is retained. Concurrent requests lock per property/business date,
and completed or failed attempts are never deleted and recreated.
