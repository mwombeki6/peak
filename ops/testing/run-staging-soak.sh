#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(CDPATH= cd -- "$(dirname -- "$0")/../.." && pwd)"

export REAL_HOTEL_WRITE_ORDERS="${REAL_HOTEL_WRITE_ORDERS:-1000}"
export REAL_HOTEL_WRITE_CONCURRENCY="${REAL_HOTEL_WRITE_CONCURRENCY:-24}"
export REAL_HOTEL_WRITE_DURATION_SECONDS="${REAL_HOTEL_WRITE_DURATION_SECONDS:-3600}"
export REAL_HOTEL_WRITE_MAX_P95_MS="${REAL_HOTEL_WRITE_MAX_P95_MS:-5000}"
export REAL_HOTEL_LOAD_REQUESTS="${REAL_HOTEL_LOAD_REQUESTS:-100000}"
export REAL_HOTEL_LOAD_CONCURRENCY="${REAL_HOTEL_LOAD_CONCURRENCY:-64}"
export REAL_HOTEL_LOAD_MAX_P95_MS="${REAL_HOTEL_LOAD_MAX_P95_MS:-5000}"
export REAL_HOTEL_LOAD_MAX_P99_MS="${REAL_HOTEL_LOAD_MAX_P99_MS:-10000}"

# Chaos recovery and the populated backup/restore drill belong to this run, not to the merge
# gate. They prove properties of the deployment — that the API survives losing PostgreSQL, that
# a dump restores with its roles and financial totals intact — which no pull request can change
# and which cost the gate more than an hour. Here there is a two-hour budget and a weekly
# cadence, which is the schedule those questions actually deserve.
export ACCEPTANCE_RESILIENCE_STAGES="${ACCEPTANCE_RESILIENCE_STAGES:-true}"

"$ROOT_DIR/ops/testing/run-real-hotel-acceptance.sh"
