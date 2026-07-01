package com.mwombeki.peak.fiscal.internal

import com.mwombeki.peak.billing.api.BillingPort
import com.mwombeki.peak.fiscal.api.*
import com.mwombeki.peak.fiscal.internal.provider.FiscalProvider
import com.mwombeki.peak.fiscal.internal.provider.FiscalProviderResult
import java.time.Instant
import java.util.UUID
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
class FiscalService(
    private val repository: FiscalReceiptRepository,
    private val billingPort: BillingPort,
    private val providers: List<FiscalProvider>
) : FiscalPort {

    private val log = LoggerFactory.getLogger(FiscalService::class.java)

    @Transactional
    override fun submitInvoice(request: FiscalSubmissionRequest) {
        val existing = repository.findByInvoiceId(request.invoiceId)
        if (existing != null && (existing.status == FiscalStatus.ACCEPTED.name || existing.overridden)) {
            log.info("Invoice {} already fiscalized or overridden", request.invoiceId)
            return
        }

        val receipt = existing?.copy(
            status = FiscalStatus.PENDING.name,
            updatedAt = Instant.now()
        ) ?: FiscalReceipt(
            id = UUID.randomUUID(),
            tenantId = request.tenantId,
            propertyId = request.propertyId,
            invoiceId = request.invoiceId,
            status = FiscalStatus.PENDING.name
        )

        repository.save(receipt)
        
        // In a real outbox scenario, this would be triggered by an outbox worker.
        // For Phase 3, we trigger the process synchronously but it's designed to be idempotent.
        try {
            processSubmission(receipt)
        } catch (e: Exception) {
            log.error("Failed to process fiscal submission for invoice ${request.invoiceId}", e)
            // We don't rethrow to keep it outbox-like, it will stay PENDING
        }
    }

    private fun processSubmission(receipt: FiscalReceipt) {
        val invoice = billingPort.getInvoice(receipt.propertyId, receipt.invoiceId)
            ?: throw IllegalArgumentException("Invoice ${receipt.invoiceId} not found")

        val provider = providers.firstOrNull() ?: throw IllegalStateException("No fiscal provider configured")
        
        log.info("Submitting invoice {} to provider {}", receipt.invoiceId, provider.name())

        val result = try {
            provider.submit(invoice)
        } catch (e: Exception) {
            log.error("Error submitting to fiscal provider", e)
            FiscalProviderResult.Failure("Internal error: ${e.message}", retryable = true)
        }

        val updatedReceipt = when (result) {
            is FiscalProviderResult.Success -> receipt.copy(
                status = FiscalStatus.ACCEPTED.name,
                fiscalReference = result.fiscalReference,
                signedPayload = result.signedPayload,
                verifiedAt = result.verifiedAt,
                attempts = receipt.attempts + 1,
                lastAttemptAt = Instant.now(),
                updatedAt = Instant.now()
            )
            is FiscalProviderResult.Failure -> receipt.copy(
                status = if (result.retryable) FiscalStatus.PENDING.name else FiscalStatus.REJECTED.name,
                errorMessage = result.errorMessage,
                attempts = receipt.attempts + 1,
                lastAttemptAt = Instant.now(),
                updatedAt = Instant.now()
            )
        }

        repository.save(updatedReceipt)
    }

    override fun getReceiptForInvoice(invoiceId: UUID): FiscalReceiptResponse? {
        return repository.findByInvoiceId(invoiceId)?.let {
            FiscalReceiptResponse(
                id = it.id,
                invoiceId = it.invoiceId,
                fiscalReference = it.fiscalReference,
                status = it.status,
                signedPayload = it.signedPayload,
                verifiedAt = it.verifiedAt,
                errorMessage = it.errorMessage
            )
        }
    }

    @Transactional
    override fun overrideFiscalization(invoiceId: UUID, request: FiscalOverrideRequest) {
        val receipt = repository.findByInvoiceId(invoiceId)
            ?: throw IllegalArgumentException("No fiscal record found for invoice $invoiceId")

        repository.save(receipt.copy(
            overridden = true,
            overrideReason = request.reason,
            status = FiscalStatus.OVERRIDDEN.name,
            updatedAt = Instant.now()
        ))
        
        log.info("Fiscalization overridden for invoice {} with reason: {}", invoiceId, request.reason)
    }
}
