package com.mwombeki.peak.fiscal.internal

import java.sql.ResultSet
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.time.Instant

@Repository
class FiscalReceiptRepository(private val jdbcTemplate: JdbcTemplate) {

    fun findByInvoiceId(invoiceId: UUID): FiscalReceipt? {
        return jdbcTemplate.query(
            "SELECT * FROM fiscal_receipts WHERE invoice_id = ?",
            { rs, _ -> mapRow(rs) },
            invoiceId
        ).singleOrNull()
    }

    fun save(receipt: FiscalReceipt) {
        val existing = findByInvoiceId(receipt.invoiceId)
        if (existing == null) {
            jdbcTemplate.update(
                """
                INSERT INTO fiscal_receipts (
                    id, tenant_id, property_id, invoice_id, status, fiscal_reference, 
                    signed_payload, error_message, attempts, last_attempt_at, verified_at, 
                    overridden, override_reason, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                receipt.id, receipt.tenantId, receipt.propertyId, receipt.invoiceId, receipt.status,
                receipt.fiscalReference, receipt.signedPayload, receipt.errorMessage, receipt.attempts,
                receipt.lastAttemptAt, receipt.verifiedAt, receipt.overridden, receipt.overrideReason,
                receipt.createdAt, receipt.updatedAt
            )
        } else {
            jdbcTemplate.update(
                """
                UPDATE fiscal_receipts SET 
                    status = ?, fiscal_reference = ?, signed_payload = ?, error_message = ?, 
                    attempts = ?, last_attempt_at = ?, verified_at = ?, overridden = ?, 
                    override_reason = ?, updated_at = ?
                WHERE id = ?
                """.trimIndent(),
                receipt.status, receipt.fiscalReference, receipt.signedPayload, receipt.errorMessage,
                receipt.attempts, receipt.lastAttemptAt, receipt.verifiedAt, receipt.overridden,
                receipt.overrideReason, receipt.updatedAt, receipt.id
            )
        }
    }

    private fun mapRow(rs: ResultSet): FiscalReceipt {
        return FiscalReceipt(
            id = rs.getObject("id", UUID::class.java),
            tenantId = rs.getObject("tenant_id", UUID::class.java),
            propertyId = rs.getObject("property_id", UUID::class.java),
            invoiceId = rs.getObject("invoice_id", UUID::class.java),
            status = rs.getString("status"),
            fiscalReference = rs.getString("fiscal_reference"),
            signedPayload = rs.getString("signed_payload"),
            errorMessage = rs.getString("error_message"),
            attempts = rs.getInt("attempts"),
            lastAttemptAt = rs.getTimestamp("last_attempt_at")?.toInstant(),
            verifiedAt = rs.getTimestamp("verified_at")?.toInstant(),
            overridden = rs.getBoolean("overridden"),
            overrideReason = rs.getString("override_reason"),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            updatedAt = rs.getTimestamp("updated_at").toInstant()
        )
    }
}
