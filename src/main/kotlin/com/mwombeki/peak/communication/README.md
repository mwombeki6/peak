# Communication Module

## Overview
The Communication Module provides infrastructure for multi-channel tenant notifications, contact management, and message templates. It utilizes an outbox pattern to ensure reliable, asynchronous delivery of messages via Email, SMS, and WhatsApp.

## Key Features
- **Contact Management**: CRUD for tenant contacts, roles, and communication channels.
- **Multi-channel Support**: Integration for Email, SMS, and WhatsApp.
- **Verification Workflow**: Token-based verification for communication channels.
- **Message Templates**: Dynamic templates with variable support.
- **Reliable Delivery**: Outbox-based asynchronous delivery with retry logic and dead-letter support.
- **Audit & Security**: Audit logging, idempotency, tenant-isolated data access, and sanitized audit payloads for sensitive communication changes.

## API Endpoints

### Contact Management
| Method | Endpoint | Description | Roles |
|--------|----------|-------------|-------|
| POST | `/api/v1/communication/contacts` | Create a contact and email/phone/WhatsApp channels | `communications.manage` |
| GET | `/api/v1/communication/contacts` | List contacts and channel verification state | `communications.view` |
| POST | `/api/v1/communication/contacts/{contactId}/roles` | Assign a readiness/business role to a contact | `communications.manage` |
| POST | `/api/v1/communication/contacts/{contactId}/channels/{channelId}/consents` | Record the latest purpose-specific consent decision | `communications.manage` |
| POST | `/api/v1/communication/report-recipients` | Configure a consent-aware operational report recipient | `communications.manage` |
| GET | `/api/v1/communication/report-recipients` | List operational report recipients with masked channel data | `communications.view` |

### Channel Verification
| Method | Endpoint | Description | Roles |
|--------|----------|-------------|-------|
| POST | `/api/v1/communication/channels/{id}/request-verification` | Request verification token delivery through outbox | `communications.manage` |
| POST | `/api/v1/communication/channels/{id}/verify` | Submit verification token in a JSON body | `communications.manage` |

### Notifications
| Method | Endpoint | Description | Roles |
|--------|----------|-------------|-------|
| POST | `/api/v1/communication/notifications` | Enqueue a tenant or property notification and create a delivery request | `communications.send` |
| GET | `/api/v1/communication/delivery-requests` | List recent tenant delivery requests | `communications.view` |
| GET | `/api/v1/communication/delivery-requests/{id}` | Get delivery request status | `communications.view` |
| GET | `/api/v1/communication/delivery-requests/{id}/attempts` | List provider delivery attempts | `communications.view` |
| POST | `/api/v1/communication/delivery-requests/{id}/retry` | Retry a failed or dead-lettered delivery request | `communications.send` |

### Templates
| Method | Endpoint | Description | Roles |
|--------|----------|-------------|-------|
| POST | `/api/v1/communication/templates` | Create a message template | `communications.manage` |

All mutating endpoints require `Idempotency-Key`. Replay returns the original resource id with `replayed=true`.

## Outbox Pattern
The module stages notifications through the shared `reliability::api` `OutboxPort`.
1. **Producer**: `OutboxService` creates a typed `OutboxEventCommand` in the same transaction as the communication action.
2. **Worker**: Worker runtime is owned by the Reliability module and is enabled only in `peak.runtime.mode=worker`.
3. **Delivery**: `NotificationOutboxHandler` handles `notification` destination events, creates provider attempts, updates `communication_delivery_requests`, and records operational metrics.
4. **Completion**: The Reliability module owns claim locking, retry, timeout, metrics, and dead-letter state transitions.
5. **Provider adapters**: `NotificationDeliveryProvider` isolates provider integrations from delivery state management. The default local provider accepts messages for development and tests; production providers should be configured without placing credentials in YAML defaults.

Production uses the HTTP provider adapter with credentials supplied through secrets/environment configuration. The local provider is rejected by production validation. Provider payloads, raw addresses, message bodies, and verification tokens are excluded from platform-operation logs.

## Data Model
- **Tenant Contact**: Individual people associated with a tenant.
- **Contact Channel**: Specific addresses (email, phone number) for a contact.
- **Communication Template**: Pre-defined messages for common notifications.
- **Communication Delivery Request**: Logical notification delivery with status, fingerprints, current outbox event, and retry state.
- **Communication Delivery Attempt**: Provider attempt history for each delivery request and outbox claim.
- **Outbox Event**: Reliability work item claimed by the bounded worker runtime.

## Metrics
- `peak.communication.delivery.attempts.started{channel,provider}`: provider attempt started.
- `peak.communication.delivery.attempts.delivered{channel,provider}`: provider attempt delivered.
- `peak.communication.delivery.attempts.failed{channel,provider,status}`: provider attempt failed or dead-lettered.

## Security
- All endpoints require an active tenant context.
- Permissions are enforced through `module_access_matrix` route contracts and tenant RBAC permissions: `communications.view`, `communications.manage`, and `communications.send`.
- Verification tokens are stored only as SHA-256 hashes and checked with constant-time comparison.
- Verification tokens are delivered through outbox payloads and are not returned by the request API.
- Contact consent is append-only by decision time; report readiness requires a verified channel with active `operational_reports` consent.
- Property-scoped notifications validate tenant ownership before enqueue.
- Communication audit entries avoid storing raw recipient addresses or message content.
- Delivery status rows store recipient and content fingerprints for lookup and diagnostics without duplicating raw message content.
