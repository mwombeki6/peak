package com.mwombeki.peak.payment.internal

import com.mwombeki.peak.TestcontainersConfiguration
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
 * The network a guest collection is pushed to must be asked for, carried, and never guessed.
 *
 * Guessing is the tempting shortcut: 075 and 078 are M-Pesa ranges, 071 is Tigo, 068 is
 * Airtel. Tanzania has mobile number portability, so a prefix records which operator was
 * *originally allocated* the range rather than who serves the number today. A ported number
 * would be pushed to the wrong operator, and the failure would reach the hotel as a guest who
 * did not pay.
 *
 * These assert against the database constraint and the stored column rather than through the
 * service, because the constraint is what holds when someone later adds a second write path.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class MobileMoneyNetworkIntegrationTests {

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @AfterTest
    fun resetSession() {
        jdbcTemplate.execute("RESET ALL")
    }

    @Test
    fun theTransactionRecordsTheNetworkItWasToldToUse() {
        val fixture = collectionFixture(network = "Mpesa")

        assertEquals(
            "Mpesa",
            jdbcTemplate.queryForObject(
                "SELECT mobile_network FROM payment_transactions WHERE id = ?",
                String::class.java,
                fixture,
            ),
            "the worker reads this to tell the provider where to push; losing it here means " +
                "the adapter is called with nothing",
        )
    }

    /**
     * The constraint, not the service, is what stops a typo becoming a payment nobody
     * receives. A second write path added later would bypass a Kotlin check.
     */
    @Test
    fun aNetworkNoAdapterSupportsIsRefusedByTheDatabase() {
        val failure = assertFailsWith<Exception> { collectionFixture(network = "Vodacom") }

        val reason = failure.message.orEmpty() + failure.cause?.message.orEmpty()
        assertTrue(
            reason.contains("chk_payment_transactions_mobile_network"),
            "Vodacom is not an AzamPay channel, and the check constraint should be what " +
                "refuses it rather than a Kotlin guard a second write path could bypass. " +
                "Got: $reason",
        )
    }

    @Test
    fun aCollectionMayCarryNoNetworkForAProviderThatWorksItOutItself() {
        val fixture = collectionFixture(network = null)

        assertEquals(
            null,
            jdbcTemplate.queryForObject(
                "SELECT mobile_network FROM payment_transactions WHERE id = ?",
                String::class.java,
                fixture,
            ),
            "ClickPesa derives the network from the MSISDN, so requiring one would be " +
                "asking the front desk a question the provider does not need answered",
        )
    }

    /**
     * Pins the intent, so that anyone later tempted to derive the network from the number
     * has to delete a test that says why not.
     */
    @Test
    fun nothingInThePaymentPathDerivesANetworkFromAPhonePrefix() {
        val paymentSources = java.nio.file.Files.walk(
            java.nio.file.Path.of("src/main/kotlin/com/mwombeki/peak/payment"),
        ).use { paths ->
            paths.filter { it.toString().endsWith(".kt") }
                .map { java.nio.file.Files.readString(it) }
                .toList()
        }

        val prefixRanges = listOf("\"075", "\"078", "\"071", "\"068", "\"065", "\"067")
        val offenders = paymentSources.filter { source ->
            prefixRanges.any { source.contains(it) }
        }

        assertTrue(
            offenders.isEmpty(),
            "something in the payment module tests a phone number prefix. Tanzania has " +
                "number portability, so a prefix identifies the original allocation and not " +
                "the current operator — an interface may preselect from it, but what Peak " +
                "sends must be what someone confirmed.",
        )
    }

    private fun collectionFixture(network: String?): UUID {
        val planId = UUID.randomUUID()
        val tenantId = UUID.randomUUID()
        val propertyId = UUID.randomUUID()
        val transactionId = UUID.randomUUID()

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
            VALUES (?, ?, 'Network Test Hotel', ?, 'HOTEL', 'active', true)
            """.trimIndent(),
            propertyId, tenantId, "NT-${propertyId.toString().take(6)}",
        )
        jdbcTemplate.update(
            """
            INSERT INTO payment_transactions (
                id, tenant_id, property_id, transaction_direction, transaction_type,
                internal_reference, payer_identifier, mobile_network, amount, currency, status
            ) VALUES (?, ?, ?, 'inbound', 'collection', ?, '255754123456', ?, 180000.00,
                      'TZS', 'created')
            """.trimIndent(),
            transactionId, tenantId, propertyId,
            "REF-${transactionId.toString().take(8)}".uppercase(),
            network,
        )
        return transactionId
    }
}
