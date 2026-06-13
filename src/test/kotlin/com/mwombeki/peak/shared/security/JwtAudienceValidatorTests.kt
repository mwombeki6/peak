package com.mwombeki.peak.shared.security

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.springframework.security.oauth2.jwt.Jwt

class JwtAudienceValidatorTests {

    @Test
    fun acceptsConfiguredAudience() {
        val result = JwtAudienceValidator("peak-api").validate(
            jwt("peak-api", "account"),
        )

        assertFalse(result.hasErrors())
    }

    @Test
    fun rejectsMissingConfiguredAudience() {
        val result = JwtAudienceValidator("peak-api").validate(
            jwt("account"),
        )

        assertTrue(result.hasErrors())
    }

    private fun jwt(vararg audience: String): Jwt {
        return Jwt.withTokenValue("token")
            .header("alg", "none")
            .claim("aud", audience.toList())
            .build()
    }
}
