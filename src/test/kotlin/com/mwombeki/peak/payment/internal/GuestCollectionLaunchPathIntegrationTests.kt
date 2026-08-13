package com.mwombeki.peak.payment.internal

import com.mwombeki.peak.TestcontainersConfiguration
import com.mwombeki.peak.payment.api.InitiateMobileMoneyRequest
import com.mwombeki.peak.payment.api.PaymentPort
import com.mwombeki.peak.payment.api.PaymentRejectedException
import com.mwombeki.peak.shared.context.RequestContext
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * The front-desk collection path, as far as it can honestly be driven today.
 *
 * ```
 * guest at the desk -> folio -> charge -> "Request payment"
 *      -> payment_transactions row carrying the network
 *      -> outbox event
 *      -> worker -> adapter -> USSD push
 *      -> provider confirmation -> folio payment posted        <-- see the note below
 * ```
 *
 * Everything above the line is exercised here. The confirmation leg is **not**, and not
 * because it is hard to test: the guest webhook route is
 * `/api/v1/payments/webhooks/clickpesa` and `PaymentWebhookService` refuses any provider
 * that is not ClickPesa. So there is exactly one guest rail with a complete loop, and it is
 * the one marked dormant. A launch narrative of "front desk clicks, guest gets a prompt,
 * hotel is auto-reconciled" does not yet exist on the rail Peak intends to launch with.
 *
 * That is a gap in the product, not in the tests, and writing a green end-to-end test
 * against a rail that cannot confirm would hide it.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class GuestCollectionLaunchPathIntegrationTests {

    @Autowired private lateinit var paymentPort: PaymentPort
    @Autowired private lateinit var requestContextHolder: RequestContextHolder
    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    @AfterTest
    fun clearContext() {
        requestContextHolder.clear()
        jdbcTemplate.execute("RESET ALL")
    }

    /**
     * The launch happy path up to the push: the network the receptionist confirmed is
     * recorded against the transaction, and an outbox event exists for the worker to act on.
     */
    @Test
    fun aFrontDeskCollectionRecordsTheNetworkAndQueuesThePush() {
        val fixture = walkInWithCharge(providerCode = "clickpesa")
        requestContextHolder.set(fixture.context("idem-collect-1"))

        val transaction = paymentPort.initiateMobileMoney(
            fixture.propertyId,
            InitiateMobileMoneyRequest(
                folioId = fixture.folioId,
                providerAccountId = fixture.providerAccountId,
                phoneNumber = "0754123456",
                amount = BigDecimal("180000.00"),
                mobileNetwork = "Mpesa",
            ),
        )

        assertEquals(
            "Mpesa",
            jdbcTemplate.queryForObject(
                "SELECT mobile_network FROM payment_transactions WHERE id = ?",
                String::class.java,
                transaction.id,
            ),
            "the worker reads this column to tell the provider where to push",
        )
        assertEquals(
            "+255754123456",
            jdbcTemplate.queryForObject(
                "SELECT payer_identifier FROM payment_transactions WHERE id = ?",
                String::class.java,
                transaction.id,
            ),
            "a receptionist types 0754…; this is stored in E.164. Note AzamPay's documented " +
                "accountNumber examples carry no leading +, so a property-scoped AzamPay " +
                "adapter will need to decide which form it sends — one more reason that " +
                "adapter does not exist yet.",
        )
        assertEquals(
            1,
            outboxCount(fixture.tenantId, transaction.id),
            "the push is queued rather than performed in the request, so a slow provider " +
                "does not hold the front desk",
        )
    }

    /**
     * The whole point of moving this check to the boundary.
     *
     * Before, a collection with no network was accepted, committed, queued, and then failed
     * inside the worker — repeatedly, until it dead-lettered — while the front desk watched
     * a payment that never arrived. Now it is refused while the receptionist is still
     * looking at the screen, and nothing is queued.
     */
    @Test
    fun aCollectionWithNoNetworkIsRefusedAndNothingIsQueued() {
        val fixture = walkInWithCharge(providerCode = "azampay")
        requestContextHolder.set(fixture.context("idem-collect-missing"))

        val failure = assertFailsWith<Exception> {
            paymentPort.initiateMobileMoney(
                fixture.propertyId,
                InitiateMobileMoneyRequest(
                    folioId = fixture.folioId,
                    providerAccountId = fixture.providerAccountId,
                    phoneNumber = "0754123456",
                    amount = BigDecimal("180000.00"),
                    mobileNetwork = null,
                ),
            )
        }

        assertTrue(
            failure.message.orEmpty().contains("mobile network"),
            "the message has to tell the receptionist what to do: ${failure.message}",
        )
        assertEquals(
            0,
            outboxCountForTenant(fixture.tenantId),
            "nothing may be queued for a collection that cannot be initiated",
        )
        assertEquals(
            0,
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM payment_transactions WHERE tenant_id = ?",
                Int::class.java,
                fixture.tenantId,
            ),
            "and no transaction row either — a payment nobody can push is not a payment",
        )
    }

    @Test
    fun aNetworkNoAdapterSupportsIsRefusedAtTheBoundary() {
        val fixture = walkInWithCharge(providerCode = "azampay")
        requestContextHolder.set(fixture.context("idem-collect-bad-network"))

        val failure = assertFailsWith<PaymentRejectedException> {
            paymentPort.initiateMobileMoney(
                fixture.propertyId,
                InitiateMobileMoneyRequest(
                    folioId = fixture.folioId,
                    providerAccountId = fixture.providerAccountId,
                    phoneNumber = "0754123456",
                    amount = BigDecimal("180000.00"),
                    // A real Tanzanian network, and not one AzamPay pushes to.
                    mobileNetwork = "Vodacom",
                ),
            )
        }

        assertTrue(failure.message.contains("Vodacom"), failure.message)
        assertEquals(0, outboxCountForTenant(fixture.tenantId))
    }

    /**
     * A provider that works the network out from the MSISDN must not be made to ask.
     * Requiring one everywhere would put a question in front of a receptionist that the
     * provider does not need answered, which is its own kind of defect.
     */
    @Test
    fun aProviderThatInfersTheNetworkIsNotMadeToAsk() {
        val fixture = walkInWithCharge(providerCode = "clickpesa")
        requestContextHolder.set(fixture.context("idem-collect-inferring"))

        val transaction = paymentPort.initiateMobileMoney(
            fixture.propertyId,
            InitiateMobileMoneyRequest(
                folioId = fixture.folioId,
                providerAccountId = fixture.providerAccountId,
                phoneNumber = "0754123456",
                amount = BigDecimal("180000.00"),
                mobileNetwork = null,
            ),
        )

        assertEquals(1, outboxCount(fixture.tenantId, transaction.id))
    }

    private fun outboxCount(tenantId: UUID, transactionId: UUID): Int =
        jdbcTemplate.queryForObject(
            """
            SELECT count(*) FROM outbox_events
            WHERE tenant_id = ? AND aggregate_id = ? AND destination = 'payment'
            """.trimIndent(),
            Int::class.java,
            tenantId,
            transactionId,
        ) ?: 0

    private fun outboxCountForTenant(tenantId: UUID): Int =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM outbox_events WHERE tenant_id = ? AND destination = 'payment'",
            Int::class.java,
            tenantId,
        ) ?: 0

    private fun walkInWithCharge(providerCode: String): CollectionFixture {
        val planId = UUID.randomUUID()
        val tenantId = UUID.randomUUID()
        val propertyId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val folioId = UUID.randomUUID()
        val providerId = UUID.randomUUID()
        val providerAccountId = UUID.randomUUID()

        jdbcTemplate.update(
            "INSERT INTO plans (id, name, code) VALUES (?, ?, ?)",
            planId, "Plan $planId", "plan-$planId",
        )
        jdbcTemplate.update(
            "INSERT INTO tenants (id, name, slug, schema_name, plan_id) VALUES (?, ?, ?, ?, ?)",
            tenantId, "Tenant $tenantId", "tenant-$tenantId",
            "tenant_$tenantId".replace("-", "_"), planId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO properties (id, tenant_id, name, code, type, status, is_active)
            VALUES (?, ?, 'Launch Hotel', ?, 'HOTEL', 'active', true)
            """.trimIndent(),
            propertyId, tenantId, "LH-${propertyId.toString().take(6)}",
        )
        jdbcTemplate.update(
            """
            INSERT INTO users (id, tenant_id, full_name, email, status, is_active)
            VALUES (?, ?, 'Front Desk', ?, 'active', true)
            """.trimIndent(),
            userId, tenantId, "desk-$userId@example.com",
        )

        // A guest at the desk with something to pay for.
        jdbcTemplate.update(
            """
            INSERT INTO folios (id, tenant_id, property_id, folio_type, status)
            VALUES (?, ?, ?, 'guest', 'open')
            """.trimIndent(),
            folioId, tenantId, propertyId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO folio_charges (
                id, tenant_id, property_id, folio_id, charge_type, description,
                quantity, unit_price, subtotal, tax_rate, tax_amount, amount,
                posted_by, status
            ) VALUES (?, ?, ?, ?, 'ROOM', 'Two nights', 1, 180000, 180000, 0, 0, 180000,
                      ?, 'POSTED')
            """.trimIndent(),
            UUID.randomUUID(), tenantId, propertyId, folioId, userId,
        )
        jdbcTemplate.queryForList("SELECT recalculate_folio_totals(?)", folioId)

        // The property's own provider account — the money lands in the hotel's merchant
        // context, never Peak's.
        jdbcTemplate.update(
            """
            INSERT INTO payment_providers (
                id, tenant_id, provider_code, name, provider_type, is_active
            ) VALUES (?, ?, ?, ?, 'mobile_money', true)
            """.trimIndent(),
            providerId, tenantId, providerCode, providerCode,
        )
        jdbcTemplate.update(
            """
            INSERT INTO payment_provider_accounts (
                id, tenant_id, property_id, provider_id, account_name, client_id,
                secret_ref, api_key_secret_ref, checksum_key_secret_ref, endpoint_url,
                is_default, is_active, environment
            ) VALUES (?, ?, ?, ?, 'Hotel Account', 'MERCHANT-001',
                      'literal:api-secret', 'literal:api-secret', 'literal:checksum-secret',
                      'https://api.clickpesa.com/third-parties', true, true, 'sandbox')
            """.trimIndent(),
            providerAccountId, tenantId, propertyId, providerId,
        )

        return CollectionFixture(tenantId, propertyId, userId, folioId, providerAccountId)
    }

    private data class CollectionFixture(
        val tenantId: UUID,
        val propertyId: UUID,
        val userId: UUID,
        val folioId: UUID,
        val providerAccountId: UUID,
    ) {
        fun context(idempotencyKey: String): RequestContext = RequestContext(
            identity = RequestIdentity.Tenant(
                tenantId = tenantId,
                tenantUserId = userId,
                correlationId = "corr-$idempotencyKey",
            ),
            correlationId = "corr-$idempotencyKey",
            idempotencyKey = idempotencyKey,
            httpMethod = "POST",
            requestPath = "/api/v1/properties/$propertyId/payments/mobile-money",
        )
    }
}
