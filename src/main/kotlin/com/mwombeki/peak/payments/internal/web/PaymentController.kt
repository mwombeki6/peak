package com.mwombeki.peak.payments.internal.web

import com.mwombeki.peak.payments.api.*
import com.mwombeki.peak.payments.internal.PaymentTransactionService
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/v1/properties/{propertyId}/payments")
class PaymentController(
    private val paymentTransactionService: PaymentTransactionService
) {
    @PostMapping("/cash")
    @PreAuthorize("hasAnyRole('ROLE_TENANT_ADMIN', 'ROLE_PROPERTY_MANAGER', 'ROLE_CASHIER')")
    fun recordCash(
        @PathVariable propertyId: UUID,
        @RequestBody request: CashPaymentRequest
    ): ResponseEntity<Map<String, UUID>> {
        val transactionId = paymentTransactionService.recordCashPayment(request.copy(propertyId = propertyId))
        return ResponseEntity.ok(mapOf("transactionId" to transactionId))
    }

    @PostMapping("/manual-mobile-money")
    @PreAuthorize("hasAnyRole('ROLE_TENANT_ADMIN', 'ROLE_PROPERTY_MANAGER', 'ROLE_CASHIER')")
    fun recordManualMobileMoney(
        @PathVariable propertyId: UUID,
        @RequestBody request: ManualMobileMoneyRequest
    ): ResponseEntity<Map<String, UUID>> {
        val transactionId = paymentTransactionService.recordManualMobileMoney(request.copy(propertyId = propertyId))
        return ResponseEntity.ok(mapOf("transactionId" to transactionId))
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyRole('ROLE_TENANT_ADMIN', 'ROLE_PROPERTY_MANAGER', 'ROLE_CASHIER')")
    fun getTransaction(
        @PathVariable propertyId: UUID,
        @PathVariable id: UUID
    ): ResponseEntity<PaymentTransactionResponse> {
        return ResponseEntity.ok(paymentTransactionService.getTransaction(id))
    }

    @PostMapping("/clickpesa/initiate")
    @PreAuthorize("hasAnyRole('ROLE_TENANT_ADMIN', 'ROLE_PROPERTY_MANAGER', 'ROLE_CASHIER')")
    fun initiateClickPesa(
        @PathVariable propertyId: UUID,
        @RequestBody request: ClickPesaInitiationRequest
    ): ResponseEntity<Map<String, UUID>> {
        val transactionId = paymentTransactionService.initiateClickPesaPayment(request.copy(propertyId = propertyId))
        return ResponseEntity.ok(mapOf("transactionId" to transactionId))
    }

    @PostMapping("/clickpesa/webhook")
    fun handleClickPesaWebhook(
        @RequestBody payload: ClickPesaWebhookPayload
    ): ResponseEntity<Map<String, String>> {
        // Note: In production, we MUST verify the HMAC signature here.
        paymentTransactionService.processClickPesaWebhook(payload)
        return ResponseEntity.ok(mapOf("status" to "Accepted"))
    }
}
