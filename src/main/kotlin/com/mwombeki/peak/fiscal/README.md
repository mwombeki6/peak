# Fiscal Module

Owns fiscal provider configuration, invoice submission, receipt state,
provider mappings, retries, and recovery.

## Invariants

- Only issued invoices can be submitted.
- One fiscal receipt identity exists per invoice.
- Accepted receipts are immutable.
- Rejected receipts require an explicit, idempotent retry command.
- Provider calls run through the outbox worker and every attempt is persisted.
- Checkout requires an accepted receipt unless the dedicated audited override is used.
- Provider credentials use environment-backed secret references.

## Production

The `contract_mock` adapter is rejected under `prod`. Production uses the
provider-neutral `http_gateway` adapter with an exact HTTPS endpoint and an
environment-backed credential reference. Calls reject redirects, use bounded
timeouts, send the receipt id as `Idempotency-Key`, and reject hosts outside
the operator-owned `PEAK_OUTBOUND_PROVIDER_ALLOWED_HOSTS` exact-host allowlist.

The external gateway is responsible for translating Peak's canonical fiscal
request to the approved TRA/EFD/VFD provider contract. A property must complete
provider certification before enabling the configuration in production.

## Metrics

- `peak.fiscal.command{operation,result}`
- `peak.fiscal.provider.submission{provider,result}`
- `peak.fiscal.provider.latency{provider}`
