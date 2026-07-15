package com.mwombeki.peak.tenantmanagement.internal.application

import com.mwombeki.peak.tenantmanagement.api.ConfigureTenantModuleCommand
import com.mwombeki.peak.tenantmanagement.api.TenantLifecycleMutationPort
import com.mwombeki.peak.tenantmanagement.api.TenantLifecycleTransition
import com.mwombeki.peak.tenantmanagement.api.TenantLifecycleTransitionCommand
import com.mwombeki.peak.tenantmanagement.api.TenantModuleConfigurationPort
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

@Component
class TenantOwnedMutationService(
    private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
) : TenantLifecycleMutationPort, TenantModuleConfigurationPort {

    override fun transition(
        command: TenantLifecycleTransitionCommand,
    ): TenantLifecycleTransition {
        require(command.reason.isNotBlank()) { "Tenant lifecycle reason is required" }
        require(command.allowedCurrentStatuses.isNotEmpty()) {
            "At least one current tenant status must be allowed"
        }

        val currentStatus = jdbcTemplate.query(
            """
            SELECT status
            FROM tenants
            WHERE id = ?
              AND deleted_at IS NULL
            FOR UPDATE
            """.trimIndent(),
            { rs, _ -> rs.getString("status") },
            command.tenantId,
        ).singleOrNull() ?: throw IllegalArgumentException("Tenant was not found")

        require(currentStatus in command.allowedCurrentStatuses) {
            "Tenant cannot move from $currentStatus to ${command.newStatus}"
        }

        val changed = jdbcTemplate.update(
            """
            UPDATE tenants
            SET status = ?,
                updated_at = now()
            WHERE id = ?
              AND deleted_at IS NULL
            """.trimIndent(),
            command.newStatus,
            command.tenantId,
        )
        check(changed == 1) { "Tenant lifecycle transition lost its locked target" }

        jdbcTemplate.update(
            """
            INSERT INTO tenant_lifecycle_events (
                tenant_id,
                event_type,
                status,
                reason,
                metadata,
                performed_by_platform_user_id
            )
            VALUES (?, ?, 'completed', ?, ?::jsonb, ?)
            """.trimIndent(),
            command.tenantId,
            command.eventType,
            command.reason.trim(),
            objectMapper.writeValueAsString(
                mapOf(
                    "previousStatus" to currentStatus,
                    "newStatus" to command.newStatus,
                ),
            ),
            command.operatorId,
        )

        return TenantLifecycleTransition(
            tenantId = command.tenantId,
            previousStatus = currentStatus,
            newStatus = command.newStatus,
        )
    }

    override fun enableConfiguredModule(command: ConfigureTenantModuleCommand) {
        jdbcTemplate.update(
            """
            INSERT INTO tenant_modules (
                tenant_id, module_id, is_enabled, is_configured, source, configured_at
            )
            VALUES (?, ?, true, true, ?, now())
            ON CONFLICT ON CONSTRAINT tenant_modules_tenant_id_module_id_key
            DO UPDATE SET is_enabled = true,
                          is_configured = true,
                          source = EXCLUDED.source,
                          configured_at = COALESCE(tenant_modules.configured_at, now()),
                          updated_at = now()
            """.trimIndent(),
            command.tenantId,
            command.moduleId,
            command.source,
        )
    }

    override fun isEnabled(tenantId: java.util.UUID, moduleId: String): Boolean {
        return jdbcTemplate.queryForObject(
            """
            SELECT EXISTS(
                SELECT 1
                FROM tenant_modules
                WHERE tenant_id = ?
                  AND module_id = ?
                  AND is_enabled = true
            )
            """.trimIndent(),
            Boolean::class.java,
            tenantId,
            moduleId,
        ) == true
    }
}
