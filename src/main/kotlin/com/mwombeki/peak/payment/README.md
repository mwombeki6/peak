# Payment Module

Owns accountable cash collection, mobile-money intents, provider accounts,
signed callbacks, transaction state, reversals, and reconciliation.

## Invariants

- Cash collection requires one open cashier session for the current user and property.
- Mobile-money initiation never posts a folio payment.
- Only a verified provider callback can confirm a mobile-money transaction.
- Callback signatures cover `timestamp + "." + rawBody`; timestamps have a five-minute replay window.
- Provider event ids and payment transactions are idempotent.
- Money uses `BigDecimal` and TZS at two decimal places.
- Provider credentials are referenced as `env:VARIABLE_NAME`; literal secrets are test-only.
- Payment provider configuration is accepted only when a matching runtime adapter is registered.
- Reversals append a linked transaction; confirmed financial rows are never deleted or rewritten.
- Reconciliation approval requires zero variance and all statement items matched.

## Production

The `contract_mock` adapter is rejected by the `prod` profile. Production uses
the provider-neutral `http_gateway` adapter and an exact HTTPS collection
endpoint stored on the provider account. Its canonical request includes the
transaction id, internal reference, merchant, payer, amount, and currency. The
gateway must return `providerReference` and `status`.

Outbound calls use the payment transaction id as `Idempotency-Key`, reject
redirects, and apply bounded connect/request timeouts. Provider account
`secretRef` and `webhookSecretRef` values must resolve from runtime environment
secrets; they are never returned by APIs. The endpoint host must be present in
the operator-owned `PEAK_OUTBOUND_PROVIDER_ALLOWED_HOSTS` exact-host allowlist.

Provider callbacks use
`POST /api/v1/payments/webhooks/{providerAccountId}`. Scope is resolved from the
provider account in the database, never from callback headers or JSON.

POS uses internal `PaymentPort` commands rather than exposing a second payment
HTTP surface. Cash creates a confirmed transaction linked by `pos_order_id`.
Mobile money follows the same provider outbox and signed-callback flow; a
dedicated POS outbox event closes the order only after confirmation.

## Metrics

- `peak.payment.command{operation,result}`
- `peak.payment.provider.initiation{provider,result}`
- `peak.payment.provider.latency{provider}`
- `peak.payment.webhook.received`
- `peak.payment.webhook.processed{result,status}`
