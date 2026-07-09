package com.mwombeki.peak.reporting.internal

import com.mwombeki.peak.nightaudit.api.NightAuditCloseSnapshotResponse
import java.math.BigDecimal
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.junit.jupiter.api.Test

class DeterministicPdfRendererTests {
    private val renderer = DeterministicPdfRenderer()

    @Test
    fun `renders deterministic PDF with embedded Unicode font`() {
        val snapshot = snapshot()

        val first = renderer.render("daily_management_summary", snapshot)
        val second = renderer.render("daily_management_summary", snapshot)

        assertContentEquals("%PDF".toByteArray(), first.copyOf(4))
        assertEquals(sha256(first), sha256(second))
        Loader.loadPDF(first).use { document ->
            assertTrue(document.numberOfPages >= 1)
            val fonts = document.pages.flatMap { page ->
                page.resources.fontNames.map { page.resources.getFont(it) }
            }
            assertTrue(fonts.any { it is PDType0Font && it.isEmbedded })
        }
    }

    private fun snapshot() = NightAuditCloseSnapshotResponse(
        id = UUID.fromString("10000000-0000-0000-0000-000000000001"),
        tenantId = UUID.fromString("10000000-0000-0000-0000-000000000002"),
        propertyId = UUID.fromString(
            "10000000-0000-0000-0000-000000000003",
        ),
        nightAuditRunId = UUID.fromString(
            "10000000-0000-0000-0000-000000000004",
        ),
        businessDate = LocalDate.of(2026, 7, 3),
        schemaVersion = 1,
        currency = "TZS",
        payloadHash =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        availableRooms = 10,
        roomsSold = 5,
        occupiedRooms = 5,
        occupancy = BigDecimal("0.50"),
        adr = BigDecimal("100.00"),
        revpar = BigDecimal("50.00"),
        roomRevenue = BigDecimal("500.00"),
        posRevenue = BigDecimal("75.00"),
        taxTotal = BigDecimal("90.00"),
        grossTotal = BigDecimal("665.00"),
        netTotal = BigDecimal("575.00"),
        revenueJournalDifference = BigDecimal("0.00"),
        paymentAllocationDifference = BigDecimal("0.00"),
        payload = linkedMapOf(
            "currency" to "TZS",
            "guest" to "Mgeni – Dar es Salaam",
            "metrics" to linkedMapOf(
                "occupancy" to BigDecimal("0.50"),
                "adr" to BigDecimal("100.00"),
            ),
        ),
        capturedAt = Instant.parse("2026-07-04T00:00:00Z"),
    )

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
