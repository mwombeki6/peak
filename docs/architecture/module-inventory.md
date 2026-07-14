# Canonical Module Inventory

This inventory is the source of truth for the 22 Spring Modulith modules in the
V1 backend. Package declarations in `src/main/java/**/package-info.java` are the
machine-enforced dependency contract. `database-ownership.csv` is the
machine-enforced table contract. Generated diagrams and module canvases are
written under `build/spring-modulith-docs` by `ModulithDocumentationTests`.

| Module | Named API / query boundary | Database and function ownership | Published events / destinations | Allowed cross-module reads |
|---|---|---|---|---|
| `audit` | `AuditPort` | Audit tables; append-only audit guards | none | request context only |
| `billing` | `BillingPort` | Folios, charges, payments, invoices, journals, document allocation and total assertions | `billing.*` / `internal`, `fiscal`, `realtime` | property, reservation snapshots |
| `communication` | `CommunicationPort`, `ReportLinkDeliveryPort` | Contacts, consent, templates, delivery requests and attempts | `communication.*` / `communication`, `realtime` | property and report-recipient contracts |
| `fiscal` | `FiscalPort`, `FiscalStatusPort`, provider SPI | Fiscal provider configuration, receipts, attempts and corrections | `fiscal.*` / `fiscal`, `realtime` | immutable billing documents |
| `frontdesk` | `FrontDeskPort`, `HousekeepingStaySummaryPort` | Stays, moves, handovers and unpaid-checkout overrides | `frontdesk.*` / `internal`, `realtime` | reservation, billing, fiscal and property APIs |
| `housekeeping` | `HousekeepingPort`, close snapshot port | Tasks, inspections, custody and housekeeping settings | `housekeeping.*` / `internal`, `realtime` | property and stay APIs |
| `integrations` | provider adapters only | No active business table mutations; deferred channel/edge tables are dormant | consumes provider events | payment, fiscal, reservation and reporting SPIs |
| `inventory` | `InventoryPort`, close snapshot port | Items, locations, stock, movements, recipes and low-stock crossings | `inventory.*` / `internal`, `realtime` | property API and POS-owned menu reads by documented recipe contract |
| `maintenance` | `MaintenancePort`, close snapshot port | Requests, work orders, room blocks and windows | `maintenance.*` / `internal`, `realtime` | property API |
| `nightaudit` | `NightAuditPort`, close snapshot port | Runs, issues and immutable close snapshots | `night_audit.*`, `report.generation.requested` / `internal`, `reporting`, `realtime` | published close snapshot ports only |
| `payment` | `PaymentPort`, status/webhook ports, provider SPI | Cash sessions, provider accounts, transactions, webhooks and reconciliation | `payment.*` / `payment`, `internal`, `realtime` | billing API |
| `platformgovernance` | `TenantGovernancePort` | Platform operations, health, incident and support tables | `platform.tenant.*` / `platform` | tenant lifecycle and platform authorization APIs |
| `pos` | POS status/configuration ports | Outlets, menus, sessions, orders, tickets and settlement snapshots | `pos.*` / `internal`, `realtime` | billing, payment, inventory and property APIs |
| `procurement` | `ProcurementPort` | Suppliers, purchase orders, approvals and receipts | `procurement.*` / `internal`, `realtime` | inventory and property APIs |
| `property` | `PropertyPort`, `PropertyOperationsPort` | Properties, buildings, rooms, tax rates, revenue centers and property modules | `property.*` / `internal`, `realtime` | tenant module configuration and user bootstrap APIs |
| `realtime` | `RealtimePort` | Durable tenant/property event journal and replay functions | fanout only | audit API |
| `reliability` | `IdempotencyPort`, `OutboxPort`, handler SPI | Idempotency, outbox claim/retry/dead-letter and worker heartbeat | routes all declared outbox destinations | audit API |
| `reporting` | `ReportingPort`, object-storage SPI | Catalog, subscriptions, runs, artifacts, deliveries and retention | `report.*` / `reporting`, `communication` | immutable night-audit snapshots and communication API |
| `reservations` | reservation, guest identity and close snapshot ports | Guests, reservations, room nights, policies, rate and availability state | `reservations.*` / `internal`, `realtime` | billing API |
| `shared` | `context`, `exception`, `outbound`, `secrets`, `security`, `time` | Shared catalogs and generic workflow metadata only | none | no module dependencies |
| `tenantmanagement` | onboarding, administration, lifecycle mutation and module configuration ports | Tenants, profiles, plans, modules, lifecycle and verification | `tenant.*`, `platform.tenants.*` / `platform`, `internal` | user-management authorization API |
| `usermanagement` | authorization, platform/tenant/property RBAC, invitation and lifecycle ports | Users, identities, RBAC, access matrix, sessions and break-glass grants | administration and identity events / `platform`, `internal`, `communication` | audit and reliability APIs |

Runtime modes are deliberately separate: `api` serves HTTP and never claims
outbox work; `worker` claims outbox work and never runs Flyway; `migration` runs
Flyway with the migrator login; `bootstrap` performs the one-time platform-root
workflow; `acceptance` enables deterministic signed providers only in isolated
test topology.

Tables marked `deferred` in the CSV belong to product domains outside V1. They
must have no production Kotlin mutation. Cross-module reads are permitted only
through the named API/query boundaries above or the documented read contracts;
new direct SQL reads require an inventory update and review.
