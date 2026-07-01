package com.mwombeki.peak.reservations.internal

import java.nio.charset.StandardCharsets
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.springframework.stereotype.Component

@Component
class GuestIdentityNumberHasher(
    private val properties: GuestIdentityProperties,
) {
    fun fingerprint(documentType: String, documentNumber: String): String {
        return fingerprint(properties.hashKey.requireUsable(), documentType, documentNumber)
    }

    fun candidateFingerprints(documentType: String, documentNumber: String): List<String> {
        return buildList {
            add(fingerprint(documentType, documentNumber))
            if (properties.previousHashKey.isNotBlank()) {
                add(fingerprint(properties.previousHashKey, documentType, documentNumber))
            }
        }.distinct()
    }

    private fun fingerprint(key: String, documentType: String, documentNumber: String): String {
        val value = "$documentType:${normalize(documentNumber)}"
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(key.toByteArray(StandardCharsets.UTF_8), HMAC_ALGORITHM))
        return HexFormat.of().formatHex(mac.doFinal(value.toByteArray(StandardCharsets.UTF_8)))
    }

    fun lastFour(documentNumber: String): String {
        return normalize(documentNumber).takeLast(4)
    }

    fun masked(documentNumber: String): String {
        return "***${lastFour(documentNumber)}"
    }

    fun validate(documentNumber: String) {
        val normalized = normalize(documentNumber)
        require(normalized.length in 4..64) {
            "Document number must contain between 4 and 64 letters or digits"
        }
    }

    private fun normalize(value: String): String {
        return value
            .trim()
            .uppercase()
            .filter(Char::isLetterOrDigit)
    }

    private fun String.requireUsable(): String {
        require(isNotBlank()) {
            "Guest identity hash key is not configured"
        }
        return this
    }

    private companion object {
        const val HMAC_ALGORITHM = "HmacSHA256"
    }
}
