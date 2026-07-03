# Phase 4 Department Operations Acceptance

Run `ops/testing/run-phase4-acceptance.sh` from a clean checkout with the
production environment file configured. The runner reuses the Phase 2/3 API-only
foundation, Keycloak JWTs, separate API and worker database roles, and writes
evidence under `build/evidence/phase4`.

Required gates:

- Flyway clean migration and populated `V44` upgrade end at `V49`.
- Checkout outbox processing creates one departure-clean task.
- Inspection-required cleaning needs a different inspector before the room is
  `vacant_clean`.
- Occupied rooms reject maintenance blocks; releases produce `vacant_dirty`.
- Concurrent outgoing inventory commands cannot produce negative stock.
- Transfers have reciprocal movement IDs, source cost, and preserved value.
- Purchase-order creators cannot approve their order; partial receipts cannot
  exceed remaining quantities.
- Replayed kitchen sends create one ticket and one consumption batch.
- Post-send `RETURN_TO_STOCK` creates exact compensating movements; `WASTE`
  leaves consumption in place.
- Cash, room-charge, and simulated mobile-money settlement do not initiate
  additional recipe consumption.
- The authenticated property WebSocket accepts the KDS subscription and the KDS
  read model contains the created ticket.

ClickPesa protected sandbox access is recorded separately and is not a Phase 4
acceptance prerequisite.
