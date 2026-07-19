#!/usr/bin/env python3
"""Build, apply, and verify Peak's one-time legacy Keycloak realm migration.

The source realm must be produced by Keycloak's offline export command with
``--users realm_file``. The generated partial-import files contain credential
material and must remain in a mode-0700 temporary directory.
"""

from __future__ import annotations

import argparse
import collections
import hashlib
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any


PUBLIC_BASE = os.environ.get("KEYCLOAK_BASE_URL", "http://localhost:8081").rstrip("/")
ADMIN_BASE = os.environ.get("KEYCLOAK_ADMIN_BASE_URL", PUBLIC_BASE).rstrip("/")
ADMIN_USER = os.environ.get("KEYCLOAK_ADMIN", "admin")
ADMIN_PASSWORD = os.environ.get("KEYCLOAK_ADMIN_PASSWORD", "")

SAFE_USER_FIELDS = (
    "id",
    "username",
    "firstName",
    "lastName",
    "email",
    "emailVerified",
    "enabled",
    "createdTimestamp",
    "totp",
    "requiredActions",
    "credentials",
    "disableableCredentialTypes",
)
LOGIN_CREDENTIAL_TYPES = {"password", "otp", "webauthn", "webauthn-passwordless"}


def read_json(path: Path) -> Any:
    return json.loads(path.read_text())


def write_private_json(path: Path, payload: Any) -> None:
    path.write_text(json.dumps(payload, indent=2, sort_keys=True) + "\n")
    path.chmod(0o600)


def request(
    method: str,
    url: str,
    *,
    token: str | None = None,
    payload: Any | None = None,
    form: dict[str, str] | None = None,
    allow_404: bool = False,
) -> tuple[int, Any | None]:
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


def admin_token() -> str:
    if not ADMIN_PASSWORD:
        raise RuntimeError("KEYCLOAK_ADMIN_PASSWORD is required")
    _, payload = request(
        "POST",
        f"{PUBLIC_BASE}/realms/master/protocol/openid-connect/token",
        form={
            "client_id": "admin-cli",
            "grant_type": "password",
            "username": ADMIN_USER,
            "password": ADMIN_PASSWORD,
        },
    )
    token = str((payload or {}).get("access_token", ""))
    if not token:
        raise RuntimeError("Keycloak admin token response did not contain access_token")
    return token


def admin(
    method: str,
    path: str,
    token: str,
    **kwargs: Any,
) -> tuple[int, Any | None]:
    return request(method, f"{ADMIN_BASE}/admin{path}", token=token, **kwargs)


def credential_types(user: dict[str, Any]) -> collections.Counter[str]:
    return collections.Counter(
        str(credential.get("type", ""))
        for credential in user.get("credentials", [])
        if credential.get("type")
    )


def sanitized_user(source: dict[str, Any], identity_mode: str) -> dict[str, Any]:
    if source.get("serviceAccountClientId"):
        raise RuntimeError(f"Legacy subject {source.get('id')} is a service account")
    if source.get("federatedIdentities"):
        raise RuntimeError(
            f"Legacy subject {source.get('id')} uses identity federation; migrate its broker explicitly",
        )
    result = {field: source[field] for field in SAFE_USER_FIELDS if field in source}
    subject = str(result.get("id", "")).strip()
    username = str(result.get("username", "")).strip()
    if not subject or not username:
        raise RuntimeError("Every migrated Keycloak user requires an id and username")
    types = credential_types(result)
    if not LOGIN_CREDENTIAL_TYPES.intersection(types):
        raise RuntimeError(f"Legacy subject {subject} has no portable login credential")
    actions = list(dict.fromkeys(str(value) for value in result.get("requiredActions", [])))
    if identity_mode == "platform" and "otp" not in types and "CONFIGURE_TOTP" not in actions:
        actions.append("CONFIGURE_TOTP")
    result["requiredActions"] = actions
    # Keycloak attributes, groups, realm roles and client roles are deliberately
    # excluded. Peak's database remains the only business authorization source.
    return result


def build(args: argparse.Namespace) -> dict[str, Any]:
    export_path = Path(args.export)
    links_path = Path(args.links)
    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    output_dir.chmod(0o700)

    realm = read_json(export_path)
    if realm.get("realm") != args.legacy_realm:
        raise RuntimeError(
            f"Expected legacy realm {args.legacy_realm}, found {realm.get('realm')}",
        )
    users = {str(user.get("id", "")): user for user in realm.get("users", [])}
    links = read_json(links_path)
    if not isinstance(links, list):
        raise RuntimeError("Identity-link inventory must be a JSON array")

    by_subject: dict[str, dict[str, Any]] = {}
    for link in links:
        mode = str(link.get("identityMode", ""))
        subject = str(link.get("subject", ""))
        if mode not in {"platform", "tenant"} or not subject:
            raise RuntimeError("Every legacy identity link requires platform/tenant mode and subject")
        if subject in by_subject:
            raise RuntimeError(f"Legacy subject {subject} has more than one active Peak identity link")
        by_subject[subject] = link

    missing = sorted(set(by_subject) - set(users))
    if missing:
        raise RuntimeError(f"Peak identity links reference missing legacy subjects: {', '.join(missing)}")
    unmapped = sorted(set(users) - set(by_subject))
    if unmapped:
        raise RuntimeError(
            "Legacy realm contains subjects without active Peak identity links: "
            + ", ".join(unmapped),
        )

    partition: dict[str, list[dict[str, Any]]] = {"platform": [], "tenant": []}
    manifest_users: list[dict[str, str]] = []
    for subject, link in sorted(by_subject.items()):
        mode = str(link["identityMode"])
        partition[mode].append(sanitized_user(users[subject], mode))
        manifest_users.append(
            {
                "identityLinkId": str(link["identityLinkId"]),
                "identityMode": mode,
                "subject": subject,
                "targetRealm": args.platform_realm if mode == "platform" else args.hospitality_realm,
            },
        )

    platform_import = output_dir / "platform-users.partial-import.json"
    hospitality_import = output_dir / "hospitality-users.partial-import.json"
    manifest_path = output_dir / "migration-manifest.json"
    write_private_json(platform_import, {"ifResourceExists": "FAIL", "users": partition["platform"]})
    write_private_json(hospitality_import, {"ifResourceExists": "FAIL", "users": partition["tenant"]})
    manifest = {
        "legacyRealm": args.legacy_realm,
        "platformRealm": args.platform_realm,
        "hospitalityRealm": args.hospitality_realm,
        "sourceExportSha256": hashlib.sha256(export_path.read_bytes()).hexdigest(),
        "platformIdentityLinks": len(partition["platform"]),
        "hospitalityIdentityLinks": len(partition["tenant"]),
        "users": manifest_users,
    }
    write_private_json(manifest_path, manifest)
    print(
        f"Prepared {len(partition['platform'])} platform and "
        f"{len(partition['tenant'])} hospitality identities",
    )
    return manifest


def existing_user(realm: str, subject: str, token: str) -> dict[str, Any] | None:
    status, payload = admin(
        "GET",
        f"/realms/{urllib.parse.quote(realm, safe='')}/users/{urllib.parse.quote(subject, safe='')}",
        token,
        allow_404=True,
    )
    return None if status == 404 else dict(payload or {})


def verify_user(realm: str, desired: dict[str, Any], token: str) -> None:
    subject = str(desired["id"])
    actual = existing_user(realm, subject, token)
    if actual is None:
        raise RuntimeError(f"Target realm {realm} is missing migrated subject {subject}")
    for field in ("id", "username", "email", "enabled"):
        if actual.get(field) != desired.get(field):
            raise RuntimeError(f"Target realm {realm} subject {subject} differs on {field}")
    _, credentials_payload = admin(
        "GET",
        f"/realms/{urllib.parse.quote(realm, safe='')}/users/{urllib.parse.quote(subject, safe='')}/credentials",
        token,
    )
    actual_types = collections.Counter(
        str(value.get("type", "")) for value in credentials_payload or [] if value.get("type")
    )
    expected_types = credential_types(desired)
    if actual_types != expected_types:
        raise RuntimeError(
            f"Target realm {realm} subject {subject} credential types differ: "
            f"expected {dict(expected_types)}, found {dict(actual_types)}",
        )
    expected_actions = set(desired.get("requiredActions", []))
    if not expected_actions.issubset(set(actual.get("requiredActions", []))):
        raise RuntimeError(f"Target realm {realm} subject {subject} lost required actions")


def apply_import(args: argparse.Namespace) -> None:
    token = admin_token()
    for realm, import_path in (
        (args.platform_realm, Path(args.platform_import)),
        (args.hospitality_realm, Path(args.hospitality_import)),
    ):
        payload = read_json(import_path)
        desired_users = list(payload.get("users", []))
        missing = [
            user for user in desired_users if existing_user(realm, str(user["id"]), token) is None
        ]
        if missing:
            admin(
                "POST",
                f"/realms/{urllib.parse.quote(realm, safe='')}/partialImport",
                token,
                payload={"ifResourceExists": "FAIL", "users": missing},
            )
        for user in desired_users:
            verify_user(realm, user, token)
        print(f"Verified {len(desired_users)} migrated identities in {realm}")


def disable_legacy(args: argparse.Namespace) -> None:
    token = admin_token()
    realm_path = f"/realms/{urllib.parse.quote(args.legacy_realm, safe='')}"
    status, payload = admin("GET", realm_path, token, allow_404=True)
    if status == 404:
        print(f"Legacy realm {args.legacy_realm} does not exist")
        return
    representation = dict(payload or {})
    representation["enabled"] = False
    admin("PUT", realm_path, token, payload=representation)
    _, verified = admin("GET", realm_path, token)
    if (verified or {}).get("enabled") is not False:
        raise RuntimeError(f"Legacy realm {args.legacy_realm} remained enabled")
    print(f"Disabled legacy realm {args.legacy_realm}")


def parser() -> argparse.ArgumentParser:
    result = argparse.ArgumentParser(description=__doc__)
    subcommands = result.add_subparsers(dest="command", required=True)

    build_parser = subcommands.add_parser("build")
    build_parser.add_argument("--export", required=True)
    build_parser.add_argument("--links", required=True)
    build_parser.add_argument("--output-dir", required=True)
    build_parser.add_argument("--legacy-realm", default="peak")
    build_parser.add_argument("--platform-realm", required=True)
    build_parser.add_argument("--hospitality-realm", required=True)
    build_parser.set_defaults(handler=build)

    apply_parser = subcommands.add_parser("apply")
    apply_parser.add_argument("--platform-import", required=True)
    apply_parser.add_argument("--hospitality-import", required=True)
    apply_parser.add_argument("--platform-realm", required=True)
    apply_parser.add_argument("--hospitality-realm", required=True)
    apply_parser.set_defaults(handler=apply_import)

    disable_parser = subcommands.add_parser("disable")
    disable_parser.add_argument("--legacy-realm", default="peak")
    disable_parser.set_defaults(handler=disable_legacy)
    return result


def main() -> int:
    args = parser().parse_args()
    args.handler(args)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except (OSError, ValueError, RuntimeError) as error:
        print(f"Legacy Keycloak migration failed: {error}", file=sys.stderr)
        raise SystemExit(1)
