# Phase 3 Automated Acceptance Matrix

| Scenario | Automated evidence |
|---|---|
| 1. Security, RLS, BOLA, route coverage | `RuntimeDatabaseRoleIntegrationTests`, `RouteAccessMatrixCoverageIntegrationTests`, `Phase3StayLifecycleIntegrationTests.preventsCrossPropertyGuestAndIdentityAccess` |
| 2. Module ownership | `ModulithArchitectureTests` |
| 3. Guest identity readiness | `Phase3StayLifecycleIntegrationTests` identity scenarios |
| 4. Reservation overlap and check-in | `Phase3StayLifecycleIntegrationTests`, database overlap constraints |
| 5. Billing and split settlement | Phase 3 lifecycle cash/mobile-money tests |
| 6. Canonical payment migration/state | `PaymentLifecycleMigrationUpgradeIntegrationTests` |
| 7. ClickPesa checksum/replay/polling | `ClickPesaChecksumTests`, Phase 3 webhook lifecycle, protected ClickPesa workflow |
| 8. Refund and reconciliation concurrency | Phase 3 partial/full refund and reconciliation lifecycle |
| 9. POS price, settlement, variance | `PosOrderServiceIntegrationTests` |
| 10. Invoice void, credit note, fiscal recovery | Phase 3 invoice lifecycle and `SignedSimulatorFiscalProviderTests` |
| 11. Unpaid checkout and night audit | `unpaidCheckoutLeavesFolioOpenAndNightAuditNonOverridable` and clean completion lifecycle |
| 12. Operations contract | `Phase3OpenApiIntegrationTests`, Podman/Newman runner, dashboards, alert rules, and environment validation |

The protected ClickPesa sandbox run remains a release gate because it requires
operator-supplied credentials and a user who can approve the USSD prompt.
