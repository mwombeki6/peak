# Integrations Module

Integrations owns external identity-provider adapters. It currently implements
the NIDA verification adapter contract owned by reservations. It does not own
guest identity records, tenant onboarding, platform governance, user
management, payments, fiscalization, or settlement accounting.

## V1 Boundary

The public Booking Engine is outside V1 and has no controller or route
permission. Its existing schema is dormant for a later phase. Payment and
fiscal provider workflows live in their dedicated business modules.

## NIDA Verification

`peak.integrations.nida.mode` supports:

- `disabled`: production-safe controlled physical-document fallback.
- `simulator`: deterministic local/test verification; prohibited in production.
- `cig`: reserved for the official NIDA Common Interface Gateway adapter.

The application exposes NIDA health state and records provider outcomes without
logging request identity data. CIG mode is rejected in production until NIDA
stakeholder onboarding supplies the private wire contract and sandbox.
