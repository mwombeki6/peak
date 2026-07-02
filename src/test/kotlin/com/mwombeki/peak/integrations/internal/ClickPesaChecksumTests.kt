package com.mwombeki.peak.integrations.internal

import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test
import tools.jackson.databind.json.JsonMapper

class ClickPesaChecksumTests {
    private val checksum = ClickPesaChecksum(JsonMapper.builder().build())

    @Test
    fun `canonicalizes nested objects recursively and preserves array order`() {
        val payload =
            """
            {
              "z": 1,
              "data": {
                "b": 2,
                "checksum": "nested-ignored",
                "a": [{"d": 4, "c": 3}, 2]
              },
              "checksumMethod": "HMAC-SHA256",
              "checksum": "top-level-ignored",
              "a": "first"
            }
            """.trimIndent()

        assertEquals(
            """{"a":"first","data":{"a":[{"c":3,"d":4},2],"b":2},"z":1}""",
            checksum.canonicalJson(payload),
        )
    }

    @Test
    fun `verifies checksum independent of field order and rejects malformed values`() {
        val first = """{"amount":"1000","data":{"z":2,"a":1}}"""
        val reordered = """{"data":{"a":1,"z":2},"amount":"1000"}"""
        val signature = checksum.create(first, "test-checksum-secret")

        assertTrue(checksum.verify(reordered, "test-checksum-secret", signature))
        assertFalse(checksum.verify(reordered, "wrong-secret", signature))
        assertFalse(checksum.verify(reordered, "test-checksum-secret", "not-hex"))
    }
}
