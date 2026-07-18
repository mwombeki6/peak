package com.mwombeki.peak.reporting.internal

import com.mwombeki.peak.shared.context.DatabaseSessionContext
import com.mwombeki.peak.shared.context.RequestIdentity
import com.mwombeki.peak.shared.outbound.ObjectStoragePort
import io.micrometer.core.instrument.MeterRegistry
import java.util.UUID
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.env.Environment
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate

@Component
class ReportArtifactRetentionCleanup(
    private val jdbcTemplate: JdbcTemplate,
    private val transactionTemplate: TransactionTemplate,
    private val databaseSessionContext: DatabaseSessionContext,
    private val objectStoragePort: ObjectStoragePort,
    private val meterRegistry: MeterRegistry,
    private val environment: Environment,
    @Value("\${peak.reporting.storage.enabled:false}")
    private val storageEnabled: Boolean,
) {
    @Scheduled(
        fixedDelayString =
            "\${peak.reporting.retention.cleanup-interval-ms:3600000}",
    )
    fun cleanup() {
        if (!storageEnabled ||
            environment.getProperty("peak.runtime.mode", "api") != "worker"
        ) {
            return
        }
        val candidates = jdbcTemplate.query(
            "SELECT * FROM claim_expired_report_artifacts(?)",
            { rs, _ ->
                ExpiredArtifact(
                    id = rs.getObject("artifact_id", UUID::class.java),
                    tenantId = rs.getObject("tenant_id", UUID::class.java),
                    propertyId = rs.getObject(
                        "property_id",
                        UUID::class.java,
                    ),
                    objectKey = rs.getString("object_key"),
                )
            },
            CLEANUP_BATCH_SIZE,
        )
        candidates.forEach { artifact ->
            try {
                objectStoragePort.delete(artifact.objectKey)
                transactionTemplate.executeWithoutResult {
                    databaseSessionContext.bind(
                        RequestIdentity.Public(
                            tenantId = artifact.tenantId,
                            propertyId = artifact.propertyId,
                            correlationId = "report-retention-${artifact.id}",
                        ),
                    )
                    jdbcTemplate.update(
                        """
                        UPDATE report_artifacts
                        SET expired_at = COALESCE(expired_at, now()),
                            object_deleted_at = COALESCE(
                                object_deleted_at, now()
                            )
                        WHERE tenant_id = ?
                          AND id = ?
                          AND object_deleted_at IS NULL
                        """.trimIndent(),
                        artifact.tenantId,
                        artifact.id,
                    )
                }
                meterRegistry.counter(
                    "peak.reporting.artifacts.expired",
                ).increment()
            } catch (ex: Exception) {
                meterRegistry.counter(
                    "peak.reporting.storage.errors",
                    "operation",
                    "retention_delete",
                ).increment()
            }
        }
    }

    private data class ExpiredArtifact(
        val id: UUID,
        val tenantId: UUID,
        val propertyId: UUID,
        val objectKey: String,
    )

    private companion object {
        const val CLEANUP_BATCH_SIZE = 100
    }
}
