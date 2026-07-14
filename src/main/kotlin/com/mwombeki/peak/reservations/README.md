# Reservations Module

Owns guest profiles and the reservation lifecycle.

## Responsibilities

- Create and list tenant guest profiles.
- Record date of birth and nationality for identity policy evaluation.
- Verify masked guest identity records without persisting raw document numbers.
- Attach every occupant and guardian relationship to a reservation.
- Report reservation identity readiness before check-in.
- Create direct property reservations.
- Amend pending or confirmed reservations before check-in.
- Cancel pending or confirmed reservations with an audited reason.
- Persist reservation room nights from fixed-scale `BigDecimal` rates.
- Use database exclusion constraints to prevent active room overlap.

## API

- `POST /api/v1/properties/{propertyId}/guests`
- `GET /api/v1/properties/{propertyId}/guests`
- `GET /api/v1/properties/{propertyId}/guests/{guestId}`
- `PATCH /api/v1/properties/{propertyId}/guests/{guestId}`
- `GET /api/v1/properties/{propertyId}/guests/{guestId}/identity-documents`
- `POST /api/v1/properties/{propertyId}/guests/{guestId}/identity-documents/verify`
- `POST /api/v1/properties/{propertyId}/guests/{guestId}/identity-documents/manual-verification`
- `POST /api/v1/properties/{propertyId}/guests/{guestId}/identity-documents/{documentId}/revoke`
- `POST /api/v1/properties/{propertyId}/reservations`
- `GET /api/v1/properties/{propertyId}/reservations`
- `GET /api/v1/properties/{propertyId}/reservations/{reservationId}`
- `PATCH /api/v1/properties/{propertyId}/reservations/{reservationId}`
- `POST /api/v1/properties/{propertyId}/reservations/{reservationId}/cancel`
- `POST /api/v1/properties/{propertyId}/reservations/{reservationId}/guests`
- `GET /api/v1/properties/{propertyId}/reservations/{reservationId}/guests`
- `DELETE /api/v1/properties/{propertyId}/reservations/{reservationId}/guests/{guestId}`
- `GET /api/v1/properties/{propertyId}/reservations/{reservationId}/identity-readiness`

## Contracts

`ReservationPort`, `GuestIdentityPort`, and `GuestIdentityReadinessPort` are
the exposed module contracts. Frontdesk calls the readiness contract inside
the check-in transaction. Integrations implements
`GuestIdentityVerificationProvider` without owning guest identity state.

## Identity Policy

- Tanzanian adults require a valid verified NIDA or recognised alternative ID.
- Foreign adults require a verified passport, NIDA, or residence permit.
- Minors require a guardian attestation linked to an identity-ready adult.
- Attached adult/child counts must equal the reservation declaration.
- Expired, failed, legacy-unverified, or revoked documents do not qualify.
- Raw document numbers are used transiently, reduced to keyed HMAC plus last
  four characters, and excluded from API responses, audit, outbox, and logs.
- A guest is visible from its origin property and properties where that guest
  has a reservation; a property permission alone cannot read another
  property's guest directory.

## Security

All routes are property scoped, deny-by-default, and registered in
`module_access_matrix`. Mutations require `Idempotency-Key`, audit, outbox, and
tenant/property ownership checks.
