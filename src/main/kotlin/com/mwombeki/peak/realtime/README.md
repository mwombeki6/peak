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

-   **Property Module**: When a room's status is updated in the Property Management module, it sends a `RealtimeEvent` to the Real-time module.
-   **Audit Module**: If someone tries to "eavesdrop" on another hotel's stream, the Real-time module reports this suspicious activity to the Audit module.
-   **Shared Context**: The module uses the `RequestContext` to identify who is connecting and which tenant they belong to.

## Dual-Stream Support
We support two ways for clients to receive data:

1.  **WebSockets (STOMP)**: 
    -   Best for interactive, two-way communication.
    -   Endpoint: `/ws-connect`
    -   Subscription Path: `/topic/tenants/{tenantId}/properties/{propertyId}/stream`
    -   Includes heartbeats every 10 seconds to keep the connection alive.

2.  **Server-Sent Events (SSE)**:
    -   A simpler, lightweight alternative to WebSockets.
    -   Endpoint: `GET /api/v1/realtime/tenants/{tenantId}/properties/{propertyId}/stream`
    -   Ideal for simple "read-only" live updates.

## Security & Isolation
-   **Tenant and Property Authorization**: SSE routes are covered by `module_access_matrix`; WebSocket subscriptions call `can_access_module` directly. A user needs the `realtime.stream` permission and enabled `realtime` module for the target property.
-   **Origin Control**: WebSocket origins are configured with `peak.realtime.websocket.allowed-origins`. Wildcard origins are rejected.
-   **No Platform Bypass**: Platform users do not bypass tenant/property realtime permissions.

## Monitoring & Metrics
The module tracks its own health using professional metrics:
-   **Active Connections**: How many people are currently "tuned in."
-   **Connect Attempts**: Total number of connection requests.
-   **Security Violations**: Count of blocked cross-tenant access attempts.

## For Developers: Adding New Events
To broadcast a new type of event from another module:
1.  Inject `ApplicationEventPublisher`.
2.  Publish a `RealtimeEvent` with the `tenantId`, `propertyId`, `eventType`, and a `payload` (the data you want to send).
3.  The `RealtimeEventListener` will automatically catch it and send it to all WebSockets and SSE clients.
