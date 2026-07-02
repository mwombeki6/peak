package com.mwombeki.peak.payment.internal.web

import com.mwombeki.peak.payment.api.CashSessionResponse
import com.mwombeki.peak.payment.api.CloseCashSessionRequest
import com.mwombeki.peak.payment.api.CollectCashPaymentRequest
import com.mwombeki.peak.payment.api.ConfigurePaymentProviderRequest
import com.mwombeki.peak.payment.api.CreatePaymentReconciliationRequest
import com.mwombeki.peak.payment.api.InitiateMobileMoneyRequest
import com.mwombeki.peak.payment.api.OpenCashSessionRequest
import com.mwombeki.peak.payment.api.PaymentPort
import com.mwombeki.peak.payment.api.PaymentProviderAccountResponse
import com.mwombeki.peak.payment.api.PaymentReconciliationResponse
import com.mwombeki.peak.payment.api.PaymentTransactionResponse
import com.mwombeki.peak.payment.api.RecordManualMobileMoneyPaymentRequest
import com.mwombeki.peak.payment.api.ReversePaymentRequest
import java.util.UUID
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/properties/{propertyId}/payments")
class PaymentController(
    private val paymentPort: PaymentPort,
) {
    @PostMapping("/cash-sessions")
    fun openCashSession(
        @PathVariable propertyId: UUID,
        @RequestBody request: OpenCashSessionRequest,
    ): CashSessionResponse = paymentPort.openCashSession(propertyId, request)

    @GetMapping("/cash-sessions/current")
    fun currentCashSession(
        @PathVariable propertyId: UUID,
    ): CashSessionResponse? = paymentPort.currentCashSession(propertyId)

    @PostMapping("/cash-sessions/{cashSessionId}/close")
    fun closeCashSession(
        @PathVariable propertyId: UUID,
        @PathVariable cashSessionId: UUID,
        @RequestBody request: CloseCashSessionRequest,
    ): CashSessionResponse = paymentPort.closeCashSession(propertyId, cashSessionId, request)

    @PostMapping("/cash")
    fun collectCash(
        @PathVariable propertyId: UUID,
        @RequestBody request: CollectCashPaymentRequest,
    ): PaymentTransactionResponse = paymentPort.collectCash(propertyId, request)

    @PostMapping("/mobile-money")
    fun initiateMobileMoney(
        @PathVariable propertyId: UUID,
        @RequestBody request: InitiateMobileMoneyRequest,
    ): PaymentTransactionResponse = paymentPort.initiateMobileMoney(propertyId, request)

    @PostMapping("/mobile-money/manual-reference")
    fun recordManualMobileMoney(
        @PathVariable propertyId: UUID,
        @RequestBody request: RecordManualMobileMoneyPaymentRequest,
    ): PaymentTransactionResponse = paymentPort.recordManualMobileMoney(propertyId, request)

    @GetMapping("/transactions")
    fun listTransactions(
        @PathVariable propertyId: UUID,
        @RequestParam(defaultValue = "100") limit: Int,
    ): List<PaymentTransactionResponse> = paymentPort.listTransactions(propertyId, limit)

    @GetMapping("/transactions/{transactionId}")
    fun getTransaction(
        @PathVariable propertyId: UUID,
        @PathVariable transactionId: UUID,
    ): PaymentTransactionResponse {
        return paymentPort.getTransaction(propertyId, transactionId)
            ?: throw com.mwombeki.peak.payment.api.PaymentNotFoundException(
                "Payment transaction was not found",
            )
    }

    @PostMapping("/transactions/{transactionId}/reverse")
    fun reversePayment(
        @PathVariable propertyId: UUID,
        @PathVariable transactionId: UUID,
        @RequestBody request: ReversePaymentRequest,
    ): PaymentTransactionResponse {
        return paymentPort.reversePayment(propertyId, transactionId, request)
    }

    @PostMapping("/provider-accounts")
    fun configureProvider(
        @PathVariable propertyId: UUID,
        @RequestBody request: ConfigurePaymentProviderRequest,
    ): PaymentProviderAccountResponse = paymentPort.configureProvider(propertyId, request)

    @GetMapping("/provider-accounts")
    fun listProviderAccounts(
        @PathVariable propertyId: UUID,
    ): List<PaymentProviderAccountResponse> = paymentPort.listProviderAccounts(propertyId)

    @PostMapping("/reconciliations")
    fun createReconciliation(
        @PathVariable propertyId: UUID,
        @RequestBody request: CreatePaymentReconciliationRequest,
    ): PaymentReconciliationResponse = paymentPort.createReconciliation(propertyId, request)

    @PostMapping("/reconciliations/{reconciliationId}/approve")
    fun approveReconciliation(
        @PathVariable propertyId: UUID,
        @PathVariable reconciliationId: UUID,
    ): PaymentReconciliationResponse {
        return paymentPort.approveReconciliation(propertyId, reconciliationId)
    }
}
