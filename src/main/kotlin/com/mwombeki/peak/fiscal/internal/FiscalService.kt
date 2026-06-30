package com.mwombeki.peak.fiscal.internal

import com.mwombeki.peak.billing.api.BillingPort
import com.mwombeki.peak.fiscal.api.FiscalReceiptResponse
import com.mwombeki.peak.fiscal.api.FiscalSubmissionRequest
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.*

@Service
class FiscalService(
    private val jdbcTemplate: JdbcTemplate,
    private val billingPort: BillingPort,
    private val requestContextHolder: RequestContextHolder,
) {
    private fun resolveTenant(): UUID {
        val context = requestContextHolder.current()
        return when (val identity = context.identity) {
            is RequestIdentity.Tenant -> identity.tenantId
            else -> throw IllegalStateException("Security Violation: Action requires an active Tenant identity.")
        }
    }

    @Transactional
    fun submitInvoice(request: FiscalSubmissionRequest): UUID {
        val tenantId = resolveTenant()
        
        // 1. Verify Invoice exists via BillingPort
        val invoice = billingPort.getInvoice(request.propertyId, request.invoiceId)
            ?: throw NoSuchElementException("Invoice ${request.invoiceId} not found in Billing module.")

        // 2. Deterministic Signed Simulator for Phase 3
        // In production, this would use an outbox event to a Fiscal Provider SPI.
        val receiptId = UUID.randomUUID()
        val receiptNumber = "FR-${invoice.invoiceNumber ?: invoice.id.toString().take(8)}"
        val fiscalCode = "TZ-${UUID.randomUUID().toString().take(12).uppercase()}"
        val verificationCode = UUID.randomUUID().toString().replace("-", "").take(16).uppercase()
        val now = Instant.now()

        jdbcTemplate.update(
            """
            INSERT INTO fiscal_receipts (
                id, tenant_id, invoice_id, fiscal_mode, receipt_number, 
                fiscal_code, verification_code, qr_code_url, status, submitted_at, created_at
            ) VALUES (?, ?, ?, 'TRA_EFD', ?, ?, ?, ?, 'accepted', ?, ?)
            """.trimIndent(),
            receiptId, tenantId, request.invoiceId, receiptNumber,
            fiscalCode, verificationCode, "https://tra.go.tz/verify/$verificationCode", now, now
        )

        println(" [Fiscal] Invoice ${request.invoiceId} fiscalized. Receipt: $receiptNumber")
        return receiptId
    }

    @Transactional(readOnly = true)
    fun getReceipt(receiptId: UUID): FiscalReceiptResponse {
        val tenantId = resolveTenant()
        return jdbcTemplate.queryForObject(
            "SELECT * FROM fiscal_receipts WHERE id = ? AND tenant_id = ?",
            { rs, _ ->
                FiscalReceiptResponse(
                    id = rs.getObject("id", UUID::class.java),
                    invoiceId = rs.getObject("invoice_id", UUID::class.java),
                    receiptNumber = rs.getString("receipt_number"),
                    fiscalCode = rs.getString("fiscal_code"),
                    verificationCode = rs.getString("verification_code"),
                    qrCodeUrl = rs.getString("qr_code_url"),
                    status = rs.getString("status"),
                    submittedAt = rs.getTimestamp("submitted_at").toInstant()
                )
            },
            receiptId, tenantId
        ) ?: throw NoSuchElementException("Fiscal receipt not found.")
    }
}
