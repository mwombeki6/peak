package com.mwombeki.peak.phase3

import com.mwombeki.peak.TestcontainersConfiguration
import com.mwombeki.peak.reliability.api.OutboxDestination
import com.mwombeki.peak.reliability.internal.OutboxWorkerProcessor
import com.mwombeki.peak.shared.context.PeakRequestHeaders
import java.time.LocalDate
import java.time.Instant
import java.nio.charset.StandardCharsets
import java.util.HexFormat
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.AfterTest
import kotlin.test.Test
import org.hamcrest.Matchers.hasItem
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.junit.jupiter.Testcontainers

@Import(TestcontainersConfiguration::class)
@SpringBootTest(
    properties = [
        "peak.security.request-context.allow-header-identity=true",
        "peak.security.outbound.allowed-provider-hosts[0]=api.clickpesa.com",
    ],
)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class Phase3StayLifecycleIntegrationTests {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var outboxWorkerProcessor: OutboxWorkerProcessor

    private val createdTenantIds = mutableSetOf<UUID>()

    @AfterTest
    fun removePendingPhase3OutboxEvents() {
        createdTenantIds.forEach { tenantId ->
            jdbcTemplate.update("DELETE FROM outbox_events WHERE tenant_id = ?", tenantId)
        }
        createdTenantIds.clear()
    }

    @Test
    fun completesReservationToCheckoutAndNightAuditControlsThroughApis() {
        val fixture = phase3Fixture()
        insertAuthorizedFixture(fixture)

        val guestId = postForId(
            fixture = fixture,
            path = "/api/v1/properties/${fixture.propertyId}/guests",
            idempotencyKey = "idem-guest-${fixture.tenantId}",
            idField = "id",
            json = """
                {
                  "fullName": "Phase Three Guest",
                  "email": "phase3.guest@example.com",
                  "phonePrimary": "+255700000001",
                  "dateOfBirth": "1990-01-01",
                  "nationality": "TZ"
                }
            """.trimIndent(),
        )

        val today = LocalDate.now()
        mockMvc.perform(
            post("/api/v1/properties/${fixture.propertyId}/guests/$guestId/identity-documents/manual-verification")
                .secureJson(
                    """
                    {
                      "documentType": "NIDA",
                      "documentNumber": "19900101123456789000",
                      "issuingCountry": "TZ",
                      "attestationReason": "Physical NIDA card inspected at reception"
                    }
                    """.trimIndent(),
                )
                .headersFor(
                    fixture,
                    "corr-identity-${fixture.tenantId}",
                    "idem-identity-${fixture.tenantId}",
                ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.document.verificationStatus").value("VERIFIED"))
            .andExpect(jsonPath("$.document.maskedDocumentNumber").value("***9000"))

        val reservationId = postForId(
            fixture = fixture,
            path = "/api/v1/properties/${fixture.propertyId}/reservations",
            idempotencyKey = "idem-reservation-${fixture.tenantId}",
            idField = "reservationId",
            json = """
                {
                  "primaryGuestId": "$guestId",
                  "roomTypeId": "${fixture.roomTypeId}",
                  "roomId": "${fixture.roomId}",
                  "checkInDate": "$today",
                  "checkOutDate": "${today.plusDays(1)}",
                  "adults": 1,
                  "children": 0,
                  "ratePerNight": 100.00
                }
            """.trimIndent(),
        )

        val folioId = requireNotNull(
            jdbcTemplate.queryForObject(
                "SELECT id FROM folios WHERE tenant_id = ? AND reservation_id = ?",
                UUID::class.java,
                fixture.tenantId,
                reservationId,
            ),
        )

        val stayId = postForId(
            fixture = fixture,
            path = "/api/v1/properties/${fixture.propertyId}/checkins",
            idempotencyKey = "idem-checkin-${fixture.tenantId}",
            idField = "stayId",
            json = """
                {
                  "reservationId": "$reservationId",
                  "roomId": "${fixture.roomId}"
                }
            """.trimIndent(),
        )

        mockMvc.perform(
            get("/api/v1/properties/${fixture.propertyId}/folios/$folioId")
                .secureJson()
                .headersFor(fixture, "corr-folio-view"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.totalAmount").value(118.00))
            .andExpect(jsonPath("$.charges[0].chargeType").value("ROOM"))

        val cashSessionId = postForId(
            fixture = fixture,
            path = "/api/v1/properties/${fixture.propertyId}/payments/cash-sessions",
            idempotencyKey = "idem-cash-session-${fixture.tenantId}",
            idField = "id",
            json = """{"openingFloat": 0.00}""",
        )

        postForId(
            fixture = fixture,
            path = "/api/v1/properties/${fixture.propertyId}/payments/cash",
            idempotencyKey = "idem-payment-${fixture.tenantId}",
            idField = "id",
            json = """
                {
                  "folioId": "$folioId",
                  "cashSessionId": "$cashSessionId",
                  "amount": 118.00
                }
            """.trimIndent(),
        )

        postForId(
            fixture = fixture,
            path = "/api/v1/properties/${fixture.propertyId}/fiscal/provider-configs",
            idempotencyKey = "idem-fiscal-config-${fixture.tenantId}",
            idField = "id",
            json = """
                {
                  "providerCode": "contract_mock",
                  "providerName": "Contract Fiscal Provider",
                  "environment": "sandbox",
                  "endpointUrl": "https://fiscal.test.invalid",
                  "secretRef": "literal:fiscal-test-secret",
                  "taxpayerIdentifier": "TIN-${fixture.tenantId.toString().take(8)}",
                  "isDefault": true
                }
            """.trimIndent(),
        )

        val invoiceId = postForId(
            fixture = fixture,
            path = "/api/v1/properties/${fixture.propertyId}/folios/$folioId/invoice",
            idempotencyKey = "idem-invoice-${fixture.tenantId}",
            idField = "id",
            json = """{"dueDateDays": 0}""",
        )
        outboxWorkerProcessor.processBatchBlocking(OutboxDestination.FISCAL)
        mockMvc.perform(
            get("/api/v1/properties/${fixture.propertyId}/fiscal/receipts")
                .secureJson()
                .headersFor(fixture, "corr-fiscal-receipts"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].invoiceId").value(invoiceId.toString()))
            .andExpect(jsonPath("$[0].status").value("accepted"))

        mockMvc.perform(
            post("/api/v1/properties/${fixture.propertyId}/payments/cash-sessions/$cashSessionId/close")
                .secureJson("""{"actualCash": 118.00}""")
                .headersFor(
                    fixture,
                    "corr-cash-session-close",
                    "idem-cash-session-close-${fixture.tenantId}",
                ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("closed"))
            .andExpect(jsonPath("$.variance").value(0.00))

        mockMvc.perform(
            post("/api/v1/properties/${fixture.propertyId}/checkouts/$stayId")
                .secureJson("{}")
                .headersFor(fixture, "corr-checkout", "idem-checkout-${fixture.tenantId}"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("checked_out"))

        mockMvc.perform(
            get("/api/v1/properties/${fixture.propertyId}/stays/$stayId")
                .secureJson()
                .headersFor(fixture, "corr-stay-view"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("checked_out"))

        mockMvc.perform(
            get("/api/v1/properties/${fixture.propertyId}/invoices/$invoiceId")
                .secureJson()
                .headersFor(fixture, "corr-invoice-view"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("issued"))
            .andExpect(jsonPath("$.total").value(118.00))

        mockMvc.perform(
            post("/api/v1/properties/${fixture.propertyId}/invoices/$invoiceId/void")
                .secureJson(
                    """
                    {"reason": "Fiscalized invoice cannot be directly voided"}
                    """.trimIndent(),
                )
                .headersFor(
                    fixture,
                    "corr-fiscalized-invoice-void",
                    "idem-fiscalized-invoice-void-${fixture.tenantId}",
                ),
        )
            .andExpect(status().isBadRequest)

        val invoiceItemId = requireNotNull(
            jdbcTemplate.queryForObject(
                """
                SELECT id
                FROM invoice_items
                WHERE tenant_id = ? AND invoice_id = ?
                ORDER BY created_at
                LIMIT 1
                """.trimIndent(),
                UUID::class.java,
                fixture.tenantId,
                invoiceId,
            ),
        )
        val creditNoteId = postForId(
            fixture = fixture,
            path = "/api/v1/properties/${fixture.propertyId}/invoices/" +
                    "$invoiceId/credit-notes",
            idempotencyKey = "idem-credit-note-${fixture.tenantId}",
            idField = "id",
            json = """
                {
                  "reason": "Correct part of the fiscalized room charge",
                  "lines": [
                    {
                      "invoiceItemId": "$invoiceItemId",
                      "amount": 50.00,
                      "taxAmount": 9.00
                    }
                  ]
                }
            """.trimIndent(),
        )
        outboxWorkerProcessor.processBatchBlocking(OutboxDestination.FISCAL)
        kotlin.test.assertEquals(
            "accepted",
            jdbcTemplate.queryForObject(
                """
                SELECT status
                FROM fiscal_corrections
                WHERE tenant_id = ? AND credit_note_id = ?
                """.trimIndent(),
                String::class.java,
                fixture.tenantId,
                creditNoteId,
            ),
        )

        val auditResult = mockMvc.perform(
            post("/api/v1/properties/${fixture.propertyId}/night-audit")
                .secureJson("""{"auditDate": "$today"}""")
                .headersFor(fixture, "corr-night-audit", "idem-night-audit-${fixture.tenantId}"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("ready"))
            .andExpect(jsonPath("$.issues.length()").value(0))
            .andReturn()
        val auditRunId = UUID.fromString(
            Regex(""""id"\s*:\s*"([^"]+)"""")
                .find(auditResult.response.contentAsString)
                ?.groupValues
                ?.get(1)
                ?: error("Night audit response did not contain id"),
        )
        mockMvc.perform(
            post(
                "/api/v1/properties/${fixture.propertyId}/night-audit/$auditRunId/complete",
            )
                .secureJson("{}")
                .headersFor(
                    fixture,
                    "corr-night-audit-complete",
                    "idem-night-audit-complete-${fixture.tenantId}",
                ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("completed"))
            .andExpect(jsonPath("$.reportGenerationQueued").value(true))
        mockMvc.perform(
            get(
                "/api/v1/properties/${fixture.propertyId}/night-audit/" +
                    "$auditRunId/close-snapshot",
            ).headersFor(
                fixture,
                "corr-night-audit-snapshot",
                idempotencyKey = null,
            ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.payloadHash").isString)
            .andExpect(jsonPath("$.revenueJournalDifference").value(0.0))
            .andExpect(jsonPath("$.paymentAllocationDifference").value(0.0))
    }

    @Test
    fun recordsManualMobileMoneyAndCreatesAppendOnlyCashReversal() {
        val fixture = phase3Fixture()
        insertAuthorizedFixture(fixture)
        val folioId = UUID.randomUUID()
        val chargeId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO folios (id, tenant_id, property_id, folio_type, status)
            VALUES (?, ?, ?, 'guest', 'open')
            """.trimIndent(),
            folioId,
            fixture.tenantId,
            fixture.propertyId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO folio_charges (
                id, tenant_id, property_id, folio_id, charge_type, description,
                quantity, unit_price, subtotal, tax_rate, tax_amount, amount,
                posted_by, status
            )
            VALUES (?, ?, ?, ?, 'MISC', 'Payment workflow test', 1, 200, 200, 0, 0, 200, ?, 'POSTED')
            """.trimIndent(),
            chargeId,
            fixture.tenantId,
            fixture.propertyId,
            folioId,
            fixture.tenantUserId,
        )
        jdbcTemplate.queryForList("SELECT recalculate_folio_totals(?)", folioId)

        val providerAccountId = postForId(
            fixture = fixture,
            path = "/api/v1/properties/${fixture.propertyId}/payments/provider-accounts",
            idempotencyKey = "idem-payment-provider-${fixture.tenantId}",
            idField = "id",
            json = """
                {
                  "providerCode": "clickpesa",
                  "providerName": "ClickPesa",
                  "accountName": "Manual Reference Account",
                  "clientId": "MERCHANT-001",
                  "apiKeySecretRef": "literal:payment-test-secret",
                  "checksumKeySecretRef": "literal:webhook-test-secret",
                  "endpointUrl": "https://api.clickpesa.com/third-parties",
                  "isDefault": true
                }
            """.trimIndent(),
        )
        val manualTransactionId = postForId(
            fixture = fixture,
            path = "/api/v1/properties/${fixture.propertyId}/payments/mobile-money/manual-reference",
            idempotencyKey = "idem-manual-payment-${fixture.tenantId}",
            idField = "id",
            json = """
                {
                  "folioId": "$folioId",
                  "providerAccountId": "$providerAccountId",
                  "referenceNumber": "MANUAL-${fixture.tenantId.toString().take(12)}",
                  "phoneNumber": "+255712345678",
                  "amount": 120.00
                }
            """.trimIndent(),
        )
        mockMvc.perform(
            get("/api/v1/properties/${fixture.propertyId}/payments/transactions/$manualTransactionId")
                .secureJson()
                .headersFor(fixture, "corr-manual-payment-view"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("POSTED"))
            .andExpect(jsonPath("$.providerReference").exists())

        val cashSessionId = postForId(
            fixture = fixture,
            path = "/api/v1/properties/${fixture.propertyId}/payments/cash-sessions",
            idempotencyKey = "idem-reversal-cash-session-${fixture.tenantId}",
            idField = "id",
            json = """{"openingFloat": 100.00}""",
        )
        val cashTransactionId = postForId(
            fixture = fixture,
            path = "/api/v1/properties/${fixture.propertyId}/payments/cash",
            idempotencyKey = "idem-reversal-cash-${fixture.tenantId}",
            idField = "id",
            json = """
                {
                  "folioId": "$folioId",
                  "cashSessionId": "$cashSessionId",
                  "amount": 80.00
                }
            """.trimIndent(),
        )
        val reversalId = postForId(
            fixture = fixture,
            path = "/api/v1/properties/${fixture.propertyId}/payments/transactions/$cashTransactionId/reverse",
            idempotencyKey = "idem-cash-reversal-${fixture.tenantId}",
            idField = "id",
            json = """
                {
                  "reason": "Guest payment was entered against the wrong folio",
                  "cashSessionId": "$cashSessionId"
                }
            """.trimIndent(),
        )

        mockMvc.perform(
            get("/api/v1/properties/${fixture.propertyId}/payments/transactions/$reversalId")
                .secureJson()
                .headersFor(fixture, "corr-cash-reversal-view"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.transactionType").value("reversal"))
            .andExpect(jsonPath("$.status").value("POSTED"))
            .andExpect(jsonPath("$.reversalOfTransactionId").value(cashTransactionId.toString()))
        kotlin.test.assertEquals(
            0,
            jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM payment_transactions
                WHERE tenant_id = ? AND id IN (?, ?) AND amount <= 0
                """.trimIndent(),
                Int::class.java,
                fixture.tenantId,
                cashTransactionId,
                reversalId,
            ),
        )
        kotlin.test.assertEquals(
            java.math.BigDecimal("120.00"),
            jdbcTemplate.queryForObject(
                "SELECT total_paid FROM folios WHERE id = ?",
                java.math.BigDecimal::class.java,
                folioId,
            ),
        )
        kotlin.test.assertEquals(
            java.math.BigDecimal("100.00"),
            jdbcTemplate.queryForObject(
                "SELECT expected_cash FROM cash_sessions WHERE id = ?",
                java.math.BigDecimal::class.java,
                cashSessionId,
            ),
        )

        val gatewayTransactionId = postForId(
            fixture = fixture,
            path = "/api/v1/properties/${fixture.propertyId}/payments/mobile-money",
            idempotencyKey = "idem-gateway-payment-${fixture.tenantId}",
            idField = "id",
            json = """
                {
                  "folioId": "$folioId",
                  "providerAccountId": "$providerAccountId",
                  "phoneNumber": "+255712345678",
                  "amount": 80.00
                }
            """.trimIndent(),
        )
        val clickPesaTransactionId = "CP-${UUID.randomUUID()}"
        jdbcTemplate.update(
            """
            UPDATE payment_transactions
            SET status = 'pending',
                provider_reference = ?,
                provider_status = 'PROCESSING'
            WHERE tenant_id = ? AND id = ? AND status = 'created'
            """.trimIndent(),
            clickPesaTransactionId,
            fixture.tenantId,
            gatewayTransactionId,
        )
        val gatewayTransaction = jdbcTemplate.queryForMap(
            """
            SELECT internal_reference, provider_reference, status
            FROM payment_transactions
            WHERE tenant_id = ? AND id = ?
            """.trimIndent(),
            fixture.tenantId,
            gatewayTransactionId,
        )
        kotlin.test.assertEquals("pending", gatewayTransaction["status"])
        val providerTimestamp = Instant.now().toString()
        val canonicalWebhookPayload = """
            {"data":{"clientId":"MERCHANT-001","collectedAmount":"80.00","collectedCurrency":"TZS","id":"$clickPesaTransactionId","orderReference":"${gatewayTransaction["internal_reference"]}","status":"SUCCESS","updatedAt":"$providerTimestamp"},"event":"PAYMENT RECEIVED"}
        """.trimIndent()
        val checksum = hmacSha256Hex(
            secret = "webhook-test-secret",
            value = canonicalWebhookPayload,
        )
        val webhookPayload = """
            {
              "event": "PAYMENT RECEIVED",
              "data": {
                "id": "$clickPesaTransactionId",
                "status": "SUCCESS",
                "orderReference": "${gatewayTransaction["internal_reference"]}",
                "collectedAmount": "80.00",
                "collectedCurrency": "TZS",
                "updatedAt": "$providerTimestamp",
                "clientId": "MERCHANT-001"
              },
              "checksumMethod": "HMAC-SHA256",
              "checksum": "$checksum"
            }
        """.trimIndent()
        val invalidWebhookPayload = webhookPayload.replace(checksum, "00")

        mockMvc.perform(
            post("/api/v1/payments/webhooks/clickpesa/$providerAccountId")
                .secureJson(invalidWebhookPayload)
                .header(PeakRequestHeaders.CORRELATION_ID, "corr-payment-webhook-invalid")
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errorCode").value("PAYMENT_REJECTED"))

        repeat(2) { attempt ->
            mockMvc.perform(
                post("/api/v1/payments/webhooks/clickpesa/$providerAccountId")
                    .secureJson(webhookPayload)
                    .header(PeakRequestHeaders.CORRELATION_ID, "corr-payment-webhook-$attempt"),
            )
                .andExpect(status().isOk)
                .andExpect(jsonPath("$.status").value("POSTED"))
                .andExpect(jsonPath("$.replayed").value(attempt == 1))
        }
        kotlin.test.assertEquals(
            java.math.BigDecimal("200.00"),
            jdbcTemplate.queryForObject(
                "SELECT total_paid FROM folios WHERE id = ?",
                java.math.BigDecimal::class.java,
                folioId,
            ),
        )
        kotlin.test.assertEquals(
            1,
            jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM folio_payments
                WHERE tenant_id = ? AND payment_transaction_id = ?
                """.trimIndent(),
                Int::class.java,
                fixture.tenantId,
                gatewayTransactionId,
            ),
        )

        val reconciliationId = postForId(
            fixture = fixture,
            path = "/api/v1/properties/${fixture.propertyId}/payments/reconciliations",
            idempotencyKey = "idem-reconciliation-${fixture.tenantId}",
            idField = "id",
            json = """
                {
                  "providerAccountId": "$providerAccountId",
                  "reconciliationDate": "${LocalDate.now()}",
                  "statementReference": "STATEMENT-${fixture.tenantId}",
                  "items": [
                    {
                      "providerReference": "$clickPesaTransactionId",
                      "itemDate": "$providerTimestamp",
                      "providerAmount": 80.00
                    }
                  ]
                }
            """.trimIndent(),
        )
        mockMvc.perform(
            get(
                "/api/v1/properties/${fixture.propertyId}/payments/" +
                        "reconciliations/$reconciliationId",
            )
                .secureJson()
                .headersFor(fixture, "corr-reconciliation-view"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("matched"))
        mockMvc.perform(
            post(
                "/api/v1/properties/${fixture.propertyId}/payments/" +
                        "reconciliations/$reconciliationId/approve",
            )
                .secureJson("{}")
                .headersFor(
                    fixture,
                    "corr-reconciliation-approve",
                    "idem-reconciliation-approve-${fixture.tenantId}",
                ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("approved"))
        kotlin.test.assertEquals(
            "reconciled",
            jdbcTemplate.queryForObject(
                "SELECT status FROM payment_transactions WHERE id = ?",
                String::class.java,
                gatewayTransactionId,
            ),
        )
        val preFiscalInvoiceId = postForId(
            fixture,
            "/api/v1/properties/${fixture.propertyId}/folios/$folioId/invoice",
            "prefiscal-invoice-${fixture.tenantId}",
            "id",
            """{"dueDateDays":0}""",
        )
        mockMvc.perform(
            post(
                "/api/v1/properties/${fixture.propertyId}/invoices/" +
                        "$preFiscalInvoiceId/void",
            )
                .secureJson(
                    """
                    {"reason":"Invoice was issued before the guest confirmed charges"}
                    """.trimIndent(),
                )
                .headersFor(
                    fixture,
                    "corr-prefiscal-void",
                    "idem-prefiscal-void-${fixture.tenantId}",
                ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("voided"))
        outboxWorkerProcessor.processBatchBlocking(OutboxDestination.FISCAL)
        kotlin.test.assertEquals(
            "delivered",
            jdbcTemplate.queryForObject(
                """
                SELECT status
                FROM outbox_events
                WHERE tenant_id = ?
                  AND aggregate_id = ?
                  AND destination = 'fiscal'
                """.trimIndent(),
                String::class.java,
                fixture.tenantId,
                preFiscalInvoiceId,
            ),
        )

        mockMvc.perform(
            post(
                "/api/v1/properties/${fixture.propertyId}/payments/transactions/" +
                        "$gatewayTransactionId/refund",
            )
                .secureJson(
                    """
                    {
                      "amount": 30.00,
                      "reason": "Guest was charged for a service that was not delivered",
                      "providerEvidence": "CP-REFUND-PARTIAL-${fixture.tenantId}"
                    }
                    """.trimIndent(),
                )
                .headersFor(
                    fixture,
                    "corr-mobile-refund-partial",
                    "idem-mobile-refund-partial-${fixture.tenantId}",
                ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("POSTED"))
            .andExpect(jsonPath("$.refundOfTransactionId").value(gatewayTransactionId.toString()))

        kotlin.test.assertEquals(
            mapOf(
                "status" to "partially_refunded",
                "refunded_amount" to java.math.BigDecimal("30.00"),
            ),
            jdbcTemplate.queryForMap(
                """
                SELECT status, refunded_amount
                FROM payment_transactions
                WHERE tenant_id = ? AND id = ?
                """.trimIndent(),
                fixture.tenantId,
                gatewayTransactionId,
            ),
        )

        mockMvc.perform(
            post(
                "/api/v1/properties/${fixture.propertyId}/payments/transactions/" +
                        "$gatewayTransactionId/refund",
            )
                .secureJson(
                    """
                    {
                      "amount": 51.00,
                      "reason": "This amount exceeds the remaining refundable balance",
                      "providerEvidence": "CP-REFUND-EXCESS-${fixture.tenantId}"
                    }
                    """.trimIndent(),
                )
                .headersFor(
                    fixture,
                    "corr-mobile-refund-excess",
                    "idem-mobile-refund-excess-${fixture.tenantId}",
                ),
        )
            .andExpect(status().isBadRequest)

        mockMvc.perform(
            post(
                "/api/v1/properties/${fixture.propertyId}/payments/transactions/" +
                        "$gatewayTransactionId/refund",
            )
                .secureJson(
                    """
                    {
                      "amount": 50.00,
                      "reason": "Refund the remaining mobile money collection balance",
                      "providerEvidence": "CP-REFUND-FULL-${fixture.tenantId}"
                    }
                    """.trimIndent(),
                )
                .headersFor(
                    fixture,
                    "corr-mobile-refund-full",
                    "idem-mobile-refund-full-${fixture.tenantId}",
                ),
        )
            .andExpect(status().isOk)

        kotlin.test.assertEquals(
            mapOf(
                "status" to "refunded",
                "refunded_amount" to java.math.BigDecimal("80.00"),
            ),
            jdbcTemplate.queryForMap(
                """
                SELECT status, refunded_amount
                FROM payment_transactions
                WHERE tenant_id = ? AND id = ?
                """.trimIndent(),
                fixture.tenantId,
                gatewayTransactionId,
            ),
        )
    }

    @Test
    fun blocksCheckInUntilEveryAdultOccupantIsVerifiedWithoutPersistingRawIdentity() {
        val fixture = phase3Fixture()
        insertAuthorizedFixture(fixture)
        val primaryGuestId = postForId(
            fixture,
            "/api/v1/properties/${fixture.propertyId}/guests",
            "identity-primary-${fixture.tenantId}",
            "id",
            """
            {
              "fullName": "Primary Identity Guest",
              "dateOfBirth": "1990-01-01",
              "nationality": "TZ"
            }
            """.trimIndent(),
        )
        val additionalGuestId = postForId(
            fixture,
            "/api/v1/properties/${fixture.propertyId}/guests",
            "identity-additional-${fixture.tenantId}",
            "id",
            """
            {
              "fullName": "Additional Identity Guest",
              "dateOfBirth": "1992-02-02",
              "nationality": "KE"
            }
            """.trimIndent(),
        )
        val today = LocalDate.now()
        val reservationId = postForId(
            fixture,
            "/api/v1/properties/${fixture.propertyId}/reservations",
            "identity-reservation-${fixture.tenantId}",
            "reservationId",
            """
            {
              "primaryGuestId": "$primaryGuestId",
              "roomTypeId": "${fixture.roomTypeId}",
              "roomId": "${fixture.roomId}",
              "checkInDate": "$today",
              "checkOutDate": "${today.plusDays(1)}",
              "adults": 2,
              "children": 0,
              "ratePerNight": 100.00
            }
            """.trimIndent(),
        )
        mockMvc.perform(
            post("/api/v1/properties/${fixture.propertyId}/reservations/$reservationId/guests")
                .secureJson(
                    """{"guestId":"$additionalGuestId","relationship":"ADULT"}""",
                )
                .headersFor(
                    fixture,
                    "corr-additional-${fixture.tenantId}",
                    "identity-additional-occupant-${fixture.tenantId}",
                ),
        ).andExpect(status().isOk)
        mockMvc.perform(
            get("/api/v1/properties/${fixture.propertyId}/reservations/$reservationId/guests")
                .secureJson()
                .headersFor(fixture, "corr-list-occupants-${fixture.tenantId}"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(2))

        manuallyVerify(
            fixture,
            primaryGuestId,
            "NIDA",
            "19900101123456781234",
            "TZ",
            "identity-verify-primary-${fixture.tenantId}",
        )
        manuallyVerify(
            fixture,
            primaryGuestId,
            "NIDA",
            "19900101123456781234",
            "TZ",
            "identity-verify-primary-${fixture.tenantId}",
            replayed = true,
        )
        kotlin.test.assertEquals(
            1,
            jdbcTemplate.queryForObject(
                """
                SELECT count(*) FROM guest_identity_verification_attempts
                WHERE tenant_id = ? AND guest_id = ?
                """.trimIndent(),
                Int::class.java,
                fixture.tenantId,
                primaryGuestId,
            ),
        )

        mockMvc.perform(
            post("/api/v1/properties/${fixture.propertyId}/checkins")
                .secureJson("""{"reservationId":"$reservationId","roomId":"${fixture.roomId}"}""")
                .headersFor(
                    fixture,
                    "corr-blocked-checkin-${fixture.tenantId}",
                    "identity-blocked-checkin-${fixture.tenantId}",
                ),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.errorCode").value("GUEST_IDENTITY_INCOMPLETE"))

        kotlin.test.assertEquals(
            "confirmed",
            jdbcTemplate.queryForObject(
                "SELECT status FROM reservations WHERE tenant_id = ? AND id = ?",
                String::class.java,
                fixture.tenantId,
                reservationId,
            ),
        )
        kotlin.test.assertEquals(
            0,
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM stays WHERE tenant_id = ? AND reservation_id = ?",
                Int::class.java,
                fixture.tenantId,
                reservationId,
            ),
        )

        manuallyVerify(
            fixture,
            additionalGuestId,
            "PASSPORT",
            "A123456789",
            "KE",
            "identity-verify-additional-${fixture.tenantId}",
        )

        mockMvc.perform(
            get("/api/v1/properties/${fixture.propertyId}/reservations/$reservationId/identity-readiness")
                .secureJson()
                .headersFor(fixture, "corr-ready-${fixture.tenantId}"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.ready").value(true))
            .andExpect(jsonPath("$.occupants.length()").value(2))

        postForId(
            fixture,
            "/api/v1/properties/${fixture.propertyId}/checkins",
            "identity-successful-checkin-${fixture.tenantId}",
            "stayId",
            """{"reservationId":"$reservationId","roomId":"${fixture.roomId}"}""",
        )

        kotlin.test.assertEquals(
            0,
            jdbcTemplate.queryForObject(
                """
                SELECT count(*) FROM guest_documents
                WHERE tenant_id = ? AND document_number IN (?, ?)
                """.trimIndent(),
                Int::class.java,
                fixture.tenantId,
                "19900101123456781234",
                "A123456789",
            ),
        )
        kotlin.test.assertEquals(
            0,
            jdbcTemplate.queryForObject(
                """
                SELECT
                    (SELECT count(*) FROM audit_logs
                     WHERE tenant_id = ? AND new_values::text LIKE ?)
                  + (SELECT count(*) FROM outbox_events
                     WHERE tenant_id = ? AND payload::text LIKE ?)
                  + (SELECT count(*) FROM idempotency_keys
                     WHERE tenant_id = ? AND response_body::text LIKE ?)
                """.trimIndent(),
                Int::class.java,
                fixture.tenantId,
                "%19900101123456781234%",
                fixture.tenantId,
                "%19900101123456781234%",
                fixture.tenantId,
                "%19900101123456781234%",
            ),
        )
    }

    @Test
    fun recordsUnavailableOnlineNidaVerificationWithoutLeakingTheNin() {
        val fixture = phase3Fixture()
        insertAuthorizedFixture(fixture)
        val guestId = postForId(
            fixture,
            "/api/v1/properties/${fixture.propertyId}/guests",
            "nida-unavailable-guest-${fixture.tenantId}",
            "id",
            """
            {
              "fullName": "NIDA Unavailable Guest",
              "dateOfBirth": "1990-01-01",
              "nationality": "TZ"
            }
            """.trimIndent(),
        )
        val rawNin = "19900101123456785678"
        mockMvc.perform(
            post("/api/v1/properties/${fixture.propertyId}/guests/$guestId/identity-documents/verify")
                .secureJson(
                    """
                    {
                      "documentType": "NIDA",
                      "documentNumber": "$rawNin",
                      "issuingCountry": "TZ"
                    }
                    """.trimIndent(),
                )
                .headersFor(
                    fixture,
                    "corr-nida-unavailable-${fixture.tenantId}",
                    "nida-unavailable-${fixture.tenantId}",
                ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.failureCode").value("NIDA_NOT_CONFIGURED"))
            .andExpect(jsonPath("$.document.verificationStatus").value("FAILED"))
            .andExpect(jsonPath("$.document.maskedDocumentNumber").value("***5678"))

        kotlin.test.assertEquals(
            "unavailable",
            jdbcTemplate.queryForObject(
                """
                SELECT status FROM guest_identity_verification_attempts
                WHERE tenant_id = ? AND guest_id = ?
                """.trimIndent(),
                String::class.java,
                fixture.tenantId,
                guestId,
            ),
        )
        kotlin.test.assertEquals(
            0,
            jdbcTemplate.queryForObject(
                """
                SELECT count(*) FROM guest_documents
                WHERE tenant_id = ? AND document_number = ?
                """.trimIndent(),
                Int::class.java,
                fixture.tenantId,
                rawNin,
            ),
        )
    }

    @Test
    fun revokedIdentityImmediatelyRemovesReservationReadiness() {
        val fixture = phase3Fixture()
        insertAuthorizedFixture(fixture)
        val guestId = postForId(
            fixture,
            "/api/v1/properties/${fixture.propertyId}/guests",
            "revoked-identity-guest-${fixture.tenantId}",
            "id",
            """
            {
              "fullName": "Revoked Identity Guest",
              "dateOfBirth": "1990-01-01",
              "nationality": "TZ"
            }
            """.trimIndent(),
        )
        manuallyVerify(
            fixture,
            guestId,
            "NIDA",
            "19900101123456789999",
            "TZ",
            "revoked-identity-verify-${fixture.tenantId}",
        )
        val today = LocalDate.now()
        val reservationId = postForId(
            fixture,
            "/api/v1/properties/${fixture.propertyId}/reservations",
            "revoked-identity-reservation-${fixture.tenantId}",
            "reservationId",
            """
            {
              "primaryGuestId": "$guestId",
              "roomTypeId": "${fixture.roomTypeId}",
              "roomId": "${fixture.roomId}",
              "checkInDate": "$today",
              "checkOutDate": "${today.plusDays(1)}",
              "adults": 1,
              "children": 0,
              "ratePerNight": 100.00
            }
            """.trimIndent(),
        )
        val documentId = requireNotNull(
            jdbcTemplate.queryForObject(
                "SELECT id FROM guest_documents WHERE tenant_id = ? AND guest_id = ?",
                UUID::class.java,
                fixture.tenantId,
                guestId,
            ),
        )
        mockMvc.perform(
            post("/api/v1/properties/${fixture.propertyId}/guests/$guestId/identity-documents/$documentId/revoke")
                .secureJson("""{"reason":"Identity document reported invalid"}""")
                .headersFor(
                    fixture,
                    "corr-revoke-${fixture.tenantId}",
                    "revoke-${fixture.tenantId}",
                ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.verificationStatus").value("REVOKED"))

        mockMvc.perform(
            get("/api/v1/properties/${fixture.propertyId}/reservations/$reservationId/identity-readiness")
                .secureJson()
                .headersFor(fixture, "corr-revoked-readiness-${fixture.tenantId}"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.ready").value(false))
            .andExpect(
                jsonPath(
                    "$.occupants[0].reasons",
                    hasItem("valid_verified_identity_required"),
                ),
            )
    }

    @Test
    fun unpaidCheckoutLeavesFolioOpenAndNightAuditNonOverridable() {
        val fixture = phase3Fixture()
        insertAuthorizedFixture(fixture)
        val guestId = postForId(
            fixture,
            "/api/v1/properties/${fixture.propertyId}/guests",
            "unpaid-guest-${fixture.tenantId}",
            "id",
            """
            {
              "fullName": "Unpaid Checkout Guest",
              "dateOfBirth": "1990-01-01",
              "nationality": "TZ"
            }
            """.trimIndent(),
        )
        manuallyVerify(
            fixture,
            guestId,
            "PASSPORT",
            "P${fixture.tenantId.toString().replace("-", "").take(12)}",
            "TZ",
            "unpaid-identity-${fixture.tenantId}",
        )
        val today = LocalDate.now()
        val reservationId = postForId(
            fixture,
            "/api/v1/properties/${fixture.propertyId}/reservations",
            "unpaid-reservation-${fixture.tenantId}",
            "reservationId",
            """
            {
              "primaryGuestId": "$guestId",
              "roomTypeId": "${fixture.roomTypeId}",
              "roomId": "${fixture.roomId}",
              "checkInDate": "$today",
              "checkOutDate": "${today.plusDays(1)}",
              "adults": 1,
              "children": 0,
              "ratePerNight": 100.00
            }
            """.trimIndent(),
        )
        val stayId = postForId(
            fixture,
            "/api/v1/properties/${fixture.propertyId}/checkins",
            "unpaid-checkin-${fixture.tenantId}",
            "stayId",
            """
            {"reservationId":"$reservationId","roomId":"${fixture.roomId}"}
            """.trimIndent(),
        )
        val folioId = requireNotNull(
            jdbcTemplate.queryForObject(
                "SELECT id FROM folios WHERE tenant_id = ? AND reservation_id = ?",
                UUID::class.java,
                fixture.tenantId,
                reservationId,
            ),
        )

        mockMvc.perform(
            post("/api/v1/properties/${fixture.propertyId}/checkouts/$stayId")
                .secureJson("{}")
                .headersFor(
                    fixture,
                    "corr-unpaid-normal-checkout",
                    "idem-unpaid-normal-checkout-${fixture.tenantId}",
                ),
        )
            .andExpect(status().isConflict)
        mockMvc.perform(
            post(
                "/api/v1/properties/${fixture.propertyId}/checkouts/" +
                        "$stayId/unpaid-override",
            )
                .secureJson(
                    """
                    {
                      "reason": "Supervisor approved emergency departure with debt follow-up"
                    }
                    """.trimIndent(),
                )
                .headersFor(
                    fixture,
                    "corr-unpaid-override",
                    "idem-unpaid-override-${fixture.tenantId}",
                ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("checked_out"))

        mockMvc.perform(
            get("/api/v1/properties/${fixture.propertyId}/folios/$folioId")
                .secureJson()
                .headersFor(fixture, "corr-unpaid-folio"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("open"))
            .andExpect(jsonPath("$.balanceDue").value(118.00))

        val audit = mockMvc.perform(
            post("/api/v1/properties/${fixture.propertyId}/night-audit")
                .secureJson("""{"auditDate":"$today"}""")
                .headersFor(
                    fixture,
                    "corr-unpaid-night-audit",
                    "idem-unpaid-night-audit-${fixture.tenantId}",
                ),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("blocked"))
            .andExpect(
                jsonPath(
                    "$.issues[*].issueCode",
                    hasItem("open_unpaid_folios"),
                ),
            )
            .andReturn()
        val runId = UUID.fromString(
            Regex(""""id"\s*:\s*"([^"]+)"""")
                .find(audit.response.contentAsString)!!
                .groupValues[1],
        )
        val issueId = requireNotNull(
            jdbcTemplate.queryForObject(
                """
                SELECT id
                FROM night_audit_issues
                WHERE tenant_id = ?
                  AND run_id = ?
                  AND issue_code = 'open_unpaid_folios'
                """.trimIndent(),
                UUID::class.java,
                fixture.tenantId,
                runId,
            ),
        )
        mockMvc.perform(
            post(
                "/api/v1/properties/${fixture.propertyId}/night-audit/" +
                        "$runId/issues/$issueId/override",
            )
                .secureJson(
                    """
                    {"reason":"Attempted supervisor override of unpaid debt"}
                    """.trimIndent(),
                )
                .headersFor(
                    fixture,
                    "corr-unpaid-issue-override",
                    "idem-unpaid-issue-override-${fixture.tenantId}",
                ),
        )
            .andExpect(status().isConflict)
        mockMvc.perform(
            post(
                "/api/v1/properties/${fixture.propertyId}/night-audit/" +
                        "$runId/complete",
            )
                .secureJson("{}")
                .headersFor(
                    fixture,
                    "corr-unpaid-complete",
                    "idem-unpaid-complete-${fixture.tenantId}",
                ),
        )
            .andExpect(status().isConflict)
    }

    @Test
    fun preventsCrossPropertyGuestAndIdentityAccess() {
        val fixture = phase3Fixture()
        insertAuthorizedFixture(fixture)
        val secondPropertyId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO properties (id, tenant_id, name, status, is_active, total_rooms)
            VALUES (?, ?, 'Second Property', 'active', true, 0)
            """.trimIndent(),
            secondPropertyId,
            fixture.tenantId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO property_modules (
                tenant_id, property_id, module_id, is_enabled, is_configured
            )
            VALUES (?, ?, 'reservations', true, true)
            """.trimIndent(),
            fixture.tenantId,
            secondPropertyId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO user_property_roles (user_id, property_id, role_id, tenant_id)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
            fixture.tenantUserId,
            secondPropertyId,
            fixture.propertyRoleId,
            fixture.tenantId,
        )
        val guestId = postForId(
            fixture,
            "/api/v1/properties/${fixture.propertyId}/guests",
            "property-isolated-guest-${fixture.tenantId}",
            "id",
            """
            {
              "fullName": "Property Isolated Guest",
              "dateOfBirth": "1990-01-01",
              "nationality": "TZ"
            }
            """.trimIndent(),
        )

        mockMvc.perform(
            get("/api/v1/properties/$secondPropertyId/guests/$guestId")
                .secureJson()
                .headersFor(fixture, "corr-cross-property-guest-${fixture.tenantId}"),
        )
            .andExpect(status().isNotFound)

        mockMvc.perform(
            get("/api/v1/properties/$secondPropertyId/guests/$guestId/identity-documents")
                .secureJson()
                .headersFor(fixture, "corr-cross-property-identity-${fixture.tenantId}"),
        )
            .andExpect(status().isNotFound)
    }

    private fun manuallyVerify(
        fixture: Phase3Fixture,
        guestId: UUID,
        documentType: String,
        documentNumber: String,
        issuingCountry: String,
        idempotencyKey: String,
        replayed: Boolean = false,
    ) {
        val request = post(
            "/api/v1/properties/${fixture.propertyId}/guests/$guestId/identity-documents/manual-verification",
        )
            .secureJson(
                """
                {
                  "documentType": "$documentType",
                  "documentNumber": "$documentNumber",
                  "issuingCountry": "$issuingCountry",
                  "attestationReason": "Physical identity document inspected at reception"
                }
                """.trimIndent(),
            )
            .headersFor(fixture, "corr-$idempotencyKey", idempotencyKey)
        mockMvc.perform(request)
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.document.verificationStatus").value("VERIFIED"))
            .andExpect(jsonPath("$.replayed").value(replayed))
    }

    private fun postForId(
        fixture: Phase3Fixture,
        path: String,
        idempotencyKey: String,
        idField: String,
        json: String,
    ): UUID {
        val result = mockMvc.perform(
            post(path)
                .secureJson(json)
                .headersFor(fixture, "corr-$idempotencyKey", idempotencyKey),
        )
            .andExpect(status().is2xxSuccessful)
            .andReturn()

        val payload = result.response.contentAsString
        val id = Regex(""""$idField"\s*:\s*"([^"]+)"""").find(payload)
            ?.groupValues
            ?.get(1)
            ?: error("Response did not contain $idField: $payload")
        return UUID.fromString(id)
    }

    private fun phase3Fixture(): Phase3Fixture {
        return Phase3Fixture(
            planId = UUID.randomUUID(),
            tenantId = UUID.randomUUID(),
            tenantUserId = UUID.randomUUID(),
            tenantRoleId = UUID.randomUUID(),
            propertyRoleId = UUID.randomUUID(),
            propertyId = UUID.randomUUID(),
            roomTypeId = UUID.randomUUID(),
            roomId = UUID.randomUUID(),
        ).also { createdTenantIds += it.tenantId }
    }

    private fun insertAuthorizedFixture(fixture: Phase3Fixture) {
        jdbcTemplate.update(
            "INSERT INTO plans (id, name, code) VALUES (?, ?, ?)",
            fixture.planId,
            "Phase 3 Plan ${fixture.planId}",
            "phase3-${fixture.planId}",
        )
        jdbcTemplate.update(
            """
            INSERT INTO tenants (id, name, slug, status, schema_name, plan_id)
            VALUES (?, ?, ?, 'active', ?, ?)
            """.trimIndent(),
            fixture.tenantId,
            "Phase 3 Tenant ${fixture.tenantId}",
            "phase3-${fixture.tenantId}",
            "tenant_${fixture.tenantId}".replace("-", "_"),
            fixture.planId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO users (id, tenant_id, full_name, email, status, is_active)
            VALUES (?, ?, 'Phase 3 Operator', ?, 'active', true)
            """.trimIndent(),
            fixture.tenantUserId,
            fixture.tenantId,
            "phase3-${fixture.tenantUserId}@example.com",
        )
        jdbcTemplate.update(
            "INSERT INTO tenant_roles (id, tenant_id, name, code) VALUES (?, ?, 'Phase 3 Tenant Role', ?)",
            fixture.tenantRoleId,
            fixture.tenantId,
            "phase3-tenant-${fixture.tenantRoleId}",
        )
        jdbcTemplate.update(
            "INSERT INTO user_tenant_roles (user_id, tenant_id, tenant_role_id) VALUES (?, ?, ?)",
            fixture.tenantUserId,
            fixture.tenantId,
            fixture.tenantRoleId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO properties (id, tenant_id, name, status, is_active, total_rooms)
            VALUES (?, ?, 'Phase 3 Property', 'active', true, 1)
            """.trimIndent(),
            fixture.propertyId,
            fixture.tenantId,
        )
        MODULES.forEach { moduleId ->
            jdbcTemplate.update(
                "INSERT INTO tenant_modules (tenant_id, module_id, is_enabled, is_configured) VALUES (?, ?, true, true)",
                fixture.tenantId,
                moduleId,
            )
            jdbcTemplate.update(
                "INSERT INTO property_modules (tenant_id, property_id, module_id, is_enabled, is_configured) VALUES (?, ?, ?, true, true)",
                fixture.tenantId,
                fixture.propertyId,
                moduleId,
            )
        }
        jdbcTemplate.update(
            """
            INSERT INTO roles (id, tenant_id, name, is_system, is_active)
            VALUES (?, ?, 'Phase 3 Property Role', false, true)
            """.trimIndent(),
            fixture.propertyRoleId,
            fixture.tenantId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO user_property_roles (user_id, property_id, role_id, tenant_id)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
            fixture.tenantUserId,
            fixture.propertyId,
            fixture.propertyRoleId,
            fixture.tenantId,
        )
        PERMISSIONS.forEach { permissionCode ->
            val permissionId = UUID.randomUUID()
            jdbcTemplate.update(
                "INSERT INTO permissions (id, tenant_id, code, description) VALUES (?, ?, ?, ?)",
                permissionId,
                fixture.tenantId,
                permissionCode,
                "Permission $permissionCode",
            )
            jdbcTemplate.update(
                "INSERT INTO tenant_role_permissions (tenant_role_id, permission_id) VALUES (?, ?)",
                fixture.tenantRoleId,
                permissionId,
            )
            jdbcTemplate.update(
                "INSERT INTO role_permissions (role_id, permission_id) VALUES (?, ?)",
                fixture.propertyRoleId,
                permissionId,
            )
        }
        jdbcTemplate.update(
            """
            INSERT INTO room_types (id, tenant_id, property_id, name, code, base_price, max_adults, max_children, max_occupancy, is_active)
            VALUES (?, ?, ?, 'Standard', 'STD', 100.00, 2, 1, 3, true)
            """.trimIndent(),
            fixture.roomTypeId,
            fixture.tenantId,
            fixture.propertyId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO rooms (id, tenant_id, property_id, room_type_id, room_number, floor, status)
            VALUES (?, ?, ?, ?, '101', 1, 'vacant_clean')
            """.trimIndent(),
            fixture.roomId,
            fixture.tenantId,
            fixture.propertyId,
            fixture.roomTypeId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO tax_rates (tenant_id, name, code, rate, tax_type, applies_to, is_active)
            VALUES (?, 'VAT', 'VAT18', 0.18, 'vat', ARRAY['room'], true)
            """.trimIndent(),
            fixture.tenantId,
        )
    }

    private fun MockHttpServletRequestBuilder.secureJson(
        json: String? = null,
    ): MockHttpServletRequestBuilder {
        contentType(MediaType.APPLICATION_JSON)
        accept(MediaType.APPLICATION_JSON)
        if (json != null) {
            content(json)
        }
        return this
    }

    private fun MockHttpServletRequestBuilder.headersFor(
        fixture: Phase3Fixture,
        correlationId: String,
        idempotencyKey: String? = null,
    ): MockHttpServletRequestBuilder {
        header(PeakRequestHeaders.TENANT_ID, fixture.tenantId.toString())
        header(PeakRequestHeaders.TENANT_USER_ID, fixture.tenantUserId.toString())
        header(PeakRequestHeaders.CORRELATION_ID, correlationId)
        idempotencyKey?.let { header(PeakRequestHeaders.IDEMPOTENCY_KEY, it) }
        return this
    }

    private fun hmacSha256Hex(secret: String, value: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(StandardCharsets.UTF_8), "HmacSHA256"))
        return HexFormat.of().formatHex(mac.doFinal(value.toByteArray(StandardCharsets.UTF_8)))
    }

    private data class Phase3Fixture(
        val planId: UUID,
        val tenantId: UUID,
        val tenantUserId: UUID,
        val tenantRoleId: UUID,
        val propertyRoleId: UUID,
        val propertyId: UUID,
        val roomTypeId: UUID,
        val roomId: UUID,
    )

    private companion object {
        val MODULES = listOf(
            "reservations",
            "frontdesk",
            "billing",
            "payments",
            "fiscal",
            "night_audit",
        )
        val PERMISSIONS = listOf(
            "guests.view",
            "guests.manage",
            "guests.identity.manual_verify",
            "guests.identity.verify",
            "guests.identity.manage",
            "guests.identity.view",
            "reservations.guests.manage",
            "reservations.view",
            "reservations.create",
            "checkin.process",
            "frontdesk.stays.view",
            "checkout.process",
            "checkout.unpaid_override",
            "folio.view",
            "billing.invoice",
            "billing.invoice.void",
            "billing.credit_note",
            "payments.view",
            "payments.collect",
            "payments.cash.manage",
            "payments.configure",
            "payments.reconcile",
            "payments.status.view",
            "payments.reverse",
            "payments.refund",
            "fiscal.view",
            "fiscal.configure",
            "fiscal.retry",
            "night_audit.view",
            "night_audit.run",
            "night_audit.override",
            "night_audit.complete",
            "night_audit.close_snapshot.view",
        )
    }
}
