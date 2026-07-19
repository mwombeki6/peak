#!/usr/bin/env python3

from __future__ import annotations

import concurrent.futures
import importlib.util
import io
import json
import sys
import threading
import unittest
import urllib.error
from pathlib import Path
from unittest import mock


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = ROOT / "ops/testing/api_write_load_test.py"
SPEC = importlib.util.spec_from_file_location("api_write_load_test", SCRIPT)
assert SPEC is not None and SPEC.loader is not None
WRITE_LOAD = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = WRITE_LOAD
SPEC.loader.exec_module(WRITE_LOAD)


class MutableClock:
    def __init__(self) -> None:
        self.now = 0.0

    def __call__(self) -> float:
        return self.now


class RotatingTokenEndpoint:
    def __init__(self) -> None:
        self.lock = threading.Lock()
        self.forms: list[dict[str, str]] = []
        self.sequence = 0

    def __call__(self, keycloak_url: str, form: dict[str, str]) -> dict[str, object]:
        self.assert_url(keycloak_url)
        with self.lock:
            self.forms.append(dict(form))
            self.sequence += 1
            sequence = self.sequence
        if form["grant_type"] == "refresh_token":
            expected = f"refresh-{sequence - 1}"
            if form["refresh_token"] != expected:
                raise AssertionError(f"Expected rotated refresh token {expected}")
        return {
            "access_token": f"access-{sequence}",
            "refresh_token": f"refresh-{sequence}",
            "expires_in": 100,
        }

    @staticmethod
    def assert_url(keycloak_url: str) -> None:
        if keycloak_url != "https://keycloak.example.test":
            raise AssertionError("Unexpected Keycloak URL")


class FakeHttpResponse:
    def __init__(self, status: int, payload: dict[str, object]) -> None:
        self.status = status
        self.body = json.dumps(payload).encode()

    def __enter__(self) -> "FakeHttpResponse":
        return self

    def __exit__(self, *_: object) -> None:
        return None

    def read(self) -> bytes:
        return self.body


class ApiWriteLoadTokenRefreshTests(unittest.TestCase):
    def manager(
        self,
        endpoint: RotatingTokenEndpoint,
        clock: MutableClock,
    ) -> object:
        return WRITE_LOAD.KeycloakTokenManager(
            "https://keycloak.example.test",
            "restaurant-user",
            "test-password",
            token_request=endpoint,
            clock=clock,
        )

    def test_concurrent_workers_share_one_rotated_refresh(self) -> None:
        clock = MutableClock()
        endpoint = RotatingTokenEndpoint()
        manager = self.manager(endpoint, clock)
        self.assertEqual("access-1", manager.access_token())
        clock.now = 95

        with concurrent.futures.ThreadPoolExecutor(max_workers=24) as executor:
            tokens = list(executor.map(lambda _: manager.access_token(), range(96)))

        self.assertEqual({"access-2"}, set(tokens))
        self.assertEqual(1, manager.password_grant_count)
        self.assertEqual(1, manager.refresh_grant_count)
        self.assertEqual("refresh-1", endpoint.forms[1]["refresh_token"])

    def test_successive_refreshes_use_each_new_rotated_token_once(self) -> None:
        clock = MutableClock()
        endpoint = RotatingTokenEndpoint()
        manager = self.manager(endpoint, clock)
        self.assertEqual("access-1", manager.access_token())
        clock.now = 95
        self.assertEqual("access-2", manager.access_token())
        clock.now = 190
        self.assertEqual("access-3", manager.access_token())
        self.assertEqual("refresh-1", endpoint.forms[1]["refresh_token"])
        self.assertEqual("refresh-2", endpoint.forms[2]["refresh_token"])

    def test_stale_unauthorized_response_does_not_rotate_new_refresh_token_again(self) -> None:
        clock = MutableClock()
        endpoint = RotatingTokenEndpoint()
        manager = self.manager(endpoint, clock)
        rejected = manager.access_token()
        self.assertEqual("access-2", manager.refresh_after_unauthorized(rejected))
        self.assertEqual("access-2", manager.refresh_after_unauthorized(rejected))
        self.assertEqual(1, manager.refresh_grant_count)

    def test_invalid_refresh_falls_back_to_password_grant(self) -> None:
        clock = MutableClock()
        calls: list[dict[str, str]] = []

        def endpoint(_: str, form: dict[str, str]) -> dict[str, object]:
            calls.append(dict(form))
            if form["grant_type"] == "refresh_token":
                raise urllib.error.HTTPError(
                    "https://keycloak.example.test/token",
                    400,
                    "Bad Request",
                    {},
                    io.BytesIO(b'{}'),
                )
            sequence = sum(1 for call in calls if call["grant_type"] == "password")
            return {
                "access_token": f"password-access-{sequence}",
                "refresh_token": f"password-refresh-{sequence}",
                "expires_in": 100,
            }

        manager = WRITE_LOAD.KeycloakTokenManager(
            "https://keycloak.example.test",
            "restaurant-user",
            "test-password",
            token_request=endpoint,
            clock=clock,
        )
        self.assertEqual("password-access-1", manager.access_token())
        clock.now = 95
        self.assertEqual("password-access-2", manager.access_token())
        self.assertEqual(2, manager.password_grant_count)
        self.assertEqual(0, manager.refresh_grant_count)

    def test_api_request_retries_one_unauthorized_response_with_fresh_token(self) -> None:
        class Manager:
            def __init__(self) -> None:
                self.rejected: str | None = None

            @staticmethod
            def access_token() -> str:
                return "expired-access"

            def refresh_after_unauthorized(self, token: str) -> str:
                self.rejected = token
                return "fresh-access"

        manager = Manager()
        attempts: list[str] = []

        def urlopen(request: object, timeout: int) -> FakeHttpResponse:
            self.assertEqual(30, timeout)
            authorization = request.headers["Authorization"]
            attempts.append(authorization)
            if len(attempts) == 1:
                raise urllib.error.HTTPError(
                    request.full_url,
                    401,
                    "Unauthorized",
                    {},
                    io.BytesIO(b'{"status":401}'),
                )
            return FakeHttpResponse(200, {"result": "ok"})

        with mock.patch.object(WRITE_LOAD.urllib.request, "urlopen", side_effect=urlopen):
            status, payload, _ = WRITE_LOAD.request_json(
                "https://api.example.test",
                manager,
                "GET",
                "/api/v1/test",
                correlation_id="refresh-test",
            )

        self.assertEqual(200, status)
        self.assertEqual({"result": "ok"}, payload)
        self.assertEqual("expired-access", manager.rejected)
        self.assertEqual(
            ["Bearer expired-access", "Bearer fresh-access"],
            attempts,
        )


if __name__ == "__main__":
    unittest.main()
