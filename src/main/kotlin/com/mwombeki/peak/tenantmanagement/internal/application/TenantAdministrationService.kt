package com.mwombeki.peak.tenantmanagement.internal.application

import com.mwombeki.peak.audit.api.AuditPort
import com.mwombeki.peak.audit.api.AuditResource
import com.mwombeki.peak.audit.api.TenantAuditEvent
import com.mwombeki.peak.reliability.api.IdempotencyCommand
import com.mwombeki.peak.reliability.api.IdempotencyPort
import com.mwombeki.peak.reliability.api.IdempotencyReservation
import com.mwombeki.peak.reliability.api.OutboxDestination
import com.mwombeki.peak.reliability.api.OutboxEventCommand
import com.mwombeki.peak.reliability.api.OutboxPort
import com.mwombeki.peak.tenantmanagement.api.TenantAdministrationConflictException
import com.mwombeki.peak.tenantmanagement.api.TenantAdministrationInProgressException
import com.mwombeki.peak.tenantmanagement.api.TenantAdministrationNotFoundException
import com.mwombeki.peak.tenantmanagement.api.TenantAdministrationPort
import com.mwombeki.peak.tenantmanagement.api.TenantModuleCommand
import com.mwombeki.peak.tenantmanagement.api.TenantModuleMutationReceipt
import com.mwombeki.peak.tenantmanagement.api.TenantModuleSummary
import com.mwombeki.peak.tenantmanagement.api.TenantReadinessResponse
import com.mwombeki.peak.usermanagement.api.TenantPermissionAccessPort
import com.mwombeki.peak.usermanagement.api.TenantPermissionAccessRequest
import io.micrometer.core.instrument.MeterRegistry
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper

@Component
class TenantAdministrationService(
    private val jdbcTemplate: JdbcTemplate,
    private val tenantPermissionAccessPort: TenantPermissionAccessPort,
    private val idempotencyPort: IdempotencyPort,
    private val auditPort: AuditPort,
    private val outboxPort: OutboxPort,
    private val transactionTemplate: TransactionTemplate,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : TenantAdministrationPort {

    override fun listTenantModules(tenantId: UUID): List<TenantModuleSummary> {
        return requireNotNull(
            transactionTemplate.execute {
                requireTenantPermission(tenantId, MODULE_VIEW_PERMISSION)
                jdbcTemplate.query(
                    """
                    SELECT
                        tm.tenant_id,
                        tm.module_id,
                        mc.name,
                        tm.is_enabled,
                        tm.is_configured,
                        tm.source
                    FROM tenant_modules tm
                    JOIN module_catalog mc
                        ON mc.module_id = tm.module_id
                    WHERE tm.tenant_id = ?
                    ORDER BY mc.display_order, mc.name
                    """.trimIndent(),
                    { rs, _ ->
                        TenantModuleSummary(
                            tenantId = rs.getObject("tenant_id", UUID::class.java),
                            moduleId = rs.getString("module_id"),
                            name = rs.getString("name"),
                            isEnabled = rs.getBoolean("is_enabled"),
                            isConfigured = rs.getBoolean("is_configured"),
                            source = rs.getString("source"),
                        )
                    },
                    tenantId,
                )
            },
        )
    }

    override fun enableTenantModule(
        command: TenantModuleCommand,
    ): TenantModuleMutationReceipt {
        return mutateModule(
            command = command,
            operationType = "tenant.module.enable",
            enabled = true,
            eventType = "tenant.module.enabled",
        )
    }

    override fun disableTenantModule(
        command: TenantModuleCommand,
    ): TenantModuleMutationReceipt {
        return mutateModule(
            command = command,
            operationType = "tenant.module.disable",
            enabled = false,
            eventType = "tenant.module.disabled",
        )
    }

    override fun getTenantReadiness(tenantId: UUID): TenantReadinessResponse {
        return requireNotNull(
            transactionTemplate.execute {
                requireTenantPermission(tenantId, TENANT_PROFILE_VIEW_PERMISSION)
                val missing = mutableListOf<String>()

                if (!tenantExistsAndUsable(tenantId)) {
                    missing.add("Tenant account must be trial or active.")
                }
                if (!businessProfileIsVerified(tenantId)) {
                    missing.add("Tenant business profile must be verified and include business contacts.")
                }
                if (!requiredBusinessContactExists(tenantId)) {
                    missing.add("Tenant must have an active owner, signatory, or primary contact.")
                }
                if (!operationalReportRecipientExists(tenantId)) {
                    missing.add("Tenant must have an enabled operational report recipient with consent.")
                }
                if (!tenantAdminModuleEnabled(tenantId)) {
                    missing.add("Tenant administration module must be enabled.")
                }

                TenantReadinessResponse(
                    tenantId = tenantId,
                    isReady = missing.isEmpty(),
                    missingRequirements = missing,
                )
            },
        )
    }

    private fun mutateModule(
        command: TenantModuleCommand,
        operationType: String,
        enabled: Boolean,
        eventType: String,
    ): TenantModuleMutationReceipt {
        return requireNotNull(
            transactionTemplate.execute {
                requireTenantPermission(command.tenantId, MODULE_MANAGE_PERMISSION)
                val moduleId = command.moduleId.normalizedModuleId()
                requireManageableModule(moduleId)
                if (enabled) {
                    jdbcTemplate.queryForList(
                        "SELECT assert_tenant_entitlement_enabled(?, ?)",
                        command.tenantId,
                        "module.$moduleId",
                    )
                }
                val reservation = idempotencyPort.reserve(
                    IdempotencyCommand(
                        operationType = operationType,
                        requestPayload = mapOf(
                            "tenantId" to command.tenantId,
                            "moduleId" to moduleId,
                            "enabled" to enabled,
                        ),
                        resourceType = "tenant_modules",
                    ),
                )

                when (reservation) {
                    is IdempotencyReservation.Started -> {
                        val changed = upsertTenantModule(command.tenantId, moduleId, enabled)
                        val receipt = TenantModuleMutationReceipt(
                            tenantId = command.tenantId,
                            moduleId = moduleId,
                            enabled = enabled,
                            changed = changed,
                            replayed = false,
                        )
                        if (changed) {
                            recordModuleSideEffects(
                                receipt = receipt,
                                eventType = eventType,
                                idempotencyKeyId = reservation.recordId,
                            )
                        }
                        idempotencyPort.markSucceeded(
                            recordId = reservation.recordId,
                            responseCode = 200,
                            responseBody = receipt,
                            resourceId = null,
                        )
                        meterRegistry.counter(
                            "peak.tenant.module.command",
                            "operation", operationType,
                            "result", "succeeded",
                        ).increment()
                        receipt
                    }

                    is IdempotencyReservation.Replay -> replayModuleMutation(reservation)
                    is IdempotencyReservation.InProgress -> {
                        meterRegistry.counter(
                            "peak.tenant.module.command",
                            "operation", operationType,
                            "result", "in_progress",
                        ).increment()
                        throw TenantAdministrationInProgressException(
                            "Tenant module command is already being processed for this idempotency key",
                        )
                    }

                    is IdempotencyReservation.Conflict -> {
                        meterRegistry.counter(
                            "peak.tenant.module.command",
                            "operation", operationType,
                            "result", "conflict",
                        ).increment()
                        throw TenantAdministrationConflictException(
                            "Idempotency key was already used for a different tenant module request",
                        )
                    }
                }
            },
        )
    }

    private fun requireTenantPermission(tenantId: UUID, permissionCode: String): UUID {
        return tenantPermissionAccessPort.requireAuthorized(
            TenantPermissionAccessRequest(tenantId, permissionCode),
        )
    }

    private fun requireManageableModule(moduleId: String) {
        val exists = jdbcTemplate.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1
                FROM module_catalog
                WHERE module_id = ?
                  AND launch_status = 'active'
                  AND access_scope IN ('tenant', 'property', 'both')
            )
            """.trimIndent(),
            Boolean::class.java,
            moduleId,
        ) == true
        if (!exists) {
            throw TenantAdministrationNotFoundException("Tenant module was not found or is not active")
        }
    }

    private fun upsertTenantModule(
        tenantId: UUID,
        moduleId: String,
        enabled: Boolean,
    ): Boolean {
        val previous = jdbcTemplate.queryForList(
            """
            SELECT is_enabled
            FROM tenant_modules
            WHERE tenant_id = ?
              AND module_id = ?
            FOR UPDATE
            """.trimIndent(),
            Boolean::class.java,
            tenantId,
            moduleId,
        ).singleOrNull()

        jdbcTemplate.update(
            """
            INSERT INTO tenant_modules (
                tenant_id,
                module_id,
                is_enabled,
                is_configured,
                source,
                configured_at
            )
            VALUES (?, ?, ?, ?, 'manual', CASE WHEN ? THEN now() ELSE NULL END)
            ON CONFLICT ON CONSTRAINT tenant_modules_tenant_id_module_id_key
            DO UPDATE SET
                is_enabled = EXCLUDED.is_enabled,
                is_configured = tenant_modules.is_configured OR EXCLUDED.is_configured,
                source = 'manual',
                configured_at = CASE
                    WHEN EXCLUDED.is_enabled THEN COALESCE(tenant_modules.configured_at, now())
                    ELSE tenant_modules.configured_at
                END,
                updated_at = now()
            """.trimIndent(),
            tenantId,
            moduleId,
            enabled,
            enabled,
            enabled,
        )

        return previous != enabled
    }

    private fun recordModuleSideEffects(
        receipt: TenantModuleMutationReceipt,
        eventType: String,
        idempotencyKeyId: UUID,
    ) {
        auditPort.recordTenantEvent(
            TenantAuditEvent(
                tenantId = receipt.tenantId,
                action = eventType,
                resource = AuditResource("tenant_modules", receipt.tenantId),
                after = mapOf(
                    "tenantId" to receipt.tenantId,
                    "moduleId" to receipt.moduleId,
                    "enabled" to receipt.enabled,
                ),
            ),
        )

        outboxPort.enqueue(
            OutboxEventCommand(
                aggregateType = "tenant_modules",
                aggregateId = receipt.tenantId,
                tenantId = receipt.tenantId,
                eventType = eventType,
                destination = OutboxDestination.PLATFORM,
                payload = mapOf(
                    "tenantId" to receipt.tenantId,
                    "moduleId" to receipt.moduleId,
                    "enabled" to receipt.enabled,
                ),
                idempotencyKeyId = idempotencyKeyId,
                priority = 3,
            ),
        )
    }

    private fun tenantExistsAndUsable(tenantId: UUID): Boolean {
        return jdbcTemplate.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1
                FROM tenants
                WHERE id = ?
                  AND deleted_at IS NULL
                  AND status IN ('trial', 'active')
            )
            """.trimIndent(),
            Boolean::class.java,
            tenantId,
        ) == true
    }

    private fun businessProfileIsVerified(tenantId: UUID): Boolean {
        return jdbcTemplate.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1
                FROM tenant_profiles
                WHERE tenant_id = ?
                  AND verification_status IN ('approved', 'verified')
                  AND business_phone IS NOT NULL
                  AND business_email IS NOT NULL
            )
            """.trimIndent(),
            Boolean::class.java,
            tenantId,
        ) == true
    }

    private fun requiredBusinessContactExists(tenantId: UUID): Boolean {
        return jdbcTemplate.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1
                FROM tenant_contact_roles tcr
                JOIN tenant_contacts tc
                    ON tc.tenant_id = tcr.tenant_id
                   AND tc.id = tcr.contact_id
                   AND tc.deleted_at IS NULL
                   AND tc.status = 'active'
                WHERE tcr.tenant_id = ?
                  AND tcr.role_code IN (
                      'owner_managing_director',
                      'authorized_signatory',
                      'primary_contact'
                  )
                  AND (tcr.effective_to IS NULL OR tcr.effective_to > now())
            )
            """.trimIndent(),
            Boolean::class.java,
            tenantId,
        ) == true
    }

    private fun operationalReportRecipientExists(tenantId: UUID): Boolean {
        return jdbcTemplate.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1
                FROM report_subscription_recipients rsr
                JOIN report_subscriptions rs
                    ON rs.tenant_id = rsr.tenant_id
                   AND rs.id = rsr.subscription_id
                   AND rs.status = 'active'
                   AND rs.deleted_at IS NULL
                WHERE rsr.tenant_id = ?
                  AND rsr.is_enabled = true
                  AND contact_channel_has_active_consent(
                      rsr.tenant_id,
                      rsr.contact_id,
                      rsr.contact_channel_id,
                      'operational_reports'
                  )
            )
            """.trimIndent(),
            Boolean::class.java,
            tenantId,
        ) == true
    }

    private fun tenantAdminModuleEnabled(tenantId: UUID): Boolean {
        return jdbcTemplate.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1
                FROM tenant_modules
                WHERE tenant_id = ?
                  AND module_id = 'tenant_admin'
                  AND is_enabled = true
            )
            """.trimIndent(),
            Boolean::class.java,
            tenantId,
        ) == true
    }

    private fun replayModuleMutation(
        reservation: IdempotencyReservation.Replay,
    ): TenantModuleMutationReceipt {
        if (reservation.responseBody.isNullOrBlank()) {
            throw TenantAdministrationConflictException(
                "Tenant module replay does not contain a stored response body",
            )
        }

        return objectMapper.readValue(
            reservation.responseBody,
            TenantModuleMutationReceipt::class.java,
        ).copy(replayed = true)
    }

    private fun String.normalizedModuleId(): String {
        return trim().lowercase().takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("moduleId is required")
    }

    private companion object {
        const val MODULE_MANAGE_PERMISSION = "module.manage"
        const val MODULE_VIEW_PERMISSION = "module.view"
        const val TENANT_PROFILE_VIEW_PERMISSION = "tenant.profile.view"
    }
}
