# Reservations Module

Owns Phase 3 guest profiles and reservation lifecycle.

## Responsibilities

- Create and list tenant guest profiles.
- Create direct property reservations.
- Amend pending or confirmed reservations before check-in.
- Cancel pending or confirmed reservations with an audited reason.
- Persist reservation room nights from fixed-scale `BigDecimal` rates.
- Use database exclusion constraints to prevent active room overlap.

## API

- `POST /api/v1/properties/{propertyId}/guests`
- `GET /api/v1/properties/{propertyId}/guests`
- `GET /api/v1/properties/{propertyId}/guests/{guestId}`
- `POST /api/v1/properties/{propertyId}/reservations`
- `GET /api/v1/properties/{propertyId}/reservations`
- `GET /api/v1/properties/{propertyId}/reservations/{reservationId}`
- `PATCH /api/v1/properties/{propertyId}/reservations/{reservationId}`
- `POST /api/v1/properties/{propertyId}/reservations/{reservationId}/cancel`

## Contracts

`ReservationPort` is the only exposed module contract. Frontdesk uses its
current-transaction methods for walk-ins so one HTTP command reserves exactly
one idempotency key.

## Security

All routes are property scoped, deny-by-default, and registered in
`module_access_matrix`. Mutations require `Idempotency-Key`, audit, outbox, and
tenant/property ownership checks.
