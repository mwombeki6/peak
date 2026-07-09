package com.mwombeki.peak.reporting.internal

import com.mwombeki.peak.nightaudit.api.NightAuditCloseSnapshotResponse
import java.io.ByteArrayOutputStream
import java.time.ZoneOffset
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone
import org.apache.pdfbox.cos.COSArray
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.cos.COSString
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.springframework.stereotype.Component

@Component
class DeterministicPdfRenderer {
    fun render(
        reportCode: String,
        snapshot: NightAuditCloseSnapshotResponse,
    ): ByteArray {
        require(reportCode in CORE_REPORT_CODES) {
            "Unsupported report generator: $reportCode"
        }
        val lines = buildLines(reportCode, snapshot)
        PDDocument().use { document ->
            val font = javaClass.getResourceAsStream(FONT_RESOURCE).use {
                requireNotNull(it) {
                    "Embedded Unicode report font is unavailable"
                }
                PDType0Font.load(document, it, true)
            }
            val fixedCalendar = GregorianCalendar(
                TimeZone.getTimeZone("UTC"),
            ).apply {
                timeInMillis = snapshot.businessDate
                    .atStartOfDay()
                    .toInstant(ZoneOffset.UTC)
                    .toEpochMilli()
            }
            document.documentInformation.apply {
                title = reportCode
                author = "Peak"
                subject = "Night audit close ${snapshot.businessDate}"
                creator = "Peak deterministic reporting"
                producer = "Peak PDF renderer"
                creationDate = fixedCalendar.clone() as Calendar
                modificationDate = fixedCalendar.clone() as Calendar
            }
            val documentId = COSArray().apply {
                add(COSString(snapshot.payloadHash.take(32)))
                add(COSString(snapshot.payloadHash.takeLast(32)))
            }
            document.document.trailer.setItem(COSName.ID, documentId)

            var page: PDPage? = null
            var content: PDPageContentStream? = null
            var y = 0f
            try {
                lines.forEachIndexed { index, line ->
                    if (page == null || y < BOTTOM_MARGIN) {
                        content?.close()
                        page = PDPage(PDRectangle.A4)
                        document.addPage(page)
                        content = PDPageContentStream(document, page)
                        y = PDRectangle.A4.height - TOP_MARGIN
                    }
                    val stream = requireNotNull(content)
                    stream.beginText()
                    stream.setFont(
                        font,
                        if (index == 0) TITLE_FONT_SIZE else BODY_FONT_SIZE,
                    )
                    stream.newLineAtOffset(LEFT_MARGIN, y)
                    stream.showText(line)
                    stream.endText()
                    y -= if (index == 0) TITLE_LINE_HEIGHT else BODY_LINE_HEIGHT
                }
            } finally {
                content?.close()
            }
            return ByteArrayOutputStream().use { output ->
                document.save(output)
                output.toByteArray()
            }
        }
    }

    private fun buildLines(
        reportCode: String,
        snapshot: NightAuditCloseSnapshotResponse,
    ): List<String> {
        val title = when (reportCode) {
            "daily_management_summary" -> "Daily Management Summary"
            else -> "Night Audit Close"
        }
        val lines = mutableListOf(
            title,
            "Business date: ${snapshot.businessDate}",
            "Property: ${snapshot.propertyId}",
            "Currency: ${snapshot.currency}",
            "Snapshot schema: ${snapshot.schemaVersion}",
            "Snapshot hash: ${snapshot.payloadHash}",
            "",
        )
        flatten("", snapshot.payload, lines)
        return lines.flatMap(::wrap)
    }

    private fun flatten(
        prefix: String,
        value: Any?,
        lines: MutableList<String>,
    ) {
        when (value) {
            is Map<*, *> -> value.entries
                .sortedBy { it.key.toString() }
                .forEach { (key, nested) ->
                    val path = if (prefix.isEmpty()) {
                        key.toString()
                    } else {
                        "$prefix.${key.toString()}"
                    }
                    flatten(path, nested, lines)
                }
            is Iterable<*> -> value.forEachIndexed { index, nested ->
                flatten("$prefix[$index]", nested, lines)
            }
            else -> lines += "$prefix: ${value ?: ""}"
        }
    }

    private fun wrap(line: String): List<String> {
        if (line.length <= MAX_LINE_LENGTH) {
            return listOf(line)
        }
        val result = mutableListOf<String>()
        var remaining = line
        while (remaining.length > MAX_LINE_LENGTH) {
            val breakAt = remaining
                .take(MAX_LINE_LENGTH + 1)
                .lastIndexOf(' ')
                .takeIf { it > 0 }
                ?: MAX_LINE_LENGTH
            result += remaining.take(breakAt)
            remaining = remaining.drop(breakAt).trimStart()
        }
        result += remaining
        return result
    }

    private companion object {
        const val FONT_RESOURCE = "/liberation/LiberationSans-Regular.ttf"
        const val LEFT_MARGIN = 48f
        const val TOP_MARGIN = 48f
        const val BOTTOM_MARGIN = 48f
        const val TITLE_FONT_SIZE = 16f
        const val BODY_FONT_SIZE = 9f
        const val TITLE_LINE_HEIGHT = 24f
        const val BODY_LINE_HEIGHT = 12f
        const val MAX_LINE_LENGTH = 98
        val CORE_REPORT_CODES = setOf(
            "daily_management_summary",
            "night_audit_close",
        )
    }
}
