# Frontdesk Module

Owns Phase 3 check-in, walk-in, stay, and checkout transitions.

## Responsibilities

- Check in confirmed reservations.
- Create walk-ins from pre-registered, identity-ready guest profiles.
- Maintain stay records and room occupancy state.
- Enforce checkout financial controls.
- Provide a separate fiscal override checkout path with mandatory reason.

## API

- `POST /api/v1/properties/{propertyId}/checkins`
- `POST /api/v1/properties/{propertyId}/walk-ins`
- `GET /api/v1/properties/{propertyId}/stays`
- `GET /api/v1/properties/{propertyId}/stays/{stayId}`
- `POST /api/v1/properties/{propertyId}/checkouts/{stayId}`
- `POST /api/v1/properties/{propertyId}/checkouts/{stayId}/fiscal-override`

## Checkout Rules

Normal checkout requires:

- Open reservation folio exists.
- Folio balance is fully paid.
- Invoice has been issued.
- Fiscal receipt has been accepted.

Fiscal override checkout is intentionally separated by route and permission. It
does not mark fiscalization complete; night audit continues to surface the
missing accepted fiscal receipt.

## Check-In Identity Gate

Check-in and walk-in commands lock the reservation and evaluate every attached
occupant before changing reservation, stay, or room state. A missing,
expired, failed, or revoked adult identity, an unattested minor, or an
occupant-count mismatch returns `RESERVATION_CONFLICT` with
`GUEST_IDENTITY_INCOMPLETE`. No stay or occupancy mutation is committed.

The assigned room must be `vacant_clean` at check-in. The transition locks the
reservation and room rows so concurrent check-ins cannot occupy one room.
