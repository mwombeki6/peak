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

## Money flows

```
FLOW 1 — guest pays property                          hotel revenue
  Guest ──USSD──► AzamPay ──► Property's own account
                      ▲
              Peak instructs with the PROPERTY'S credentials

FLOW 2 — property pays Peak                           software revenue
  Owner ──mobile money │ bank │ card──► AzamPay ──► Peak's own account
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

```
monthly = base
        + max(0, rooms   − included_rooms)   × price_per_extra_room
        + max(0, outlets − included_outlets) × price_per_extra_outlet
```

| | Starter | Pro | Enterprise |
|---|---|---|---|
| Base | 30,000 TZS | 150,000 TZS | Contract |
| Rooms included / extra | 10 / 3,000 | 50 / 2,500 | — |
| Outlets included / extra | 1 / 10,000 | 5 / 8,000 | — |

A 20-room lodge with two outlets pays 70,000 TZS. A 50-room hotel pays 150,000 TZS,
roughly 0.17% of the revenue it processes. Capacity changes reprice at renewal;
mid-cycle proration is deliberately not implemented, because the disputes it generates
exceed the amounts involved.

## Components

| Component | Module | Status |
|---|---|---|
| `PaymentProvider` SPI, `PaymentPort`, `PaymentService` | `payment` | Existing |
| `AzamPayPaymentProvider`, `AzamPaySignature`, `AzamPayPublicKeyProvider`, `AzamPayHealthIndicator` | `integrations` | New |
| `PaymentStatusSweep` | `payment` | New |
| `PlanService`, `SubscriptionService`, `PlatformInvoiceService`, `PlatformCollectionService`, `SubscriptionLifecycle` | `platformbilling` | New module |

`ClickPesaPaymentProvider` is retained and dormant. Removing it before launch is
gratuitous risk; the SPI supports both and AzamPay becomes the default.

### Module boundaries

`integrations` and `payment` need no dependency changes. `integrations` already
declares `payment::api`, so the AzamPay adapter sits where `ClickPesaPaymentProvider`
already sits.

`platformbilling` is the twenty-third module:

```java
@ApplicationModule(
        id = "platformbilling",
        displayName = "Platform Billing",
        allowedDependencies = {
                "shared::context", "shared::exception", "shared::secrets",
                "audit::api", "reliability::api",
                "payment::api", "tenantmanagement::api"
        }
)
```

It depends on `payment::api` for the `PaymentProvider` SPI rather than `PaymentPort`,
because every `PaymentPort` method is property-scoped and writes to guest folios, which
is wrong for Peak's own invoices. Collection uses the provider adapter directly with
Peak's own credentials. No cycle results: `payment` does not depend on
`platformbilling`, and `tenantmanagement` depends only on audit, reliability, shared and
usermanagement.

`plans`, `plan_entitlements` and `tenant_subscriptions` stay owned by
`tenantmanagement`, which remains the authority on what plan a tenant holds.
`platformbilling` reads them through `tenantmanagement::api` and owns only its own new
tables. Moving ownership would mean relocating write paths out of a working module for
no benefit. The cost of this choice is that `tenantmanagement::api` grows slightly.

Adding the module requires a `package-info.java` declaration, an entry in
`module-inventory.md`, and `database-ownership.csv` rows for every new table. All three
are enforced by existing architecture tests.

## Provider integration

Verified against AzamPay's published OpenAPI schema. Community SDKs disagree with it —
they document `/azampay/createtransfer` and a POST status endpoint, neither of which
appears in the schema — so the schema is the reference.

| SPI | AzamPay |
|---|---|
| `initiate()` | `POST /azampay/mno/checkout` |
| `queryStatus()` | `GET /api/v1/azampay/transactionstatus` |
| `parseWebhook()` | `POST /api/v1/Checkout/Callback` |
| `statement()` | not offered — `UnsupportedOperationException` |

Authentication is `POST /AppRegistration/GenerateToken` on a separate authenticator
host, returning `data.accessToken` and `data.expire`. Sandbox hosts are
`authenticator-sandbox.azampay.co.tz` and `sandbox.azampay.co.tz`; the production hosts
are not published and must be obtained from AzamPay.

Collection takes `accountNumber`, `amount`, `currency`, `externalId` and `provider`,
where provider is one of `Airtel`, `Tigo`, `Halopesa`, `Azampesa` or `Mpesa`.

**Amount is capped at 5,000,000 TZS per transaction.** This is a product constraint,
not only an adapter one: a group booking or long stay above the cap cannot be collected
in a single mobile money transaction and needs splitting, a bank transfer or cash. The
adapter rejects an over-cap request before calling the provider so the failure is
Peak's, explained in Peak's language, rather than an opaque provider error.

### Callback verification

Callbacks carry an RSA signature, not an HMAC. Verification is `SHA-256` with
`PKCS#1 v1.5` padding over the concatenation of four callback fields:

```
{utilityref}{externalreference}{transactionstatus}{operator}
```

The public key comes from `GET /azampay/v1/public-key?format=Pem`, is cached, and is
refreshed periodically. On a verification failure the key is re-fetched once before the
callback is rejected, so a key rotation does not present as a wave of forged callbacks.

This makes the AzamPay adapter's key handling different from ClickPesa's: the
verification material is a fetched public key rather than a shared secret held on the
provider account, so `AzamPayPublicKeyProvider` owns it rather than
`payment_provider_accounts`.

The callback body also carries `user`, `password` and `clientId`. These are additional
shared-secret fields and are checked, but the RSA signature is the authority.

### Assurance

AzamPay exposes no statement endpoint, so settlement assurance runs on two
authoritative legs — signed callback and status query — with a scheduled sweep over
pending and recently completed transactions as compensation. The sweep catches a
callback that never arrived. It cannot catch a transaction Peak never knew about, and
the provider health contributor states this rather than implying full coverage.

## Data model

**`plans`** — replace `monthly_usd` and `annual_usd` with `base_monthly_tzs`,
`included_rooms`, `price_per_extra_room_tzs`, `included_outlets`,
`price_per_extra_outlet_tzs`, `trial_days`. Existing capacity columns remain as
entitlement limits. Free is a price of zero, not a special case, so introducing a
Starter price later is a data change.

**`payment_provider_accounts`** — unchanged. Already per tenant and property, already
stores credentials by reference, already rejects inline secrets in production.

**V88** — `production_provider_readiness_counts`, introduced in `V43`, hardcodes
`pp.provider_code <> 'clickpesa'`, so any other provider's active production account
is counted unsafe and the runtime refuses to start. The preceding line already enforces
the approved-codes allowlist, making the comparison redundant. Replace it with a
denylist, matching the pattern the fiscal branch uses.

## Provider connection

Registration happens at AzamPay. No onboarding API exists, so Peak cannot create the
account.

1. Peak presents the requirements — Certificate of Incorporation, business licence,
   TIN, director's identification — and links out.
2. The property registers with AzamPay and receives its credentials.
3. The property enters them in Peak. `configureProvider()` validates them against
   AzamPay's token endpoint before storing, records them as secret references, and
   opens the account in `sandbox`. Promotion to `production` passes the existing
   certification gate.

A sandbox app registration sets its own callback URL, but **a production callback URL is
registered by AzamPay's customer care team after KYC approval**. Connecting a property
in production therefore has a step Peak cannot perform or automate, and the onboarding
flow must set that expectation rather than presenting connection as instant.

## Operational flows

**Property onboarding.** A property is operational before any provider exists.

1. Tenant registered through `registerNewTenant`.
2. Property, rooms and outlets configured. This determines the subscription price,
   since billing is driven by capacity.
3. Subscription starts, on trial or paid.
4. Staff invited.
5. *Optionally*, AzamPay connected. This upgrades mobile money from recorded to
   initiated and changes nothing else.

Step 5 is the only step that involves a payment provider, and the property is fully
working without it.

**Guest stay.** Payment is a checkout concern, not an arrival concern.

| Stage | Payment involvement |
|---|---|
| Check-in | None. `checkIn` takes no payment and opens the folio. |
| During stay | Room nights and POS charges accrue to the folio. |
| Check-out | Folio settled by cash, or by mobile money — recorded against the property's own till when unconnected, initiated by Peak when connected. |
| Unsettled at check-out | `checkOutWithUnpaidOverride`, which is permissioned and audited. |

The capability tier changes only how a mobile money payment is captured. Arrival,
departure, folio behaviour and the audit trail are identical either way, so a property
can connect a provider mid-operation without changing how its staff work.

## Non-payment

| Stage | Restricted |
|---|---|
| Grace | Nothing; warning only |
| Restricted | Administrative capability — new users, new properties, exports |
| Suspended | Read-only |
| Never | Payment acceptance, folio settlement, check-in and check-out |

## Security

Peak stores credentials that can initiate collections and, through
`POST /api/v1/azampay/disburse`, disbursements. A compromise could therefore move a
property's money out, not merely collect on its behalf. Credentials are
envelope-encrypted, stored only by reference, rejected inline in production, and audited
on use.

The material open mitigation is whether AzamPay can issue collection-scoped
credentials with disbursement disabled. If so, a breach can only pull money in, and
the blast radius reduces categorically. This is a question for AzamPay, not a design
decision.

## Build order

1. **V88** — unhardcode the activation guard. Blocks everything else.
2. **AzamPay adapter** — serves both flows.
3. **`platformbilling`** — plans in TZS, subscription lifecycle, collection through the
   adapter from step 2.
4. **Status sweep**.

## Open with AzamPay

Four items are unresolved and none can be settled from the published documentation.

**The signed data is ambiguous.** AzamPay's callback page states the signature covers
`{utilityref}{externalreference}{transactionstatus}{operator}`, while the description of
the `signature` field on the same page states it covers `{utilityref}{externalreference}`
only. Their sample code in five languages uses the four-field form, which is the one
recorded above, but implementing the wrong one fails every callback. Confirm in sandbox
before the verifier is written.

**Production hosts are unpublished.** Only sandbox hosts appear in the documentation.

**Token lifetime is undocumented.** `data.expire` is returned but its unit and duration
are not specified, which the token cache needs.

**Whether the 5,000,000 cap is per transaction, per day, or negotiable at volume.**

Two further questions are commercial rather than blocking: whether credentials can be
scoped to collections only, and what `submerchantAcc` — present in the callback schema
and documented as reserved for future use — is reserved for. The second matters because
sub-merchant support is the capability whose absence produced this architecture.

## Deferred

Metered transaction fees. Split settlement and platform sub-merchants, unavailable from
any Tanzanian provider. SMS reconciliation. Provider-executed refunds. Multi-currency —
`exchange_rate` defaults to 1 and no code sets it, so launch metering and billing are
restricted to TZS-settled properties. Merchant lending, which the transaction ledger
makes reachable later.
