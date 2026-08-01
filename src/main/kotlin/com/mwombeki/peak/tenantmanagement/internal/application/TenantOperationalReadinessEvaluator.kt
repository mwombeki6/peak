package com.mwombeki.peak.tenantmanagement.internal.application

import com.mwombeki.peak.tenantmanagement.api.TenantReadinessResponse
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

@Component
class TenantOperationalReadinessEvaluator(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun evaluate(tenantId: UUID): TenantReadinessResponse {
        val evidence = evidence(tenantId)
        val missing = mutableListOf<String>()

        if (!evidence.accountUsable) {
            missing += "Tenant account must be trial or active."
        }
        if (!evidence.businessProfileVerified) {
            missing += "Tenant business profile must be verified and include business contacts."
        }
        if (!evidence.requiredBusinessContactPresent) {
            missing += "Tenant must have an active owner, signatory, or primary contact."
        }
        if (!evidence.operationalReportRecipientPresent) {
            missing += "Tenant must have an enabled operational report recipient with consent."
        }
        if (!evidence.tenantAdminModuleReady) {
            missing += "Tenant administration module must be enabled."
        }

        return TenantReadinessResponse(
            tenantId = tenantId,
            isReady = missing.isEmpty(),
            missingRequirements = missing,
        )
    }

    fun evidence(tenantId: UUID): TenantOperationalReadinessEvidence =
        TenantOperationalReadinessEvidence(
            accountUsable = tenantExistsAndUsable(tenantId),
            businessProfileVerified = businessProfileIsVerified(tenantId),
            requiredBusinessContactPresent = requiredBusinessContactExists(tenantId),
            operationalReportRecipientPresent = operationalReportRecipientExists(tenantId),
            tenantAdminModuleReady = tenantAdminModuleEnabled(tenantId),
        )

    private fun tenantExistsAndUsable(tenantId: UUID): Boolean =
        jdbcTemplate.queryForObject(
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

    private fun businessProfileIsVerified(tenantId: UUID): Boolean =
        jdbcTemplate.queryForObject(
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

    private fun requiredBusinessContactExists(tenantId: UUID): Boolean =
        jdbcTemplate.queryForObject(
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

    private fun operationalReportRecipientExists(tenantId: UUID): Boolean =
        jdbcTemplate.queryForObject(
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

    private fun tenantAdminModuleEnabled(tenantId: UUID): Boolean =
        jdbcTemplate.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1
                FROM tenant_modules
                WHERE tenant_id = ?
                  AND module_id = 'tenant_admin'
                  AND is_enabled = true
                  AND is_configured = true
            )
            """.trimIndent(),
            Boolean::class.java,
            tenantId,
        ) == true
}

data class TenantOperationalReadinessEvidence(
    val accountUsable: Boolean,
    val businessProfileVerified: Boolean,
    val requiredBusinessContactPresent: Boolean,
    val operationalReportRecipientPresent: Boolean,
    val tenantAdminModuleReady: Boolean,
)
