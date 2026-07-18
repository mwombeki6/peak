#!/usr/bin/env python3

from __future__ import annotations

import importlib.util
import json
import tempfile
import types
import unittest
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "ops/scripts/legacy-keycloak-realm-migration.py"
SPEC = importlib.util.spec_from_file_location("legacy_keycloak_migration", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
MIGRATION = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(MIGRATION)


class LegacyKeycloakRealmMigrationTests(unittest.TestCase):
    def build_fixture(self, *, federated: bool = False) -> tuple[Path, dict[str, object]]:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        root = Path(temporary.name)
        export = {
            "realm": "peak",
            "users": [
                {
                    "id": "platform-subject",
                    "username": "operator",
                    "email": "operator@example.test",
                    "emailVerified": True,
                    "enabled": True,
                    "attributes": {"untrustedRole": ["root"]},
                    "realmRoles": ["admin"],
                    "clientRoles": {"peak-api": ["admin"]},
                    "groups": ["/operators"],
                    "federatedIdentities": (
                        [{"identityProvider": "external", "userId": "external-1"}]
                        if federated
                        else []
                    ),
                    "credentials": [
                        {
                            "id": "password-credential",
                            "type": "password",
                            "secretData": '{"value":"hash"}',
                            "credentialData": '{"hashIterations":5}',
                        },
                    ],
                },
                {
                    "id": "tenant-subject",
                    "username": "manager",
                    "email": "manager@example.test",
                    "emailVerified": True,
                    "enabled": True,
                    "credentials": [
                        {
                            "id": "passkey-credential",
                            "type": "webauthn-passwordless",
                            "secretData": "{}",
                            "credentialData": "{}",
                        },
                    ],
                },
            ],
        }
        links = [
            {
                "identityLinkId": "00000000-0000-0000-0000-000000000001",
                "identityMode": "platform",
                "subject": "platform-subject",
            },
            {
                "identityLinkId": "00000000-0000-0000-0000-000000000002",
                "identityMode": "tenant",
                "subject": "tenant-subject",
            },
        ]
        export_path = root / "peak-realm.json"
        links_path = root / "links.json"
        export_path.write_text(json.dumps(export))
        links_path.write_text(json.dumps(links))
        args = types.SimpleNamespace(
            export=str(export_path),
            links=str(links_path),
            output_dir=str(root / "generated"),
            legacy_realm="peak",
            platform_realm="peak-platform",
            hospitality_realm="peak-hospitality",
        )
        return root, {"args": args, "export": export}

    def test_partitions_by_peak_identity_mode_and_removes_keycloak_authorization(self) -> None:
        root, fixture = self.build_fixture()
        manifest = MIGRATION.build(fixture["args"])
        generated = root / "generated"
        platform = json.loads((generated / "platform-users.partial-import.json").read_text())
        hospitality = json.loads((generated / "hospitality-users.partial-import.json").read_text())

        self.assertEqual(1, manifest["platformIdentityLinks"])
        self.assertEqual(1, manifest["hospitalityIdentityLinks"])
        self.assertEqual("platform-subject", platform["users"][0]["id"])
        self.assertEqual("tenant-subject", hospitality["users"][0]["id"])
        self.assertIn("CONFIGURE_TOTP", platform["users"][0]["requiredActions"])
        self.assertEqual("hash", json.loads(platform["users"][0]["credentials"][0]["secretData"])["value"])
        for forbidden in ("attributes", "realmRoles", "clientRoles", "groups"):
            self.assertNotIn(forbidden, platform["users"][0])

        manifest_text = (generated / "migration-manifest.json").read_text()
        self.assertNotIn("operator@example.test", manifest_text)
        self.assertNotIn("secretData", manifest_text)
        self.assertEqual(0o600, (generated / "platform-users.partial-import.json").stat().st_mode & 0o777)

    def test_rejects_federated_identity_instead_of_silently_breaking_login(self) -> None:
        _, fixture = self.build_fixture(federated=True)
        with self.assertRaisesRegex(RuntimeError, "identity federation"):
            MIGRATION.build(fixture["args"])

    def test_rejects_identity_link_without_matching_legacy_subject(self) -> None:
        root, fixture = self.build_fixture()
        links_path = root / "links.json"
        links = json.loads(links_path.read_text())
        links.append(
            {
                "identityLinkId": "00000000-0000-0000-0000-000000000003",
                "identityMode": "tenant",
                "subject": "missing-subject",
            },
        )
        links_path.write_text(json.dumps(links))
        with self.assertRaisesRegex(RuntimeError, "missing legacy subjects"):
            MIGRATION.build(fixture["args"])

    def test_rejects_unmapped_legacy_user_before_disabling_realm(self) -> None:
        root, fixture = self.build_fixture()
        links_path = root / "links.json"
        links = json.loads(links_path.read_text())
        links.pop()
        links_path.write_text(json.dumps(links))
        with self.assertRaisesRegex(RuntimeError, "without active Peak identity links"):
            MIGRATION.build(fixture["args"])


if __name__ == "__main__":
    unittest.main()
