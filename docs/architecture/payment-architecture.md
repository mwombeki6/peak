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
  (provider, method, currency)
      min_amount, max_amount
      requires_msisdn
      supports_status_query
      is_enabled
```

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

Metered transaction fees. Split settlement and platform sub-merchants, unavailable from any
Tanzanian provider. SMS reconciliation. Provider-executed refunds. Multi-currency —
`exchange_rate` defaults to 1 and no code sets it, so launch billing is restricted to
TZS-settled properties. Merchant lending, which the transaction ledger makes reachable later.

## Snippe

Built and uncertified. The contract is documented and unambiguous, which is what made it
buildable at all.

| Concern | Contract |
|---|---|
| Auth | `Authorization: Bearer <api key>` |
| Hosted checkout | `POST /api/v1/sessions` — `amount` (min 500), `currency` (TZS default), `allowed_methods`, `customer`, `redirect_url`, `webhook_url`, `metadata` |
| Identifier at initiation | `reference`, e.g. `sess_abc123def456` |
| Status query | `GET /api/v1/sessions/:reference` — keyed on the same reference |
| Webhook signature | `X-Webhook-Signature`, HMAC-SHA256 hex over `{timestamp}.{raw_body}` |
| Signing key | `GET /api/v1/settings/webhook-secret`, or the dashboard |
| Events | `payment.completed`, `payment.failed`, `payment.voided`, `payment.expired` |

Two things stand out against AzamPay. The recovery gate passes cleanly: `reference` is
returned at initiation and is what the status endpoint accepts. And the signature is
unambiguous — one header, one algorithm, one message, with an explicit instruction to verify
against the raw body rather than a re-serialised one, which is the mistake that usually breaks
HMAC verification.

`ProviderCollectionResult.redirectUrl` already exists for the hosted checkout URL. The
`X-Webhook-Signature` scheme needs the raw request body, which the current
`PlatformBillingWebhookController` already takes as a `String` for exactly this reason.

`SnippePaymentProvider` implements hosted checkout, status query and webhook verification,
with a five-minute replay window on `X-Webhook-Timestamp`. Only hosted checkout is
implemented: the direct payments endpoint appears as both `/v1/payments` and
`/api/v1/payments` in the documentation and its request schema is not published, so it was
left alone rather than guessed at.

Three things remain before the rail can be enabled, none settleable by reading:

**Amount units.** Snippe documents "Integer (smallest unit)". For TZS the smallest
circulating unit *is* the shilling, and the magnitudes agree — a documented minimum of 500 is
sensible in shillings and absurd in hundredths — so the adapter treats the value as whole
shillings. If that is wrong the failure is safe rather than silent: settlement already refuses
a callback whose amount disagrees with the attempt, so every payment would be rejected loudly
rather than settled at a hundredth of its value. One sandbox payment settles it.

**The production base URL**, and whether sessions serve a USSD push or only hosted checkout.

**Certification**, on the same terms as any other rail: a sandbox payment initiated, confirmed
by callback, and then independently recovered by status query with the callback dropped.
