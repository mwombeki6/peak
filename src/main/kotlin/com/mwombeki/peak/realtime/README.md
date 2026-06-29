# Real-time Module

## Overview
The Real-time module is the "nervous system" of the platform. It allows users (like hotel staff) to receive instant updates without refreshing their browser. For example, when a room status changes from "Dirty" to "Available," the change is streamed immediately to all connected staff members.

## Simple Terms: How it Works
Think of it like a **Radio Station**:
1.  **Broadcasting**: When something important happens in the system (like a room being cleaned), the system "broadcasts" an event.
2.  **Frequency**: Each property has its own "frequency" (stream).
3.  **Tuning In**: Frontend applications "tune in" to their specific property stream.
4.  **Security**: We have a "security guard" who checks that you only listen to the frequency belonging to your hotel (tenant).

## Communication with Other Modules
The Real-time module doesn't work in isolation; it listens to what other modules are doing:

-   **Property Module**: When committed property changes occur, it publishes a shared `RealtimeStreamEvent` that the Real-time module broadcasts.
-   **Audit Module**: If someone tries to "eavesdrop" on another hotel's stream, the Real-time module reports this suspicious activity to the Audit module.
-   **Shared Context**: The module uses the `RequestContext` to identify who is connecting and which tenant they belong to.

## Dual-Stream Support
We support two ways for clients to receive data:

1.  **WebSockets (STOMP)**: 
    -   Best for interactive, two-way communication.
    -   Endpoint: `/ws-connect`
    -   The HTTP upgrade request must include `Authorization: Bearer <JWT>`.
    -   Subscription Path: `/topic/tenants/{tenantId}/properties/{propertyId}/stream`
    -   Includes heartbeats every 10 seconds to keep the connection alive.

2.  **Server-Sent Events (SSE)**:
    -   A simpler, lightweight alternative to WebSockets.
    -   Endpoint: `GET /api/v1/realtime/tenants/{tenantId}/properties/{propertyId}/stream`
    -   Ideal for simple "read-only" live updates.
    -   Bounded to 100 active SSE connections per tenant/property stream.
    -   Supports reconnect/resume with the `Last-Event-ID` header. The server keeps a bounded replay buffer per tenant/property stream and replays events newer than the supplied id.

## Security & Isolation
-   **Tenant and Property Authorization**: SSE routes are covered by `module_access_matrix`; WebSocket subscriptions call `can_access_module` directly. A user needs the `realtime.stream` permission and enabled `realtime` module for the target property.
-   **Session-bound identity**: The authenticated, DB-resolved request identity is copied into the WebSocket session during the HTTP handshake. STOMP headers and trusted identity headers cannot replace it.
-   **Audited denials**: Cross-tenant/property subscription attempts are denied first, then audited in a tenant-scoped transaction using the handshake correlation ID.
-   **Origin Control**: WebSocket origins are configured with `peak.realtime.websocket.allowed-origins`. Wildcard origins are rejected.
-   **No Platform Bypass**: Platform users do not bypass tenant/property realtime permissions.

## Monitoring & Metrics
The module tracks its own health using professional metrics:
-   `peak.realtime.sse.connections.active`: active SSE connections.
-   `peak.realtime.sse.connections.opened`: accepted SSE connections.
-   `peak.realtime.sse.connections.closed{reason}`: SSE disconnects by reason.
-   `peak.realtime.sse.connections.rejected{reason}`: rejected SSE connections, including per-property limit pressure.
-   `peak.realtime.sse.events.published{eventType}`: events accepted into the SSE replay window.
-   `peak.realtime.sse.events.delivered{eventType}`: SSE event deliveries.
-   `peak.realtime.sse.events.failed{eventType}`: SSE delivery failures.
-   `peak.realtime.sse.events.dropped{eventType,reason}`: events dropped from the bounded replay window or future backpressure controls.
-   `realtime.websocket.active_connections`: active WebSocket sessions.
-   `realtime.websocket.connect_attempts`: WebSocket connection attempts.
-   `realtime.websocket.subscriptions`: accepted WebSocket subscriptions.
-   `realtime.security.violations`: blocked realtime subscription attempts.
-   `realtime.security.audit_failures`: denied-subscription audit writes that failed after access was already blocked.

## For Developers: Adding New Events
To broadcast a new type of event from another module:
1.  Inject `ApplicationEventPublisher`.
2.  Publish a shared `RealtimeStreamEvent` with the `tenantId`, `propertyId`, `eventType`, and a `payload` after the owning transaction commits.
3.  The `RealtimeEventListener` will catch it and send it to all authorized WebSocket and SSE subscribers.
