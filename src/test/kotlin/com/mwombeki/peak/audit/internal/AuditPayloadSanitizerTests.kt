package com.mwombeki.peak.audit.internal

import kotlin.test.Test
import kotlin.test.assertEquals

class AuditPayloadSanitizerTests {

    @Test
    fun redactsSensitiveKeysRecursively() {
        val sanitized = AuditPayloadSanitizer().sanitize(
            mapOf(
                "name" to "Peak",
                "password" to "plain",
                "nested" to mapOf(
                    "apiToken" to "secret-token",
                    "visible" to "value",
                ),
                "items" to listOf(
                    mapOf(
                        "secretReference" to "secret/path",
                        "code" to "SAFE",
                    ),
                ),
            ),
        )

        assertEquals("Peak", sanitized?.get("name"))
        assertEquals("[REDACTED]", sanitized?.get("password"))

        val nested = sanitized?.get("nested") as Map<*, *>
        assertEquals("[REDACTED]", nested["apiToken"])
        assertEquals("value", nested["visible"])

        val items = sanitized["items"] as List<*>
        val first = items.first() as Map<*, *>
        assertEquals("[REDACTED]", first["secretReference"])
        assertEquals("SAFE", first["code"])
    }
}
