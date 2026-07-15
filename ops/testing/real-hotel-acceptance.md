# Adversarial real-hotel acceptance

Run `ops/testing/run-real-hotel-acceptance.sh` to exercise the production
topology as a populated hotel rather than as a collection of isolated happy
paths. The gate starts PostgreSQL, Keycloak, separate API and worker runtimes,
MinIO, signed payment/fiscal simulators, and the communication provider.

The journey first completes tenant onboarding and the reservation-to-close
financial loop. It then creates separate Keycloak identities and property roles
for housekeeping, maintenance, stores/procurement, restaurant operations, and
supervision. All business transitions use V1 APIs or workers.

The test deliberately proves that:

- a cleaner cannot inspect their own completed room;
- maintenance staff cannot read inventory and a released room remains dirty;
- stock cannot become negative and a rejected mutation changes no balances;
- purchase-order creators cannot self-approve and receipts cannot exceed the
  approved remaining quantity;
- an offline kitchen replay does not consume stock or create a ticket twice;
- anonymous, malformed/tampered JWT, cross-tenant BOLA, identity-header spoof,
  untrusted CORS, malformed payload, and idempotency-conflict requests fail;
- a concurrent reservation race cannot allocate one room twice;
- a mixed, authenticated workload across 32 operational endpoints and six
  least-privilege staff roles remains inside explicit error-rate, latency, and
  throughput thresholds;
- the day closes once, financial truth is certified, reports are delivered,
  the PDF hash matches, audit/outbox records exist, and no outbox event is dead
  lettered.

Defaults run 800 measured requests with concurrency 24 after warm-up. Override
`REAL_HOTEL_LOAD_REQUESTS`, `REAL_HOTEL_LOAD_CONCURRENCY`, and the
`REAL_HOTEL_LOAD_*` thresholds for a longer staging soak. This bounded CI gate
is a regression and capacity-warning test; it is not a claim of production
capacity without staging hardware and traffic profiles.

Evidence is written to `build/evidence/real-hotel`, including the department
trace, API-security checks, per-endpoint load statistics, close/reporting
evidence, the daily PDF, and `real-hotel-acceptance.json` as the consolidated
result.
