#!/usr/bin/env python3
"""The read load harness must renew tokens, not hold one for the whole run.

api_load_test.py authenticated once and reused that token for every request.
That is invisible at the 800 requests CI issues and fails at the 100,000 the
weekly soak issues, because access tokens expire within five minutes. The soak
found it on 2026-08-02 as a 0.72% error rate against a 0.5% ceiling, entirely
401s on reads.

These assert the behaviour rather than the wiring, so reverting to a single
static token fails them.
"""

from __future__ import annotations

import importlib.util
import sys
import unittest
import unittest.mock
import urllib.error
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "ops/testing"))
SCRIPT = ROOT / "ops/testing/api_load_test.py"
SPEC = importlib.util.spec_from_file_location("api_load_test", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
LOAD = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = LOAD
SPEC.loader.exec_module(LOAD)


class RecordingManager:
    """Stands in for KeycloakTokenManager, counting what the harness asks for."""

    def __init__(self, tokens: list[str]) -> None:
        self.tokens = tokens
        self.issued: list[str] = []
        self.unauthorized_refreshes = 0

    def access_token(self) -> str:
        token = self.tokens[min(len(self.issued), len(self.tokens) - 1)]
        self.issued.append(token)
        return token

    def refresh_after_unauthorized(self, rejected_token: str) -> str:
        self.unauthorized_refreshes += 1
        assert rejected_token == self.tokens[0]
        return self.tokens[-1]


class ApiLoadTokenRefreshTests(unittest.TestCase):
    def test_call_asks_the_manager_for_a_token_per_request(self) -> None:
        manager = RecordingManager(["first"])
        seen: list[str] = []

        def urlopen(request, timeout=None):
            seen.append(request.headers["Authorization"])
            return FakeResponse(200, b"{}")

        with unittest.mock.patch.object(LOAD.urllib.request, "urlopen", urlopen):
            for sequence in range(3):
                LOAD.call("http://api", "management", manager, "/api/v1/x", sequence)

        # A harness that captured one token up front would ask once, not per call.
        self.assertEqual(3, len(manager.issued))
        self.assertEqual(["Bearer first"] * 3, seen)

    def test_unauthorized_response_is_retried_once_with_a_fresh_token(self) -> None:
        manager = RecordingManager(["stale", "fresh"])
        seen: list[str] = []

        def urlopen(request, timeout=None):
            authorization = request.headers["Authorization"]
            seen.append(authorization)
            if authorization == "Bearer stale":
                raise urllib.error.HTTPError("http://api", 401, "Unauthorized", {}, None)
            return FakeResponse(200, b"{}")

        with unittest.mock.patch.object(LOAD.urllib.request, "urlopen", urlopen):
            role, path, status, _, _ = LOAD.call(
                "http://api", "management", manager, "/api/v1/x", 1
            )

        self.assertEqual(200, status, "an expired token must not be reported as a failure")
        self.assertEqual(1, manager.unauthorized_refreshes)
        self.assertEqual(["Bearer stale", "Bearer fresh"], seen)
        self.assertEqual(("management", "/api/v1/x"), (role, path))

    def test_a_genuine_unauthorized_is_still_reported_after_one_retry(self) -> None:
        manager = RecordingManager(["stale", "fresh"])

        def urlopen(request, timeout=None):
            raise urllib.error.HTTPError("http://api", 401, "Unauthorized", {}, None)

        with unittest.mock.patch.object(LOAD.urllib.request, "urlopen", urlopen):
            _, _, status, _, _ = LOAD.call(
                "http://api", "management", manager, "/api/v1/x", 1
            )

        # Retrying forever would hide a real authorization failure behind a hang.
        self.assertEqual(401, status)
        self.assertEqual(1, manager.unauthorized_refreshes)


class FakeResponse:
    def __init__(self, status: int, body: bytes) -> None:
        self.status = status
        self._body = body

    def read(self) -> bytes:
        return self._body

    def __enter__(self) -> "FakeResponse":
        return self

    def __exit__(self, *_: object) -> None:
        return None


if __name__ == "__main__":
    unittest.main()
