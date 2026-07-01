# Fiscal Module

## Purpose

The `fiscal` module is responsible for the immutable submission of invoices to external fiscal authorities (e.g., TRA in Tanzania). It ensures that every issued invoice is legally recorded and assigned a verified fiscal reference.

## Goal

- Provide a provider-neutral SPI for different fiscal authorities.
- Handle asynchronous submission and automatic retries for temporary provider outages.
- Support manual overrides by authorized personnel during extended outages.
- Maintain a local ledger of fiscal receipts and their current status.

## Architecture

- **api**: Defines `FiscalPort` for other modules (like `billing` and `nightaudit`) and standard DTOs.
- **internal**: Contains the core logic, persistence (`JdbcTemplate`), and state management.
- **provider**: Defines the `FiscalProvider` SPI.

## Implementation Details

- **Submission Workflow**: Outbox-driven (in Phase 3, triggered synchronously but designed for idempotent retry).
- **Persistence**: Managed via `fiscal_receipts` table.
- **Simulator**: A deterministic signed simulator is available for development (`peak.fiscal.simulator.enabled=true`).

## Key States

- `PENDING`: Waiting for submission or scheduled for retry.
- `ACCEPTED`: Successfully verified by the authority.
- `REJECTED`: Permanently rejected by the authority (requires manual correction).
- `OVERRIDDEN`: Manually authorized to proceed without fiscalization.

## Security

- Access to manual overrides requires specific supervisor permissions.
- Every submission and override is audited.
- Multi-tenant and multi-property isolation is enforced at the database and API level.

## API Endpoints

All fiscal endpoints are prefixed with `/api/v1/fiscal`.

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/invoices/{invoiceId}/receipt` | Retrieves the fiscal receipt for a specific invoice. |
| `POST` | `/invoices/{invoiceId}/override` | Manually overrides fiscalization for an invoice (requires supervisor permission). |

Note: Initial submission of invoices is handled asynchronously via the outbox pattern and `FiscalPort`.
