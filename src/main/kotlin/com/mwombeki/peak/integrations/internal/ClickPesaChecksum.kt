package com.mwombeki.peak.integrations.internal

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat
import java.util.TreeMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

@Component
class ClickPesaChecksum(
    private val objectMapper: ObjectMapper,
) {
    fun canonicalJson(payload: String): String {
        val value = objectMapper.readValue(payload, Any::class.java)
        return objectMapper.writeValueAsString(canonicalize(value))
    }

    fun create(payload: String, secret: String): String {
        val mac = Mac.getInstance(HMAC_SHA_256)
        mac.init(
            SecretKeySpec(
                secret.toByteArray(StandardCharsets.UTF_8),
                HMAC_SHA_256,
            ),
        )
        return HexFormat.of().formatHex(
            mac.doFinal(
                canonicalJson(payload).toByteArray(StandardCharsets.UTF_8),
            ),
        )
    }

    fun verify(payload: String, secret: String, supplied: String): Boolean {
        val expected = HexFormat.of().parseHex(create(payload, secret))
        val actual = try {
            HexFormat.of().parseHex(supplied.trim().lowercase())
        } catch (_: IllegalArgumentException) {
            return false
        }
        return MessageDigest.isEqual(expected, actual)
    }

    private fun canonicalize(value: Any?): Any? {
        return when (value) {
            is Map<*, *> -> TreeMap<String, Any?>().apply {
                value.entries
                    .filterNot {
                        it.key == "checksum" || it.key == "checksumMethod"
                    }
                    .forEach { (key, child) ->
                        put(key.toString(), canonicalize(child))
                    }
            }
            is List<*> -> value.map(::canonicalize)
            else -> value
        }
    }

    private companion object {
        const val HMAC_SHA_256 = "HmacSHA256"
    }
}
