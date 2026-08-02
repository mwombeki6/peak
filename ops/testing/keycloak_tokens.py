#!/usr/bin/env python3
"""Rotation-safe Keycloak token lifecycle shared by the load harnesses.

Access tokens in both Peak realms expire within five minutes, which
verify-keycloak-realms.sh asserts. Any harness that outlives that window has to
renew, and the two that do so must agree on how, because a refresh token is
rotated on use: two implementations drifting apart means one of them starts
replaying a spent refresh token.

This lived in api_write_load_test.py and was not shared. api_load_test.py
authenticated once and reused that token for its whole run, which is invisible
at the 800 requests CI issues and fails at the 100,000 the weekly soak issues.
The soak found it on 2026-08-02: an error rate of 0.72% against a 0.5% ceiling,
entirely 401s on reads. Extracting it here is the part that stops the two
diverging again.
"""

from __future__ import annotations

import json
import os
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from typing import Any, Callable


class TokenError(RuntimeError):
    pass


HOSPITALITY_REALM = os.environ.get("KEYCLOAK_HOSPITALITY_REALM", "peak-hospitality")
TokenRequest = Callable[[str, dict[str, str]], dict[str, Any]]
Clock = Callable[[], float]


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
        raise TokenError("Keycloak token response did not contain access_token")
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
            raise TokenError("Keycloak token response has invalid expires_in") from error
        if not access_token or not refresh_token or expires_in <= 0:
            raise TokenError(
                "Keycloak token response requires access_token, refresh_token, and positive expires_in",
            )
        refresh_skew = min(30.0, max(1.0, expires_in * 0.1))
        self.current_access_token = access_token
        self.current_refresh_token = refresh_token
        self.refresh_at = self.clock() + max(0.0, expires_in - refresh_skew)
        return access_token
