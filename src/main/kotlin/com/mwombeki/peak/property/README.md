# Property Management Module

## Overview
The Property Management module is responsible for defining and managing the physical and operational structure of a hospitality property (hotel, resort, etc.). It enables tenant admins to configure buildings, floors, rooms, departments, and financial structures like revenue centers and tax rates.

This module also enforces a "Readiness" workflow, ensuring a property is fully configured before it can be activated for operational use.

## Key Features
- **Property Lifecycle**: Manage properties through canonical schema states: `active`, `suspended`, `frozen`, `archived`, and `terminated`.
- **Structural Configuration**: CRUD operations for Buildings, Floors, and Room Types.
- **Inventory Management**: Room allocation and real-time status updates using canonical statuses (`vacant_clean`, `vacant_dirty`, `occupied`, `maintenance`, `out_of_order`, `blocked`).
- **Operational Setup**: Manage Departments and Revenue Centers.
- **Financial Configuration**: Configure Tax Rates (VAT, Levies, etc.) and Base Rates for room types.
- **Property Readiness**: Automated checklist to verify configuration completeness.
- **Module Management**: Enable or disable specific platform modules at the property level.

## Domain Model
- **Property**: The top-level entity representing a hotel.
- **Building**: Physical structures within a property.
- **Floor**: Vertical levels within a building.
- **Room Type**: Categories of rooms (e.g., Deluxe, Suite) with capacity and base rates.
- **Room**: Individual units with status tracking.
- **Revenue Center**: Points of sale or revenue tracking categories (e.g., Restaurant, Front Desk).
- **Department**: Operational units (e.g., Housekeeping, F&B).
- **Tax Rate**: Configuration for taxes applied to transactions.

## API Endpoints

### Property Management
| Method | Endpoint | Description | Roles |
|--------|----------|-------------|-------|
| POST | `/api/v1/properties` | Create a new property | `TENANT_ADMIN` |
| GET | `/api/v1/properties` | List all properties | `TENANT_ADMIN`, `PLATFORM_OPERATOR` |
| GET | `/api/v1/properties/{id}` | Get property details | `TENANT_ADMIN`, `PROPERTY_MANAGER`, `PLATFORM_OPERATOR` |
| PUT | `/api/v1/properties/{id}` | Update property details | `TENANT_ADMIN` |
| DELETE | `/api/v1/properties/{id}` | Soft-delete/Archive property | `TENANT_ADMIN` |
| POST | `/api/v1/properties/{id}/suspend` | Suspend property operations | `TENANT_ADMIN` |
| POST | `/api/v1/properties/{id}/activate` | Activate property (triggers readiness check) | `TENANT_ADMIN` |

### Structural & Operational Setup
| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/properties/{id}/buildings` | Add a building |
| POST | `/api/v1/properties/{id}/floors` | Add a floor |
| POST | `/api/v1/properties/{id}/room-types` | Add a room type |
| POST | `/api/v1/properties/{id}/rooms` | Add a room |
| POST | `/api/v1/properties/{id}/departments` | Add a department |
| POST | `/api/v1/properties/{id}/revenue-centers` | Add a revenue center |
| POST | `/api/v1/properties/{id}/rates` | Configure base rate for a room type |
| PUT | `/api/v1/properties/{id}/rooms/{roomId}/status` | Update room status |

### Tax & Module Management
| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/v1/properties/taxes` | Create a tax rate (Tenant level) |
| GET | `/api/v1/properties/taxes` | List tax rates |
| GET | `/api/v1/properties/{id}/modules` | List enabled modules for property |
| POST | `/api/v1/properties/{id}/modules` | Enable a module for property |
| DELETE | `/api/v1/properties/{id}/modules/{moduleId}` | Disable a module for property |

### Readiness
| Method | Endpoint | Description |
|--------|----------|-------------|
| GET | `/api/v1/properties/{id}/readiness` | Get readiness status and missing requirements |

## Readiness Checklist
A property cannot be `activated` until the following conditions are met:
1. Property status is not `archived`.
2. At least one **Building** is configured.
3. At least one **Floor** is configured.
4. At least one **Room Type** is configured.
5. At least one **Room** is allocated.
6. At least one **Revenue Center** is configured.
7. **Tax Configuration** exists for the tenant.
8. **Base Rates** are configured for all room types.
9. Verified **Business Contacts** exist for the tenant.

## Security & Auditing
- **Tenant Isolation**: All operations are scoped to the active `tenant_id` resolved from the security context.
- **Route Matrix Access**: HTTP access is enforced through `module_access_matrix` and tenant RBAC permissions: `property.view`, `property.manage`, and `property.lifecycle`.
- **Audit Logging**: All mutating actions (creation, updates, status changes) are recorded via the `AuditPort` for compliance and tracking.
