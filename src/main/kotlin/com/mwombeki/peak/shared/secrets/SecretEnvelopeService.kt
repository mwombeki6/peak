package com.mwombeki.peak.shared.secrets

import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.modulith.NamedInterface
import org.springframework.stereotype.Component

@NamedInterface("secrets")
@ConfigurationProperties(prefix = "peak.security.envelope")
data class SecretEnvelopeProperties(
    val keyReference: String = "",
    val previousKeyReference: String = "",
)

@NamedInterface("secrets")
@Component
class SecretEnvelopeService(
    private val properties: SecretEnvelopeProperties,
    private val secretReferenceResolver: SecretReferenceResolver,
) {
    private val secureRandom = SecureRandom()

    fun encrypt(plaintext: String, associatedData: String): String {
        require(plaintext.isNotEmpty()) {
            "Envelope plaintext is required"
        }
        val nonce = ByteArray(NONCE_LENGTH).also(secureRandom::nextBytes)
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(
            Cipher.ENCRYPT_MODE,
            key(properties.keyReference),
            GCMParameterSpec(TAG_LENGTH_BITS, nonce),
        )
        cipher.updateAAD(associatedData.toByteArray(Charsets.UTF_8))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return listOf(
            ENVELOPE_VERSION,
            encoder.encodeToString(nonce),
            encoder.encodeToString(ciphertext),
        ).joinToString(".")
    }

    fun decrypt(envelope: String, associatedData: String): String {
        val parts = envelope.split('.')
        require(parts.size == 3 && parts[0] == ENVELOPE_VERSION) {
            "Unsupported secret envelope"
        }
        val nonce = decoder.decode(parts[1])
        require(nonce.size == NONCE_LENGTH) {
            "Invalid secret envelope nonce"
        }
        val ciphertext = decoder.decode(parts[2])
        val references = listOf(
            properties.keyReference,
            properties.previousKeyReference,
        ).filter(String::isNotBlank)
        references.forEach { reference ->
            val plaintext = runCatching {
                val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
                cipher.init(
                    Cipher.DECRYPT_MODE,
                    key(reference),
                    GCMParameterSpec(TAG_LENGTH_BITS, nonce),
                )
                cipher.updateAAD(associatedData.toByteArray(Charsets.UTF_8))
                cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
            }.getOrNull()
            if (plaintext != null) {
                return plaintext
            }
        }
        throw IllegalArgumentException("Secret envelope authentication failed")
    }

    private fun key(reference: String): SecretKeySpec {
        val keyBytes = try {
            Base64.getDecoder().decode(secretReferenceResolver.resolve(reference))
        } catch (ex: IllegalArgumentException) {
            throw SecretReferenceException("Envelope key must be valid Base64")
        }
        require(keyBytes.size == KEY_LENGTH) {
            "Envelope key must be exactly 256 bits"
        }
        return SecretKeySpec(keyBytes, KEY_ALGORITHM)
    }

    private companion object {
        const val ENVELOPE_VERSION = "v1"
        const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_ALGORITHM = "AES"
        const val KEY_LENGTH = 32
        const val NONCE_LENGTH = 12
        const val TAG_LENGTH_BITS = 128
        val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()
        val decoder: Base64.Decoder = Base64.getUrlDecoder()
    }
}
