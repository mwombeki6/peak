# Phase 3 End-to-End Test Plan

## Objective

Prove that a configured property can execute a complete accountable stay using
only authenticated APIs: guest registration and identity readiness,
reservation, check-in, folio charging, cash/mobile-money payment, fiscal
submission, outlet POS settlement, checkout, reconciliation evidence, and
night audit.

## Required Runtime

- Phase 2 property acceptance has passed and the property is active.
- PostgreSQL, Keycloak, Peak API, and one Peak worker are running.
- API uses the `peak_app` login; worker uses `peak_worker`; Flyway is disabled
  in both.
- Keycloak token issuer and audience are the production values.
- The test user has `module.manage` plus property-scoped Phase 3 permissions.
- Room type, `vacant_clean` room, revenue center, tax, and base rate ids are
  available from Phase 2 API responses.
- For local acceptance only, `contract_mock` payment/fiscal adapters may be
  enabled. Staging/production acceptance must use the certified
  `http_gateway`.
- NIDA remains disabled until the approved CIG contract is available; use the
  audited physical-document verification route with an authorized verifier.

Never enable trusted identity headers for this session.

## Tools

- Postman collection: `Peak-Phase-3.postman_collection.json`.
- Phase 2 setup collection: `Peak-Phase-2.postman_collection.json`.
- WebSocket client: Postman, Bruno, or `websocat`.
- Database checks are optional evidence queries performed with a read-only
  support login. They are not setup steps.

## Test Data

Use unique values per run:

- `runId`: timestamp or UUID suffix.
- Adult guest with date of birth and nationality.
- Recognized identity document viewed physically.
- Check-in/check-out dates valid for the property business date.
- One room rate and one additional folio charge.
- Cash float and counted closing amount.
- Active outlet, menu category, menu item, and applicable tax rate.

Set Postman variables `baseUrl`, `accessToken`, `tenantId`, `propertyId`,
`roomTypeId`, `roomId`, `revenueCenterId`, `outletId`, and `menuItemId`.

## Execution

### 1. Security Gate

1. Call liveness and readiness; both must be `UP`.
2. Call a secured Phase 3 route without a token; expect `401` or `403`.
3. Use a token for another tenant/property; expect `403`.
4. Confirm response `X-Correlation-Id` is present and no stack trace or SQL is
   returned.

### 2. Phase 3 Module Activation

1. Enable `reservations`, `frontdesk`, `billing`, `payments`, `fiscal`,
   `night_audit`, and `pos` for the tenant.
2. Enable the same seven modules for the property.
3. Replay every activation with the same scoped idempotency key; expect success
   without duplicate state.
4. List enabled modules and confirm every Phase 3 module is active at both
   scopes.

Do not insert module rows manually. The Phase 3 Postman collection performs
these commands before business operations.

### 3. Guest And Identity

1. Create the adult guest and retain `guestId`.
2. Record manual physical-document verification with a recognized document
   type and a non-empty attestation reason.
3. Confirm the response exposes only a masked document number.
4. Reuse the same `Idempotency-Key`; expect the same result with
   `replayed=true`.
5. Reuse that key with a changed body; expect idempotency conflict.
6. Confirm identity list never returns the raw document number.

### 4. Reservation

1. Create a reservation for the verified guest and assigned room.
2. Retain `reservationId` and `folioId`.
3. Read identity readiness; expect `ready=true`.
4. Confirm the reservation is `confirmed`.
5. Attempt an overlapping reservation for the same room; expect conflict.

### 5. Check-In

1. Check in the confirmed reservation; retain `stayId`.
2. Confirm stay status is `checked_in`.
3. Confirm room status becomes occupied.
4. Replay the command; expect the original result without another stay.
5. Attempt a second concurrent check-in to the same room; expect conflict.

### 6. Billing

1. Read the folio.
2. Post an additional charge with quantity, unit price, and tax rate.
3. Confirm subtotal, tax, total, and balance are recalculated by the database.
4. Reverse a disposable test charge and confirm an append-only reversal; the
   original row remains.

### 7. Cash Payment

1. Open a cash session for the current cashier.
2. Attempt to open a second session for the same cashier/property; expect
   conflict.
3. Collect the exact folio balance through the open cash session.
4. Confirm one payment transaction and one linked folio payment exist.
5. Replay the payment command and confirm totals do not change.
6. Reverse a disposable cash payment with an open cash session and confirm a
   linked reversal transaction, never an update/delete of the original amount.

### 8. Mobile Money

Run both paths where applicable:

1. Configure `http_gateway` with an HTTPS endpoint and environment-backed
   secret references.
2. Initiate collection and confirm the worker sends one idempotent provider
   request.
3. Submit a signed callback over the raw body using the provider account id,
   unique event id, current timestamp, and HMAC signature.
4. Confirm the callback resolves scope from the database account and posts one
   folio payment.
5. Replay the provider event; expect `replayed=true` and no duplicate payment.
6. Send a stale timestamp, invalid signature, amount mismatch, currency
   mismatch, and unknown account; each must be rejected.
7. Record a manual reference only with evidence and the dedicated permission;
   duplicate provider reference must conflict.

### 9. Point Of Sale

1. Open an outlet session with an opening float.
2. Replay the same command and confirm no duplicate session is created.
3. Create an order under that session and add an available menu item.
4. Confirm item name, unit price, tax, and total came from server-side menu and
   tax configuration; no client price is accepted.
5. Settle a disposable order with cash and confirm a linked
   `payment_transactions.pos_order_id`, closed order, and incremented expected
   cash.
6. Initiate mobile money for another order; it must remain open and pending
   until the signed provider callback is processed by the worker.
7. Transfer another order to an open guest folio and confirm one `F&B` charge
   with `source_type=pos_order`.
8. Close the session. A zero variance closes immediately; a non-zero variance
   remains pending until a different user with `pos.variance.approve` approves
   it.
9. Attempt cross-property order/session access and expect `403` or `404`
   without data disclosure.

### 10. Fiscalization

1. Configure the fiscal `http_gateway` with an HTTPS endpoint and
   environment-backed credential.
2. Issue the invoice and retain `invoiceId`.
3. Poll fiscal receipts while the worker submits the outbox event.
4. Confirm every attempt is persisted.
5. Accepted response must include the provider document/receipt identifiers.
6. Force one rejected response in a non-production provider sandbox, then use
   the explicit retry route and confirm a new attempt.
7. Confirm accepted fiscal receipt fields cannot be mutated.

### 11. Checkout And Night Audit

1. Attempt checkout before full payment or accepted fiscalization in a
   disposable flow; expect conflict.
2. Perform normal checkout after balance is zero and fiscal receipt accepted.
3. Confirm stay is checked out and room transitions to the configured dirty
   state.
4. Close the cash session with counted cash and verify expected cash/variance.
5. Run night audit without supplying `auditDate`; confirm property timezone and
   business-day offset determine the date.
6. Confirm a clean run is `completed`.
7. Re-run concurrently; attempts must serialize and retain immutable attempt
   numbers.

### 12. Realtime And Audit Evidence

1. Connect SSE with the same tenant/property token.
2. Connect STOMP/WebSocket and subscribe only to the scoped property topic.
3. Perform a property/stay mutation and confirm both clients receive the
   committed event.
4. Reconnect SSE with `Last-Event-ID`; confirm durable replay without
   duplicates.
5. Attempt cross-tenant subscription and client `SEND`; both must be denied and
   security denial metrics/audit must increment.
6. Verify mutation audit records contain correlation id, actor, sanitized
   request address/user agent, action, resource, and no raw identity numbers or
   provider secrets.

## Concurrency Gate

Execute with Postman Runner or Newman in parallel:

- Same idempotency key and payload: one mutation, all responses converge.
- Same idempotency key with different payload: one winner, conflicts for the
  rest.
- Two room reservations/check-ins: database uniqueness prevents overlap.
- Two workers claim the same outbox batch: every event is delivered by one
  worker at a time.
- Concurrent item additions serialize on the order row and totals equal the
  sum of persisted items.
- Repeated POS settlement with one idempotency key creates one payment
  transaction.
- Cash variance approval by the closing user is rejected.
- Concurrent night audit: serialized attempts with no deleted history.

## Pass Criteria

- No manual SQL was used to create normal business data.
- Every secured route enforces DB-backed identity, permission, tenant, and
  property scope.
- All unsafe commands are idempotent and audited.
- Provider side effects are outbox-driven and retry-safe.
- Financial and fiscal history is append-only.
- Worker heartbeat, Keycloak discovery, database, and realtime journal are
  `UP` in readiness.
- Logs and API errors contain correlation ids but no secrets, raw identity
  numbers, SQL, or stack traces.
- `./gradlew test` and both GitHub workflows pass for the tested commit SHA.

Record request/response evidence, correlation ids, provider sandbox evidence,
and the image SHA in the release test report.
