# Peak Phase 3 Implementation Plan

## Goal

Phase 3 delivers the complete reservation-to-checkout financial workflow defined
by the Phase 3 cash and mobile-money guide:

1. Create a reservation or walk-in.
2. Check in the guest and open a stay and folio.
3. Post room, manual, or financial POS charges.
4. Settle with cash, mobile money, or a split of both.
5. Issue an immutable invoice.
6. Fiscalize the invoice.
7. Validate and complete checkout.
8. Reconcile payments, close cashier sessions, and run night audit.

Only `CASH` and `MOBILE_MONEY` are exposed in Phase 3. Cards, bank transfers,
cheques, payouts, and disbursements are outside the Phase 3 API and acceptance
scope.

## Branch And Merge Model

- Integration branch: `integration/phase3`
- Engineer A branch: `feature/phase3-a-core-finance`
- Engineer B branch: `feature/phase3-b-payments-fiscal`
- Both engineer branches target `integration/phase3`.
- Incomplete Phase 3 work does not merge into `master`.
- Engineer A is the migration and shared-contract steward.
- Phase 3 reaches `master` through one final integration pull request after the
  complete exit gate passes.

## Engineer A

Engineer A is Mwombeki, the repository owner working with this implementation
assistant.

Engineer A owns:

- `reservations`: guests, reservations, room nights, room assignment,
  amendments, cancellation, and overlap-safe availability.
- `frontdesk`: walk-ins, check-in, stays, checkout, and controlled overrides.
- `billing`: folios, charges, reversals, balances, payment-ledger contracts,
  invoices, and document numbering.
- `nightaudit`: audit runs, blockers, issue overrides, and business-date close.
- Shared Phase 3 contracts and Spring Modulith named interfaces.
- Flyway migration numbering and integration.
- Permission catalog, immutable system-role grants, route matrix entries, RLS,
  and runtime database grants.
- Final cross-module security, E2E, and production acceptance gates.

## Engineer B

Engineer B owns:

- `payments`: cash, manual mobile-money references, ClickPesa transactions,
  status transitions, webhooks, reversals, refunds, and reconciliation.
- `pos`: financial POS orders/items, cashier sessions, floats, settlement,
  variance approval, and folio transfer.
- `fiscal`: submission workflow, attempts, receipts, recovery, and simulator.
- `integrations`: ClickPesa and fiscal provider adapters only.
- Provider-facing observability, timeout, retry, and concurrency tests.

Engineer B must consume Engineer A's published billing contracts instead of
writing directly to billing-owned tables.

## Module Boundaries

Allowed dependencies:

- `frontdesk -> reservations.api, billing.api`
- `payments -> billing.api`
- `pos -> billing.api, payments.api`
- `fiscal -> billing.api`
- `integrations -> payments.api, fiscal.api`
- `nightaudit -> reservations.api, frontdesk.api, billing.api, payments.api,
  pos.api, fiscal.api`

Cross-module access is through named interfaces. Controllers, repositories,
provider DTOs, and `internal` packages are never exposed. Spring Modulith tests
must reject cycles and internal-package dependencies.

## Persistence And Money

- New migrations start after `V24`; `V1` is never modified.
- Kotlin money values use `BigDecimal`.
- PostgreSQL money values use fixed-scale `numeric`.
- Currency is explicit and Phase 3 defaults to `TZS`.
- `payment_transactions` owns asynchronous payment state.
- `folio_payments` is an immutable directional ledger.
- Pending, failed, and expired payments never reduce a folio balance.
- Posted financial rows cannot be deleted or edited; corrections use linked
  reversal or refund entries.
- All financial records carry tenant and property scope.
- Database constraints enforce positive amounts, valid transitions, unique
  provider references, idempotency, and tenant-safe foreign keys.

Canonical payment states are:

- `CREATED`
- `INITIATED`
- `PENDING`
- `POSTED`
- `FAILED`
- `EXPIRED`
- `REVERSED`
- `REFUNDED`
- `RECONCILED`

## Public Contracts

The billing named interface provides:

- Folio creation and retrieval.
- Charge posting and linked reversal.
- Confirmed payment-ledger posting.
- Payment reversal/refund ledger posting.
- Balance and checkout validation.
- Invoice snapshots for fiscalization.
- Night-audit financial summaries.

The reservation/front-desk named interfaces provide:

- Reservation and stay snapshots.
- Occupancy and arrival/departure checks.
- Checkout state transitions.
- Night-audit operational summaries.

Engineer B publishes payment, POS, and fiscal status interfaces for checkout
and night audit.

## ClickPesa

ClickPesa is the first production mobile-money aggregator.

- Peak exposes only ClickPesa mobile-money collection capabilities.
- Provider card and bank capabilities remain disabled.
- Initiation is outbox-driven and returns `202 Accepted`.
- Provider HTTP calls run outside database transactions.
- Calls use explicit connect/request timeouts and bounded coroutine concurrency.
- Success is accepted only from a verified webhook or verified status query.
- The webhook verifies HMAC-SHA256 in constant time.
- Scope resolves from signed provider-account data, never tenant headers.
- Event age, body size, replay, amount, currency, merchant, tenant, property,
  order reference, and provider reference are validated.
- Stored webhook data is redacted and accompanied by an immutable payload hash.
- Duplicate callbacks result in exactly one payment-ledger entry.

## Fiscalization

- Fiscal submission is outbox-driven.
- The provider SPI is production-oriented and provider-neutral.
- Phase 3 includes a deterministic signed simulator for development and E2E.
- The simulator is forbidden in the production profile.
- Normal checkout requires accepted fiscalization.
- During a provider outage, a separate supervisor permission can authorize
  checkout with a mandatory reason.
- An override leaves fiscal recovery queued and visible to night audit.
- Production activation remains gated on an approved TRA provider, credentials,
  certificates, and sandbox certification.

## Security And Permissioning

Permissions are separated for:

- Reservation view, create, amend, and cancel.
- Check-in, checkout, unpaid override, and fiscal override.
- Folio view, charge posting, and charge reversal.
- Cash posting and manual mobile-money posting.
- Mobile-money initiation and payment status viewing.
- Payment reversal, refund, and reconciliation.
- POS order, session, cash movement, close, and variance approval.
- Invoice issue and void.
- Fiscal submit, retry, and override.
- Night-audit view, run, issue override, and complete.

Every API object lookup enforces tenant and property ownership. Every unsafe
command requires an `Idempotency-Key`, audit record, and correlation ID.
Asynchronous side effects also require an outbox event. Any non-zero cash
variance requires approval by a different authorized user.

Every `/api/v1/**` route must be covered by `module_access_matrix`; unregistered
routes remain denied by default.

## Observability And Performance

- Preserve structured logs and correlation IDs without logging credentials,
  tokens, full phone numbers, or raw provider payloads.
- Record payment state/latency, invalid and duplicate webhooks, reconciliation
  variance, cash variance, fiscal attempts/backlog/failure, open POS sessions,
  and night-audit blockers.
- Add sanitized ClickPesa, fiscal, payment-worker, and night-audit health data.
- Keep JDBC transactions short and free of provider calls.
- Use row locking, exclusion constraints, and uniqueness constraints for
  correctness under concurrency.
- Bound worker parallelism and expose queue age, retry, and dead-letter metrics.

## Implementation Order

1. Shared contracts, database hardening, routes, permissions, and runtime grants.
2. Guests, reservations, room nights, and overlap-safe room allocation.
3. Walk-ins, check-in, stays, folios, and room-charge posting.
4. Financial POS and cash settlement.
5. Manual mobile-money references and split tender.
6. ClickPesa initiation, status polling, signed webhooks, and reconciliation.
7. Invoices, document numbering, fiscal submission, and recovery.
8. Checkout enforcement and night audit.
9. Observability, security hardening, documentation, and production acceptance.

## Test Plan

Required automated coverage includes:

- Unit tests for state machines and financial calculations.
- Controller integration tests for every route.
- Route-matrix and deny-by-default coverage.
- Tenant/property BOLA and RLS tests.
- Runtime database-role privilege tests.
- Idempotency replay, conflict, and parallel-use tests.
- Reservation room-overlap concurrency tests.
- Document-sequence concurrency tests.
- Duplicate and replayed webhook tests.
- Parallel outbox worker and payment-posting tests.
- POS close and cash-variance approval tests.
- Fiscal retry, rejection, acceptance, and override tests.
- Night-audit uniqueness, blocker, override, and completion tests.
- Spring Modulith boundary tests.

Required E2E scenarios are:

1. Walk-in, cash payment, invoice, fiscal receipt, and checkout.
2. Reservation deposit by manual mobile-money reference and final cash.
3. ClickPesa USSD push and verified completion.
4. Cash/mobile-money split tender.
5. Duplicate webhook posting exactly once.
6. Failed or expired payment not reducing the folio balance.
7. Cashier variance requiring independent approval.
8. Manual-reference and ClickPesa statement reconciliation.
9. Authorized reversal/refund and unauthorized denial.
10. Fiscal outage checkout override and later recovery.
11. Night audit blocking open sessions, unpaid folios, pending payments, and
    missing fiscal receipts.
12. Cross-tenant, cross-property, disabled-user, and revoked-identity denial.

## Documentation And Exit Gate

- Update the README for every Phase 3 module.
- Keep OpenAPI synchronized with implementation.
- Add `ops/testing/Peak-Phase-3.postman_collection.json`.
- Add a tracked Phase 3 E2E test guide.
- Update Podman environment validation, dashboards, alerts, provider onboarding,
  backup/recovery, and incident procedures.

Phase 3 is complete only when:

- No manual SQL is required for supported workflows.
- Only cash and mobile money are reachable.
- Every route is permissioned and deny-by-default remains active.
- All database, unit, integration, security, concurrency, and E2E tests pass.
- CI, migration validation, container scanning, and whitespace checks pass.
- Engineer A and Engineer B work is integrated and reviewed.
- Production activation gates for ClickPesa and the approved fiscal provider are
  documented and enforced.
