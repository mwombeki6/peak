# Frontdesk Module

Owns Phase 3 check-in, walk-in, stay, and checkout transitions.

## Responsibilities

- Check in confirmed reservations.
- Create walk-ins from pre-registered, identity-ready guest profiles.
- Maintain stay records and room occupancy state.
- Enforce checkout financial controls.
- Provide a separate fiscal override checkout path with mandatory reason.
- Provide a supervisor-only unpaid checkout override that leaves the folio open.
- Emit one idempotent departure-clean request on checkout.
- Expose live in-house stay summaries through `frontdesk::api` for stayover
  generation without cross-module SQL.

## API

- `POST /api/v1/properties/{propertyId}/checkins`
- `POST /api/v1/properties/{propertyId}/walk-ins`
- `GET /api/v1/properties/{propertyId}/stays`
- `GET /api/v1/properties/{propertyId}/stays/{stayId}`
- `POST /api/v1/properties/{propertyId}/checkouts/{stayId}`
- `POST /api/v1/properties/{propertyId}/checkouts/{stayId}/fiscal-override`
- `POST /api/v1/properties/{propertyId}/checkouts/{stayId}/unpaid-override`

## Checkout Rules

Normal checkout requires:

- Open reservation folio exists.
- Folio balance is fully paid.
- Invoice has been issued.
- Fiscal receipt has been accepted.

Fiscal override checkout is intentionally separated by route and permission. It
does not mark fiscalization complete; night audit continues to surface the
missing accepted fiscal receipt.

Unpaid override requires a supervisor reason and a positive balance. It checks
out the stay without closing the folio, so night audit remains blocked. It
cannot be combined with fiscal override because checkout is a single transition.

## Check-In Identity Gate

Check-in and walk-in commands lock the reservation and evaluate every attached
occupant before changing reservation, stay, or room state. A missing,
expired, failed, or revoked adult identity, an unattested minor, or an
occupant-count mismatch returns `RESERVATION_CONFLICT` with
`GUEST_IDENTITY_INCOMPLETE`. No stay or occupancy mutation is committed.

The assigned room must be `vacant_clean` at check-in. The transition locks the
reservation and room rows so concurrent check-ins cannot occupy one room.
