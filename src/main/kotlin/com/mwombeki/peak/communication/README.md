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

### Channel Verification
| Method | Endpoint | Description | Roles |
|--------|----------|-------------|-------|
| POST | `/api/v1/communication/channels/{id}/request-verification` | Request verification token delivery through outbox | `communications.manage` |
| POST | `/api/v1/communication/channels/{id}/verify` | Submit verification token in a JSON body | `communications.manage` |

### Notifications
| Method | Endpoint | Description | Roles |
|--------|----------|-------------|-------|
| POST | `/api/v1/communication/notifications` | Enqueue a tenant or property notification | `communications.send` |

### Templates
| Method | Endpoint | Description | Roles |
|--------|----------|-------------|-------|
| POST | `/api/v1/communication/templates` | Create a message template | `communications.manage` |

All mutating endpoints require `Idempotency-Key`. Replay returns the original resource id with `replayed=true`.

## Outbox Pattern
The module stages notifications through the shared `reliability::api` `OutboxPort`.
1. **Producer**: `OutboxService` creates a typed `OutboxEventCommand` in the same transaction as the communication action.
2. **Worker**: Worker runtime is owned by the Reliability module and is enabled only in `peak.runtime.mode=worker`.
3. **Delivery**: `NotificationOutboxHandler` handles `notification` destination events and records operational metrics. Provider-specific delivery adapters should hang behind this handler.
4. **Completion**: The Reliability module owns claim locking, retry, timeout, metrics, and dead-letter state transitions.

## Data Model
- **Tenant Contact**: Individual people associated with a tenant.
- **Contact Channel**: Specific addresses (email, phone number) for a contact.
- **Communication Template**: Pre-defined messages for common notifications.
- **Outbox Event**: Records tracking the delivery status of notifications.

## Security
- All endpoints require an active tenant context.
- Permissions are enforced through `module_access_matrix` route contracts and tenant RBAC permissions: `communications.view`, `communications.manage`, and `communications.send`.
- Verification tokens are stored only as SHA-256 hashes and checked with constant-time comparison.
- Verification tokens are delivered through outbox payloads and are not returned by the request API.
- Property-scoped notifications validate tenant ownership before enqueue.
- Communication audit entries avoid storing raw recipient addresses or message content.
