# Fiscal Module

Owns fiscal configuration, invoice submission, receipt state, retries, and
credit-note correction orchestration.

## Invariants

- Only issued invoices can be submitted and one fiscal receipt identity exists
  per invoice.
- Accepted receipts are immutable; rejected submissions require an explicit
  idempotent retry.
- Fiscalized invoices cannot be voided. Corrections use line-linked credit
  notes and a persisted fiscal-correction outbox flow.
- Checkout overrides never mark fiscalization complete; night audit continues
  to block until recovery succeeds.
- Credentials are environment-backed secret references.

## Providers

Provider calls run through the worker and persist every attempt. The signed
simulator provides deterministic accept, reject, timeout, retry, and credit-note
responses for local acceptance. Both mock and simulator providers are forbidden
under `prod`.

Production remains disabled until an adapter code is explicitly approved and
has secret-backed credentials, an allowlisted HTTPS endpoint, and sandbox
certification metadata. No TRA vendor is assumed by this module.

## Metrics

- `peak.fiscal.command{operation,result}`
- `peak.fiscal.provider.submission{provider,result}`
- `peak.fiscal.correction{provider,result}`
- `peak.fiscal.backlog`
