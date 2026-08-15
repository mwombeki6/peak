# Payment Module

Owns cash and mobile-money collection, canonical transaction state, provider
accounts, checksum callbacks, refunds, reversals, and reconciliation.

## Invariants

- Cash collection/refund requires the current cashier's open property session.
- Mobile-money initiation requires the property account to be **enabled**.
  Configuring credentials is `configured`; it is not collection. Cash and
  post-to-room do not need a PSP. A sibling property's merchant is never inferred.
- Snippe is the guest rail. Catalog `is_enabled` means Peak has a recoverable
  adapter. Property `lifecycle_status` is the collection gate. Production
  ENABLE still requires sandbox evidence of initiate + confirm + independent
  status-query recovery on the account. Peak does not invent a live sandbox run.
- Mobile-money initiation creates `CREATED`; provider acceptance advances it to
  `INITIATED` or `PENDING`.
- A verified provider webhook or status query can produce `POSTED`. Guest
  callbacks use `/api/v1/payments/webhooks/{provider}/accounts/{providerAccountId}`;
  the ClickPesa path is kept because it is already registered with that provider.
- Database guards enforce typed lifecycle transitions, monotonic refund totals,
  immutable posted financial fields, and append-only refund/reversal links.
- Mobile-money refunds require external evidence; provider payouts remain out
  of scope.
- Reconciliation approval requires zero variance and advances matched posted
  transactions to `RECONCILED`.

## Snippe

Guest and POS collection is **direct push**: `POST /v1/payments` with
`payment_type=mobile`, TZS integers (minimum 500), and the payer's name and
email. Peak's handle travels in `metadata.external_reference`. Webhook
`data.external_reference` is Selcom's. Status recovery is
`GET /v1/payments/{reference}` using Snippe's issued reference. Missing payer
identity fails in the adapter; Peak does not invent a guest.

ClickPesa remains a dormant complete-loop candidate, not the launch rail.

## ClickPesa

The integration implements token generation, USSD push, order-reference status
query, checksum webhooks, and statements. Cards, bank payments, payouts, and
disbursements are not implemented. Tokens are cached with refresh skew.
Checksums use recursively key-sorted compact JSON and HMAC-SHA256, excluding
`checksum` and `checksumMethod`.

Callbacks use
`POST /api/v1/payments/webhooks/clickpesa/{providerAccountId}`. Scope comes from
the provider account, never tenant headers or callback data. Stored events
contain sanitized fields, an immutable SHA-256 payload hash, provider time,
checksum method, event key, and processing result.

Under `prod`, mock accounts are forbidden. Production accounts must use an
explicitly approved provider code (Snippe for guest collection),
environment-backed API/checksum secret references, an allowlisted HTTPS host,
and sandbox certification evidence of initiate + confirm + status-query recovery.

## Metrics

- `peak.payment.command{operation,result}`
- `peak.payment.provider.initiation{provider,result}`
- `peak.payment.webhook.processed{result,status}`
- `peak.payment.poll.backlog`
- `peak.payment.webhook.failures`
- `peak.payment.webhook.replays`
- `peak.payment.refunds`
- `peak.payment.reconciliation.backlog`
