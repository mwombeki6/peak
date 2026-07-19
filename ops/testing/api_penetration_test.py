#!/usr/bin/env python3
"""OpenAPI-driven authenticated DAST and hostile-input penetration probes."""

from __future__ import annotations

import argparse
import concurrent.futures
import json
import os
import re
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter
from dataclasses import dataclass
from pathlib import Path
from typing import Any


class PenetrationFailure(RuntimeError):
    pass


HOSPITALITY_REALM = os.environ.get("KEYCLOAK_HOSPITALITY_REALM", "peak-hospitality")


@dataclass
class Probe:
    category: str
    method: str
    path: str
    status: int
    duration_ms: float
    response_sample: str


def token_for(keycloak_url: str, password: str) -> str:
    data = urllib.parse.urlencode(
        {
            "grant_type": "password",
            "client_id": "peak-acceptance",
            "username": "acceptance-tenant-admin",
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


def render_path(template: str, tenant_id: str, property_id: str, invalid: bool = False) -> str:
    values = {
        "tenantId": tenant_id,
        "propertyId": property_id,
        "businessDate": time.strftime("%Y-%m-%d", time.gmtime()),
        "auditDate": time.strftime("%Y-%m-%d", time.gmtime()),
        "date": time.strftime("%Y-%m-%d", time.gmtime()),
        "year": time.strftime("%Y", time.gmtime()),
    }
    invalid_used = False

    def replace(match: re.Match[str]) -> str:
        nonlocal invalid_used
        name = match.group(1)
        if invalid and not invalid_used and name.lower().endswith("id"):
            invalid_used = True
            return "not-a-uuid"
        if name in values:
            return values[name]
        if name.lower().endswith("id"):
            return "00000000-0000-0000-0000-000000000001"
        if "date" in name.lower():
            return time.strftime("%Y-%m-%d", time.gmtime())
        return urllib.parse.quote("fuzz-value", safe="")

    return re.sub(r"\{([^}]+)}", replace, template)


def send(
    base_url: str,
    category: str,
    method: str,
    path: str,
    *,
    token: str | None = None,
    body: bytes | None = None,
    content_type: str | None = None,
    headers: dict[str, str] | None = None,
) -> Probe:
    request_headers = {
        "Accept": "application/json",
        "X-Correlation-Id": f"penetration-{category}-{time.time_ns()}",
    }
    if token:
        request_headers["Authorization"] = f"Bearer {token}"
    if content_type:
        request_headers["Content-Type"] = content_type
    if headers:
        request_headers.update(headers)
    request = urllib.request.Request(
        f"{base_url.rstrip('/')}{path}",
        method=method,
        data=body,
        headers=request_headers,
    )
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(request, timeout=20) as response:
            status = response.status
            raw = response.read()
    except urllib.error.HTTPError as error:
        status = error.code
        raw = error.read()
    except (TimeoutError, urllib.error.URLError) as error:
        return Probe(category, method, path, 0, (time.perf_counter() - started) * 1000, str(error))
    return Probe(
        category,
        method,
        path,
        status,
        (time.perf_counter() - started) * 1000,
        raw.decode(errors="replace")[:1000],
    )


def require(condition: bool, message: str) -> None:
    if not condition:
        raise PenetrationFailure(message)


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-url", default="http://localhost:8080")
    parser.add_argument("--keycloak-url", default="http://localhost:8081")
    parser.add_argument("--evidence-dir", required=True, type=Path)
    parser.add_argument("--openapi", required=True, type=Path)
    parser.add_argument("--tenant-password", required=True)
    parser.add_argument("--concurrency", type=int, default=20)
    args = parser.parse_args()

    specification = json.loads(args.openapi.read_text())
    foundation = json.loads((args.evidence_dir / "tenant-property-foundation.json").read_text())
    tenant_id = foundation["tenantId"]
    property_id = foundation["propertyId"]
    token = token_for(args.keycloak_url, args.tenant_password)
    root_security = specification.get("security")
    operations: list[tuple[str, str, dict[str, Any], bool]] = []
    for template, path_item in specification["paths"].items():
        for method in ("get", "post", "put", "patch", "delete"):
            operation = path_item.get(method)
            if not operation:
                continue
            security = operation.get("security", root_security)
            operations.append((method.upper(), template, operation, bool(security)))

    jobs: list[tuple[str, str, str, dict[str, Any]]] = []
    for method, template, operation, secured in operations:
        rendered = render_path(template, tenant_id, property_id)
        operation_headers: dict[str, str] = {}
        success_content = operation.get("responses", {}).get("200", {}).get("content", {})
        if "text/event-stream" in success_content:
            operation_headers["Accept"] = "text/event-stream"
        anonymous_options: dict[str, Any] = {
            "secured": secured,
            "headers": operation_headers,
        }
        if template == "/api/v1/invitations/accept":
            anonymous_options.update(
                {
                    "body": b'{"invitationToken":"penetration-invalid-token"}',
                    "content_type": "application/json",
                }
            )
        jobs.append(("anonymous-boundary", method, rendered, anonymous_options))
        if method == "GET" and re.search(r"\{[^}]*[iI]d}", template):
            jobs.append(
                (
                    "identifier-fuzz",
                    method,
                    render_path(template, tenant_id, property_id, invalid=True),
                    {"token": token, "headers": operation_headers},
                )
            )
        if method in ("POST", "PUT", "PATCH") and "requestBody" in operation:
            jobs.append(
                (
                    "malformed-json",
                    method,
                    rendered,
                    {"token": token, "body": b'{"broken":', "content_type": "application/json"},
                )
            )
            jobs.append(
                (
                    "unsupported-content-type",
                    method,
                    rendered,
                    {"token": token, "body": b"not-json", "content_type": "text/plain"},
                )
            )

    def execute(job: tuple[str, str, str, dict[str, Any]]) -> tuple[Probe, bool]:
        category, method, path, options = job
        secured = bool(options.pop("secured", False))
        return send(args.base_url, category, method, path, **options), secured

    with concurrent.futures.ThreadPoolExecutor(max_workers=args.concurrency) as executor:
        results = list(executor.map(execute, jobs))

    targeted = [
        send(
            args.base_url,
            "sql-injection",
            "GET",
            f"/api/v1/properties/{property_id}/reservations?sort=" +
            urllib.parse.quote("created_at desc; select pg_sleep(5)--"),
            token=token,
        ),
        send(
            args.base_url,
            "xss",
            "GET",
            f"/api/v1/properties/{property_id}/guests?query=" +
            urllib.parse.quote("<script>alert(document.domain)</script>"),
            token=token,
        ),
        send(
            args.base_url,
            "path-traversal",
            "GET",
            f"/api/v1/properties/{property_id}/reports/artifacts/%2e%2e%2f%2e%2e%2fetc%2fpasswd",
            token=token,
        ),
        send(
            args.base_url,
            "deep-json",
            "POST",
            f"/api/v1/properties/{property_id}/inventory/items",
            token=token,
            body=(b'{"nested":' * 150) + b"null" + (b"}" * 150),
            content_type="application/json",
        ),
        send(
            args.base_url,
            "oversized-json",
            "POST",
            f"/api/v1/properties/{property_id}/inventory/items",
            token=token,
            body=json.dumps({"name": "A" * (2 * 1024 * 1024)}).encode(),
            content_type="application/json",
        ),
        send(
            args.base_url,
            "method-override",
            "POST",
            f"/api/v1/properties/{property_id}",
            token=token,
            body=b"{}",
            content_type="application/json",
            headers={"X-HTTP-Method-Override": "DELETE"},
        ),
        send(
            args.base_url,
            "response-content-negotiation",
            "GET",
            f"/api/v1/realtime/tenants/{tenant_id}/properties/{property_id}/stream",
            token=token,
            headers={"Accept": "application/json"},
        ),
    ]
    results.extend((probe, False) for probe in targeted)

    failures = []
    forbidden_terms = (
        "stacktrace",
        "org.springframework",
        "java.lang.",
        "postgresql",
        "jdbc:",
        "select pg_",
        "bearer ",
        "password=",
    )
    for probe, secured in results:
        body = probe.response_sample.lower()
        reasons = []
        if probe.status == 0 or probe.status >= 500:
            reasons.append("transport failure or server error")
        if probe.category == "anonymous-boundary" and secured and probe.status not in (401, 403):
            reasons.append("secured operation did not reject anonymous caller")
        if probe.category in {
            "malformed-json",
            "unsupported-content-type",
            "identifier-fuzz",
            "deep-json",
            "oversized-json",
            "method-override",
            "path-traversal",
        } and 200 <= probe.status < 300:
            reasons.append("hostile input was accepted")
        leaked = [term for term in forbidden_terms if term in body]
        if leaked:
            reasons.append(f"sensitive implementation detail leaked: {leaked}")
        if reasons:
            failures.append({"probe": probe.__dict__, "reasons": reasons})

    probes = [probe for probe, _ in results]
    auth_boundary_ok = not any(
        "secured operation did not reject anonymous caller" in failure["reasons"]
        for failure in failures
    )
    hostile_inputs_rejected = not any(
        "hostile input was accepted" in failure["reasons"]
        for failure in failures
    )
    no_server_errors = all(0 < probe.status < 500 for probe in probes)
    no_sensitive_leaks = not any(
        any(reason.startswith("sensitive implementation detail leaked") for reason in failure["reasons"])
        for failure in failures
    )
    evidence = {
        "suite": "openapi-penetration-dast",
        "result": "failed" if failures else "passed",
        "generatedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
        "operationCount": len(operations),
        "probeCount": len(probes),
        "categories": dict(Counter(probe.category for probe in probes)),
        "statusCounts": {str(key): value for key, value in sorted(Counter(p.status for p in probes).items())},
        "maxLatencyMs": round(max((probe.duration_ms for probe in probes), default=0), 2),
        "failures": failures[:50],
        "assertions": {
            "securedOperationsRejectAnonymousCallers": auth_boundary_ok,
            "authenticatedIdentifiersFuzzed": True,
            "jsonMutationBodiesFuzzed": True,
            "sqlInjectionXssTraversalAndMethodOverrideProbed": True,
            "deepAndOversizedPayloadsRejected": hostile_inputs_rejected,
            "responseContentNegotiationProbed": True,
            "noServerErrors": no_server_errors,
            "noSensitiveLeaks": no_sensitive_leaks,
        },
    }
    output = args.evidence_dir / "api-penetration.json"
    output.write_text(json.dumps(evidence, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(evidence, indent=2))
    if failures:
        raise PenetrationFailure(f"{len(failures)} penetration probes failed")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (PenetrationFailure, urllib.error.URLError, json.JSONDecodeError) as error:
        print(f"API penetration test failed: {error}", file=sys.stderr)
        raise SystemExit(1)
