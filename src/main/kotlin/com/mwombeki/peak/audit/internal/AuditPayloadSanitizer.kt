package com.mwombeki.peak.audit.internal

import org.springframework.stereotype.Component

@Component
class AuditPayloadSanitizer {
    fun sanitize(payload: Map<String, Any?>?): Map<String, Any?>? {
        return payload?.mapValues { (key, value) ->
            if (key.isSensitive()) {
                REDACTED
            } else {
                sanitizeValue(value)
            }
        }
    }

    private fun sanitizeValue(value: Any?): Any? {
        return when (value) {
            is Map<*, *> -> value.entries.associate { (nestedKey, nestedValue) ->
                val key = nestedKey.toString()
                key to if (key.isSensitive()) REDACTED else sanitizeValue(nestedValue)
            }

            is Iterable<*> -> value.map(::sanitizeValue)
            is Array<*> -> value.map(::sanitizeValue)
            else -> value
        }
    }

    private fun String.isSensitive(): Boolean {
        val normalized = lowercase()
        return SENSITIVE_KEY_PARTS.any { normalized.contains(it) }
    }

    private companion object {
        const val REDACTED = "[REDACTED]"

        val SENSITIVE_KEY_PARTS = listOf(
            "password",
            "secret",
            "token",
            "credential",
            "authorization",
            "auth",
            "otp",
            "pin",
            "documentnumber",
            "document_number",
            "nin",
            "passport",
        )
    }
}
