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
| `integrations` | provider adapters only | No active business table mutations; deferred channel/edge tables are dormant | consumes provider events | payment, fiscal, reservation, reporting and shared outbound storage SPIs |
| `inventory` | `InventoryPort`, close snapshot port | Items, locations, stock, movements, recipes and low-stock crossings | `inventory.*` / `internal`, `realtime` | property API and POS-owned menu reads by documented recipe contract |
| `maintenance` | `MaintenancePort`, close snapshot port | Requests, work orders, room blocks and windows | `maintenance.*` / `internal`, `realtime` | property API |
| `nightaudit` | `NightAuditPort`, close snapshot port, `FinancialControlPort` | Runs, issues, immutable close snapshots, financial-control cases, evidence and events | `night_audit.*`, `financial_control.*`, `report.generation.requested` / `internal`, `reporting`, `realtime` | module close-summary APIs and user-management staff directory |
| `payment` | `PaymentPort`, status/webhook ports, provider SPI | Cash sessions, provider accounts, transactions, webhooks and reconciliation | `payment.*` / `payment`, `internal`, `realtime` | billing API |
| `platformgovernance` | `TenantGovernancePort`, `SupportControlPort`, `FleetControlPort`, `ReleaseControlPort`, `FeatureControlPort` | Platform services, health, jobs, alerts, incidents, releases, feature flags and support evidence | `platform.tenant.*`, `platform.support.*`, `platform.monitoring.*`, `platform.release.*` / `platform` | tenant lifecycle, platform authorization and user-management privileged-access evidence APIs |
| `pos` | POS status/configuration ports | Outlets, menus, sessions, orders, tickets and settlement snapshots | `pos.*` / `internal`, `realtime` | billing, payment, inventory and property APIs |
| `procurement` | `ProcurementPort` | Suppliers, purchase orders, approvals and receipts | `procurement.*` / `internal`, `realtime` | inventory and property APIs |
| `property` | `PropertyPort`, `PropertyOperationsPort`, `PortfolioControlPort` | Properties, buildings, rooms, tax rates, revenue centers, property modules, portfolio hierarchy and versioned configuration rollout | `property.*`, `tenant.portfolio.*` / `internal`, `platform`, `realtime` | tenant module configuration and user bootstrap APIs |
| `realtime` | `RealtimePort` | Durable tenant/property event journal and replay functions | fanout only | audit API |
| `reliability` | `IdempotencyPort`, `OutboxPort`, handler SPI | Idempotency, outbox claim/retry/dead-letter and worker heartbeat | routes all declared outbox destinations | audit API |
| `reporting` | `ReportingPort` | Catalog, subscriptions, runs, artifacts, deliveries and retention | `report.*` / `reporting`, `communication` | immutable night-audit snapshots, communication API and shared outbound object storage |
| `reservations` | reservation, guest identity and close snapshot ports | Guests, reservations, room nights, policies, rate and availability state | `reservations.*` / `internal`, `realtime` | billing API |
| `shared` | `context`, `exception`, `outbound`, `secrets`, `security`, `time` | Shared catalogs and generic workflow metadata only | none | no module dependencies |
| `tenantmanagement` | onboarding, administration, `PlatformTenantControlPort`, `PlatformCommercialControlPort`, `EntitlementAccessPort`, `TenantTrustControlPort` | Tenants, profiles, control state, plans, subscriptions, entitlements, workflows, verification, privacy, legal holds and enterprise identity connections | `tenant.*`, `platform.tenants.*` / `platform`, `internal` | user-management authorization, audit timeline and shared outbound object storage |
| `usermanagement` | authorization, platform/tenant/property RBAC, invitation, lifecycle, `BreakGlassAccessPort`, `SupportPrivilegedAccessEvidencePort` and property staff-directory ports | Users, identities, RBAC, access matrix, sessions and ticket-bound break-glass grants | administration, identity and privileged-access events / `platform`, `internal`, `communication` | audit and reliability APIs |

Runtime modes are deliberately separate: `api` serves tenant/public HTTP with
only `pms_app`; `platform` serves the control plane with only `pms_platform`;
`worker` claims outbox work and never runs Flyway; `migration` runs
Flyway with the migrator login; `bootstrap` performs the one-time platform-root
workflow; `acceptance` enables deterministic signed providers only in isolated
test topology.

Tables marked `deferred` in the CSV belong to product domains outside V1. They
must have no production Kotlin mutation. Cross-module reads are permitted only
through the named API/query boundaries above or the documented read contracts;
new direct SQL reads require an inventory update and review.
