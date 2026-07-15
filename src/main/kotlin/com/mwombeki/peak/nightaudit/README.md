# Night Audit Module

Owns property close checks, blocking issue capture, immutable close snapshots,
and the accountable Daily Control revenue-assurance loop.

## Responsibilities

- Run property night-audit checks for a business date.
- Record blocking and warning issues in `night_audit_issues`.
- Report open unpaid folios, missing invoices, missing accepted fiscal receipts,
  pending payments, open POS sessions, and overdue stays.
- Revalidate live summaries before explicit completion and advance the property
  business date exactly once.
- Derive the business date from the property's IANA timezone and configured
  business-day offset.
- Convert discrepancies into one case per property/business-date/control,
  preserve immutable evidence and event history, and reopen recurring failures.
- Publish a certified Daily Control Brief without claiming actual profit before
  complete operating-cost coverage exists.

## API

- `POST /api/v1/properties/{propertyId}/night-audit`: Runs the audit for the current business date.
- `GET /api/v1/properties/{propertyId}/night-audit`: Lists recent audit runs.
- `GET /api/v1/properties/{propertyId}/night-audit/{runId}`: Gets details of a specific run and its issues.
- `POST /api/v1/properties/{propertyId}/night-audit/{runId}/issues/{issueId}/override`
- `POST /api/v1/properties/{propertyId}/night-audit/{runId}/complete`
- `GET /api/v1/properties/{propertyId}/financial-control/briefs/{businessDate}`
- `GET /api/v1/properties/{propertyId}/financial-control/cases`
- `GET /api/v1/properties/{propertyId}/financial-control/cases/{caseId}`
- `POST /api/v1/properties/{propertyId}/financial-control/cases/{caseId}/assign`
- `POST /api/v1/properties/{propertyId}/financial-control/cases/{caseId}/resolve`

## Operations

1. **Blocker Checks**: The audit checks for open POS sessions, pending payments,
   unpaid folios, missing invoices, missing fiscal receipts, and overdue stays.
2. **Immutable Attempts**: Each attempt is retained under a property and
   business date; a property/date advisory lock serializes concurrent runs.
3. **Business Date Control**: Business date is read from the property close
   configuration. Room-charge posting remains an explicit billing operation and
   is not silently retried or swallowed by night audit.

## Status Semantics

Runs transition `running -> blocked/ready -> completed`. `failed` is reserved
for technical failure. Completion revalidates current state; an open unpaid
folio cannot be overridden. A fiscal override checkout still blocks until
fiscal recovery accepts the invoice.

Case workflow state is `open -> assigned -> resolved`. A permitted supervisor
override records `accepted`, never erases the evidence. Completing case work
does not bypass the live close controls; a recurring source failure reopens the
same daily case and increments its occurrence count.

Every attempt is retained. Concurrent requests lock per property/business date,
and completed or failed attempts are never deleted and recreated.
