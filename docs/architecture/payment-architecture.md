# Payment Architecture

Peak orchestrates payments and never holds them. Guest money moves from the guest to
the property. Peak issues the instruction, records the outcome, and bills separately
for the software.

## Principles

1. **No custody.** Peak holds no third-party funds, so no Bank of Tanzania payment
   licence is required. This is not traded away for commercial convenience.
2. **Payment acceptance is never blocked.** No billing state stops a property being
   paid by a guest or completing a check-in.
3. **Peak charges for software, not for money movement.** Revenue is subscription.
4. **The product works before any provider is connected.**
5. **Silence is not an answer.** Not knowing whether a payment succeeded is a state of
   its own, distinct from knowing it failed. Collapsing the two is how a customer gets
   charged twice.
6. **Commercial standing never destroys entitlement.** Being overdue restricts what a
   tenant may do; it does not remove what they own.

## Money flows

```
FLOW 1 — guest pays property                          hotel revenue
  Guest ──USSD──► AzamPay ──► Property's own account
                      ▲
              Peak instructs with the PROPERTY'S credentials

FLOW 2 — property pays Peak                           software revenue
  Owner ──mobile money │ bank──► AzamPay ──► Peak's own account
                      ▲
              Peak instructs with PEAK'S OWN credentials
```

Same provider, same adapter, two merchant accounts. Peak is a party to Flow 2 only,
where the money is its own.

## Capability tiers

A property's tier is determined by whether it has connected its own AzamPay account.
Both tiers are fully functional.

| | Unconnected | Connected |
|---|---|---|
| Cash collection | ✅ | ✅ |
| Mobile money | Recorded against the property's existing till | Initiated by Peak, auto-reconciled |
| Folios, night audit, POS, reports | ✅ | ✅ |
| Requires an AzamPay account | No | Yes |

Every property starts unconnected. Connection is an upgrade the property chooses when
automation is worth AzamPay's onboarding to them, not a condition of using Peak.

## Revenue

Subscription only. Priced in TZS. Metered transaction fees are deferred — without
provider-side split, a percentage fee cannot be collected at source, and invoicing a
property for money it has already received costs more to chase than it returns.

The model is **base tiers plus add-ons**, implemented by the `platformbilling` module.
An earlier revision of this document specified per-room and per-outlet metering against
columns added to `plans`; that was replaced because pricing on a technical module makes
an already-sold purchase change when the bundle changes. Products are a commercial
concept of their own, and price lives on `(product, term)`.

| Tier | Monthly | Notes |
|---|---|---|
| Peak Core | 30,000 TZS | The hotel operating loop |
| Peak Pro | 120,000 TZS | Adds maintenance, analytics, capacity |
| Peak Group | Contract | Multi-property; not self-serve |

| Add-on | Monthly | Scope |
|---|---|---|
| Peak POS | 35,000 TZS | Per property |
| Peak Inventory & Procurement | 25,000 TZS | Per property, requires POS |
| Peak Direct | 40,000 TZS | Per tenant |
| Peak Revenue Assurance | 50,000 TZS | Per tenant |

Terms are 1, 3, 6 and 12 months, with the longer term stored as its own price rather
than a discount rate. Twelve months costs nine, which matters more than it looks:
mobile money has no mandate, so every renewal needs the owner to approve a PIN, and an
annual term buys one approval instead of twelve.

Capacity changes reprice at renewal. Mid-cycle proration is deliberately not
implemented, because the disputes it generates exceed the amounts involved.

## Payment rails

A provider is not a rail. AzamPay offers several, and they differ in what the payer must
supply and in what a single transaction may carry.

```
peak_payment_method_capabilities
  (provider, method, currency, collection_flow)
      min_amount, max_amount
      requires_msisdn
      supports_status_query
      is_enabled
```

`collection_flow` is a separate dimension from the rail, because naming a rail does not name
how a customer experiences it. Snippe makes that concrete: it offers mobile money as a USSD
push to a handset Peak supplies (`POST /v1/payments`) *and* as a hosted checkout page
(`POST /api/v1/sessions`). Both are mobile money; only the first is what "click Pay and
answer your phone" means. AzamPay will need the same distinction.

This exists because the mobile money ceiling used to live in the quote: a selection above
5,000,000 TZS was refused outright, as though it were unsellable. It is not. That figure
is a fact about USSD, not about the commercial agreement, and a group annual contract at
8,500,000 is perfectly sellable — it simply cannot be pushed down a PIN prompt. Refusing
to price it created pressure toward genuinely bad answers: splitting one purchase across
several pushes, inventing a partially-paid state, or trimming a term to fit a limit that
has nothing to do with what was agreed.

So a quote is always priced, and carries the rails that can carry it:

```
Quote  8,500,000 TZS
  ├── azampay / mobile_money   ineligible — above the 5,000,000 limit
  └── azampay / bank           eligible
```

Eligibility is offered at quote time and enforced again at payment time, because a quote
is valid for hours and the capability table can move inside that window.

`supports_status_query` is load-bearing rather than descriptive. The migration refuses to
apply if any *enabled* rail has it false: a rail that is switched on but cannot be asked
about is one where a lost callback silently loses a customer's payment.

The full predicate for offering a method is therefore: **configured provider, registered
adapter, declared rail, enabled capability, eligible for this amount and currency** —
and `pay()` re-checks it rather than trusting what a quote said hours ago.

**Bank is declared and disabled.** The endpoints exist and their shape is now known —
`verifybank` then `checkoutbank`, an OTP and a `verificationId`, which is a two-step flow
rather than a push. What remains unconfirmed is the supported bank list, the limits, the
settlement timing, and whether `/azam/v1/bank/transactioninquiry` accepts an identifier
returned at initiation. Until that last one is answered a lost callback is unreconcilable on
the bank rail, so it stays off. The row exists so the shape is reviewable; enabling it is a
data change once the contract is proven.

## Components

| Component | Module |
|---|---|
| `PaymentProvider` SPI, `PaymentPort`, `PaymentService` | `payment` |
| `AzamPayPaymentProvider`, `AzamPaySignature`, `AzamPayPublicKeyProvider`, `AzamPayTokenProvider` | `integrations` |
| `SnippePaymentProvider` | `integrations` |
| `ProductCatalogService`, `PaymentMethodEligibilityService` | `platformbilling` |
| `PurchaseService`, `PlatformCollectionService` | `platformbilling` |
| `PlatformBillingWebhookService`, `PaymentConfirmationService`, `PaymentStatusReconciliationService` | `platformbilling` |
| `PurchaseSettlementOutboxHandler`, `EntitlementReconciler`, `ReceiptService` | `platformbilling` |
| `SubscriptionLifecycleService`, `RenewalOfferService` | `platformbilling` |
| `OperatorReconciliationService`, `PlatformBillingAdminService` | `platformbilling` |
| `PlatformBillingWorkerLifecycle` | `platformbilling` |

`ClickPesaPaymentProvider` is retained and dormant. Removing it before launch is
gratuitous risk; the SPI supports both and AzamPay is the default.

### Module boundaries

`integrations` and `payment` need no dependency changes. `integrations` already declares
`payment::api`, so the AzamPay adapter sits where `ClickPesaPaymentProvider` already sits.

`platformbilling` is the twenty-third module:

```java
@ApplicationModule(
        id = "platformbilling",
        displayName = "Platform Billing",
        allowedDependencies = {
                "shared::context", "shared::exception", "shared::outbound", "shared::secrets",
                "audit::api", "reliability::api",
                "payment::api", "tenantmanagement::api", "property::api", "usermanagement::api"
        }
)
```

It depends on `payment::api` for the `PaymentProvider` SPI rather than `PaymentPort`,
because every `PaymentPort` method is property-scoped and writes to guest folios, which is
wrong for Peak's own revenue. Collection uses the provider adapter directly with Peak's own
credentials.

`tenant_subscriptions`, `tenant_modules` and `property_modules` stay owned by their own
modules. `platformbilling` reaches them through three projection ports —
`TenantEntitlementProjectionPort`, `PropertyModuleProjectionPort` and
`TenantModulePermissionBootstrapPort` — so that the single place a commercial fact becomes a
technical capability is reviewable as one thing. `DatabaseOwnershipArchitectureTests` holds
every module to writing only its own tables, and `ControlPlaneWriterInventoryTests`
classifies every writer of the control plane by the authority it acts under.

## Provider integration

Verified on 13 August 2026 against the OpenAPI documents the sandbox hosts serve
themselves — `sandbox.azampay.co.tz/swagger/v1/swagger.json` and the same path on
`authenticator-sandbox.azampay.co.tz`. Those are the reference, not the prose docs and not
the community SDKs, which document endpoints (`/azampay/createtransfer`, a POST status
endpoint) that do not appear in either. The published docs site renders client-side and
serves no crawlable content, so the swagger documents are also the only machine-readable
form available.

| SPI | AzamPay | Verified |
|---|---|---|
| token | `POST /AppRegistration/GenerateToken` — `{appName, clientId, clientSecret}` | ✅ in spec |
| `initiate()` | `POST /api/v1/checkout/checkoutmno` | ✅ in spec, 401 unauthenticated |
| `initiate()` (legacy path in use) | `POST /azampay/mno/checkout` | exists — 401 unauthenticated — but absent from the spec |
| `queryStatus()` | `GET /api/v1/partner/gettransactionstatus?transactionId=&provider=` | ✅ in spec |
| bank | `POST /api/v1/checkout/verifybank` then `POST /api/v1/checkout/checkoutbank` | ✅ in spec |
| public key | `GET /api/Token/PublicKey` on the **authenticator** host | ✅ in spec, 401 unauthenticated |
| `statement()` | not offered — `UnsupportedOperationException` | — |

Sandbox hosts are `authenticator-sandbox.azampay.co.tz` and `sandbox.azampay.co.tz`; the
production hosts are not published and must be obtained from AzamPay.

`checkoutmno` takes `amount`, `currency`, `accountNumber`, `externalId`,
`serviceActivationCode` and `additionalProperties`. **The spec carries no `provider`
field**, though the adapter sends one and the legacy path accepts it; whether the channel
belongs in `additionalProperties` on the documented path is unconfirmed.

**The response returns `transactionId`**, and `gettransactionstatus` is keyed on exactly
that. So the recovery guarantee holds for mobile money: a lost callback is recoverable
using an identifier obtained at initiation, which is the condition for enabling a rail.

**Bank is a two-step OTP flow**, not a push. `checkoutbank` requires
`merchantAccountNumber`, `merchantMobileNumber`, `otp`, `currencyCode` and `amount`, with
`referenceId`/`verificationId` from a prior `verifybank`. It is nothing like a USSD prompt,
which is why it must not be forced into the same initiation state machine.

### What a provider is allowed to say

Every adapter reduces its provider's vocabulary to one of five outcomes before the payment
domain sees it. `ProviderPaymentStatus` in `payment::api` is that boundary:

```
ClickPesa  SUCCESS / SETTLED  ─┐
Snippe     payment.completed  ─┼─► SUCCEEDED ─► the only status that may post to a folio
AzamPay    success            ─┘

                               ─► PENDING    in flight, ask again
                               ─► FAILED     the provider says it will not happen
                               ─► CANCELLED  the payer walked away
                               ─► UNKNOWN    Peak could not find out
```

This was a `String` until it caused an outage in waiting. Each adapter invented its own word —
ClickPesa emitted `posted`, Snippe and AzamPay emitted `succeeded` — and the domain compared
against `posted`. ClickPesa worked. **Every Snippe and AzamPay callback was rejected**, after
its signature had verified, by Peak's own validator one layer further in. A hotel on either
rail would have watched collections sit pending until the sweep expired them.

`posted` is the tell: that is not something a provider can report, it is a state in Peak's
`payment_transactions`. The first adapter mapped straight onto the database, the boundary was
never written down, and the next two adapter authors had nothing to be wrong about until
production. The same shape had leaked twice more — the domain enumerated ClickPesa's two event
names, and checked a `clientId` field that ClickPesa fills with a merchant id while the others
fill it with the guest's phone number.

Three rules follow, and `ProviderConfirmationChokePointTests` enforces the third:

- **UNKNOWN is never FAILED.** An unrecognised status word means Peak has not been told, so the
  status query keeps running. Collapsing the two is how a guest who paid is asked to pay again.
- **A progress callback is not an error.** It is accepted and leaves the payment in flight.
  Rejecting it sent providers that retry on non-2xx into a loop over a harmless message.
- **The confirmation path never names a provider.** `PaymentWebhookService`,
  `PaymentStatusOutboxHandler`, `PaymentOutboxHandler` and `GuestPaymentConfirmationService`
  are scanned for provider codes. Naming one elsewhere is fine when it is honest — statement
  import really is ClickPesa-only, and the legacy `/webhooks/clickpesa/{id}` route exists
  because a callback URL already registered with a provider cannot be changed unilaterally.
  What must not exist is a general path that quietly assumes one.

`AnyProviderCallbackConfirmsIntegrationTests` drives a real callback body through each of the
three adapters to a posted folio payment. It uses the real adapters rather than a stub on
purpose: a stub would have agreed with whatever the domain expected, which is exactly how this
survived a green suite.

### Callback verification

Callbacks carry an RSA signature, not an HMAC. Verification is `SHA-256` with
`PKCS#1 v1.5` padding over the concatenation of four callback fields:

```
{utilityref}{externalreference}{transactionstatus}{operator}
```

The adapter implements the four-field form and **fails closed**. Accepting either form
would look accommodating and is a vulnerability: if a signature covered only
`{utilityref}{externalreference}`, anyone able to modify a callback in flight could flip
`transactionstatus` from failure to success and it would still verify. Being liberal in
what you accept is a virtue for parsing and a defect for authentication.

The public key comes from `GET /api/Token/PublicKey` on the **authenticator** host, and
never from a host named in the callback body.

That correction matters: the adapter previously fetched
`/azampay/v1/public-key?format=Pem` from the *payments* host, which returns **404**. Callback
verification could therefore never have worked — the first real callback would have failed to
fetch a key and been rejected as unverifiable. No test could see it, because the tests stub
the transport. It was found by probing the live sandbox. A callback is unauthenticated until its signature verifies, so letting it say where the
verifying key comes from would let an attacker present their own key and sign anything. On a
verification failure the key is re-fetched once before the callback is rejected, so a key
rotation does not present as a wave of forged callbacks.

**The signature contract is not in the OpenAPI documents at all.** The word `signature` does
not appear once in either sandbox spec, and the only callback schema they carry is
`DisbursementCallbackRequest`. The collection callback and its RSA signature exist solely in
the prose documentation — the documentation that contradicts itself about which fields are
covered. That is why this cannot be settled by reading, and must be settled in sandbox.

This makes the AzamPay adapter's key handling different from ClickPesa's: the verification
material is a fetched public key rather than a shared secret held on the provider account,
so `AzamPayPublicKeyProvider` owns it rather than `payment_provider_accounts`.

## Knowing whether you were paid

The hardest part of this system is not taking money. It is being certain afterwards.

```
CREATED ─► INITIATED ─► PENDING ─┬─► CONFIRMED
                                 ├─► FAILED
                                 └─► RECONCILIATION_REQUIRED
```

`RECONCILIATION_REQUIRED` means Peak does not know. It is not a failure. Earlier, an attempt
the provider never answered was swept to `expired` and its purchase returned to `quoted` so
the customer could try again — right when the payment genuinely failed, and badly wrong when
it succeeded and only the callback was lost:

```
customer's account debited
  └► callback lost
       └► Peak concludes the payment did not happen
            └► module never activates
                 └► customer is invited to pay a second time
```

Three things prevent that now.

**Silence never concludes failure.** Only an answer from the provider — a signed callback or
a status query — may fail a payment. A timeout, an HTTP 500, a DNS failure, an adapter with
no status endpoint, or a status string Peak does not recognise all mean *unknown*.

**Unknown holds the retry slot.** `uq_peak_payment_attempts_open` covers
`reconciliation_required` alongside the live states, so while an outcome is unknown nothing
*can* offer another payment button. The guard is physical, not advisory. The customer is told
Peak is confirming their payment rather than invited to try again.

**Peak asks.** `PaymentStatusReconciliationService` polls with backoff (30s → 12h), records
what the provider said, and hands off to the same settlement path a callback uses. After the
schedule is exhausted it stops and waits for an operator rather than hammering the provider.

## Settlement

Everything that can learn a payment succeeded funnels through one method.

```
signed callback ────────┐
                        │
provider status query ──┼──► PaymentConfirmationService.confirm()
                        │         attempt confirmed
operator resolution ────┘         purchase paid
                                  settlement enqueued
```

Two settlement implementations would drift, and the drift would stay invisible until a
customer was settled by whichever one had the bug. It takes a row lock on the purchase and is
idempotent on its status, because the callback and the poller genuinely race — a provider that
answers a status query and delivers its retry a second later hits both.

The outbox handler then applies the purchase: grants from each line's frozen
`entitlement_snapshot`, an audit entry, and a receipt, all in one transaction. It is
idempotent on the grants it would write and on `uq_peak_receipts_purchase`, because the outbox
redelivers whenever a worker dies mid-flight.

Settlement runs in the worker rather than in the webhook, so a slow grant cannot look to the
provider like a failed callback and be retried.

## Entitlement convergence

`can_access_module` reads `tenant_modules` and `property_modules`; it never consulted
entitlements. Buying a module enabled it once and nothing ever turned it off, so a lapsed
subscription revoked precisely nothing. `EntitlementReconciler` converges the projection, and
does so **asymmetrically**:

| Grant state | Activation record | Action |
|---|---|---|
| Entitled | never activated | turn on, and remember |
| Entitled | activated before | **leave alone**, whatever its current state |
| Not entitled | — | turn off, unconditionally |

A symmetric reconciler would fight the customer: an administrator who turns POS off while
still paying for it would find it back on within the minute, forever. Convergence only ever
moves toward "must be off" and "has never been decided". Everything between belongs to the
tenant.

`tenant_admin` is never revoked. Switching it off for non-payment would lock a tenant out of
the only page where they could pay — a trap with no exit.

Activation also grants the module's permissions to the tenant-admin role, derived from
`module_access_matrix`. Without that, buying POS flips a flag and changes nothing anyone can
see: `can_access_module` wants both the module *and* a permission inside it, so a customer who
had just paid was refused by the identical code path as one who never had.

## Subscription lifecycle

```
ACTIVE ──T-14d──► RENEWAL_DUE ──lapse──► GRACE ──7d──► RESTRICTED ──14d──► SUSPENDED
   ▲                                        │              │                  │
   └──────────────────── payment ───────────┴──────────────┴──────────────────┘
```

| State | What it costs the customer |
|---|---|
| Grace | Nothing. A hotel three days late must not discover it at 2am with a guest at the desk. |
| Restricted | Growth and administration denied. Front desk, billing, payments, fiscal, night audit, housekeeping and data export all continue. |
| Suspended | Read-only plus four non-negotiables: check out a guest, take a payment, export your data, buy a subscription. |
| Never | Stranding a guest, or locking a tenant out of paying. |

**Suspension survival is a property of a route, not of the controller it lives in.** The first
version of this rule said every route under `/billing/` must stay reachable — right for today's
routes, wrong as a rule, since it makes controller membership the safety classification and a
later `/billing/refunds` or `/billing/contracts/terminate` would inherit access by living next
door. `module_access_matrix.suspension_recovery_safe` records the actual judgement: needed to
understand, pay, recover or obtain evidence for what is owed. Two assertions hold it — a route
claiming the flag must really survive suspension, and a route *not* claiming it must not be
reachable anyway through an over-broad permission. The second found
`/api/tenants/:tenantId/commercial*` on its first run, reachable under suspension because it
shares `tenant.subscription.view`; it is legitimately recovery, and is now classified rather
than accidental.

**"Check out a guest" is more than `checkout.%`.** V91 granted that pattern under suspension
and stopped there. `FrontDeskService.checkOut` refuses with "Checkout requires an issued
invoice" before it refuses for anything else, and `billing.invoice` was not allowed — so the
allowance existed, read correctly, and could not be exercised. Every layer was individually
right: the guard permitted checkout, no guest-serving module reads commercial state, and the
list said exactly what it meant. The gap was between what checkout is *called* and what
checkout *needs*, which only walking the route crosses —
`SuspendedTenantGuestCheckoutIntegrationTests` found it on its first run.

V108 adds `billing.invoice` and asserts at migration time that every permission a lawful
departure passes through resolves against the suspended allowances. Widening to `billing.%`
would have been the wrong fix: voiding invoices and issuing credit notes are not on the path
out of the building.

Restriction is data — `peak_restriction_allowances`, consulted by `tenant_restriction_permits`,
which `can_access_module` ANDs in. That places it on every route the guard covers, and on the
realtime plane for free, since `RealtimeSubscriptionAuthorizer` calls the same function.

**Suspension does not expire the subscription row**, and this is the subtlest decision here.
An expired row leaves the service-granting set, so `effective_tenant_entitlement` resolves no
plan entitlements, the reconciler disables every module, and `can_access_module` then fails at
`is_tenant_module_enabled` — *before* `tenant_restriction_permits` is consulted at all. Every
allowance above would become unreachable and the suspended hotel could not check anyone out.

So the two vocabularies stay separate:

| Commercial control | Relationship-ending |
|---|---|
| active, restricted, suspended | cancelled, expired, terminated |
| "how much of what you own may you use?" | "do you still own it?" |
| automatic, reversible, driven by payment | deliberate, operator-driven |

`CommercialDelinquencyInvariantTests` holds that line: no automatic state may leave the
service-granting set or touch a relationship-ending status.

## Renewal

At T-14 a **renewal offer** is created — not a quoted purchase.

`peak_purchases` allows one open order per tenant, so two concurrent PIN prompts cannot fight
over one handset. A renewal quote sitting open for a fortnight would occupy that slot, and the
owner who tried to add POS to another property the next morning would simply be refused. An
unattended background job must not create checkout objects that contend with what a real
customer is doing.

An offer therefore holds no slot and no price. Accepting one prices against today's catalog
through the ordinary path: a stored amount re-presented each year would grandfather customers
by accident, and grandfathering should be a decision someone makes.

Nothing here charges anything. Mobile money has no mandate — there is no stored instrument and
no standing authority — so every collection begins with a customer action in a live request.
`noBackgroundPathInitiatesACollection` asserts that as a property of every path, not one.

The offer is also the idempotency anchor: a double-click returns the purchase that already
exists rather than a second one.

## Reviewing anything consequential

Five questions, in order. A green suite answers at most the first two.

| | Question | What its absence looks like |
|---|---|---|
| **Exists** | Did we build the capability at all? | An enabled rail whose adapter has no `queryStatus` |
| **Correct** | Does it carry the right business meaning? | A commercial receipt presented as fiscal evidence |
| **Reachable** | Can the intended actor get to it through the real path? | A receipt only Peak staff can read; a checkout allowance blocked by an unlisted precondition |
| **Authorized** | Can *only* the right actor reach or mutate it? | One property's merchant context settling its sibling's payment |
| **Recoverable** | Can the system converge when an async step fails? | A lost callback that strands a debited customer forever |

Every defect found in this module fell into three, four or five — never one or two. The work was
built and the work was right; it was unreachable, or unrecoverable, or reachable by the wrong
party. Those are the ones tests do not fail on, because nothing is throwing.

The reachability question is the cheapest to ask and the most often skipped: **who is this for,
and can they get to it in every lifecycle state where they need it?** Asking it of three
artifacts in this module found three separate production defects.

## Five documents, two taxpayers

Peak produces five distinct financial artifacts. They reference each other and must never be
collapsed, because two of them belong to different taxpayers.

```
HOTEL → GUEST                              FBC → HOTEL
under the hotel's TRA identity             under FBC's TRA identity

folio          running guest account       peak_purchase   the order
  ↓                                          ↓
invoice        the hotel's bill            peak_receipt    FBC's commercial receipt
  ↓            allocate_document_number      ↓             allocate_peak_receipt_number
payment_transaction ─► folio_payment       (FBC fiscalization — does not exist yet)
  ↓            PSP evidence, not a receipt
fiscal_receipt TRA-verifiable
```

**The PSP confirmation is not a receipt.** A provider saying SUCCESS proves a payment event and
nothing else. Its reference belongs on the payment record as evidence — `Provider: AzamPay,
ref AZM-…` — and printing that reference as though it were the hotel's receipt would be
producing a tax document out of a webhook body.

**The folio is not the invoice.** The folio is a running account; the invoice is the billing
document issued from it, immutable once issued, corrected through credit notes rather than
overwritten. This is why `billing.invoice` turned out to be load-bearing for checkout.

**`peak_receipts` is not a fiscal receipt.** It is FBC's commercial evidence that a tenant
bought a subscription. A number reading `PEAK-RCP-2026-000123` looks official, and that
resemblance is the danger: a TRA fiscal receipt carries the seller's TIN and VRN, EFD
identifiers, a tax breakdown and a verification code TRA's own service answers for, and this
document has none of them. Fiscalizing FBC's own SaaS sales is a separate workflow under FBC's
taxpayer identity that has not been built. `peak_receipts.fiscal_status` carries
`not_applicable` so a screen has to say so rather than a reader having to already know.

**`fiscal_status` describes the sale, never the integration.** This is the one thing to get
right before FBC fiscalization is built. `NOT_APPLICABLE` must keep meaning *this sale is
legitimately outside the fiscal workflow* and must never absorb *the integration is off*, *it
is not configured* or *TRA is unreachable*. If it does, a production configuration fault
silently produces `NOT_APPLICABLE` on a sale that was legally required to be fiscalized, and it
reads as a deliberate exemption forever after because nothing distinguishes the two.

Those belong in a separate, FBC-wide concept:

```
FBC fiscal configuration        this receipt
────────────────────────        ────────────
DISABLED                        NOT_APPLICABLE   genuinely exempt
REQUIRED                        PENDING          owed, not yet issued
OPTIONAL                        ISSUED           done, see fiscal_reference
                                FAILED           attempted and refused
```

Under a `REQUIRED` configuration, `NOT_APPLICABLE` must be unreachable: an outage makes a sale
`PENDING` or `FAILED`, both of which are states someone is expected to act on.
`NOT_APPLICABLE` says nobody needs to.

**The two allocators must never meet.** `allocate_document_number` is tenant-scoped and numbers
a hotel's documents; `allocate_peak_receipt_number` numbers FBC's. A shared sequence would put
FBC's sales into a hotel's numbering and make both sets of books unauditable. V110 asserts
neither function reads the other's sequence.

A future FBC invoice belongs in the same shape — `purchase → FBC invoice → payment → FBC
receipt → FBC fiscalization` — and should not be forced onto `peak_receipts`, which would make
one row play invoice, payment receipt and fiscal document at once. That is the same mistake as
collapsing the guest side.

## Receipts

Issued inside the settlement transaction, so a receipt cannot outlive the access it attests to,
and idempotent on the purchase.

Numbered from Peak's own sequence, deliberately **not** `allocate_document_number()`. That
allocator is tenant-scoped and refuses to run outside a tenant PMS context because it numbers a
*property's* documents; numbering Peak's receipts from it would interleave Peak's revenue with
the hotel's. These are also not fiscal receipts — guest-facing fiscal receipts belong to the
`fiscal` module and answer to TRA rules.

The tenant is snapshotted, because a hotel that renames itself next year has not changed what
it was invoiced as.

## Operations

**Commercial standing.** During suspension `tenant_subscriptions.status` reads `past_due` and
the row stays service-granting on purpose. Read alone that column says "broadly fine", and
someone will eventually try to fix it. `peak_tenant_commercial_standing` states the facts
separately: standing, why the relationship is retained, what the tenant may still do,
paid-through, payment status, renewal offer, outstanding amount.

**The reconciliation queue.** `peak_payments_requiring_reconciliation` lists every customer who
may have been debited for something they have not received, with the MSISDN masked, the check
count and the last provider error. This queue growing is the signal that a provider integration
is misbehaving.

**Operator actions, deliberately unequal.**

*Requery* is the ordinary one and decides nothing. It asks the provider through exactly the
path the background sweep uses, so an operator pressing a button is impatience rather than new
authority and cannot reach a conclusion the loop could not.

*Resolve* is the exception, for when the API cannot answer but a human can see the truth in a
portal, a settlement report or a bank statement. It records an **observation**, which then
enters the same settlement path a signed callback enters. Confirming requires evidence, and the
observed amount and currency must match the attempt — misreading a line of a settlement report
is easy, and settling against the wrong figure grants a customer something they did not buy.
`ABANDONED` grants nothing and unblocks nothing.

What this deliberately is not is a *Mark Paid* button. No code path in the operator surface
writes a purchase, grant, receipt, subscription or module flag;
`OperatorReconciliationInvariantTests` enforces that.

Deciding not to collect a debt is not a reconciliation. A waiver or write-off routed through
`CONFIRMED_PAID` would record revenue that never arrived and make the books fiction; it needs
its own audited workflow.

`platform.billing.view` and `platform.billing.reconcile` are separate permissions, so most
support staff can see why a tenant is stuck without being able to declare that money arrived.

## Data model

**`plans`** — unchanged, and deliberately so. `monthly_usd` and `annual_usd` are deprecated in
place rather than replaced with TZS pricing columns: putting a price on a plan would make the
plan the product, and a product's contents change over time while an already-sold purchase must
not. Capacity columns stay as entitlement limits, which is what the `limit.*` synthesis in
`effective_tenant_entitlement` reads.

Pricing lives in `peak_product_prices`, keyed by `(product, term)`.

**`payment_provider_accounts`** — unchanged. Already per tenant and property, already stores
credentials by reference, already rejects inline secrets in production. Peak's own merchant
credentials deliberately do **not** live here: the table is tenant-scoped under RLS, and
`production_provider_readiness_counts` would count Peak's own account among a tenant's and
refuse to start the runtime.

**Cross-tenant reads.** Every billing table is tenant-isolated on `current_tenant_id()`. Worker
sweeps and the operator surface run without a tenant, so they reach across through either a
`SECURITY DEFINER` function with a dedicated `NOBYPASSRLS` owner (`V93`, `V94`) or a
`tenant_or_platform` policy gated on a platform permission (`V100`). Both were added after the
naive version returned zero rows in production while looking healthy — a failure mode the test
suite cannot see, because its connection is a superuser and bypasses RLS.

**Latent, not blocking** — `production_provider_readiness_counts`, introduced in `V43`,
hardcodes `pp.provider_code <> 'clickpesa'`, so an active production account for any other
payment provider is counted unsafe and the runtime refuses to start. The preceding line already
enforces the approved-codes allowlist, which makes the comparison redundant; the fiscal branch
of the same function uses a denylist and is the pattern to follow. It becomes a blocker the
moment a second payment provider is introduced for guest collections.

## Provider connection

Registration happens at AzamPay. No onboarding API exists, so Peak cannot create the account.

1. Peak presents the requirements — Certificate of Incorporation, business licence, TIN,
   director's identification — and links out.
2. The property registers with AzamPay and receives its credentials.
3. The property enters them in Peak. `configureProvider()` validates them against AzamPay's
   token endpoint before storing, records them as secret references, and opens the account in
   `sandbox`. Promotion to `production` passes the existing certification gate.

A sandbox app registration sets its own callback URL, but **a production callback URL is
registered by AzamPay's customer care team after KYC approval**. Connecting a property in
production therefore has a step Peak cannot perform or automate, and the onboarding flow must
set that expectation rather than presenting connection as instant.

## Operational flows

**Property onboarding.** A property is operational before any provider exists.

1. Tenant registered through `registerNewTenant`, which also writes its trialing subscription.
2. Property, rooms and outlets configured. Per-property add-ons are priced by how many
   properties they cover.
3. Subscription starts, on trial or paid.
4. Staff invited.
5. *Optionally*, AzamPay connected. This upgrades mobile money from recorded to initiated and
   changes nothing else.

Step 5 is the only step that involves a payment provider, and the property is fully working
without it.

**Guest stay.** Payment is a checkout concern, not an arrival concern.

| Stage | Payment involvement |
|---|---|
| Check-in | None. `checkIn` takes no payment and opens the folio. |
| During stay | Room nights and POS charges accrue to the folio. |
| Check-out | Folio settled by cash, or by mobile money — recorded against the property's own till when unconnected, initiated by Peak when connected. |
| Unsettled at check-out | `checkOutWithUnpaidOverride`, which is permissioned and audited. |

The capability tier changes only how a mobile money payment is captured. Arrival, departure,
folio behaviour and the audit trail are identical either way.

## Security

Peak stores credentials that can initiate collections and, through
`POST /api/v1/azampay/disburse`, disbursements. A compromise could therefore move a property's
money out, not merely collect on its behalf. Credentials are envelope-encrypted, stored only by
reference, rejected inline in production, and audited on use.

The material open mitigation is whether AzamPay can issue collection-scoped credentials with
disbursement disabled. If so, a breach can only pull money in, and the blast radius reduces
categorically. This is a question for AzamPay, not a design decision.

## What was built

Guest payments (Flow 1) need no build: cash and manual recording work today, and no Tanzanian
provider offers the split settlement that would let Peak route guest money to a property
without holding it.

The subscription side (Flow 2) is `platformbilling`:

| Migration | What it does |
|---|---|
| `V88` | Runtime grants and row policies, so the worker can apply a paid subscription |
| `V89` | Commercial schema and the module |
| `V90` | Entitlement resolution taught about grants; `plans.is_active` and the plan fallback fixed |
| `V91` | Selective restriction, so a lapse has a consequence without stranding a guest |
| `V92` | The catalog: tiers, add-ons, prices, permissions and routes |
| `V93` | Webhook scope resolution — a callback carries no tenant, and RLS needs one |
| `V94` | Worker sweep functions, so unbound loops are not silently inert |
| `V95` | Module permission bootstrap, so a bought module becomes visible |
| `V96` | Renewal offers |
| `V97` | Payment status reconciliation; unknown told apart from failed |
| `V98` | Payment method capabilities; rails separated from the sale |
| `V99` | Receipts and the commercial standing view |
| `V100` | Operator reconciliation, and platform read policies on billing tables |
| `V101` | Billing permissions made grantable rather than decorative |

## Open with AzamPay

Provider questions, verified facts and the certification test now live in
[`provider-certification.md`](provider-certification.md). What follows is the summary; that
document is the one to send a provider.

None of these can be settled from the published documentation, and the first is a release gate.

**The signed data is ambiguous, and no spec settles it.** AzamPay's callback page states the
signature covers `{utilityref}{externalreference}{transactionstatus}{operator}`, while the
description of the `signature` field on the same page states it covers
`{utilityref}{externalreference}` only. Their sample code in five languages uses the four-field
form, which is what the adapter implements. Neither sandbox OpenAPI document mentions a
signature at all, so this is genuinely unresolvable without a live callback. If sandbox shows
the two-field form, **the answer is not to relax the verifier** — that would accept a signature
under which `transactionstatus` is unprotected. It is to treat the callback as an untrusted
hint and confirm every settlement with a status query.

**Authenticating the public-key fetch.** `GET /api/Token/PublicKey` answers 401
unauthenticated, so it wants a bearer token. The webhook path does not currently hold the
`clientId` needed to mint one — `verifyAndParseWebhook` receives only the client secret. The
wiring is deliberately not guessed at; it needs one sandbox call to settle whether the token is
required and which credential mints it.

**Whether the channel belongs in `additionalProperties`.** The documented `checkoutmno` schema
has no `provider` field, though the legacy `/azampay/mno/checkout` path accepts one and the
adapter sends it there.

**Bank rail.** The endpoints exist and the shape is known: `verifybank` then `checkoutbank`,
with an OTP and a `verificationId`. What is not known is the supported bank list, amount
limits, settlement timing, the full status vocabulary, and whether
`/azam/v1/bank/transactioninquiry` accepts an identifier returned at initiation. Until that
last one is answered, a lost callback is unreconcilable on the bank rail and it stays disabled.

**Production hosts are unpublished.** Only sandbox hosts appear in the documentation.

**Token lifetime is undocumented.** `data.expire` is returned but its unit and duration are not
specified, which the token cache needs.

**Whether the 5,000,000 cap is per transaction, per day, or negotiable at volume.**

Two further questions are commercial rather than blocking: whether credentials can be scoped to
collections only, and what `submerchantAcc` — present in the callback schema and documented as
reserved for future use — is reserved for. The second matters because sub-merchant support is
the capability whose absence produced this architecture.

**No rail is enabled until certified.** A provider saying it supports a payment method is not
enough. Peak supports a rail when initiation, a durable lookup reference, authenticated
callbacks, independent status recovery, deterministic status mapping, limits and failure
semantics have each been proven in sandbox.

## Deferred

Metered transaction fees.

**Platform sub-merchants, uncertified rather than unavailable.** An earlier revision of this
document said the model was unavailable from any Tanzanian provider. That was too strong, and
the distinction matters because it decides whether double onboarding is a temporary cost or a
permanent one. ClickPesa markets a marketplace BillPay arrangement assigning merchant-specific
numbers that behave like subaccounts; AzamPay advertises multi-entity processing with
individual settlement accounts per entity, though its examples are subsidiaries rather than
unrelated businesses; Flutterwave documents subaccounts and split payments outright. None of
those has been certified for Peak's shape — independent hotels, one embedded onboarding flow,
hotel-owned settlement, platform-level orchestration — and that is the accurate claim:
**Peak has not certified a scalable sub-merchant arrangement, not that none exists.**

Peak should therefore not be architected on the assumption that a hotel will always onboard
its own provider separately.

SMS reconciliation. Provider-executed refunds. Multi-currency —
`exchange_rate` defaults to 1 and no code sets it, so launch billing is restricted to
TZS-settled properties. Merchant lending, which the transaction ledger makes reachable later.

## Snippe

The guest rail. Catalog-enabled and recoverable; property ENABLE is still the
collection gate, and production ENABLE still requires sandbox evidence on the
account. Peak does not mark production globally certified.

The contract is `docs.snippe.sh/docs/2026-01-25` (the installed
`snippe-integration` skill):

| Concern | Contract |
|---|---|
| Auth | `Authorization: Bearer snp_…` |
| Direct push (guest/POS) | `POST /v1/payments` — `payment_type=mobile`, `details.amount` integer TZS (min 500), `phone_number` `255XXXXXXXXX`, `customer.firstname/lastname/email` |
| Identifier at initiation | `data.reference` (UUID), e.g. `9015c155-9e29-4e8e-8fe6-d5d81553c8e6` |
| Peak's handle | `metadata.external_reference` — create has no `external_reference` field |
| Status query | `GET /v1/payments/{reference}` — keyed on Snippe's issued reference |
| Hosted checkout | `POST /api/v1/sessions` — `amount` (min 500), `currency` TZS, `allowed_methods`, `customer`, `webhook_url`, `metadata` |
| Webhook signature | `X-Webhook-Signature`, HMAC-SHA256 hex over `{timestamp}.{raw_body}` |
| Webhook `data.external_reference` | Selcom's, not Peak's |
| Events | `payment.completed`, `payment.failed`, `payment.voided`, `payment.expired` |

Amount units, base URL (`https://api.snippe.sh`), request shape, `snp_` credentials, and metadata echo are settled by the skill. A live sandbox payment is still what an operator records on certify: initiated, confirmed, and independently recovered by status query with the callback dropped. The adapter proves that recovery against a stub transport matching the docs; it does not fake a live run.

`SnippePaymentProvider` implements both flows. Guest/POS collection passes
`collectionFlow=direct_push`. Missing payer name or email fails in the adapter;
Peak does not invent a guest.

ClickPesa stays a dormant complete-loop candidate. `http_gateway` stays off.

**Bank collection is not established.** Snippe documents bank *payouts* to 40+ Tanzanian
banks, and hosted sessions list `allowed_methods: ["mobile_money"]`. Supporting bank
disbursement is not the same as accepting bank collection, and no row claims otherwise.
