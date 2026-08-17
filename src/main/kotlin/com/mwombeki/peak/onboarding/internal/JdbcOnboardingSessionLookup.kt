package com.mwombeki.peak.onboarding.internal

import com.mwombeki.peak.shared.context.OnboardingSessionAuthentication
import com.mwombeki.peak.shared.context.OnboardingSessionLookup
import com.mwombeki.peak.shared.context.OnboardingSessionPrincipal
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

@Component
class JdbcOnboardingSessionLookup(
    private val jdbcTemplate: JdbcTemplate,
) : OnboardingSessionLookup {
    override fun findActive(bearerToken: String): OnboardingSessionPrincipal? {
        if (!bearerToken.startsWith(OnboardingSessionAuthentication.TOKEN_PREFIX)) {
            return null
        }
        return jdbcTemplate.query(
            "SELECT id, application_id FROM lookup_onboarding_session(?)",
            { rs, _ ->
                OnboardingSessionPrincipal(
                    sessionId = rs.getObject("id", UUID::class.java),
                    applicationId = rs.getObject("application_id", UUID::class.java),
                )
            },
            sha256Hex(bearerToken),
        ).firstOrNull()
    }

    private companion object {
        fun sha256Hex(value: String): String {
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(Charsets.UTF_8))
            return HexFormat.of().formatHex(digest)
        }
    }
}
