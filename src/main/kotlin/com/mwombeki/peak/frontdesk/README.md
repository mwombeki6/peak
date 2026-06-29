# Frontdesk Module

Owns Phase 3 check-in, walk-in, stay, and checkout transitions.

## Responsibilities

- Check in confirmed reservations.
- Create walk-ins atomically by composing reservation and billing contracts.
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
