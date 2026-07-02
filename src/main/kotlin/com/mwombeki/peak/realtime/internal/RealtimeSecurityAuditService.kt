package com.mwombeki.peak.realtime.internal

import com.mwombeki.peak.audit.api.AuditOutcome
import com.mwombeki.peak.audit.api.AuditPort
import com.mwombeki.peak.audit.api.AuditResource
import com.mwombeki.peak.audit.api.TenantAuditEvent
import com.mwombeki.peak.shared.context.DatabaseSessionContext
import com.mwombeki.peak.shared.context.RequestContext
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import io.micrometer.core.instrument.MeterRegistry
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate

@Component
class RealtimeSecurityAuditService(
    private val auditPort: AuditPort,
    private val requestContextHolder: RequestContextHolder,
    private val databaseSessionContext: DatabaseSessionContext,
    private val transactionTemplate: TransactionTemplate,
    private val meterRegistry: MeterRegistry,
) {
    fun recordDeniedSubscription(
        context: RequestContext,
        targetTenantId: UUID,
        targetPropertyId: UUID,
        destination: String,
    ) {
        val identity = context.identity as? RequestIdentity.Tenant ?: return
        val previousContext = requestContextHolder.currentOrNull()

        try {
            transactionTemplate.executeWithoutResult {
                requestContextHolder.set(context)
                databaseSessionContext.bind(identity)
                auditPort.recordTenantEvent(
                    TenantAuditEvent(
                        tenantId = identity.tenantId,
                        action = "realtime.subscription_denied",
                        resource = AuditResource("realtime_stream", targetPropertyId),
                        outcome = AuditOutcome.DENIED,
                        after = mapOf(
                            "attempted_destination" to destination,
                            "target_tenant_id" to targetTenantId,
                            "target_property_id" to targetPropertyId,
                        ),
                    ),
                )
            }
        } catch (exception: RuntimeException) {
            meterRegistry.counter("realtime.security.audit_failures").increment()
            logger.error(
                "Failed to audit denied realtime subscription correlationId={} targetTenantId={} targetPropertyId={}",
                context.correlationId,
                targetTenantId,
                targetPropertyId,
                exception,
            )
        } finally {
            if (previousContext == null) {
                requestContextHolder.clear()
            } else {
                requestContextHolder.set(previousContext)
            }
        }
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(RealtimeSecurityAuditService::class.java)
    }
}
