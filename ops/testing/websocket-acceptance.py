#!/usr/bin/env python3

import argparse
import sys
import websocket


def receive_frame(connection):
    frame = connection.recv()
    if isinstance(frame, bytes):
        return frame.decode("utf-8")
    return frame


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", required=True)
    parser.add_argument("--token", required=True)
    parser.add_argument("--origin", required=True)
    parser.add_argument("--correlation-id", required=True)
    parser.add_argument("--tenant-id", required=True)
    parser.add_argument("--property-id", required=True)
    parser.add_argument("--expect-denied", action="store_true")
    args = parser.parse_args()

    connection = websocket.create_connection(
        args.url,
        header=[
            f"Authorization: Bearer {args.token}",
            f"X-Correlation-Id: {args.correlation_id}",
        ],
        origin=args.origin,
        subprotocols=["v12.stomp"],
        timeout=8,
    )
    try:
        connection.send("CONNECT\naccept-version:1.2\nhost:peak\n\n\x00")
        connected = receive_frame(connection)
        if "CONNECTED" not in connected:
            raise RuntimeError(f"STOMP connection was not accepted: {connected!r}")

        destination = (
            f"/topic/tenants/{args.tenant_id}/properties/"
            f"{args.property_id}/stream"
        )
        connection.send(
            "SUBSCRIBE\n"
            "id:phase2-acceptance\n"
            f"destination:{destination}\n"
            "ack:auto\n\n\x00"
        )

        if args.expect_denied:
            response = receive_frame(connection)
            if "ERROR" not in response:
                raise RuntimeError(
                    f"Cross-tenant STOMP subscription was not denied: {response!r}"
                )
            print("denied")
        else:
            connection.settimeout(1)
            try:
                response = receive_frame(connection)
                if "ERROR" in response:
                    raise RuntimeError(
                        f"Authorized STOMP subscription was denied: {response!r}"
                    )
            except websocket.WebSocketTimeoutException:
                pass
            print("subscribed")
    finally:
        connection.close()


if __name__ == "__main__":
    try:
        main()
    except Exception as exc:
        print(str(exc), file=sys.stderr)
        sys.exit(1)
