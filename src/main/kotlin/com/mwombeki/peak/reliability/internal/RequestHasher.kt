package com.mwombeki.peak.reliability.internal

import com.mwombeki.peak.shared.context.RequestContext
import java.security.MessageDigest
import java.util.HexFormat
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

@Component
class RequestHasher(
    private val objectMapper: ObjectMapper,
) {
    fun hash(
        context: RequestContext,
        operationType: String,
        payload: Any?,
    ): String {
        val canonicalPayload = payload?.let(objectMapper::writeValueAsString).orEmpty()
        val canonical = listOf(
            context.httpMethod.uppercase(),
            context.requestPath,
            operationType,
            canonicalPayload,
        ).joinToString("\n")

        val digest = MessageDigest.getInstance("SHA-256").digest(
            canonical.toByteArray(Charsets.UTF_8),
        )
        return HexFormat.of().formatHex(digest)
    }
}
