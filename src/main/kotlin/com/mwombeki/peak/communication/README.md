# Communication Module

## Overview
The Communication Module provides infrastructure for multi-channel tenant notifications, contact management, and message templates. It utilizes an outbox pattern to ensure reliable, asynchronous delivery of messages via Email, SMS, and WhatsApp.

## Key Features
- **Contact Management**: CRUD for tenant contacts, roles, and communication channels.
- **Multi-channel Support**: Integration for Email, SMS, and WhatsApp.
- **Verification Workflow**: Token-based verification for communication channels.
- **Message Templates**: Dynamic templates with variable support.
- **Reliable Delivery**: Outbox-based asynchronous delivery with retry logic and dead-letter support.
- **Audit & Security**: Full audit logging for sensitive communication changes and tenant-isolated data access.

## API Endpoints

### Contact Management
| Method | Endpoint | Description | Roles |
|--------|----------|-------------|-------|
| POST | `/api/v1/communication/contacts` | Create a new contact | `TENANT_ADMIN`, `PROPERTY_MANAGER` |
| GET | `/api/v1/communication/contacts` | List all contacts | `TENANT_ADMIN`, `PROPERTY_MANAGER` |

### Channel Verification
| Method | Endpoint | Description | Roles |
|--------|----------|-------------|-------|
| POST | `/api/v1/communication/channels/{id}/request-verification` | Request verification token | `TENANT_ADMIN` |
| POST | `/api/v1/communication/channels/{id}/verify` | Submit verification token | `TENANT_ADMIN` |

### Notifications
| Method | Endpoint | Description | Roles |
|--------|----------|-------------|-------|
| POST | `/api/v1/communication/notifications` | Enqueue a new notification | `TENANT_ADMIN`, `SYSTEM` |

### Templates
| Method | Endpoint | Description | Roles |
|--------|----------|-------------|-------|
| POST | `/api/v1/communication/templates` | Create a message template | `TENANT_ADMIN` |

## Outbox Pattern
The module uses a `outbox_events` table to stage notifications. 
1. **Producer**: The application logic (e.g., `OutboxService`) inserts a record into `outbox_events` within the same database transaction as the business operation.
2. **Worker**: A background process (`OutboxDeliveryWorker`) polls the table for `pending` events.
3. **Delivery**: The worker attempts delivery via a provider abstraction.
4. **Completion**: Upon success, the event is marked as `delivered`. On failure, it is retried with exponential backoff until it reaches `max_attempts`, after which it is marked as `failed`.

## Data Model
- **Tenant Contact**: Individual people associated with a tenant.
- **Contact Channel**: Specific addresses (email, phone number) for a contact.
- **Communication Template**: Pre-defined messages for common notifications.
- **Outbox Event**: Records tracking the delivery status of notifications.

## Security
- All endpoints require an active tenant context.
- Permissions are enforced using `@PreAuthorize` based on Phase 2 security matrix.
- `idempotency_key` support for unsafe operations to prevent duplicate notifications.
