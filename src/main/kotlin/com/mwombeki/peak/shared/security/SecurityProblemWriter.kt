package com.mwombeki.peak.shared.security

import com.mwombeki.peak.shared.context.RequestContextHolder
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.modulith.NamedInterface
import org.springframework.stereotype.Component

@NamedInterface("security")
@Component
class SecurityProblemWriter(
    private val requestContextHolder: RequestContextHolder? = null,
) {
    fun write(
        response: HttpServletResponse,
        status: HttpStatus,
        title: String,
        detail: String,
    ) {
        response.status = status.value()
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        val context = requestContextHolder?.currentOrNull()
        val traceId = context?.correlationId.orEmpty().jsonEscaped()
        val path = context?.requestPath.orEmpty().jsonEscaped()
        response.writer.write(
            """
            {"type":"about:blank","title":"${title.jsonEscaped()}","status":${status.value()},"detail":"${detail.jsonEscaped()}","traceId":"$traceId","path":"$path"}
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
