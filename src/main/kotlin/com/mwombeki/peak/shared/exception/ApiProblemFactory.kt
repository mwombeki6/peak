package com.mwombeki.peak.shared.exception

import com.mwombeki.peak.shared.context.RequestContextHolder
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.modulith.NamedInterface
import org.springframework.stereotype.Component

@NamedInterface("exception")
@Component
class ApiProblemFactory(
    private val requestContextHolder: RequestContextHolder,
) {
    fun response(
        status: HttpStatus,
        title: String,
        detail: String?,
        properties: Map<String, Any?> = emptyMap(),
    ): ResponseEntity<ProblemDetail> {
        val context = requestContextHolder.currentOrNull()
        val problem = ProblemDetail.forStatusAndDetail(
            status,
            PublicErrorSanitizer.sanitize(detail),
        )
        problem.title = title
        problem.setProperty("traceId", context?.correlationId ?: UUID.randomUUID().toString())
        problem.setProperty("path", context?.requestPath ?: "")
        properties.forEach { (key, value) ->
            if (value != null) {
                problem.setProperty(key, value)
            }
        }
        return ResponseEntity.status(status).body(problem)
    }
}

internal object PublicErrorSanitizer {
    private val email = Regex("""(?i)\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b""")
    private val jwt = Regex("""\beyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\b""")
    private val sensitiveAssignment = Regex(
        """(?i)\b(token|secret|password|credential|api[-_ ]?key)\s*[:=]\s*[^\s,;]+""",
    )
    private val signedQuery = Regex("""(?i)(https?://[^\s?]+)\?[^\s]+""")

    fun sanitize(detail: String?): String {
        val firstLine = detail
            ?.removePrefix("ERROR:")
            ?.lineSequence()
            ?.firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: "Request failed"

        return firstLine
            .replace(email, "[redacted-address]")
            .replace(jwt, "[redacted-token]")
            .replace(sensitiveAssignment) { match ->
                "${match.groupValues[1]}=[redacted]"
            }
            .replace(signedQuery) { match -> "${match.groupValues[1]}?[redacted]" }
    }
}
