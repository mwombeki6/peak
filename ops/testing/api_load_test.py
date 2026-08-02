#!/usr/bin/env python3
"""Run a bounded mixed-department load test against a populated hotel."""

from __future__ import annotations

import argparse
import concurrent.futures
import json
import math
import statistics
import sys
import time
import urllib.error
import urllib.request
from collections import Counter
from pathlib import Path
from typing import Any

# Shared with api_write_load_test.py. This harness used to hold one token for
# the whole run, which survives the 800 requests CI issues and not the 100,000
# the weekly soak issues.
from keycloak_tokens import KeycloakTokenManager


class LoadFailure(RuntimeError):
    pass


def call(
    base_url: str,
    role: str,
    token_manager: KeycloakTokenManager,
    path: str,
    sequence: int,
) -> tuple[str, str, int, float, int]:
    started = time.perf_counter()

    def send(access_token: str) -> tuple[int, bytes]:
        request = urllib.request.Request(
            f"{base_url.rstrip('/')}{path}",
            method="GET",
            headers={
                "Authorization": f"Bearer {access_token}",
                "Accept": "application/json",
                "X-Correlation-Id": f"load-{role}-{sequence}",
            },
        )
        try:
            with urllib.request.urlopen(request, timeout=15) as response:
                return response.status, response.read()
        except urllib.error.HTTPError as error:
            try:
                return error.code, error.read()
            finally:
                error.close()

    try:
        token = token_manager.access_token()
        status, raw = send(token)
        # The manager renews ahead of expiry, so a 401 means this worker raced
        # past the renewal window or the token was revoked. One retry with a
        # fresh token. The elapsed time deliberately spans both attempts,
        # because that is what a real client would have waited.
        if status == 401:
            status, raw = send(token_manager.refresh_after_unauthorized(token))
    except (TimeoutError, urllib.error.URLError):
        return role, path, 0, (time.perf_counter() - started) * 1000, 0
    return role, path, status, (time.perf_counter() - started) * 1000, len(raw)


def percentile(values: list[float], value: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    position = max(0, math.ceil(value * len(ordered)) - 1)
    return ordered[position]


def require(condition: bool, message: str) -> None:
    if not condition:
        raise LoadFailure(message)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--keycloak-url", default="http://localhost:8081")
    parser.add_argument("--evidence-dir", required=True, type=Path)
    parser.add_argument("--tenant-password", required=True)
    parser.add_argument("--staff-password", required=True)
    parser.add_argument("--requests", type=int, default=800)
    parser.add_argument("--concurrency", type=int, default=24)
    parser.add_argument("--warmup", type=int, default=30)
    parser.add_argument("--max-error-rate", type=float, default=0.005)
    parser.add_argument("--max-p95-ms", type=float, default=2000)
    parser.add_argument("--max-p99-ms", type=float, default=4000)
    parser.add_argument("--min-throughput", type=float, default=5)
    args = parser.parse_args()
    require(args.requests > 0 and args.concurrency > 0, "Requests and concurrency must be positive")

    foundation = json.loads((args.evidence_dir / "tenant-property-foundation.json").read_text())
    core = json.loads((args.evidence_dir / "core-hospitality-journey.json").read_text())
    departments = json.loads((args.evidence_dir / "real-hotel-departments.json").read_text())
    tenant_id = foundation["tenantId"]
    property_id = foundation["propertyId"]
    room_id = foundation["roomId"]
    pos_session_id = core["posSessionId"]
    core_order_id = core["posOrderId"]
    department_order_id = departments["restaurant"]["posOrderId"]
    inventory_item_id = departments["inventoryAndProcurement"]["inventoryItemId"]
    purchase_order_id = departments["inventoryAndProcurement"]["purchaseOrderId"]
    supplier_id = departments["inventoryAndProcurement"]["supplierId"]
    run_id = departments["runId"].lower()
    # One manager per role, shared across every worker. Each is internally
    # locked, so concurrent workers renew once between them rather than racing
    # to spend the same rotated refresh token.
    token_managers = {
        role: KeycloakTokenManager(args.keycloak_url, username, password)
        for role, username, password in (
            ("management", "acceptance-tenant-admin", args.tenant_password),
            ("housekeeping", f"real-hotel-housekeeper-{run_id}", args.staff_password),
            ("maintenance", f"real-hotel-maintenance-{run_id}", args.staff_password),
            ("stores-procurement", f"real-hotel-stores-{run_id}", args.staff_password),
            ("restaurant", f"real-hotel-restaurant-{run_id}", args.staff_password),
            ("supervision", f"real-hotel-supervisor-{run_id}", args.staff_password),
        )
    }

    paths = [
        ("management", f"/api/v1/properties/{property_id}"),
        ("management", f"/api/v1/properties/{property_id}/readiness"),
        ("supervision", f"/api/v1/properties/{property_id}/rooms"),
        ("supervision", f"/api/v1/properties/{property_id}/rooms/{room_id}"),
        ("management", f"/api/v1/properties/{property_id}/room-types"),
        ("management", f"/api/v1/properties/{property_id}/reservations"),
        ("management", f"/api/v1/properties/{property_id}/stays"),
        ("management", f"/api/v1/properties/{property_id}/guests"),
        ("management", f"/api/v1/properties/{property_id}/folios"),
        ("management", f"/api/v1/properties/{property_id}/invoices"),
        ("management", f"/api/v1/properties/{property_id}/payments/transactions"),
        ("management", f"/api/v1/properties/{property_id}/payments/reconciliations"),
        ("management", f"/api/v1/properties/{property_id}/fiscal/receipts"),
        ("housekeeping", f"/api/v1/properties/{property_id}/housekeeping/board"),
        ("housekeeping", f"/api/v1/properties/{property_id}/lost-and-found"),
        ("maintenance", f"/api/v1/properties/{property_id}/maintenance/requests"),
        ("maintenance", f"/api/v1/properties/{property_id}/maintenance/work-orders"),
        ("stores-procurement", f"/api/v1/properties/{property_id}/inventory/items"),
        ("stores-procurement", f"/api/v1/properties/{property_id}/inventory/items/{inventory_item_id}"),
        ("stores-procurement", f"/api/v1/properties/{property_id}/inventory/levels"),
        ("stores-procurement", f"/api/v1/properties/{property_id}/inventory/movements"),
        ("stores-procurement", f"/api/v1/properties/{property_id}/procurement/suppliers"),
        ("stores-procurement", f"/api/v1/properties/{property_id}/procurement/suppliers/{supplier_id}"),
        ("stores-procurement", f"/api/v1/properties/{property_id}/purchase-orders"),
        ("stores-procurement", f"/api/v1/properties/{property_id}/purchase-orders/{purchase_order_id}"),
        ("restaurant", f"/api/v1/properties/{property_id}/kitchen-tickets"),
        ("restaurant", f"/api/v1/properties/{property_id}/pos-sessions/{pos_session_id}"),
        ("restaurant", f"/api/v1/properties/{property_id}/pos-orders/{core_order_id}"),
        ("restaurant", f"/api/v1/properties/{property_id}/pos-orders/{department_order_id}"),
        ("management", f"/api/v1/tenants/{tenant_id}/readiness"),
        ("management", f"/api/v1/tenants/{tenant_id}/modules"),
        ("management", f"/api/v1/properties/{property_id}/modules"),
    ]

    for sequence in range(args.warmup):
        role, path = paths[sequence % len(paths)]
        _, _, status, _, _ = call(
            args.base_url, role, token_managers[role], path, -sequence
        )
        require(status == 200, f"Warmup path failed with HTTP {status}: {role} {path}")

    started = time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=args.concurrency) as executor:
        futures = [
            executor.submit(
                call,
                args.base_url,
                paths[sequence % len(paths)][0],
                token_managers[paths[sequence % len(paths)][0]],
                paths[sequence % len(paths)][1],
                sequence,
            )
            for sequence in range(args.requests)
        ]
        results = [future.result() for future in concurrent.futures.as_completed(futures)]
    elapsed = time.perf_counter() - started

    statuses = Counter(status for _, _, status, _, _ in results)
    failures = [result for result in results if result[2] != 200]
    durations = [duration for _, _, status, duration, _ in results if status == 200]
    endpoint_stats: dict[str, dict[str, Any]] = {}
    for role, path in paths:
        samples = [
            duration
            for result_role, result_path, status, duration, _ in results
            if result_role == role and result_path == path and status == 200
        ]
        endpoint_stats[f"{role} {path}"] = {
            "role": role,
            "path": path,
            "requests": sum(
                1
                for result_role, result_path, _, _, _ in results
                if result_role == role and result_path == path
            ),
            "p95Ms": round(percentile(samples, 0.95), 2),
            "maxMs": round(max(samples, default=0), 2),
        }
    error_rate = len(failures) / len(results)
    throughput = len(results) / elapsed
    summary = {
        "suite": "mixed-hotel-api-load",
        "result": "passed",
        "generatedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "tenantId": tenant_id,
        "propertyId": property_id,
        "workload": {
            "requests": len(results),
            "concurrency": args.concurrency,
            "warmupRequests": args.warmup,
            "endpointCount": len(paths),
            "actorRoleCount": len(token_managers),
            "actorRoles": sorted(token_managers),
            "durationSeconds": round(elapsed, 3),
            "throughputRequestsPerSecond": round(throughput, 2),
            "responseBytes": sum(size for _, _, _, _, size in results),
        },
        "latency": {
            "meanMs": round(statistics.fmean(durations), 2),
            "p50Ms": round(percentile(durations, 0.50), 2),
            "p95Ms": round(percentile(durations, 0.95), 2),
            "p99Ms": round(percentile(durations, 0.99), 2),
            "maxMs": round(max(durations), 2),
        },
        "statusCounts": {str(status): count for status, count in sorted(statuses.items())},
        "errorRate": round(error_rate, 6),
        "thresholds": {
            "maxErrorRate": args.max_error_rate,
            "maxP95Ms": args.max_p95_ms,
            "maxP99Ms": args.max_p99_ms,
            "minThroughputRequestsPerSecond": args.min_throughput,
        },
        "endpoints": endpoint_stats,
        "failures": [
            {"role": role, "path": path, "status": status, "durationMs": round(duration, 2)}
            for role, path, status, duration, _ in failures[:25]
        ],
    }
    p95 = percentile(durations, 0.95)
    p99 = percentile(durations, 0.99)
    violations = []
    if error_rate > args.max_error_rate:
        violations.append(f"error rate {error_rate:.4%} > {args.max_error_rate:.4%}")
    if p95 > args.max_p95_ms:
        violations.append(f"p95 {p95:.2f}ms > {args.max_p95_ms:.2f}ms")
    if p99 > args.max_p99_ms:
        violations.append(f"p99 {p99:.2f}ms > {args.max_p99_ms:.2f}ms")
    if throughput < args.min_throughput:
        violations.append(f"throughput {throughput:.2f}rps < {args.min_throughput:.2f}rps")
    if violations:
        summary["result"] = "failed"
        summary["violations"] = violations
    output = args.evidence_dir / "api-load.json"
    output.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary, indent=2))
    if violations:
        raise LoadFailure("; ".join(violations))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (LoadFailure, urllib.error.URLError, json.JSONDecodeError) as error:
        print(f"API load test failed: {error}", file=sys.stderr)
        raise SystemExit(1)
