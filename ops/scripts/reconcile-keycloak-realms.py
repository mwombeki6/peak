#!/usr/bin/env python3
"""Reconcile Peak-owned Keycloak realm, client, and mapper configuration."""

from __future__ import annotations

import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
PUBLIC_BASE = os.environ.get("KEYCLOAK_BASE_URL", "http://localhost:8081").rstrip("/")
ADMIN_BASE = os.environ.get("KEYCLOAK_ADMIN_BASE_URL", PUBLIC_BASE).rstrip("/")
ADMIN_USER = os.environ.get("KEYCLOAK_ADMIN", "admin")
ADMIN_PASSWORD = os.environ.get("KEYCLOAK_ADMIN_PASSWORD", "")
RECONCILER_CLIENT_ID = os.environ.get(
    "KEYCLOAK_RECONCILER_CLIENT_ID", "peak-realm-reconciler"
)
RECONCILER_SECRET = os.environ.get("KEYCLOAK_RECONCILER_SECRET", "")
ALLOW_BOOTSTRAP_ADMIN = (
    os.environ.get("KEYCLOAK_ALLOW_BOOTSTRAP_ADMIN", "").strip().lower() == "true"
)
TEMPLATES = (
    ROOT / "ops/keycloak/peak-platform-realm.json",
    ROOT / "ops/keycloak/peak-hospitality-realm.json",
)
CHILD_FIELDS = {"clients", "requiredActions"}
RETIRED_CLIENT_IDS = {
    "peak-hospitality": ("peak-web", "peak-tenant-admin"),
}
UNRESOLVED = re.compile(r"\$\{?[A-Za-z_][A-Za-z0-9_]*\}?")


def request(
    method: str,
    url: str,
    *,
    token: str | None = None,
    payload: object | None = None,
    form: dict[str, str] | None = None,
    allow_404: bool = False,
) -> tuple[int, object | None]:
    headers = {"Accept": "application/json"}
    data = None
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if payload is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(payload, separators=(",", ":")).encode()
    if form is not None:
        headers["Content-Type"] = "application/x-www-form-urlencoded"
        data = urllib.parse.urlencode(form).encode()
    try:
        with urllib.request.urlopen(
            urllib.request.Request(url, data=data, headers=headers, method=method),
            timeout=30,
        ) as response:
            body = response.read()
            return response.status, json.loads(body) if body else None
    except urllib.error.HTTPError as error:
        if allow_404 and error.code == 404:
            return error.code, None
        detail = error.read().decode(errors="replace")
        raise RuntimeError(f"{method} {url} failed with HTTP {error.code}: {detail}") from error


def load_template(path: Path) -> dict[str, object]:
    expanded = os.path.expandvars(path.read_text())
    unresolved = sorted(set(UNRESOLVED.findall(expanded)))
    if unresolved:
        raise RuntimeError(f"{path.name} has unresolved environment variables: {', '.join(unresolved)}")
    return json.loads(expanded)


def admin(method: str, path: str, token: str, **kwargs: object) -> tuple[int, object | None]:
    return request(method, f"{ADMIN_BASE}/admin{path}", token=token, **kwargs)


def reconcile_required_actions(realm: str, desired: list[dict[str, object]], token: str) -> None:
    _, current_payload = admin("GET", f"/realms/{realm}/authentication/required-actions", token)
    current = {action["alias"]: action for action in current_payload or []}
    for action in desired:
        alias = str(action["alias"])
        if alias not in current:
            raise RuntimeError(f"{realm} does not expose required-action provider {alias}")
        admin(
            "PUT",
            f"/realms/{realm}/authentication/required-actions/{urllib.parse.quote(alias, safe='')}",
            token,
            payload=action,
        )


def reconcile_mappers(
    realm: str,
    client_uuid: str,
    desired: list[dict[str, object]],
    token: str,
) -> None:
    base = f"/realms/{realm}/clients/{client_uuid}/protocol-mappers/models"
    _, current_payload = admin("GET", base, token)
    current = {mapper["name"]: mapper for mapper in current_payload or []}
    desired_names = {mapper["name"] for mapper in desired}
    for name, mapper in current.items():
        if name not in desired_names:
            admin("DELETE", f"{base}/{mapper['id']}", token)
    for mapper in desired:
        existing = current.get(mapper["name"])
        if existing is None:
            admin("POST", base, token, payload=mapper)
        else:
            update = dict(mapper)
            update["id"] = existing["id"]
            admin("PUT", f"{base}/{existing['id']}", token, payload=update)


def reconcile_clients(realm: str, desired: list[dict[str, object]], token: str) -> None:
    for client in desired:
        client_id = str(client["clientId"])
        query = urllib.parse.urlencode({"clientId": client_id})
        _, matches_payload = admin("GET", f"/realms/{realm}/clients?{query}", token)
        matches = matches_payload or []
        if len(matches) > 1:
            raise RuntimeError(f"{realm} contains duplicate clientId {client_id}")
        client_payload = {key: value for key, value in client.items() if key != "protocolMappers"}
        if not matches:
            admin("POST", f"/realms/{realm}/clients", token, payload=client_payload)
            _, matches_payload = admin("GET", f"/realms/{realm}/clients?{query}", token)
            matches = matches_payload or []
        else:
            client_uuid = matches[0]["id"]
            admin("PUT", f"/realms/{realm}/clients/{client_uuid}", token, payload=client_payload)
        if len(matches) != 1:
            raise RuntimeError(f"Could not resolve reconciled client {realm}/{client_id}")
        reconcile_mappers(
            realm,
            str(matches[0]["id"]),
            list(client.get("protocolMappers", [])),
            token,
        )


def retire_clients(realm: str, desired: list[dict[str, object]], token: str) -> None:
    desired_ids = {str(client["clientId"]) for client in desired}
    retired_ids = RETIRED_CLIENT_IDS.get(realm, ())
    conflicts = sorted(desired_ids & set(retired_ids))
    if conflicts:
        raise RuntimeError(
            f"{realm}/{','.join(conflicts)} is both desired and retired"
        )
    for client_id in retired_ids:
        query = urllib.parse.urlencode({"clientId": client_id})
        _, matches_payload = admin("GET", f"/realms/{realm}/clients?{query}", token)
        for client in matches_payload or []:
            client_uuid = urllib.parse.quote(str(client["id"]), safe="")
            admin("DELETE", f"/realms/{realm}/clients/{client_uuid}", token)
            print(f"Retired Keycloak client {realm}/{client_id}")


def reconcile_realm(desired: dict[str, object], token: str) -> None:
    realm = str(desired["realm"])
    status, _ = admin("GET", f"/realms/{realm}", token, allow_404=True)
    if status == 404:
        admin("POST", "/realms", token, payload=desired)
        print(f"Created Keycloak realm {realm}")
        return
    realm_payload = {key: value for key, value in desired.items() if key not in CHILD_FIELDS}
    admin("PUT", f"/realms/{realm}", token, payload=realm_payload)
    reconcile_required_actions(realm, list(desired.get("requiredActions", [])), token)
    desired_clients = list(desired.get("clients", []))
    reconcile_clients(realm, desired_clients, token)
    retire_clients(realm, desired_clients, token)
    print(f"Reconciled Keycloak realm {realm}")


def service_account_token(realm: str) -> str:
    """Client-credentials token for the realm's own reconciler service account.

    Scoped to one realm and to the roles that realm grants it, so a compromised
    reconciliation credential cannot administer the server. This is the intended
    path; the master-realm password grant below exists only for first
    installation, before the service account exists to authenticate with.
    """
    _, payload = request(
        "POST",
        f"{ADMIN_BASE}/realms/{realm}/protocol/openid-connect/token",
        form={
            "client_id": RECONCILER_CLIENT_ID,
            "grant_type": "client_credentials",
            "client_secret": RECONCILER_SECRET,
        },
    )
    token = str((payload or {}).get("access_token", ""))
    if not token:
        raise RuntimeError(
            f"Reconciler token response for {realm} did not contain access_token",
        )
    return token


def bootstrap_admin_token() -> str:
    """Master-realm administrator password grant, for first installation only.

    A permanent human master administrator holds full server administration for
    a task that only needs to manage clients and required actions in one realm.
    It is therefore refused unless explicitly enabled, so steady-state
    reconciliation cannot silently fall back to it.

    Administrative token acquisition uses the administration base, not the
    public one. Keycloak documents that hostname-admin alone does not prevent
    Admin REST access through the public frontend URL, so the reverse proxy is
    expected to block /admin/** and /realms/master/** publicly. A script
    authenticating over the public host would break the moment that block is
    correctly applied, and until then it quietly depends on the gap being open.
    """
    if not ALLOW_BOOTSTRAP_ADMIN:
        raise RuntimeError(
            "Realm reconciliation requires KEYCLOAK_RECONCILER_SECRET. Set "
            "KEYCLOAK_ALLOW_BOOTSTRAP_ADMIN=true only for a first-install or "
            "recovery ceremony, before the reconciler service account exists.",
        )
    if not ADMIN_PASSWORD:
        raise RuntimeError("KEYCLOAK_ADMIN_PASSWORD is required")
    _, token_payload = request(
        "POST",
        f"{ADMIN_BASE}/realms/master/protocol/openid-connect/token",
        form={
            "client_id": "admin-cli",
            "grant_type": "password",
            "username": ADMIN_USER,
            "password": ADMIN_PASSWORD,
        },
    )
    token = str((token_payload or {}).get("access_token", ""))
    if not token:
        raise RuntimeError("Keycloak admin token response did not contain access_token")
    return token


def main() -> int:
    bootstrap_token = None if RECONCILER_SECRET else bootstrap_admin_token()
    for template in TEMPLATES:
        desired = load_template(template)
        realm = str(desired["realm"])
        token = bootstrap_token or service_account_token(realm)
        reconcile_realm(desired, token)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, RuntimeError) as error:
        print(f"Keycloak reconciliation failed: {error}", file=sys.stderr)
        raise SystemExit(1)
