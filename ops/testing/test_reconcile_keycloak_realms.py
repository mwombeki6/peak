#!/usr/bin/env python3

from __future__ import annotations

import importlib.util
import sys
import unittest
from pathlib import Path
from unittest import mock


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "ops/scripts/reconcile-keycloak-realms.py"
SPEC = importlib.util.spec_from_file_location("reconcile_keycloak_realms", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
RECONCILER = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = RECONCILER
SPEC.loader.exec_module(RECONCILER)


class KeycloakClientRetirementTests(unittest.TestCase):
    def test_retires_only_declared_legacy_hospitality_clients(self) -> None:
        responses = {
            "/realms/peak-hospitality/clients?clientId=peak-web": [
                {"id": "legacy-web-uuid", "clientId": "peak-web"}
            ],
            "/realms/peak-hospitality/clients?clientId=peak-tenant-admin": [
                {"id": "legacy-admin-uuid", "clientId": "peak-tenant-admin"}
            ],
        }
        calls: list[tuple[str, str]] = []

        def admin(method: str, path: str, _token: str, **_: object):
            calls.append((method, path))
            if method == "GET":
                return 200, responses.get(path, [])
            return 204, None

        desired = [
            {"clientId": "peak-api"},
            {"clientId": "peak-hospitality-web"},
            {"clientId": "peak-pos-desktop"},
        ]
        with mock.patch.object(RECONCILER, "admin", side_effect=admin):
            RECONCILER.retire_clients("peak-hospitality", desired, "token")

        self.assertEqual(
            [
                (
                    "DELETE",
                    "/realms/peak-hospitality/clients/legacy-web-uuid",
                ),
                (
                    "DELETE",
                    "/realms/peak-hospitality/clients/legacy-admin-uuid",
                ),
            ],
            [call for call in calls if call[0] == "DELETE"],
        )
        self.assertFalse(any("peak-pos-desktop" in path for _, path in calls))

    def test_refuses_configuration_that_is_both_desired_and_retired(self) -> None:
        with self.assertRaisesRegex(RuntimeError, "both desired and retired"):
            RECONCILER.retire_clients(
                "peak-hospitality",
                [{"clientId": "peak-tenant-admin"}],
                "token",
            )

    def test_installs_desired_clients_before_retiring_legacy_clients(self) -> None:
        order: list[str] = []
        desired = {
            "realm": "peak-hospitality",
            "clients": [{"clientId": "peak-hospitality-web"}],
            "requiredActions": [],
        }

        def admin(method: str, path: str, _token: str, **_: object):
            if method == "GET" and path == "/realms/peak-hospitality":
                return 200, desired
            return 204, None

        with (
            mock.patch.object(RECONCILER, "admin", side_effect=admin),
            mock.patch.object(RECONCILER, "reconcile_required_actions"),
            mock.patch.object(
                RECONCILER,
                "reconcile_clients",
                side_effect=lambda *_: order.append("install"),
            ),
            mock.patch.object(
                RECONCILER,
                "retire_clients",
                side_effect=lambda *_: order.append("retire"),
            ),
        ):
            RECONCILER.reconcile_realm(desired, "token")

        self.assertEqual(["install", "retire"], order)


if __name__ == "__main__":
    unittest.main()
