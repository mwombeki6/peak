package com.mwombeki.peak.audit.internal

import com.mwombeki.peak.audit.api.AuditPort
import com.mwombeki.peak.audit.api.PlatformAuditEvent
import com.mwombeki.peak.audit.api.SystemPlatformAuditEvent
import com.mwombeki.peak.audit.api.TenantAuditEvent
import com.mwombeki.peak.shared.context.DatabaseSessionContext
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper

@Component
class JdbcAuditPort(
    private val jdbcTemplate: JdbcTemplate,
    private val requestContextHolder: RequestContextHolder,
    private val databaseSessionContext: DatabaseSessionContext,
    private val transactionManager: PlatformTransactionManager,
    private val payloadSanitizer: AuditPayloadSanitizer,
    private val objectMapper: ObjectMapper,
    private val meterRegistry: MeterRegistry,
) : AuditPort {
    override fun recordTenantEvent(event: TenantAuditEvent) {
        requireActiveTransaction()

        val context = requestContextHolder.current()
        val tenantUserId = when (val identity = context.identity) {
            is RequestIdentity.Tenant -> {
                require(identity.tenantId == event.tenantId) {
                    "Tenant audit event tenant must match request tenant"
                }
                identity.tenantUserId
            }

            else -> null
        }

        jdbcTemplate.update(
            """
            INSERT INTO audit_logs (
                tenant_id,
                user_id,
                action,
                entity_type,
                entity_id,
                old_values,
                new_values,
                ip_address,
                user_agent,
                correlation_id,
                outcome
            )
            VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, ?, ?, ?)
            """.trimIndent(),
            event.tenantId,
            tenantUserId,
            event.action,
            event.resource.type,
            event.resource.id,
            json(event.before),
            json(event.after),
            context.remoteAddress,
            context.userAgent,
            context.correlationId,
            event.outcome.databaseValue,
        )
        recordMetric("tenant", event.outcome.databaseValue)
    }

    override fun recordPlatformEvent(event: PlatformAuditEvent) {
        requireActiveTransaction()
        insertPlatformEvent(event)
    }

    override fun recordPlatformEventImmediately(event: PlatformAuditEvent) {
        TransactionTemplate(transactionManager).apply {
            propagationBehavior = TransactionDefinition.PROPAGATION_REQUIRES_NEW
            isReadOnly = false
        }.executeWithoutResult {
            databaseSessionContext.bind(requestContextHolder.current().identity)
            insertPlatformEvent(event)
        }
    }

    override fun recordSystemPlatformEvent(event: SystemPlatformAuditEvent) {
        requireActiveTransaction()
        jdbcTemplate.update(
            """
            INSERT INTO platform_audit_logs (
                platform_user_id,
                action,
                entity_type,
                entity_id,
                tenant_id,
                new_values,
                correlation_id,
                outcome
            )
            VALUES (?, ?, ?, ?, ?, ?::jsonb, ?, ?)
            """.trimIndent(),
            event.platformUserId,
            event.action,
            event.resource.type,
            event.resource.id,
            event.targetTenantId,
            json(event.after),
            event.correlationId,
            event.outcome.databaseValue,
        )
        recordMetric("platform_system", event.outcome.databaseValue)
    }

    private fun insertPlatformEvent(event: PlatformAuditEvent) {
        val context = requestContextHolder.current()
        val platformUserId = when (val identity = context.identity) {
            is RequestIdentity.Platform -> identity.platformUserId
            is RequestIdentity.Support -> identity.platformUserId
            else -> null
        }

        jdbcTemplate.update(
            """
            INSERT INTO platform_audit_logs (
                platform_user_id,
                action,
                entity_type,
                entity_id,
                tenant_id,
                old_values,
                new_values,
                ip_address,
                user_agent,
                correlation_id,
                outcome
            )
            VALUES (?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::inet, ?, ?, ?)
            """.trimIndent(),
            platformUserId,
            event.action,
            event.resource.type,
            event.resource.id,
            event.targetTenantId,
            json(event.before),
            json(event.after),
            context.remoteAddress,
            context.userAgent,
            context.correlationId,
            event.outcome.databaseValue,
        )
        recordMetric("platform", event.outcome.databaseValue)
    }

    private fun json(payload: Map<String, Any?>?): String? {
        return payloadSanitizer.sanitize(payload)?.let(objectMapper::writeValueAsString)
    }

    private fun requireActiveTransaction() {
        require(TransactionSynchronizationManager.isActualTransactionActive()) {
            "Audit events must be recorded inside an active transaction"
        }
    }

    private fun recordMetric(scope: String, outcome: String) {
        meterRegistry.counter(
            "peak.audit.events",
            "scope",
            scope,
            "outcome",
            outcome,
        ).increment()
    }
}
