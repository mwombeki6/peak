# Core Hospitality Journey

Run `ops/testing/run-core-hospitality-journey.sh` from a clean checkout with
`ENV_FILE` pointing to a production-contract environment file. The runner uses
the production Compose topology, Keycloak JWTs, separate migration/API/worker
database roles, signed provider simulators, and public V1 APIs. Setup SQL is not
used for business transitions.

The journey covers platform bootstrap, tenant onboarding, owner identity,
property/room/rate/outlet setup, reservation and guest identity readiness,
check-in, folio and POS charges, cash/mobile settlement, housekeeping,
maintenance, inventory and procurement effects, checkout, invoice/fiscal
processing, and isolated realtime delivery.

Evidence is written to `build/evidence/core-hospitality-journey` and includes:

- tenant/property/resource identifiers and deterministic financial totals;
- business dates, revenue-center attribution, room/stock state, and order data;
- replay and outbox evidence for API and worker commands;
- authorized and cross-tenant WebSocket assertions;
- Newman JSON output with secrets and bearer tokens excluded.

The test fails on unauthorized access, tenant/property mismatch, duplicate
effects, incorrect totals, worker dead letters, or missing evidence.

External ClickPesa credentials are tested separately by
`run-clickpesa-sandbox-acceptance.sh`; the core journey uses deterministic
signed simulators.
