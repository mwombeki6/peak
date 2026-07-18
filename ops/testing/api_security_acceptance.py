#!/usr/bin/env python3
"""Attack the running API boundary and preserve reproducible security evidence."""

from __future__ import annotations

import argparse
import base64
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Iterable


class SecurityFailure(RuntimeError):
    pass


HOSPITALITY_REALM = os.environ.get("KEYCLOAK_HOSPITALITY_REALM", "peak-hospitality")


@dataclass
class Response:
    status: int
    headers: dict[str, str]
    raw: bytes

    @property
    def text(self) -> str:
        return self.raw.decode(errors="replace")

    def json(self) -> Any:
        return json.loads(self.raw) if self.raw else None


def send(
    base_url: str,
    method: str,
    path: str,
    *,
    token: str | None = None,
    body: bytes | None = None,
    content_type: str | None = None,
    headers: dict[str, str] | None = None,
) -> Response:
    request_headers = {"Accept": "application/json"}
    if token:
        request_headers["Authorization"] = f"Bearer {token}"
    if content_type:
        request_headers["Content-Type"] = content_type
    if headers:
        request_headers.update(headers)
    request = urllib.request.Request(
        f"{base_url.rstrip('/')}{path}", method=method, data=body, headers=request_headers
    )
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return Response(response.status, dict(response.headers.items()), response.read())
    except urllib.error.HTTPError as error:
        return Response(error.code, dict(error.headers.items()), error.read())


def token_for(keycloak_url: str, username: str, password: str) -> str:
    data = urllib.parse.urlencode(
        {
            "grant_type": "password",
            "client_id": "peak-acceptance",
            "username": username,
            "password": password,
        }
    ).encode()
    request = urllib.request.Request(
        f"{keycloak_url.rstrip('/')}/realms/{HOSPITALITY_REALM}/protocol/openid-connect/token",
        method="POST",
        data=data,
        headers={"Content-Type": "application/x-www-form-urlencoded"},
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        return json.load(response)["access_token"]


def expect(name: str, response: Response, statuses: int | Iterable[int], checks: list[dict[str, Any]]) -> None:
    values = [statuses] if isinstance(statuses, int) else list(statuses)
    if response.status not in values:
        raise SecurityFailure(
            f"{name}: received {response.status}, expected {values}: {response.text[:1000]}"
        )
    checks.append({"name": name, "status": response.status, "expected": values})


def require(condition: bool, message: str) -> None:
    if not condition:
        raise SecurityFailure(message)


def json_bytes(value: Any) -> bytes:
    return json.dumps(value, separators=(",", ":")).encode()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--keycloak-url", default="http://localhost:8081")
    parser.add_argument("--evidence-dir", required=True, type=Path)
    parser.add_argument("--tenant-password", required=True)
    parser.add_argument("--other-password", required=True)
    args = parser.parse_args()

    foundation = json.loads((args.evidence_dir / "tenant-property-foundation.json").read_text())
    tenant_id = foundation["tenantId"]
    property_id = foundation["propertyId"]
    tenant_user_id = foundation["tenantUserId"]
    admin_token = token_for(args.keycloak_url, "acceptance-tenant-admin", args.tenant_password)
    other_token = token_for(args.keycloak_url, "acceptance-other-admin", args.other_password)
    checks: list[dict[str, Any]] = []

    health = send(args.base_url, "GET", "/actuator/health")
    expect("public-health-only", health, 200, checks)
    anonymous = send(args.base_url, "GET", f"/api/v1/properties/{property_id}")
    expect("anonymous-api-rejected", anonymous, 403, checks)
    require("application/problem+json" in anonymous.headers.get("Content-Type", ""), "Anonymous denial was not a problem response")

    malformed = send(args.base_url, "GET", f"/api/v1/properties/{property_id}", token="not-a-jwt")
    expect("malformed-jwt-rejected", malformed, 401, checks)
    parts = admin_token.split(".")
    require(len(parts) == 3, "Admin token was not a JWT")
    tampered_signature = ("A" if parts[2][:1] != "A" else "B") + parts[2][1:]
    tampered = send(
        args.base_url, "GET", f"/api/v1/properties/{property_id}", token=".".join([parts[0], parts[1], tampered_signature])
    )
    expect("tampered-jwt-rejected", tampered, 401, checks)
    unsigned_header = base64.urlsafe_b64encode(b'{"alg":"none","typ":"JWT"}').decode().rstrip("=")
    unsigned = send(
        args.base_url,
        "GET",
        f"/api/v1/properties/{property_id}",
        token=f"{unsigned_header}.{parts[1]}.",
    )
    expect("unsigned-jwt-rejected", unsigned, 401, checks)

    cross_tenant = send(args.base_url, "GET", f"/api/v1/properties/{property_id}", token=other_token)
    expect("cross-tenant-bola-rejected", cross_tenant, 403, checks)
    spoofed = send(
        args.base_url,
        "GET",
        f"/api/v1/properties/{property_id}",
        token=other_token,
        headers={"X-Tenant-Id": tenant_id, "X-Tenant-User-Id": tenant_user_id},
    )
    expect("identity-header-spoof-rejected", spoofed, 403, checks)

    correlation = "security-no-sensitive-details"
    invalid_uuid = send(
        args.base_url,
        "GET",
        "/api/v1/properties/not-a-uuid",
        token=admin_token,
        headers={"X-Correlation-Id": correlation},
    )
    expect("malformed-identifier-rejected", invalid_uuid, 400, checks)
    error_text = invalid_uuid.text.lower()
    for forbidden in ("exception", "stacktrace", "password", "bearer ", "select ", "jdbc", "postgres"):
        require(forbidden not in error_text, f"Problem response leaked internal detail: {forbidden}")
    require(invalid_uuid.headers.get("X-Correlation-Id") == correlation, "Correlation ID was not returned")

    wrong_type = send(
        args.base_url,
        "POST",
        f"/api/v1/properties/{property_id}/inventory/items",
        token=admin_token,
        body=b"name=not-json",
        content_type="text/plain",
        headers={"X-Correlation-Id": "security-content-type"},
    )
    expect("unsupported-content-type-rejected", wrong_type, 415, checks)
    malformed_json = send(
        args.base_url,
        "POST",
        f"/api/v1/properties/{property_id}/inventory/items",
        token=admin_token,
        body=b'{"name":',
        content_type="application/json",
        headers={"X-Correlation-Id": "security-malformed-json"},
    )
    expect("malformed-json-rejected", malformed_json, 400, checks)

    cors = send(
        args.base_url,
        "OPTIONS",
        f"/api/v1/properties/{property_id}",
        headers={
            "Origin": "https://attacker.invalid",
            "Access-Control-Request-Method": "GET",
            "Access-Control-Request-Headers": "authorization",
        },
    )
    expect("untrusted-cors-origin-rejected", cors, 403, checks)
    require("Access-Control-Allow-Origin" not in cors.headers, "Untrusted origin received CORS access")

    security_headers = send(args.base_url, "GET", f"/api/v1/properties/{property_id}", token=admin_token)
    expect("authorized-api-request", security_headers, 200, checks)
    require(security_headers.headers.get("X-Frame-Options") == "DENY", "X-Frame-Options is missing")
    require(security_headers.headers.get("Referrer-Policy") == "no-referrer", "Referrer-Policy is missing")
    require("frame-ancestors 'none'" in security_headers.headers.get("Content-Security-Policy", ""),
            "Content-Security-Policy is missing frame protection")

    key = f"security-idempotency-{int(time.time())}"
    payload = {"name": f"Security Replay Item {key}", "sku": f"SEC-{int(time.time())}", "category": "Test", "unit": "each"}
    base_headers = {"Idempotency-Key": key, "X-Correlation-Id": "security-idempotency"}
    created = send(args.base_url, "POST", f"/api/v1/properties/{property_id}/inventory/items", token=admin_token,
                   body=json_bytes(payload), content_type="application/json", headers=base_headers)
    expect("idempotent-mutation-created", created, 201, checks)
    replay = send(args.base_url, "POST", f"/api/v1/properties/{property_id}/inventory/items", token=admin_token,
                  body=json_bytes(payload), content_type="application/json", headers=base_headers)
    expect("idempotent-replay-safe", replay, 201, checks)
    require(created.json()["id"] == replay.json()["id"], "Idempotent replay created a second entity")
    conflict_payload = dict(payload)
    conflict_payload["name"] = "Conflicting payload must fail"
    conflict = send(args.base_url, "POST", f"/api/v1/properties/{property_id}/inventory/items", token=admin_token,
                    body=json_bytes(conflict_payload), content_type="application/json", headers=base_headers)
    expect("idempotency-payload-conflict-rejected", conflict, 409, checks)

    method = send(args.base_url, "PATCH", f"/api/v1/properties/{property_id}/inventory/items", token=admin_token,
                  body=b"{}", content_type="application/json")
    expect("undeclared-http-method-rejected", method, 405, checks)

    evidence = {
        "suite": "api-security-adversarial",
        "result": "passed",
        "generatedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "tenantId": tenant_id,
        "propertyId": property_id,
        "checks": checks,
        "assertions": {
            "jwtSignatureValidated": True,
            "unsignedJwtRejected": True,
            "tenantAndHeaderSpoofingRejected": True,
            "corsRestricted": True,
            "rfc9457ProblemsWithoutSensitiveDetails": True,
            "idempotencyReplayAndConflictEnforced": True,
            "browserSecurityHeadersPresent": True,
        },
    }
    output = args.evidence_dir / "api-security.json"
    output.write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(evidence, indent=2))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (SecurityFailure, urllib.error.URLError, json.JSONDecodeError) as error:
        print(f"API security acceptance failed: {error}", file=sys.stderr)
        raise SystemExit(1)
