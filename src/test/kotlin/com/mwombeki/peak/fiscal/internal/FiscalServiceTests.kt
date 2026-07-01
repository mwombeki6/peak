package com.mwombeki.peak.fiscal.internal

import com.mwombeki.peak.billing.api.BillingPort
import com.mwombeki.peak.billing.api.InvoiceResponse
import com.mwombeki.peak.fiscal.api.FiscalSubmissionRequest
import com.mwombeki.peak.fiscal.internal.provider.FiscalProvider
import com.mwombeki.peak.fiscal.internal.provider.FiscalProviderResult
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FiscalServiceTests {

    private class MockBillingPort : BillingPort {
        var getInvoiceResponse: InvoiceResponse? = null
        
        override fun getInvoice(propertyId: UUID, invoiceId: UUID): InvoiceResponse? = getInvoiceResponse
        
        // No-ops for other methods
        override fun openReservationFolio(t: UUID, p: UUID, r: UUID, i: UUID) = UUID.randomUUID()
        override fun postRoomChargeForReservation(t: UUID, p: UUID, r: UUID, i: UUID) = null
        override fun postConfirmedPayment(t: UUID, p: UUID, r: com.mwombeki.peak.billing.api.ConfirmedPaymentRequest, i: UUID?) = UUID.randomUUID()
        override fun checkoutFinancialState(t: UUID, p: UUID, r: UUID) = com.mwombeki.peak.billing.api.CheckoutFinancialState(UUID.randomUUID(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, false, false)
        override fun closeFolio(t: UUID, p: UUID, f: UUID) {}
        override fun listFolios(p: UUID) = emptyList<com.mwombeki.peak.billing.api.FolioResponse>()
        override fun getFolio(p: UUID, f: UUID) = null
        override fun postCharge(p: UUID, f: UUID, r: com.mwombeki.peak.billing.api.PostChargeRequest) = com.mwombeki.peak.billing.api.BillingMutationReceipt(p, f, "", UUID.randomUUID(), false, false)
        override fun postPayment(p: UUID, f: UUID, r: com.mwombeki.peak.billing.api.PostPaymentRequest) = com.mwombeki.peak.billing.api.BillingMutationReceipt(p, f, "", UUID.randomUUID(), false, false)
        override fun reverseCharge(p: UUID, f: UUID, c: UUID, r: com.mwombeki.peak.billing.api.ReverseChargeRequest) = com.mwombeki.peak.billing.api.BillingMutationReceipt(p, f, "", UUID.randomUUID(), false, false)
        override fun issueInvoice(p: UUID, f: UUID, r: com.mwombeki.peak.billing.api.IssueInvoiceRequest) = getInvoiceResponse!!
        override fun listInvoices(p: UUID) = emptyList<InvoiceResponse>()
    }

    private class MockFiscalReceiptRepository : FiscalReceiptRepository(org.springframework.jdbc.core.JdbcTemplate()) {
        val savedReceipts = mutableListOf<FiscalReceipt>()
        var findByInvoiceIdResponse: FiscalReceipt? = null

        override fun findByInvoiceId(invoiceId: UUID): FiscalReceipt? = findByInvoiceIdResponse
        override fun save(receipt: FiscalReceipt) {
            savedReceipts.add(receipt)
        }
    }

    private class MockFiscalProvider : FiscalProvider {
        var submitResponse: FiscalProviderResult = FiscalProviderResult.Failure("Not set")
        override fun name(): String = "MOCK"
        override fun submit(invoice: InvoiceResponse): FiscalProviderResult = submitResponse
    }

    private val repository = MockFiscalReceiptRepository()
    private val billingPort = MockBillingPort()
    private val provider = MockFiscalProvider()
    private val service = FiscalService(repository, billingPort, listOf(provider))

    @Test
    fun successfullyFiscalizesInvoice() {
        // Given
        val tenantId = UUID.randomUUID()
        val propertyId = UUID.randomUUID()
        val invoiceId = UUID.randomUUID()
        val request = FiscalSubmissionRequest(invoiceId, tenantId, propertyId)
        
        billingPort.getInvoiceResponse = InvoiceResponse(
            id = invoiceId,
            folioId = UUID.randomUUID(),
            propertyId = propertyId,
            invoiceNumber = "INV-001",
            subtotal = BigDecimal("100.00"),
            vatTotal = BigDecimal("18.00"),
            serviceCharge = BigDecimal.ZERO,
            tourismLevy = BigDecimal.ZERO,
            total = BigDecimal("118.00"),
            status = "ISSUED",
            issuedAt = Instant.now()
        )

        provider.submitResponse = FiscalProviderResult.Success(
            fiscalReference = "FISC-REF",
            signedPayload = "SIGNED-PAYLOAD"
        )

        // When
        service.submitInvoice(request)

        // Then
        val saved = repository.savedReceipts.last()
        assertEquals(FiscalStatus.ACCEPTED.name, saved.status)
        assertEquals("FISC-REF", saved.fiscalReference)
    }

    @Test
    fun handlesProviderFailureAndMarksAsPendingForRetry() {
        // Given
        val tenantId = UUID.randomUUID()
        val propertyId = UUID.randomUUID()
        val invoiceId = UUID.randomUUID()
        val request = FiscalSubmissionRequest(invoiceId, tenantId, propertyId)
        
        billingPort.getInvoiceResponse = InvoiceResponse(
            id = invoiceId,
            folioId = UUID.randomUUID(),
            propertyId = propertyId,
            invoiceNumber = "INV-001",
            subtotal = BigDecimal("100.00"),
            vatTotal = BigDecimal("18.00"),
            serviceCharge = BigDecimal.ZERO,
            tourismLevy = BigDecimal.ZERO,
            total = BigDecimal("118.00"),
            status = "ISSUED",
            issuedAt = Instant.now()
        )

        provider.submitResponse = FiscalProviderResult.Failure(
            errorMessage = "Provider offline",
            retryable = true
        )

        // When
        service.submitInvoice(request)

        // Then
        val saved = repository.savedReceipts.last()
        assertEquals(FiscalStatus.PENDING.name, saved.status)
        assertEquals("Provider offline", saved.errorMessage)
    }
}
