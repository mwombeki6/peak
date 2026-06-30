# Fiscal Module

The Fiscal module handles legal invoice submission and fiscal receipt recovery, ensuring compliance with tax authority requirements (Phase 3 focused on TRA EFD/VFD).

## Features

- **Invoice Submission**: Connects to the Billing module to retrieve issued invoices and submit them for fiscalization.
- **Fiscal Simulator**: Provides a deterministic signed simulator for Phase 3 E2E testing without requiring a live TRA provider.
- **Receipt Management**: Tracks fiscal codes, verification codes, and QR code URLs for every fiscalized invoice.
- **Audit Ready**: Maintains an immutable log of all fiscal submission attempts and authority responses.

## Main Routes

| Method | Route | Description |
|---|---|---|
| `POST` | `/api/v1/properties/{propertyId}/fiscal/submit` | Submit an invoice for fiscalization |
| `GET`  | `/api/v1/properties/{propertyId}/fiscal/receipts/{id}` | Get fiscal receipt details |

## Security

- **Multi-Tenant**: Strict tenant and property scoping via `RequestContext`.
- **RBAC**: Restricted to `ROLE_TENANT_ADMIN` and `ROLE_PROPERTY_MANAGER`.

## Integration

The module consumes the `billing.api` contract to validate invoice state before attempting fiscalization.
