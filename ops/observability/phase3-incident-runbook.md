# Phase 3 Incident Runbook

## Scope

Use this runbook for ClickPesa collection, refund/reconciliation, fiscal
submission/correction, POS variance, and night-audit incidents. Never paste
provider credentials, callback bodies, guest identifiers, or raw database rows
into incident channels.

## Initial Triage

1. Record the Peak correlation id, property id, transaction/invoice/run id, and
   UTC incident time.
2. Check `/actuator/health/readiness` and the sanitized `clickPesa` component.
3. Inspect bounded backlog/failure metrics and worker heartbeat before retrying.
4. Confirm the API and worker use separate runtime roles and the same accepted
   image digest.

## ClickPesa

- Token failures: validate that the account uses environment secret references,
  the referenced variables exist in the worker, and `api.clickpesa.com` is
  allowlisted. Do not print resolved values.
- Pending collection: query the Peak transaction status. Allow scheduled status
  polling to resolve it; do not manually mark it posted.
- Webhook failure/replay: compare event key, immutable payload hash, checksum
  method, provider time, amount, currency, order reference, and provider
  reference. Replays must not create a second folio payment.
- Refund: confirm the linked original and remaining refundable amount. Mobile
  money requires external provider evidence because payouts are out of scope.

## Fiscal

- Keep invoices and folios intact while retrying rejected/timeout submissions.
- Never void an invoice after fiscal acceptance; issue a line-linked credit note.
- Pending correction blocks night audit until the signed provider correction is
  accepted.
- Mock and simulator providers must never be enabled under `prod`.

## Night Audit

- `BLOCKED` is a business-control state; `FAILED` means a technical execution
  failure.
- Completion revalidates live state and advances business date once.
- Open unpaid folios cannot be overridden. Settle the folio and rerun the audit.

## Recovery Evidence

Capture correlation ids, sanitized API responses, alert timestamps, accepted
commit/image digest, and the relevant protected acceptance evidence JSON.
