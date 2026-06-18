# Audit Module

Audit owns append-only security and business event recording. Other modules publish audit events through `audit.api.AuditPort`; they must not write directly to audit tables.

## Responsibilities

- Persist platform and tenant audit events inside the caller transaction.
- Sanitize payloads before storage.
- Preserve correlation id, actor identity, tenant id, resource, action, outcome, and timestamp.

## Security Model

- Audit writes require an active database transaction.
- Audit records are append-only from application code.
- Payloads must not contain secrets, credentials, raw tokens, payment PINs, or full provider responses.

## Production Rules

1. Audit every permission, identity, tenant lifecycle, and externally visible payment state change.
2. Keep audit writes in the same transaction as the business change.
3. Prefer structured payload fields over log-style text.
4. Do not expose broad audit reads through tenant-facing APIs without explicit authorization design.
