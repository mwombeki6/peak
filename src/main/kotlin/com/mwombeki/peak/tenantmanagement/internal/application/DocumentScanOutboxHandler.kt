package com.mwombeki.peak.tenantmanagement.internal.application

import com.mwombeki.peak.reliability.api.ClaimedOutboxEvent
import com.mwombeki.peak.reliability.api.OutboxDestination
import com.mwombeki.peak.reliability.api.OutboxEventHandler
import com.mwombeki.peak.shared.outbound.KYC_DOCUMENT_OBJECT_STORAGE_QUALIFIER
import com.mwombeki.peak.shared.outbound.MalwareScanOutcome
import com.mwombeki.peak.shared.outbound.MalwareScanPort
import com.mwombeki.peak.shared.outbound.ObjectStoragePort
import java.util.UUID
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

/**
 * The other half of the forward reference in [TenantTrustControlService.addVerificationDocument]:
 * a claimed key in the KYC bucket is never trusted on the strength of its presigned URL alone,
 * so this reads the real bytes and runs them past ClamAV before the document counts as usable
 * evidence. Runs entirely through [recordDocumentScanResultFn] — a SECURITY DEFINER function —
 * rather than RLS-bound writes, because a document can belong to either a tenant or a pre-tenant
 * onboarding application and this worker has no session identity to bind as either one.
 */
@Component
class DocumentScanOutboxHandler(
    private val jdbcTemplate: JdbcTemplate,
    @Qualifier(KYC_DOCUMENT_OBJECT_STORAGE_QUALIFIER)
    private val kycDocumentStoragePort: ObjectStoragePort,
    private val malwareScanPort: MalwareScanPort,
    private val objectMapper: ObjectMapper,
) : OutboxEventHandler {
    override val destination = OutboxDestination.DOCUMENT_SCAN

    override suspend fun handle(event: ClaimedOutboxEvent) {
        @Suppress("UNCHECKED_CAST")
        val payload = objectMapper.readValue(event.payload, Map::class.java) as Map<String, Any?>
        val documentId = UUID.fromString(payload["documentId"] as String)
        val objectKey = payload["objectKey"] as String

        // Not found here means the object was already quarantined by a previous, since-failed
        // attempt (delete succeeded, the DB write that follows it did not) — nothing left to
        // scan, and record_document_scan_result is what makes the retry idempotent either way.
        val stat = kycDocumentStoragePort.stat(objectKey)
        val outcome = if (stat == null) {
            MalwareScanOutcome.Infected("object-missing-presumed-quarantined")
        } else {
            kycDocumentStoragePort.getObject(objectKey).use { content ->
                malwareScanPort.scan(content, stat.contentLength)
            }
        }

        when (outcome) {
            is MalwareScanOutcome.Clean -> recordResult(documentId, "clean", event)
            is MalwareScanOutcome.Infected -> {
                if (stat != null) {
                    kycDocumentStoragePort.delete(objectKey)
                }
                recordResult(documentId, "infected", event)
            }
        }
    }

    private fun recordResult(
        documentId: UUID,
        scanStatus: String,
        event: ClaimedOutboxEvent,
    ) {
        jdbcTemplate.queryForList(
            "SELECT record_document_scan_result(?, ?, ?, ?)",
            documentId,
            scanStatus,
            SCAN_ENGINE,
            event.correlationId,
        )
    }

    private companion object {
        const val SCAN_ENGINE = "clamav"
    }
}
