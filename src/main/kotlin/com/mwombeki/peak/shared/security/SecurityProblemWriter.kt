package com.mwombeki.peak.shared.security

import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.modulith.NamedInterface
import org.springframework.stereotype.Component

@NamedInterface("security")
@Component
class SecurityProblemWriter {
    fun write(
        response: HttpServletResponse,
        status: HttpStatus,
        title: String,
        detail: String,
    ) {
        response.status = status.value()
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        response.writer.write(
            """
            {"type":"about:blank","title":"${title.jsonEscaped()}","status":${status.value()},"detail":"${detail.jsonEscaped()}"}
            """.trimIndent(),
        )
    }

    private fun String.jsonEscaped(): String {
        return buildString {
            for (char in this@jsonEscaped) {
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(char)
                }
            }
        }
    }
}
