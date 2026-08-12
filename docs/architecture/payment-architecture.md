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
| `AzamPayPaymentProvider`, `AzamPaySignature`, `AzamPayHealthIndicator` | `integrations` | New |
| `PaymentStatusSweep` | `payment` | New |
| `PlanService`, `SubscriptionService`, `PlatformInvoiceService`, `PlatformCollectionService`, `SubscriptionLifecycle` | `platformbilling` | New module |

`ClickPesaPaymentProvider` is retained and dormant. Removing it before launch is
gratuitous risk; the SPI supports both and AzamPay becomes the default.

`platformbilling` requires a `package-info.java` dependency declaration, an entry in
`module-inventory.md`, and `database-ownership.csv` rows. All three are enforced by
existing architecture tests.

## Provider mapping

| SPI | AzamPay |
|---|---|
| `initiate()` | `MnoCheckout` |
| `queryStatus()` | `TransactionalStatus` |
| `parseWebhook()` | signed callback |
| `statement()` | not offered — `UnsupportedOperationException` |

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

**V76** — `production_provider_readiness_counts` hardcodes
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

## Non-payment

| Stage | Restricted |
|---|---|
| Grace | Nothing; warning only |
| Restricted | Administrative capability — new users, new properties, exports |
| Suspended | Read-only |
| Never | Payment acceptance, folio settlement, check-in and check-out |

## Security

Peak stores credentials that can initiate collections and, through
`createtransfer`, disbursements. A compromise could therefore move a property's money
out, not merely collect on its behalf. Credentials are envelope-encrypted, stored only
by reference, rejected inline in production, and audited on use.

The material open mitigation is whether AzamPay can issue collection-scoped
credentials with disbursement disabled. If so, a breach can only pull money in, and
the blast radius reduces categorically. This is a question for AzamPay, not a design
decision.

## Build order

1. **V76** — unhardcode the activation guard. Blocks everything else.
2. **AzamPay adapter** — serves both flows.
3. **`platformbilling`** — plans in TZS, subscription lifecycle, collection through the
   adapter from step 2.
4. **Status sweep**.

## Deferred

Metered transaction fees. Split settlement and platform sub-merchants, unavailable from
any Tanzanian provider. SMS reconciliation. Provider-executed refunds. Multi-currency —
`exchange_rate` defaults to 1 and no code sets it, so launch metering and billing are
restricted to TZS-settled properties. Merchant lending, which the transaction ledger
makes reachable later.
