package com.mwombeki.peak.shared.database

import com.mwombeki.peak.TestcontainersConfiguration
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Transactional
import org.testcontainers.junit.jupiter.Testcontainers

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@Transactional
class GeneratedEconomicInvariantIntegrationTests {

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Test
    fun generatedChargesCollectionsAndRefundsAlwaysReconcile() {
        val fixture = insertFixture()
        val random = Random(0x5045414B)

        repeat(80) { scenario ->
            val folioId = UUID.randomUUID()
            jdbc.update(
                """
                INSERT INTO folios (
                    id, tenant_id, property_id, folio_type, status,
                    currency_code, service_charge, tourism_levy
                ) VALUES (?, ?, ?, 'guest', 'open', 'TZS', 0, 0)
                """.trimIndent(),
                folioId,
                fixture.tenantId,
                fixture.propertyId,
            )

            var expectedSubtotal = BigDecimal.ZERO.money()
            var expectedTax = BigDecimal.ZERO.money()
            repeat(random.nextInt(1, 9)) { chargeNumber ->
                val quantity = BigDecimal(random.nextInt(1, 4))
                val unitPrice = BigDecimal(random.nextInt(500, 250_001)).money()
                val subtotal = quantity.multiply(unitPrice).money()
                val taxRate = if (random.nextBoolean()) BigDecimal("0.18") else BigDecimal.ZERO
                val tax = subtotal.multiply(taxRate).money()
                jdbc.update(
                    """
                    INSERT INTO folio_charges (
                        id, tenant_id, property_id, folio_id, charge_type,
                        description, quantity, unit_price, subtotal, tax_rate,
                        tax_amount, amount, posted_by, status
                    ) VALUES (?, ?, ?, ?, 'MISC', ?, ?, ?, ?, ?, ?, ?, ?, 'POSTED')
                    """.trimIndent(),
                    UUID.randomUUID(),
                    fixture.tenantId,
                    fixture.propertyId,
                    folioId,
                    "Generated charge $scenario-$chargeNumber",
                    quantity,
                    unitPrice,
                    subtotal,
                    taxRate,
                    tax,
                    subtotal.add(tax).money(),
                    fixture.userId,
                )
                expectedSubtotal = expectedSubtotal.add(subtotal).money()
                expectedTax = expectedTax.add(tax).money()
            }

            val total = expectedSubtotal.add(expectedTax).money()
            var remainingToCollect = total
            var expectedPaid = BigDecimal.ZERO.money()
            val collections = mutableListOf<Collection>()
            repeat(random.nextInt(1, 6)) { paymentNumber ->
                if (remainingToCollect > BigDecimal.ZERO) {
                    val amount = if (paymentNumber == 4 || random.nextInt(4) == 0) {
                        remainingToCollect
                    } else {
                        val cents = remainingToCollect.movePointRight(2).toLong()
                        BigDecimal(random.nextLong(1, cents + 1)).movePointLeft(2).money()
                    }
                    val collection = insertCollection(
                        fixture,
                        folioId,
                        amount,
                        "GEN-$scenario-$paymentNumber",
                    )
                    collections += collection
                    expectedPaid = expectedPaid.add(amount).money()
                    remainingToCollect = remainingToCollect.subtract(amount).money()
                }
            }

            collections.shuffled(random).take(random.nextInt(0, collections.size + 1))
                .forEachIndexed { refundNumber, collection ->
                    val cents = collection.amount.movePointRight(2).toLong()
                    val refund = BigDecimal(random.nextLong(1, cents + 1))
                        .movePointLeft(2)
                        .money()
                    insertRefund(
                        fixture,
                        folioId,
                        collection,
                        refund,
                        "REF-$scenario-$refundNumber",
                    )
                    expectedPaid = expectedPaid.subtract(refund).money()
                }

            jdbc.queryForList("SELECT recalculate_folio_totals(?)", folioId)
            val totals = jdbc.queryForMap(
                """
                SELECT subtotal, tax_amount, total_amount, total_paid
                FROM folios WHERE tenant_id = ? AND id = ?
                """.trimIndent(),
                fixture.tenantId,
                folioId,
            )
            assertEquals(expectedSubtotal, totals["subtotal"], "scenario=$scenario subtotal")
            assertEquals(expectedTax, totals["tax_amount"], "scenario=$scenario tax")
            assertEquals(total, totals["total_amount"], "scenario=$scenario total")
            assertEquals(expectedPaid, totals["total_paid"], "scenario=$scenario paid")
            assertTrue(expectedPaid >= BigDecimal.ZERO, "scenario=$scenario negative paid")
            assertTrue(expectedPaid <= total, "scenario=$scenario overpaid")
        }
    }

    private fun insertCollection(
        fixture: Fixture,
        folioId: UUID,
        amount: BigDecimal,
        reference: String,
    ): Collection {
        val transactionId = UUID.randomUUID()
        val folioPaymentId = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO payment_transactions (
                id, tenant_id, property_id, folio_id, initiated_by,
                transaction_direction, transaction_type, internal_reference,
                amount, currency, status, posted_at, confirmed_at
            ) VALUES (?, ?, ?, ?, ?, 'inbound', 'collection', ?, ?, 'TZS',
                      'posted', now(), now())
            """.trimIndent(),
            transactionId,
            fixture.tenantId,
            fixture.propertyId,
            folioId,
            fixture.userId,
            reference,
            amount,
        )
        jdbc.update(
            """
            INSERT INTO folio_payments (
                id, tenant_id, property_id, folio_id, payment_method,
                amount, status, payment_transaction_id, processed_by, paid_at
            ) VALUES (?, ?, ?, ?, 'mobile_money', ?, 'POSTED', ?, ?, now())
            """.trimIndent(),
            folioPaymentId,
            fixture.tenantId,
            fixture.propertyId,
            folioId,
            amount,
            transactionId,
            fixture.userId,
        )
        return Collection(transactionId, folioPaymentId, amount)
    }

    private fun insertRefund(
        fixture: Fixture,
        folioId: UUID,
        original: Collection,
        amount: BigDecimal,
        reference: String,
    ) {
        val transactionId = UUID.randomUUID()
        jdbc.update(
            """
            INSERT INTO payment_transactions (
                id, tenant_id, property_id, folio_id, initiated_by,
                transaction_direction, transaction_type, internal_reference,
                amount, currency, status, posted_at, confirmed_at,
                refund_of_transaction_id, external_refund_evidence, refund_reason
            ) VALUES (?, ?, ?, ?, ?, 'outbound', 'refund', ?, ?, 'TZS',
                      'posted', now(), now(), ?, ?, 'Generated invariant refund')
            """.trimIndent(),
            transactionId,
            fixture.tenantId,
            fixture.propertyId,
            folioId,
            fixture.userId,
            reference,
            amount,
            original.transactionId,
            reference,
        )
        jdbc.update(
            """
            INSERT INTO folio_payments (
                id, tenant_id, property_id, folio_id, payment_method,
                amount, status, payment_transaction_id, processed_by,
                paid_at, reversal_of
            ) VALUES (?, ?, ?, ?, 'mobile_money', ?, 'POSTED', ?, ?, now(), ?)
            """.trimIndent(),
            UUID.randomUUID(),
            fixture.tenantId,
            fixture.propertyId,
            folioId,
            amount,
            transactionId,
            fixture.userId,
            original.folioPaymentId,
        )
    }

    private fun insertFixture(): Fixture {
        val fixture = Fixture(
            planId = UUID.randomUUID(),
            tenantId = UUID.randomUUID(),
            propertyId = UUID.randomUUID(),
            userId = UUID.randomUUID(),
        )
        jdbc.update(
            "INSERT INTO plans (id, name, code) VALUES (?, 'Invariant Plan', ?)",
            fixture.planId,
            "invariant-${fixture.planId}",
        )
        jdbc.update(
            """
            INSERT INTO tenants (id, name, slug, status, schema_name, plan_id)
            VALUES (?, 'Invariant Tenant', ?, 'active', ?, ?)
            """.trimIndent(),
            fixture.tenantId,
            "invariant-${fixture.tenantId}",
            "tenant_${fixture.tenantId}".replace("-", "_"),
            fixture.planId,
        )
        jdbc.update(
            """
            INSERT INTO users (id, tenant_id, full_name, email, status, is_active)
            VALUES (?, ?, 'Invariant Operator', ?, 'active', true)
            """.trimIndent(),
            fixture.userId,
            fixture.tenantId,
            "invariant-${fixture.userId}@example.com",
        )
        jdbc.update(
            """
            INSERT INTO properties (id, tenant_id, name, status, is_active, total_rooms)
            VALUES (?, ?, 'Invariant Property', 'active', true, 0)
            """.trimIndent(),
            fixture.propertyId,
            fixture.tenantId,
        )
        return fixture
    }

    private fun BigDecimal.money(): BigDecimal = setScale(2, RoundingMode.HALF_UP)

    private data class Collection(
        val transactionId: UUID,
        val folioPaymentId: UUID,
        val amount: BigDecimal,
    )

    private data class Fixture(
        val planId: UUID,
        val tenantId: UUID,
        val propertyId: UUID,
        val userId: UUID,
    )
}
