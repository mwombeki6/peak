package com.mwombeki.peak.payments.internal

import com.mwombeki.peak.billing.api.BillingPort
import com.mwombeki.peak.billing.api.ConfirmedPaymentRequest
import com.mwombeki.peak.payments.api.*
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.*

@Service
class PaymentTransactionService(
    private val jdbcTemplate: JdbcTemplate,
    private val requestContextHolder: RequestContextHolder,
    private val billingPort: BillingPort,
) {
    private fun resolveTenant(): UUID {
        val context = requestContextHolder.current()
        return when (val identity = context.identity) {
            is RequestIdentity.Tenant -> identity.tenantId
            else -> throw IllegalStateException("Security Violation: Action requires an active Tenant identity.")
        }
    }

    @Transactional
    fun recordCashPayment(request: CashPaymentRequest): UUID {
        val tenantId = resolveTenant()
        val transactionId = UUID.randomUUID()
        val now = Instant.now()

        // 1. Insert into payment_transactions (as per V26 schema)
        jdbcTemplate.update(
            """
            INSERT INTO payment_transactions (
                id, tenant_id, property_id, pos_session_id, folio_id, 
                amount, currency, payment_method, status, created_at, posted_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, 'CASH', 'POSTED', ?, ?)
            """.trimIndent(),
            transactionId, tenantId, request.propertyId, request.posSessionId, request.folioId,
            request.amount, request.currency, now, now
        )

        // 2. Notify Billing Module
        billingPort.postConfirmedPayment(
            tenantId = tenantId,
            propertyId = request.propertyId,
            request = ConfirmedPaymentRequest(
                folioId = request.folioId,
                paymentMethod = "cash",
                amount = request.amount,
                referenceNumber = transactionId.toString(),
                notes = "Recorded via Payments module"
            ),
            idempotencyKeyId = null
        )

        return transactionId
    }

    @Transactional
    fun recordManualMobileMoney(request: ManualMobileMoneyRequest): UUID {
        val tenantId = resolveTenant()
        val transactionId = UUID.randomUUID()
        val now = Instant.now()

        // 1. Verify provider reference uniqueness per tenant (Phase 3 safety)
        val duplicateCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(1) FROM payment_transactions WHERE tenant_id = ? AND provider_reference = ?",
            Int::class.java, tenantId, request.providerReference
        ) ?: 0

        if (duplicateCount > 0) {
            throw IllegalStateException("Duplicate transaction reference: ${request.providerReference}")
        }

        jdbcTemplate.update(
            """
            INSERT INTO payment_transactions (
                id, tenant_id, property_id, pos_session_id, folio_id, 
                amount, currency, payment_method, status, provider_reference, created_at, posted_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, 'MOBILE_MONEY', 'POSTED', ?, ?, ?)
            """.trimIndent(),
            transactionId, tenantId, request.propertyId, request.posSessionId, request.folioId,
            request.amount, request.currency, request.providerReference, now, now
        )

        // 2. Notify Billing Module
        billingPort.postConfirmedPayment(
            tenantId = tenantId,
            propertyId = request.propertyId,
            request = ConfirmedPaymentRequest(
                folioId = request.folioId,
                paymentMethod = "mobile_money",
                amount = request.amount,
                referenceNumber = request.providerReference,
                notes = "Manual reference: ${request.providerReference}"
            ),
            idempotencyKeyId = null
        )

        return transactionId
    }

    @Transactional
    fun initiateClickPesaPayment(request: ClickPesaInitiationRequest): UUID {
        val tenantId = resolveTenant()
        val transactionId = UUID.randomUUID()
        val now = Instant.now()

        // 1. Create Transaction in INITIATED state
        jdbcTemplate.update(
            """
            INSERT INTO payment_transactions (
                id, tenant_id, property_id, pos_session_id, folio_id, 
                amount, currency, payment_method, status, created_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, 'MOBILE_MONEY', 'INITIATED', ?)
            """.trimIndent(),
            transactionId, tenantId, request.propertyId, request.posSessionId, request.folioId,
            request.amount, request.currency, now
        )

        // 2. TODO: Call Integrations module to reach ClickPesa SPI
        // In Phase 3, this usually returns a 202 and then we wait for webhook.
        println(" [ClickPesa] Initiated payment $transactionId for ${request.amount} ${request.currency}")
        
        return transactionId
    }

    @Transactional
    fun processClickPesaWebhook(payload: ClickPesaWebhookPayload) {
        // Resolve transaction
        val transactionId = payload.externalReference
        
        // Update transaction status based on ClickPesa result
        val targetStatus = when (payload.status.uppercase()) {
            "SUCCESS", "COMPLETED" -> "POSTED"
            "FAILED", "DECLINED" -> "FAILED"
            else -> return // Ignore other states for now
        }

        val now = Instant.now()
        val postedAt = if (targetStatus == "POSTED") now else null

        // Get tenant/property before updating
        val tx = jdbcTemplate.queryForMap(
            "SELECT tenant_id, property_id, folio_id, amount FROM payment_transactions WHERE id = ?",
            transactionId
        )
        val tenantId = tx["tenant_id"] as UUID
        val propertyId = tx["property_id"] as UUID
        val folioId = tx["folio_id"] as UUID
        val amount = tx["amount"] as BigDecimal

        jdbcTemplate.update(
            """
            UPDATE payment_transactions 
            SET status = ?, provider_reference = ?, posted_at = ?
            WHERE id = ? AND status = 'INITIATED'
            """.trimIndent(),
            targetStatus, payload.providerReference, postedAt, transactionId
        )

        // If successful, notify Billing
        if (targetStatus == "POSTED") {
            billingPort.postConfirmedPayment(
                tenantId = tenantId,
                propertyId = propertyId,
                request = ConfirmedPaymentRequest(
                    folioId = folioId,
                    paymentMethod = "mobile_money",
                    amount = amount,
                    referenceNumber = payload.providerReference,
                    notes = "ClickPesa USSD Push"
                ),
                idempotencyKeyId = null
            )
        }
        
        println(" [ClickPesa Webhook] Transaction $transactionId updated to $targetStatus. Ref: ${payload.providerReference}")
    }

    @Transactional(readOnly = true)
    fun getTransaction(id: UUID): PaymentTransactionResponse {
        val tenantId = resolveTenant()
        
        return jdbcTemplate.queryForObject(
            "SELECT * FROM payment_transactions WHERE id = ? AND tenant_id = ?",
            { rs, _ ->
                PaymentTransactionResponse(
                    id = rs.getObject("id", UUID::class.java),
                    tenantId = rs.getObject("tenant_id", UUID::class.java),
                    propertyId = rs.getObject("property_id", UUID::class.java),
                    amount = rs.getBigDecimal("amount"),
                    currency = rs.getString("currency"),
                    method = PaymentMethod.valueOf(rs.getString("payment_method")),
                    status = PaymentStatus.valueOf(rs.getString("status")),
                    providerReference = rs.getString("provider_reference"),
                    createdAt = rs.getTimestamp("created_at").toInstant(),
                    postedAt = rs.getTimestamp("posted_at")?.toInstant()
                )
            },
            id, tenantId
        ) ?: throw NoSuchElementException("Transaction not found.")
    }
}
