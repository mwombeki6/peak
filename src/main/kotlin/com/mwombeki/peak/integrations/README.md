# Integrations Module

Integrations owns public booking sessions and external provider handoffs. It does not own tenant onboarding, platform governance, user management, or provider settlement accounting.

## Public API

- `POST /api/v1/public/properties/{propertyId}/booking-engine/sessions`
- `POST /api/v1/public/properties/{propertyId}/booking-engine/payments/initiate`

Public routes derive tenant and property scope from the URL property id through the database function `resolve_public_property_scope`. Do not trust tenant or property ids from public headers or request bodies.

## Security Model

- Public booking and payment routes are guarded by `module_access_matrix` with `route_scope=public_property`.
- Property access requires an active tenant, active property, and enabled public module access.
- Payment initiation must use `Idempotency-Key` for duplicate-charge protection.
- Provider credentials must come from environment or a secrets manager, never source files.

## Persistence

- Booking sessions and payment transactions are written inside transactions with request context bound to the resolved tenant and property.
- External payment calls should be isolated behind provider adapters and must not leak provider-specific models through the public API.
- Provider callbacks must verify signatures, timestamps, and replay windows before mutating payment state.

## Production Rules

1. Keep public route scope URL-derived.
2. Keep provider secrets out of Git and application YAML defaults.
3. Treat every provider command as idempotent.
4. Emit audit and outbox events for externally visible state changes.
5. Keep provider calls timeout-bound and retry only through explicit, observable policies.
