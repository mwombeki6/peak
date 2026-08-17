package com.mwombeki.peak.payment.internal

import com.mwombeki.peak.TestcontainersConfiguration
import com.mwombeki.peak.payment.api.PaymentWebhookPort
import com.mwombeki.peak.shared.context.RequestContext
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * A guest payment confirms on every guest rail, not only the first one built.
 *
 * This test exists because the suite was green while two of the three rails could not confirm
 * a payment at all. `V106` opened the callback route to any provider and `PaymentWebhookService`
 * stopped gating on ClickPesa — and one layer further in, a validator still required the event
 * name to be `PAYMENT RECEIVED` or `PAYMENT FAILED` and the status to be `posted`. Those are
 * ClickPesa's words. Snippe says `payment.completed` and `succeeded`; AzamPay says
 * `collection.updated` and `success`.
 *
 * So a Snippe callback arrived, verified its signature correctly, and was then rejected by
 * Peak's own validator. The hotel saw collections sit pending until the sweep expired them,
 * and no test noticed, because every adapter test asserted the word that adapter itself had
 * invented. Each half was consistent with itself and the two halves had never been introduced.
 *
 * The fix was to make the boundary a type rather than a string. This is the test that would
 * have caught it, and the reason it is written against the real adapters rather than a stub:
 * a stub would have agreed with whatever the domain expected, which is exactly the failure.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class AnyProviderCallbackConfirmsIntegrationTests {

    @Autowired private lateinit var webhookPort: PaymentWebhookPort
    @Autowired private lateinit var requestContextHolder: RequestContextHolder
    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    /**
     * Every callback is stamped now. The replay window is five minutes and rejecting a stale
     * callback is correct behaviour, so a fixture with a hardcoded date tests the clock rather
     * than the rail.
     */
    private val now: String get() = "\"" + Instant.now().truncatedTo(ChronoUnit.SECONDS) + "\""

    @AfterTest
    fun clearContext() {
        requestContextHolder.clear()
        jdbcTemplate.execute("RESET ALL")
    }

    @Test
    fun aSnippeCallbackConfirmsTheGuestPayment() {
        val hotel = hotelOn("snippe")

        webhookPort.receive(
            providerAccountId = hotel.providerAccountId,
            payload = """
                {"id":"evt_snippe_1","type":"payment.completed",
                 "api_version":"2026-01-25","created_at":$now,
                 "data":{"reference":"sess_abc123def456",
                   "external_reference":"SEL123456789","status":"completed",
                   "amount":{"value":30000,"currency":"TZS"},
                   "settlement":{"fees":{"value":1000,"currency":"TZS"}},
                   "channel":{"type":"mobile_money","provider":"mpesa"},
                   "customer":{"phone":"+255700000001"},
                   "metadata":{"external_reference":"${hotel.internalReference}"},
                   "completed_at":$now}}
            """.trimIndent(),
        )

        assertConfirmed(hotel)
    }

    @Test
    fun anAzamPayCallbackConfirmsTheGuestPayment() {
        val hotel = hotelOn("azampay")

        webhookPort.receive(
            providerAccountId = hotel.providerAccountId,
            payload = """
                {"utilityref":"UTIL-1","externalreference":"${hotel.internalReference}",
                 "transactionstatus":"success","operator":"Mpesa",
                 "transactionid":"AZ-123","amount":"30000","currency":"TZS",
                 "msisdn":"255700000001","time":$now}
            """.trimIndent(),
        )

        assertConfirmed(hotel)
    }

    /**
     * The rail that always worked, kept alongside the two that did not.
     *
     * Its value is in what a future failure would mean: if this one breaks while the others
     * pass, the canonical vocabulary has drifted back toward one provider's words.
     */
    @Test
    fun aClickPesaCallbackStillConfirmsTheGuestPayment() {
        val hotel = hotelOn("clickpesa")

        webhookPort.receive(
            providerAccountId = hotel.providerAccountId,
            payload = """
                {"event":"PAYMENT RECEIVED","data":{"id":"CP-123",
                 "orderReference":"${hotel.internalReference}","status":"SUCCESS",
                 "collectedAmount":"30000","collectedCurrency":"TZS",
                 "updatedAt":$now}}
            """.trimIndent(),
        )

        assertConfirmed(hotel)
    }

    /**
     * A provider reporting progress rather than an outcome must leave the payment collectable.
     *
     * The old validator rejected any status that was not `posted` or `failed`, so a legitimate
     * progress ping looked like a malformed callback. Refusing it is not merely noisy — a
     * provider that retries on a non-2xx would be sent into a retry loop by a message that was
     * never a problem.
     */
    @Test
    fun aProgressCallbackLeavesThePaymentInFlight() {
        val hotel = hotelOn("snippe")

        webhookPort.receive(
            providerAccountId = hotel.providerAccountId,
            payload = """
                {"id":"evt_snippe_2","type":"payment.processing",
                 "api_version":"2026-01-25","created_at":$now,
                 "data":{"reference":"sess_abc123def456",
                   "external_reference":"SEL123456789","status":"processing",
                   "amount":{"value":30000,"currency":"TZS"},
                   "channel":{"type":"mobile_money","provider":"mpesa"},
                   "customer":{"phone":"+255700000001"},
                   "metadata":{"external_reference":"${hotel.internalReference}"},
                   "completed_at":$now}}
            """.trimIndent(),
        )

        assertEquals(
            "pending",
            transactionStatus(hotel),
            "a progress notification must neither settle the payment nor kill it",
        )
        assertEquals(0, folioPaymentCount(hotel), "nothing may reach the folio yet")
        assertEquals(
            0,
            outboxCount(hotel),
            "a progress notification must be published to nobody. POS subscribes only to " +
                "payment.transaction.posted and .failed, so a 'pending' event would find no " +
                "handler, throw, retry and dead-letter — an operational alert raised by a " +
                "callback that was working exactly as intended",
        )
    }

    private fun assertConfirmed(hotel: Hotel) {
        assertEquals(
            "posted",
            transactionStatus(hotel),
            "the callback verified and matched, so ${hotel.providerCode} must be able to " +
                "confirm the payment — this is the assertion that was missing while two of " +
                "three rails silently could not",
        )
        assertEquals(
            1,
            folioPaymentCount(hotel),
            "confirmation must reach the folio, or the guest has paid and the hotel's books " +
                "still show a balance",
        )
    }

    private fun transactionStatus(hotel: Hotel): String? =
        jdbcTemplate.queryForObject(
            "SELECT status FROM payment_transactions WHERE id = ?",
            String::class.java,
            hotel.transactionId,
        )

    private fun outboxCount(hotel: Hotel): Int =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM outbox_events WHERE aggregate_id = ?",
            Int::class.java,
            hotel.transactionId,
        ) ?: 0

    private fun folioPaymentCount(hotel: Hotel): Int =
        jdbcTemplate.queryForObject(
            "SELECT count(*) FROM folio_payments WHERE payment_transaction_id = ?",
            Int::class.java,
            hotel.transactionId,
        ) ?: 0

    /** A hotel with a pending collection on the given rail, waiting for its callback. */
    private fun hotelOn(providerCode: String): Hotel {
        val planId = UUID.randomUUID()
        val tenantId = UUID.randomUUID()
        val propertyId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val folioId = UUID.randomUUID()
        val providerId = UUID.randomUUID()
        val providerAccountId = UUID.randomUUID()
        val transactionId = UUID.randomUUID()
        // PaymentWebhookService requires Peak's own reference to match PEAK-[A-F0-9]{20}.
        val internalReference = "PEAK-" +
            UUID.randomUUID().toString().replace("-", "").take(20).uppercase()

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
            VALUES (?, ?, 'Callback Hotel', ?, 'HOTEL', 'active', true)
            """.trimIndent(),
            propertyId, tenantId, "CB-${propertyId.toString().take(6)}",
        )
        jdbcTemplate.update(
            """
            INSERT INTO users (id, tenant_id, full_name, email, status, is_active)
            VALUES (?, ?, 'Front Desk', ?, 'active', true)
            """.trimIndent(),
            userId, tenantId, "desk-$userId@example.com",
        )
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
            ) VALUES (?, ?, ?, ?, 'ROOM', 'One night', 1, 30000, 30000, 0, 0, 30000,
                      ?, 'POSTED')
            """.trimIndent(),
            UUID.randomUUID(), tenantId, propertyId, folioId, userId,
        )
        jdbcTemplate.queryForList("SELECT recalculate_folio_totals(?)", folioId)

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
                      'https://example.test', true, true, 'sandbox')
            """.trimIndent(),
            providerAccountId, tenantId, propertyId, providerId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO payment_transactions (
                id, tenant_id, property_id, folio_id, provider_account_id, initiated_by,
                transaction_direction, transaction_type, internal_reference,
                payer_identifier, mobile_network, amount, currency, status
            ) VALUES (?, ?, ?, ?, ?, ?, 'inbound', 'collection', ?,
                      '+255700000001', 'Mpesa', 30000.00, 'TZS', 'pending')
            """.trimIndent(),
            transactionId, tenantId, propertyId, folioId, providerAccountId, userId,
            internalReference,
        )

        // A callback has no logged-in user; the route is public and the account is what
        // identifies the hotel.
        requestContextHolder.set(
            RequestContext(
                identity = RequestIdentity.Public(
                    tenantId = tenantId,
                    propertyId = propertyId,
                    correlationId = "corr-$transactionId",
                ),
                correlationId = "corr-$transactionId",
                idempotencyKey = null,
                httpMethod = "POST",
                requestPath = "/api/payments/webhooks/$providerCode/accounts/$providerAccountId",
            ),
        )

        return Hotel(providerCode, providerAccountId, transactionId, internalReference)
    }

    private data class Hotel(
        val providerCode: String,
        val providerAccountId: UUID,
        val transactionId: UUID,
        val internalReference: String,
    )
}
