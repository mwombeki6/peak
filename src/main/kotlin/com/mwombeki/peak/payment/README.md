# Payment Module

Owns cash and mobile-money collection, canonical transaction state, provider
accounts, checksum callbacks, refunds, reversals, and reconciliation.

## Invariants

- Cash collection/refund requires the current cashier's open property session.
- Mobile-money initiation requires the property account to be **enabled**.
  Configuring credentials is `configured`; it is not collection. Cash and
  post-to-room do not need a PSP. A sibling property's merchant is never inferred.
- Mobile-money initiation creates `CREATED`; provider acceptance advances it to
  `INITIATED` or `PENDING`.
- Only a verified ClickPesa webhook or status result can produce `POSTED`.
- Database guards enforce typed lifecycle transitions, monotonic refund totals,
  immutable posted financial fields, and append-only refund/reversal links.
- Mobile-money refunds require external evidence; provider payouts remain out
  of scope.
- Reconciliation approval requires zero variance and advances matched posted
  transactions to `RECONCILED`.

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

Under `prod`, mock accounts are forbidden. Production accounts must use
ClickPesa, an explicitly approved provider code, environment-backed API/checksum
secret references, an allowlisted HTTPS host, and sandbox certification
metadata.

## Metrics

- `peak.payment.command{operation,result}`
- `peak.payment.provider.initiation{provider,result}`
- `peak.payment.webhook.processed{result,status}`
- `peak.payment.poll.backlog`
- `peak.payment.webhook.failures`
- `peak.payment.webhook.replays`
- `peak.payment.refunds`
- `peak.payment.reconciliation.backlog`
