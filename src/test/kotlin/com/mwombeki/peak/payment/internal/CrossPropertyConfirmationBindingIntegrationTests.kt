package com.mwombeki.peak.payment.internal

import com.mwombeki.peak.TestcontainersConfiguration
import com.mwombeki.peak.shared.context.RequestContext
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * One hotel's merchant context must not be able to settle its sibling's payment.
 *
 * ```
 * Tenant T
 * ├── Property A ── Merchant Account A
 * └── Property B ── Merchant Account B ── transaction under test
 * ```
 *
 * A hotel group is one tenant with several properties, so tenant scoping — the isolation the
 * rest of Peak leans on — does not separate A from B at all. Every RLS policy in the system
 * is satisfied by a callback from A that names B's transaction, because both rows carry the
 * same `tenant_id`. Isolation here has to come from the payment's own binding or it does not
 * exist.
 *
 * The attack this forecloses is not exotic. A provider account's credentials belong to one
 * hotel; its callbacks are signed with them. Without the binding, hotel A's signed callback —
 * genuinely authentic, correctly verified — could mark hotel B's guest as paid. A dishonest
 * franchisee with a valid merchant account would be enough, and both halves would look
 * correct in isolation: real signature, real transaction, same tenant.
 *
 * These cases are one-variable-at-a-time on purpose. A test that mutates two fields passes
 * when either check fires, which means it keeps passing after one of them is deleted.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class CrossPropertyConfirmationBindingIntegrationTests {

    @Autowired private lateinit var confirmationService: GuestPaymentConfirmationService
    @Autowired private lateinit var requestContextHolder: RequestContextHolder
    @Autowired private lateinit var jdbcTemplate: JdbcTemplate
    @Autowired private lateinit var transactionTemplate: TransactionTemplate

    @AfterTest
    fun clearContext() {
        requestContextHolder.clear()
        jdbcTemplate.execute("RESET ALL")
    }

    /**
     * Confirmation is two writes — the status change and the folio posting — and they are only
     * one event because a transaction makes them so. Both production callers wrap it, but by
     * convention rather than by contract, so this pins the contract.
     *
     * Unwrapped, the status change commits alone. If the folio posting then fails, the
     * transaction reads `posted`, the compare-and-set refuses every retry, and the guest has
     * paid against a folio that still shows a balance. Nothing about that is visible in a log.
     */
    @Test
    fun confirmingOutsideATransactionIsRefusedBeforeAnythingIsWritten() {
        val group = hotelGroup()

        val refused = assertFailsWith<IllegalArgumentException> {
            confirmationService.confirm(group.truthfulObservation())
        }

        assertTrue(refused.message!!.contains("inside a transaction"), refused.message!!)
        assertStillUnpaid(group)
    }

    /** The one that matters: A's account cannot settle B's payment. */
    @Test
    fun theSiblingHotelsMerchantAccountCannotSettleThisPayment() {
        val group = hotelGroup()

        val refused = assertFailsWith<IllegalArgumentException> {
            confirmInTransaction(
                group.truthfulObservation().copy(providerAccountId = group.accountA),
            )
        }

        assertTrue(
            refused.message!!.contains("different merchant account"),
            refused.message!!,
        )
        assertStillUnpaid(group)
    }

    /** Same transaction, same tenant, wrong property. Also refused. */
    @Test
    fun theSiblingHotelsPropertyCannotSettleThisPayment() {
        val group = hotelGroup()

        val refused = assertFailsWith<IllegalArgumentException> {
            confirmInTransaction(
                group.truthfulObservation().copy(propertyId = group.propertyA),
            )
        }

        assertTrue(refused.message!!.contains("different property"), refused.message!!)
        assertStillUnpaid(group)
    }

    /**
     * The right hotel, naming a payment it does not have. Peak's own reference is what ties an
     * observation to the payment it started, so a mismatch means the provider is talking about
     * something else.
     */
    @Test
    fun anObservationNamingADifferentReferenceIsRefused() {
        val group = hotelGroup()

        val refused = assertFailsWith<IllegalArgumentException> {
            confirmInTransaction(
                group.truthfulObservation().copy(internalReference = "PMTOTHER00000001"),
            )
        }

        assertTrue(refused.message!!.contains("different payment reference"), refused.message!!)
        assertStillUnpaid(group)
    }

    /**
     * Underpayment is the case with teeth. Without this the guest pays TZS 1,000 against a
     * TZS 180,000 folio and the folio is marked settled in full, because the posting takes its
     * amount from the transaction rather than from what actually arrived.
     */
    @Test
    fun anObservationForADifferentAmountIsRefused() {
        val group = hotelGroup()

        val refused = assertFailsWith<IllegalArgumentException> {
            confirmInTransaction(
                group.truthfulObservation().copy(amount = BigDecimal("1000.00")),
            )
        }

        assertTrue(refused.message!!.contains("1000.00"), refused.message!!)
        assertStillUnpaid(group)
    }

    /** 180,000 shillings and 180,000 of anything else are not the same payment. */
    @Test
    fun anObservationInADifferentCurrencyIsRefused() {
        val group = hotelGroup()

        val refused = assertFailsWith<IllegalArgumentException> {
            confirmInTransaction(
                group.truthfulObservation().copy(currency = "USD"),
            )
        }

        assertTrue(refused.message!!.contains("USD"), refused.message!!)
        assertStillUnpaid(group)
    }

    /**
     * The control. Without it every assertion above would still pass if `confirm()` refused
     * unconditionally, and the binding would look protective while being merely broken.
     *
     * The second call stands in for the ordinary case of a callback and a status poll both
     * arriving: it must change nothing rather than post the folio payment twice.
     */
    @Test
    fun theCorrectIdentityConfirmsExactlyOnce() {
        val group = hotelGroup()

        assertTrue(
            confirmInTransaction(group.truthfulObservation()),
            "the honest observation must be applied, or the checks above prove nothing",
        )
        assertFalse(
            confirmInTransaction(group.truthfulObservation()),
            "a second observation of the same payment must be a no-op",
        )

        assertEquals(
            "posted",
            jdbcTemplate.queryForObject(
                "SELECT status FROM payment_transactions WHERE id = ?",
                String::class.java,
                group.transactionId,
            ),
        )
        assertEquals(
            1,
            jdbcTemplate.queryForObject(
                """
                SELECT count(*) FROM folio_payments
                WHERE folio_id = ? AND payment_transaction_id = ?
                """.trimIndent(),
                Int::class.java,
                group.folioB,
                group.transactionId,
            ),
            "one payment on the folio, however many times the provider says so",
        )
    }

    /**
     * Exactly how `PaymentWebhookService` and `PaymentStatusOutboxHandler` call it: inside a
     * transaction, under a public identity carrying the tenant and property the callback was
     * resolved to. A callback has no logged-in user, so `Public` is the honest identity — and
     * the audit trail is written from it, which is why the context must be bound before rather
     * than derived from the observation.
     */
    private fun confirmInTransaction(observation: ProviderPaymentObservation): Boolean {
        requestContextHolder.set(
            RequestContext(
                identity = RequestIdentity.Public(
                    tenantId = observation.tenantId,
                    propertyId = observation.propertyId,
                    correlationId = "corr-${observation.transactionId}",
                ),
                correlationId = "corr-${observation.transactionId}",
                idempotencyKey = null,
                httpMethod = "POST",
                requestPath = "/api/payments/webhooks/clickpesa/accounts/" +
                    observation.providerAccountId,
            ),
        )
        return transactionTemplate.execute { confirmationService.confirm(observation) } == true
    }

    /**
     * A refusal must leave the payment collectable. If a rejected observation moved the row
     * out of its in-flight state, the sibling hotel could not settle the payment but could
     * still strand it — a denial of service dressed as a security check.
     */
    private fun assertStillUnpaid(group: HotelGroup) {
        assertEquals(
            "pending",
            jdbcTemplate.queryForObject(
                "SELECT status FROM payment_transactions WHERE id = ?",
                String::class.java,
                group.transactionId,
            ),
            "a refused observation must not disturb the payment it failed to settle",
        )
        assertEquals(
            0,
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM folio_payments WHERE payment_transaction_id = ?",
                Int::class.java,
                group.transactionId,
            ),
            "nothing may reach the folio",
        )
    }

    /** Two hotels under one tenant, each with its own merchant account. */
    private fun hotelGroup(): HotelGroup {
        val planId = UUID.randomUUID()
        val tenantId = UUID.randomUUID()
        val propertyA = UUID.randomUUID()
        val propertyB = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val folioB = UUID.randomUUID()
        val providerId = UUID.randomUUID()
        val accountA = UUID.randomUUID()
        val accountB = UUID.randomUUID()
        val transactionId = UUID.randomUUID()
        val internalReference = "PMT${transactionId.toString().replace("-", "").take(13)}"
            .uppercase()

        jdbcTemplate.update(
            "INSERT INTO plans (id, name, code) VALUES (?, ?, ?)",
            planId, "Plan $planId", "plan-$planId",
        )
        jdbcTemplate.update(
            "INSERT INTO tenants (id, name, slug, schema_name, plan_id) VALUES (?, ?, ?, ?, ?)",
            tenantId, "Group $tenantId", "group-$tenantId",
            "tenant_$tenantId".replace("-", "_"), planId,
        )
        listOf(propertyA to "Sibling A", propertyB to "Sibling B").forEach { (id, name) ->
            jdbcTemplate.update(
                """
                INSERT INTO properties (id, tenant_id, name, code, type, status, is_active)
                VALUES (?, ?, ?, ?, 'HOTEL', 'active', true)
                """.trimIndent(),
                id, tenantId, name, "SB-${id.toString().take(6)}",
            )
        }
        jdbcTemplate.update(
            """
            INSERT INTO users (id, tenant_id, full_name, email, status, is_active)
            VALUES (?, ?, 'Front Desk', ?, 'active', true)
            """.trimIndent(),
            userId, tenantId, "desk-$userId@example.com",
        )

        // The guest is at Property B.
        jdbcTemplate.update(
            """
            INSERT INTO folios (id, tenant_id, property_id, folio_type, status)
            VALUES (?, ?, ?, 'guest', 'open')
            """.trimIndent(),
            folioB, tenantId, propertyB,
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
            UUID.randomUUID(), tenantId, propertyB, folioB, userId,
        )
        jdbcTemplate.queryForList("SELECT recalculate_folio_totals(?)", folioB)

        jdbcTemplate.update(
            """
            INSERT INTO payment_providers (
                id, tenant_id, provider_code, name, provider_type, is_active
            ) VALUES (?, ?, 'clickpesa', 'ClickPesa', 'mobile_money', true)
            """.trimIndent(),
            providerId, tenantId,
        )
        listOf(accountA to propertyA, accountB to propertyB).forEach { (accountId, propertyId) ->
            jdbcTemplate.update(
                """
                INSERT INTO payment_provider_accounts (
                    id, tenant_id, property_id, provider_id, account_name, client_id,
                    secret_ref, api_key_secret_ref, checksum_key_secret_ref, endpoint_url,
                    is_default, is_active, environment
                ) VALUES (?, ?, ?, ?, ?, ?,
                          'literal:api-secret', 'literal:api-secret',
                          'literal:checksum-secret',
                          'https://api.clickpesa.com/third-parties', true, true, 'sandbox')
                """.trimIndent(),
                accountId, tenantId, propertyId, providerId,
                "Account ${accountId.toString().take(4)}",
                "MERCHANT-${accountId.toString().take(4)}",
            )
        }

        // A collection in flight against Property B's account.
        jdbcTemplate.update(
            """
            INSERT INTO payment_transactions (
                id, tenant_id, property_id, folio_id, provider_account_id, initiated_by,
                transaction_direction, transaction_type, internal_reference,
                payer_identifier, mobile_network, amount, currency, status
            ) VALUES (?, ?, ?, ?, ?, ?, 'inbound', 'collection', ?,
                      '+255754123456', 'Mpesa', 180000.00, 'TZS', 'pending')
            """.trimIndent(),
            transactionId, tenantId, propertyB, folioB, accountB, userId, internalReference,
        )

        return HotelGroup(
            tenantId = tenantId,
            propertyA = propertyA,
            propertyB = propertyB,
            accountA = accountA,
            accountB = accountB,
            folioB = folioB,
            userId = userId,
            transactionId = transactionId,
            internalReference = internalReference,
        )
    }

    private data class HotelGroup(
        val tenantId: UUID,
        val propertyA: UUID,
        val propertyB: UUID,
        val accountA: UUID,
        val accountB: UUID,
        val folioB: UUID,
        val userId: UUID,
        val transactionId: UUID,
        val internalReference: String,
    ) {
        /** Everything as it really is; each test spoils exactly one field of it. */
        fun truthfulObservation() = ProviderPaymentObservation(
            tenantId = tenantId,
            propertyId = propertyB,
            transactionId = transactionId,
            providerAccountId = accountB,
            internalReference = internalReference,
            provider = "clickpesa",
            status = ProviderPaymentObservation.CanonicalStatus.SUCCEEDED,
            providerReference = "CP-CONFIRMED-001",
            providerStatus = "SUCCESS",
            amount = BigDecimal("180000.00"),
            currency = "TZS",
            folioId = folioB,
            posOrderId = null,
            initiatedBy = userId,
            source = ProviderPaymentObservation.ObservationSource.WEBHOOK,
        )
    }
}
