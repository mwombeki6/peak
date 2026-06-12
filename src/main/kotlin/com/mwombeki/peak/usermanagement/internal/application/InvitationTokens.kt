package com.mwombeki.peak.usermanagement.internal.application

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

object InvitationTokens {
    private const val TOKEN_BYTES = 32
    private val secureRandom = SecureRandom()
    private val base64Url = Base64.getUrlEncoder().withoutPadding()

    fun newToken(): String {
        val bytes = ByteArray(TOKEN_BYTES)
        secureRandom.nextBytes(bytes)
        return base64Url.encodeToString(bytes)
    }

    fun hash(token: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray(Charsets.UTF_8))
        return base64Url.encodeToString(digest)
    }
}
