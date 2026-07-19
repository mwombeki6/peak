#!/usr/bin/env python3
"""Exercise financially meaningful POS writes under shared-session contention."""

from __future__ import annotations

import argparse
import concurrent.futures
import json
import math
import os
import statistics
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from decimal import Decimal
from pathlib import Path
from typing import Any, Callable


class WriteLoadFailure(RuntimeError):
    pass


HOSPITALITY_REALM = os.environ.get("KEYCLOAK_HOSPITALITY_REALM", "peak-hospitality")
TokenRequest = Callable[[str, dict[str, str]], dict[str, Any]]
Clock = Callable[[], float]


@dataclass
class Call:
    operation: str
    status: int
    duration_ms: float


def request_token(keycloak_url: str, form: dict[str, str]) -> dict[str, Any]:
    data = urllib.parse.urlencode({"client_id": "peak-acceptance", **form}).encode()
    request = urllib.request.Request(
        f"{keycloak_url.rstrip('/')}/realms/{HOSPITALITY_REALM}/protocol/openid-connect/token",
        method="POST",
        data=data,
        headers={"Content-Type": "application/x-www-form-urlencoded"},
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        payload = json.load(response)
    if not payload.get("access_token"):
        raise WriteLoadFailure("Keycloak token response did not contain access_token")
    return payload


class KeycloakTokenManager:
    """Share one rotation-safe refresh-token lifecycle across load workers."""

    def __init__(
        self,
        keycloak_url: str,
        username: str,
        password: str,
        *,
        token_request: TokenRequest = request_token,
        clock: Clock = time.monotonic,
    ) -> None:
        self.keycloak_url = keycloak_url
        self.username = username
        self.password = password
        self.token_request = token_request
        self.clock = clock
        self.lock = threading.Lock()
        self.current_access_token: str | None = None
        self.current_refresh_token: str | None = None
        self.refresh_at = 0.0
        self.password_grant_count = 0
        self.refresh_grant_count = 0

    def access_token(self) -> str:
        with self.lock:
            if self.current_access_token and self.clock() < self.refresh_at:
                return self.current_access_token
            return self._renew_locked()

    def refresh_after_unauthorized(self, rejected_token: str) -> str:
        with self.lock:
            # Another worker may already have rotated the refresh token. Never
            # reuse the previous refresh token or invalidate the newer access token.
            if self.current_access_token != rejected_token:
                if not self.current_access_token:
                    return self._renew_locked()
                return self.current_access_token
            self.refresh_at = 0.0
            return self._renew_locked()

    def _renew_locked(self) -> str:
        payload: dict[str, Any]
        if self.current_refresh_token:
            try:
                payload = self.token_request(
                    self.keycloak_url,
                    {
                        "grant_type": "refresh_token",
                        "refresh_token": self.current_refresh_token,
                    },
                )
                self.refresh_grant_count += 1
            except urllib.error.HTTPError as error:
                if error.code not in (400, 401):
                    raise
                error.close()
                payload = self._password_grant()
        else:
            payload = self._password_grant()
        return self._install(payload)

    def _password_grant(self) -> dict[str, Any]:
        payload = self.token_request(
            self.keycloak_url,
            {
                "grant_type": "password",
                "username": self.username,
                "password": self.password,
            },
        )
        self.password_grant_count += 1
        return payload

    def _install(self, payload: dict[str, Any]) -> str:
        access_token = str(payload.get("access_token", ""))
        refresh_token = str(payload.get("refresh_token", ""))
        try:
            expires_in = float(payload.get("expires_in", 0))
        except (TypeError, ValueError) as error:
            raise WriteLoadFailure("Keycloak token response has invalid expires_in") from error
        if not access_token or not refresh_token or expires_in <= 0:
            raise WriteLoadFailure(
                "Keycloak token response requires access_token, refresh_token, and positive expires_in",
            )
        refresh_skew = min(30.0, max(1.0, expires_in * 0.1))
        self.current_access_token = access_token
        self.current_refresh_token = refresh_token
        self.refresh_at = self.clock() + max(0.0, expires_in - refresh_skew)
        return access_token


def request_json(
    base_url: str,
    token_manager: KeycloakTokenManager,
    method: str,
    path: str,
    *,
    body: dict[str, Any] | None = None,
    idempotency_key: str | None = None,
    correlation_id: str,
) -> tuple[int, dict[str, Any], float]:
    data = None
    if body is not None:
        data = json.dumps(body, separators=(",", ":")).encode()
    started = time.perf_counter()

    def send(access_token: str) -> tuple[int, bytes]:
        headers = {
            "Authorization": f"Bearer {access_token}",
            "Accept": "application/json",
            "X-Correlation-Id": correlation_id,
        }
        if idempotency_key:
            headers["Idempotency-Key"] = idempotency_key
        if body is not None:
            headers["Content-Type"] = "application/json"
        request = urllib.request.Request(
            f"{base_url.rstrip('/')}{path}",
            method=method,
            headers=headers,
            data=data,
        )
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                return response.status, response.read()
        except urllib.error.HTTPError as error:
            try:
                return error.code, error.read()
            finally:
                error.close()

    token = token_manager.access_token()
    status, raw = send(token)
    if status == 401:
        status, raw = send(token_manager.refresh_after_unauthorized(token))
    elapsed = (time.perf_counter() - started) * 1000
    try:
        payload = json.loads(raw) if raw else {}
    except json.JSONDecodeError:
        payload = {"raw": raw.decode(errors="replace")[:1000]}
    return status, payload, elapsed


def percentile(values: list[float], fraction: float) -> float:
    if not values:
        return 0.0
    ordered = sorted(values)
    return ordered[max(0, math.ceil(len(ordered) * fraction) - 1)]


def require(condition: bool, message: str) -> None:
    if not condition:
        raise WriteLoadFailure(message)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--keycloak-url", default="http://localhost:8081")
    parser.add_argument("--evidence-dir", required=True, type=Path)
    parser.add_argument("--staff-password", required=True)
    parser.add_argument("--orders", type=int, default=40)
    parser.add_argument("--concurrency", type=int, default=8)
    parser.add_argument("--duration-seconds", type=int, default=0)
    parser.add_argument("--max-error-rate", type=float, default=0.0)
    parser.add_argument("--max-p95-ms", type=float, default=3000.0)
    args = parser.parse_args()
    require(args.orders > 0, "orders must be positive")
    require(args.concurrency > 0, "concurrency must be positive")
    require(args.duration_seconds >= 0, "duration-seconds cannot be negative")

    foundation = json.loads((args.evidence_dir / "tenant-property-foundation.json").read_text())
    stay = json.loads((args.evidence_dir / "stay-finance-foundation.json").read_text())
    departments = json.loads((args.evidence_dir / "real-hotel-departments.json").read_text())
    property_id = foundation["propertyId"]
    run_id = departments["runId"].lower()
    username = f"real-hotel-restaurant-{run_id}"
    token_manager = KeycloakTokenManager(
        args.keycloak_url,
        username,
        args.staff_password,
    )
    test_id = time.strftime("%Y%m%d%H%M%S", time.gmtime())

    status, session, opening_ms = request_json(
        args.base_url,
        token_manager,
        "POST",
        f"/api/v1/properties/{property_id}/pos-sessions/open",
        body={"outletId": stay["outletId"], "openingFloat": 1000},
        idempotency_key=f"write-load-session-{test_id}",
        correlation_id="write-load-session-open",
    )
    require(status == 201, f"Could not open write-load session: HTTP {status} {session}")
    session_id = session["id"]
    counter = 0
    counter_lock = threading.Lock()
    deadline = time.monotonic() + args.duration_seconds if args.duration_seconds else None

    def next_sequence() -> int | None:
        nonlocal counter
        with counter_lock:
            if deadline is None and counter >= args.orders:
                return None
            if deadline is not None and counter >= args.orders and time.monotonic() >= deadline:
                return None
            counter += 1
            return counter

    def execute_order(sequence: int) -> dict[str, Any]:
        calls: list[Call] = []
        operation = f"write-load-{test_id}-{sequence}"
        status, order, elapsed = request_json(
            args.base_url,
            token_manager,
            "POST",
            f"/api/v1/properties/{property_id}/pos-orders",
            body={
                "sessionId": session_id,
                "orderType": "takeaway",
                "clientOperationId": f"{operation}-create",
            },
            idempotency_key=f"{operation}-create",
            correlation_id=f"{operation}-create",
        )
        calls.append(Call("create", status, elapsed))
        if status != 201:
            return {"calls": calls, "error": order}
        order_id = order["id"]
        status, priced, elapsed = request_json(
            args.base_url,
            token_manager,
            "POST",
            f"/api/v1/properties/{property_id}/pos-orders/{order_id}/items",
            body={
                "menuItemId": stay["menuItemId"],
                "quantity": 1,
                "clientOperationId": f"{operation}-item",
            },
            idempotency_key=f"{operation}-item",
            correlation_id=f"{operation}-item",
        )
        calls.append(Call("item", status, elapsed))
        if status != 200:
            return {"calls": calls, "error": priced}
        settle_key = f"{operation}-settle"
        status, settled, elapsed = request_json(
            args.base_url,
            token_manager,
            "POST",
            f"/api/v1/properties/{property_id}/pos-orders/{order_id}/settle",
            body={"paymentMethod": "cash"},
            idempotency_key=settle_key,
            correlation_id=settle_key,
        )
        calls.append(Call("settle", status, elapsed))
        if status != 200:
            return {"calls": calls, "error": settled}
        if sequence % 10 == 0:
            replay_status, replay, replay_ms = request_json(
                args.base_url,
                token_manager,
                "POST",
                f"/api/v1/properties/{property_id}/pos-orders/{order_id}/settle",
                body={"paymentMethod": "cash"},
                idempotency_key=settle_key,
                correlation_id=f"{settle_key}-replay",
            )
            calls.append(Call("settle_replay", replay_status, replay_ms))
            if replay_status != 200 or replay.get("id") != order_id:
                return {"calls": calls, "error": replay}
        return {
            "calls": calls,
            "orderId": order_id,
            "paymentTransactionId": settled["paymentTransactionId"],
            "amount": str(settled["totalAmount"]),
        }

    started = time.perf_counter()

    def worker() -> list[dict[str, Any]]:
        results = []
        while True:
            sequence = next_sequence()
            if sequence is None:
                return results
            results.append(execute_order(sequence))

    with concurrent.futures.ThreadPoolExecutor(max_workers=args.concurrency) as executor:
        nested = [future.result() for future in [executor.submit(worker) for _ in range(args.concurrency)]]
    results = [result for worker_results in nested for result in worker_results]
    elapsed = time.perf_counter() - started
    calls = [call for result in results for call in result["calls"]]
    failed_calls = [call for call in calls if call.status not in (200, 201)]
    failed_orders = [result for result in results if "error" in result]
    successful = [result for result in results if "error" not in result]
    error_rate = len(failed_orders) / max(1, len(results))
    durations = [call.duration_ms for call in calls]
    total = sum((Decimal(result["amount"]) for result in successful), Decimal("0.00"))
    payment_ids = {result["paymentTransactionId"] for result in successful}
    require(len(payment_ids) == len(successful), "A payment transaction was reused across POS orders")

    status, summary, summary_ms = request_json(
        args.base_url,
        token_manager,
        "GET",
        f"/api/v1/properties/{property_id}/pos-sessions/{session_id}",
        correlation_id="write-load-session-summary",
    )
    require(status == 200, f"Could not read write-load session: HTTP {status}")
    expected_cash = Decimal(str(summary["session"]["expectedCash"]))
    require(expected_cash == Decimal("1000.00") + total, "POS expected cash did not equal settled total")
    close_status, closed, close_ms = request_json(
        args.base_url,
        token_manager,
        "POST",
        f"/api/v1/properties/{property_id}/pos-sessions/{session_id}/close",
        body={"actualCash": str(expected_cash)},
        idempotency_key=f"write-load-session-close-{test_id}",
        correlation_id="write-load-session-close",
    )
    require(close_status == 200 and closed.get("status") == "closed", "Write-load session did not close")

    p95 = percentile(durations, 0.95)
    violations = []
    if error_rate > args.max_error_rate:
        violations.append(f"error rate {error_rate:.4%} > {args.max_error_rate:.4%}")
    if p95 > args.max_p95_ms:
        violations.append(f"p95 {p95:.2f}ms > {args.max_p95_ms:.2f}ms")
    evidence = {
        "suite": "financial-write-load",
        "result": "failed" if violations else "passed",
        "generatedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "propertyId": property_id,
        "workload": {
            "orders": len(results),
            "successfulOrders": len(successful),
            "concurrency": args.concurrency,
            "durationTargetSeconds": args.duration_seconds,
            "durationSeconds": round(elapsed, 3),
            "writeCalls": len(calls),
            "throughputOrdersPerSecond": round(len(results) / max(elapsed, 0.001), 2),
        },
        "financial": {
            "openingFloat": "1000.00",
            "settledTotal": str(total),
            "expectedCash": str(expected_cash),
            "uniquePaymentTransactions": len(payment_ids),
            "sessionClosed": True,
        },
        "latency": {
            "meanMs": round(statistics.fmean(durations), 2),
            "p50Ms": round(percentile(durations, 0.50), 2),
            "p95Ms": round(p95, 2),
            "p99Ms": round(percentile(durations, 0.99), 2),
            "maxMs": round(max(durations), 2),
            "sessionOpenMs": round(opening_ms, 2),
            "sessionSummaryMs": round(summary_ms, 2),
            "sessionCloseMs": round(close_ms, 2),
        },
        "errorRate": round(error_rate, 6),
        "authentication": {
            "passwordGrants": token_manager.password_grant_count,
            "refreshGrants": token_manager.refresh_grant_count,
        },
        "statusCounts": {
            str(status): sum(1 for call in calls if call.status == status)
            for status in sorted({call.status for call in calls})
        },
        "failures": [
            {"operations": [call.__dict__ for call in result["calls"]], "error": result.get("error")}
            for result in failed_orders[:20]
        ],
        "violations": violations,
    }
    output = args.evidence_dir / "api-write-load.json"
    output.write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(evidence, indent=2))
    if violations or failed_calls:
        raise WriteLoadFailure("; ".join(violations) or "At least one write call failed")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (WriteLoadFailure, urllib.error.URLError, json.JSONDecodeError, KeyError) as error:
        print(f"Financial write load failed: {error}", file=sys.stderr)
        raise SystemExit(1)
