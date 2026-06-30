package com.mwombeki.peak.payments.internal

import com.mwombeki.peak.TestcontainersConfiguration
import com.mwombeki.peak.payments.api.*
import com.mwombeki.peak.shared.context.RequestContext
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.util.*
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertFailsWith

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@Transactional
class PaymentTransactionServiceIntegrationTests {

    @Autowired
    private lateinit var paymentTransactionService: PaymentTransactionService

    @Autowired
    private lateinit var requestContextHolder: RequestContextHolder

    private val tenantId = UUID.randomUUID()
    private val propertyId = UUID.randomUUID()
    private val tenantUserId = UUID.randomUUID()

    @BeforeEach
    fun setup() {
        requestContextHolder.set(
            RequestContext(
                identity = RequestIdentity.Tenant(tenantId, tenantUserId),
                correlationId = UUID.randomUUID().toString(),
                idempotencyKey = null,
                httpMethod = "POST",
                requestPath = "/api/v1/payments"
            )
        )
    }

    @Test
    fun `should record cash payment`() {
        val request = CashPaymentRequest(
            amount = BigDecimal("5000.00"),
            posSessionId = UUID.randomUUID(),
            folioId = UUID.randomUUID(),
            propertyId = propertyId
        )

        val transactionId = paymentTransactionService.recordCashPayment(request)
        assertNotNull(transactionId)

        val transaction = paymentTransactionService.getTransaction(transactionId)
        assertEquals(BigDecimal("5000.0000"), transaction.amount.stripTrailingZeros())
        assertEquals(PaymentMethod.CASH, transaction.method)
        assertEquals(PaymentStatus.POSTED, transaction.status)
    }

    @Test
    fun `should record manual mobile money and reject duplicates`() {
        val reference = "TXN123456"
        val request = ManualMobileMoneyRequest(
            amount = BigDecimal("10000.00"),
            providerReference = reference,
            posSessionId = UUID.randomUUID(),
            folioId = UUID.randomUUID(),
            propertyId = propertyId
        )

        val transactionId = paymentTransactionService.recordManualMobileMoney(request)
        assertNotNull(transactionId)

        val transaction = paymentTransactionService.getTransaction(transactionId)
        assertEquals(reference, transaction.providerReference)
        assertEquals(PaymentMethod.MOBILE_MONEY, transaction.method)

        // Duplicate rejection
        assertFailsWith<IllegalStateException> {
            paymentTransactionService.recordManualMobileMoney(request)
        }
    }

    @Test
    fun `should initiate ClickPesa and update via webhook`() {
        // 1. Initiate
        val request = ClickPesaInitiationRequest(
            amount = BigDecimal("1500.00"),
            phoneNumber = "+255700000000",
            posSessionId = UUID.randomUUID(),
            folioId = UUID.randomUUID(),
            propertyId = propertyId,
            externalReference = "PEAK-REF-1"
        )
        
        val transactionId = paymentTransactionService.initiateClickPesaPayment(request)
        assertNotNull(transactionId)
        
        val initiatedTx = paymentTransactionService.getTransaction(transactionId)
        assertEquals(PaymentStatus.INITIATED, initiatedTx.status)

        // 2. Webhook Success
        val webhook = ClickPesaWebhookPayload(
            providerReference = "CP-999",
            externalReference = transactionId,
            status = "SUCCESS",
            amount = BigDecimal("1500.00"),
            currency = "TZS"
        )
        
        paymentTransactionService.processClickPesaWebhook(webhook)
        
        val postedTx = paymentTransactionService.getTransaction(transactionId)
        assertEquals(PaymentStatus.POSTED, postedTx.status)
        assertEquals("CP-999", postedTx.providerReference)
    }
}
