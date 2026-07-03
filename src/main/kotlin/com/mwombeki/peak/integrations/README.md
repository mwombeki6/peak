# Integrations Module

Integrations owns concrete external provider adapters. Provider SPIs and
business state remain in their payment, fiscal, and reservations APIs.

## V1 Boundary

The public Booking Engine is outside V1 and has no controller or route
permission. Its existing schema is dormant for a later phase. Payment and
fiscal orchestration lives in the dedicated business modules.

## Payment And Fiscal

- ClickPesa: token cache, USSD collection, status query, checksum webhook, and
  statement query.
- Signed fiscal simulator: deterministic non-production acceptance scenarios.
- HTTP fiscal adapter: production-neutral boundary pending an approved,
  certified TRA vendor.

## NIDA Verification

`peak.integrations.nida.mode` supports:

- `disabled`: production-safe controlled physical-document fallback.
- `simulator`: deterministic local/test verification; prohibited in production.
- `cig`: reserved for the official NIDA Common Interface Gateway adapter.

The application exposes NIDA health state and records provider outcomes without
logging request identity data. CIG mode is rejected in production until NIDA
stakeholder onboarding supplies the private wire contract and sandbox.
