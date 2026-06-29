#!/usr/bin/env python3

import argparse
import json
import threading
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlparse


class DeliveryStore:
    def __init__(self):
        self._lock = threading.Lock()
        self._messages = []

    def add(self, message):
        with self._lock:
            self._messages.append(message)

    def latest(self, recipient):
        with self._lock:
            for message in reversed(self._messages):
                if recipient is None or message.get("recipient") == recipient:
                    return message
        return None


def handler(store, api_key):
    class ProviderHandler(BaseHTTPRequestHandler):
        def do_GET(self):
            parsed = urlparse(self.path)
            if parsed.path == "/health":
                return self.respond(200, {"status": "UP"})
            if parsed.path == "/v1/messages/latest":
                recipient = parse_qs(parsed.query).get("recipient", [None])[0]
                message = store.latest(recipient)
                return self.respond(200 if message else 404, message or {"error": "not_found"})
            return self.respond(404, {"error": "not_found"})

        def do_POST(self):
            if self.path != "/v1/messages":
                return self.respond(404, {"error": "not_found"})
            if self.headers.get("Authorization") != f"Bearer {api_key}":
                return self.respond(401, {"error": "unauthorized"})

            try:
                length = int(self.headers.get("Content-Length", "0"))
                message = json.loads(self.rfile.read(length))
            except (ValueError, json.JSONDecodeError):
                return self.respond(400, {"error": "invalid_json"})

            required = {"deliveryRequestId", "outboxEventId", "tenantId", "channel", "recipient", "content"}
            if not required.issubset(message):
                return self.respond(400, {"error": "missing_fields"})

            message["providerMessageId"] = str(uuid.uuid4())
            message["idempotencyKey"] = self.headers.get("Idempotency-Key")
            store.add(message)
            return self.respond(202, {"messageId": message["providerMessageId"]})

        def respond(self, status, body):
            payload = json.dumps(body).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)

        def log_message(self, message_format, *args):
            return

    return ProviderHandler


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8090)
    parser.add_argument("--api-key", required=True)
    args = parser.parse_args()

    server = ThreadingHTTPServer((args.host, args.port), handler(DeliveryStore(), args.api_key))
    server.serve_forever()


if __name__ == "__main__":
    main()
