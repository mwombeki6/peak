package com.mwombeki.peak.shared.database

import com.mwombeki.peak.TestcontainersConfiguration
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.support.TransactionTemplate
import org.testcontainers.junit.jupiter.Testcontainers

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class DocumentNumberConcurrencyIntegrationTests {

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Autowired
    private lateinit var transactions: TransactionTemplate

    private var fixture: Fixture? = null

    @AfterTest
    fun cleanFixture() {
        fixture?.let {
            jdbc.update("DELETE FROM document_sequences WHERE tenant_id = ?", it.tenantId)
            jdbc.update("DELETE FROM tenants WHERE id = ?", it.tenantId)
            jdbc.update("DELETE FROM plans WHERE id = ?", it.planId)
        }
        fixture = null
    }

    @Test
    fun concurrentInvoiceAllocationIsGapFreeAndUnique() {
        val f = insertFixture()
        val workers = 64
        val parallelism = 16
        val ready = CountDownLatch(parallelism)
        val start = CountDownLatch(1)
        val executor = Executors.newFixedThreadPool(parallelism)
        try {
            val numbers = (1..workers).map {
                executor.submit<String> {
                    ready.countDown()
                    check(start.await(20, TimeUnit.SECONDS))
                    requireNotNull(
                        transactions.execute {
                            jdbc.queryForObject(
                                "SELECT set_config('app.current_tenant_id', ?, true)",
                                String::class.java,
                                f.tenantId.toString(),
                            )
                            jdbc.queryForObject(
                                """
                                SELECT formatted_document_number
                                FROM allocate_document_number(?, 'invoice', ?)
                                """.trimIndent(),
                                String::class.java,
                                f.tenantId,
                                f.year,
                            )
                        },
                    )
                }
            }
            check(ready.await(20, TimeUnit.SECONDS))
            start.countDown()
            val allocated = numbers.map { it.get(60, TimeUnit.SECONDS) }
            assertEquals(workers, allocated.toSet().size)
            assertEquals(
                (1..workers).map { "INV-${f.year}-${it.toString().padStart(6, '0')}" },
                allocated.sorted(),
            )
        } finally {
            executor.shutdownNow()
        }
        assertEquals(
            workers.toLong() + 1,
            jdbc.queryForObject(
                "SELECT next_value FROM document_sequences WHERE tenant_id = ? AND document_type = 'invoice'",
                Long::class.java,
                f.tenantId,
            ),
        )
    }

    private fun insertFixture(): Fixture {
        val f = Fixture(
            planId = UUID.randomUUID(),
            tenantId = UUID.randomUUID(),
            year = LocalDate.now().year.toShort(),
        )
        fixture = f
        jdbc.update(
            "INSERT INTO plans (id, name, code) VALUES (?, 'Sequence Plan', ?)",
            f.planId,
            "sequence-${f.planId}",
        )
        jdbc.update(
            """
            INSERT INTO tenants (id, name, slug, status, schema_name, plan_id)
            VALUES (?, 'Sequence Tenant', ?, 'active', ?, ?)
            """.trimIndent(),
            f.tenantId,
            "sequence-${f.tenantId}",
            "tenant_${f.tenantId}".replace("-", "_"),
            f.planId,
        )
        jdbc.update(
            """
            INSERT INTO document_sequences (
                id, tenant_id, document_type, prefix, year, next_value, padding
            ) VALUES (?, ?, 'invoice', 'INV', ?, 1, 6)
            """.trimIndent(),
            UUID.randomUUID(),
            f.tenantId,
            f.year,
        )
        return f
    }

    private data class Fixture(
        val planId: UUID,
        val tenantId: UUID,
        val year: Short,
    )
}
