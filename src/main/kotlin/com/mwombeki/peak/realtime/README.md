# Realtime Module

## Overview

Realtime publishes committed, tenant/property-scoped events to authenticated
SSE and STOMP/WebSocket clients. PostgreSQL owns the durable 24-hour replay
journal; each API replica polls the journal and fans events out only to its
local connections. Delivery uses bounded property-sharded executors, preserving
order within one property while allowing separate properties to progress
concurrently. Saturation applies caller backpressure instead of dropping a
committed event.

## Communication with Other Modules
The Real-time module doesn't work in isolation; it listens to what other modules are doing:

-   **Business Modules**: Property-scoped platform outbox events are mirrored
    into the journal in the same database transaction as the mutation.
-   **Audit Module**: If someone tries to "eavesdrop" on another hotel's stream, the Real-time module reports this suspicious activity to the Audit module.
-   **Shared Context**: The module uses the `RequestContext` to identify who is connecting and which tenant they belong to.

## Dual-Stream Support
We support two ways for clients to receive data:

1.  **WebSockets (STOMP)**: 
    -   Read-only server broadcasts over STOMP.
    -   Endpoint: `/ws-connect`
    -   The HTTP upgrade request must include `Authorization: Bearer <JWT>`.
    -   Subscription Path: `/topic/tenants/{tenantId}/properties/{propertyId}/stream`
    -   Includes heartbeats every 10 seconds to keep the connection alive.

2.  **Server-Sent Events (SSE)**:
    -   A simpler, lightweight alternative to WebSockets.
    -   Endpoint: `GET /api/v1/realtime/tenants/{tenantId}/properties/{propertyId}/stream`
    -   Ideal for simple "read-only" live updates.
    -   Bounded to 100 active SSE connections per tenant/property stream.
    -   Supports reconnect/resume with the `Last-Event-ID` header against the durable journal.

## Security & Isolation
-   **Tenant and Property Authorization**: SSE routes are covered by `module_access_matrix`; WebSocket subscriptions call `can_access_module` directly. A user needs the `realtime.stream` permission and enabled `realtime` module for the target property.
-   **Session-bound identity**: The authenticated, DB-resolved request identity is copied into the WebSocket session during the HTTP handshake. STOMP headers and trusted identity headers cannot replace it.
-   **Audited denials**: Cross-tenant/property subscription attempts are denied first, then audited in a tenant-scoped transaction using the handshake correlation ID.
-   **Origin Control**: WebSocket origins are configured with `peak.realtime.websocket.allowed-origins`. Wildcard origins are rejected.
-   **No Platform Bypass**: Platform users do not bypass tenant/property realtime permissions.
-   **No Client Publish**: Every client STOMP `SEND` frame is rejected.
-   **Bounded Connections**: SSE has a per-property limit and WebSocket has a process-wide limit. Abrupt and normal disconnects release capacity idempotently.

## Monitoring & Metrics
The module tracks its own health using professional metrics:
-   `peak.realtime.sse.connections.active`: active SSE connections.
-   `peak.realtime.sse.connections.opened`: accepted SSE connections.
-   `peak.realtime.sse.connections.closed{reason}`: SSE disconnects by reason.
-   `peak.realtime.sse.connections.rejected{reason}`: rejected SSE connections, including per-property limit pressure.
-   `peak.realtime.sse.events.delivered{eventType}`: SSE event deliveries.
-   `peak.realtime.sse.events.failed{eventType}`: SSE delivery failures.
-   `peak.realtime.journal.events.appended{eventType}`: committed journal events.
-   `peak.realtime.journal.events.polled`: events read by API replicas.
-   `peak.realtime.journal.events.replayed`: events replayed after reconnect.
-   `peak.realtime.journal.events.expired`: expired journal rows deleted.
-   `peak.realtime.events.fanned_out{eventType}`: events broadcast by an API replica.
-   `peak.realtime.fanout.backpressure.applied`: bounded fanout queue saturation.
-   `peak.realtime.websocket.connections.active`: active WebSocket sessions.
-   `peak.realtime.websocket.connect_attempts`: WebSocket connection attempts.
-   `peak.realtime.websocket.subscriptions`: accepted WebSocket subscriptions.
-   `peak.realtime.security.violations`: blocked realtime subscription attempts.
-   `realtime.security.audit_failures`: denied-subscription audit writes that failed after access was already blocked.

## For Developers: Adding New Events
To broadcast a new type of event from another module:
1.  Enqueue a property-scoped `OutboxDestination.PLATFORM` event in the owning
    transaction.
2.  The database trigger mirrors its sanitized payload and correlation id into
    the durable journal atomically.
3.  Every API replica advances its own sequence cursor and sends the event to
    local authorized subscribers.
