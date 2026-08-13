package com.mwombeki.peak.integrations.internal

import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.concurrent.atomic.AtomicReference
import org.springframework.stereotype.Component

/**
 * Fetches and caches AzamPay's RSA public key.
 *
 * Cached because it changes rarely and a fetch on every callback would put an outbound
 * round trip in the path of settling a payment. [refresh] exists because "rarely" is not
 * "never": when a signature fails to verify, a rotated key is a likelier explanation than
 * an attack, so the caller retries once against a freshly fetched key before rejecting.
 */
@Component
class AzamPayPublicKeyProvider(
    private val transport: AzamPayHttpTransport,
) {
    private val cached = AtomicReference<CachedKey?>(null)

    fun publicKey(baseUrl: String?): PublicKey {
        cached.get()?.let { current ->
            if (current.baseUrl == baseUrl) {
                return current.key
            }
        }
        return refresh(baseUrl)
    }

    fun refresh(baseUrl: String?): PublicKey {
        val endpoint = azamPayEndpoint(baseUrl, PUBLIC_KEY_PATH)
        val response = transport.exchange(
            method = "GET",
            endpoint = endpoint,
            headers = emptyMap(),
            payload = null,
        )
        val key = parsePublicKey(response)
        cached.set(CachedKey(baseUrl, key))
        return key
    }

    /**
     * AzamPay serves the key as PEM, sometimes bare and sometimes wrapped in a JSON
     * envelope. Both are accepted; anything that does not yield an RSA key is rejected
     * rather than defaulted, since a missing key must never read as "no signature needed".
     */
    private fun parsePublicKey(response: String): PublicKey {
        val pem = extractPem(response)
        val base64 = pem
            .replace("-----BEGIN PUBLIC KEY-----", "")
            .replace("-----END PUBLIC KEY-----", "")
            .replace(Regex("\\s"), "")
        require(base64.isNotEmpty()) { "AzamPay public key response contained no key" }

        val decoded = runCatching { Base64.getDecoder().decode(base64) }.getOrElse {
            throw IllegalArgumentException("AzamPay public key was not valid base64", it)
        }
        return runCatching {
            KeyFactory.getInstance("RSA").generatePublic(X509EncodedKeySpec(decoded))
        }.getOrElse {
            throw IllegalArgumentException("AzamPay public key was not a valid RSA key", it)
        }
    }

    private fun extractPem(response: String): String {
        val trimmed = response.trim()
        if (trimmed.contains("BEGIN PUBLIC KEY")) {
            return trimmed
        }
        // A JSON envelope such as {"data":"-----BEGIN PUBLIC KEY-----..."}; pull the first
        // string that looks like a key rather than binding to a field name the docs do not
        // pin down.
        val match = Regex("-----BEGIN PUBLIC KEY-----[^\"]*-----END PUBLIC KEY-----")
            .find(trimmed.replace("\\n", "\n"))
        return match?.value ?: trimmed
    }

    private data class CachedKey(val baseUrl: String?, val key: PublicKey)

    private companion object {
        const val PUBLIC_KEY_PATH = "/azampay/v1/public-key?format=Pem"
    }
}

/**
 * Verifies the RSA signature AzamPay puts on a callback.
 *
 * ## The signed message
 *
 * AzamPay's documentation is not self-consistent about which fields the signature covers:
 * one page describes two, their published sample code concatenates four. This implements
 * the four-field form and **fails closed**.
 *
 * Accepting either form would be the accommodating choice and is the wrong one. If a
 * signature only covered `{utilityref}{externalreference}`, then a man-in-the-middle could
 * flip `transactionstatus` from failure to success and the signature would still verify —
 * we would be paying out entitlements on forged confirmations. Being liberal in what you
 * accept is a virtue for parsing and a vulnerability for authentication.
 *
 * If sandbox shows AzamPay signs only two fields, the answer is not to relax this to two.
 * It is to treat the callback as an untrusted hint and confirm every settlement with an
 * out-of-band status query before granting anything.
 */
@Component
class AzamPaySignature {
    fun signedMessage(
        utilityReference: String,
        externalReference: String,
        transactionStatus: String,
        operator: String,
    ): String = utilityReference + externalReference + transactionStatus + operator

    fun verify(
        publicKey: PublicKey,
        message: String,
        signatureBase64: String,
    ): Boolean {
        val signatureBytes = runCatching {
            Base64.getDecoder().decode(signatureBase64.trim())
        }.getOrElse { return false }

        return runCatching {
            Signature.getInstance(ALGORITHM).apply {
                initVerify(publicKey)
                update(message.toByteArray(Charsets.UTF_8))
            }.verify(signatureBytes)
        }.getOrElse { false }
    }

    private companion object {
        /** PKCS#1 v1.5 over SHA-256, which is what AzamPay's sample code produces. */
        const val ALGORITHM = "SHA256withRSA"
    }
}
