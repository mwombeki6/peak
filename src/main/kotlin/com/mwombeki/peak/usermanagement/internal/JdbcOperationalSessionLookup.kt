package com.mwombeki.peak.usermanagement.internal

import com.mwombeki.peak.shared.context.OperationalSessionAuthentication
import com.mwombeki.peak.shared.context.OperationalSessionLookup
import com.mwombeki.peak.shared.context.OperationalSessionPrincipal
import java.security.MessageDigest
import java.util.HexFormat
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

@Component
class JdbcOperationalSessionLookup(
    private val jdbcTemplate: JdbcTemplate,
) : OperationalSessionLookup {
    override fun findActive(bearerToken: String): OperationalSessionPrincipal? {
        if (!bearerToken.startsWith(OperationalSessionAuthentication.TOKEN_PREFIX)) {
            return null
        }
        return jdbcTemplate.query(
            """
            SELECT id, tenant_id, user_id, device_id, property_id
            FROM lookup_operational_session(?)
            """.trimIndent(),
            { rs, _ ->
                OperationalSessionPrincipal(
                    sessionId = rs.getObject("id", UUID::class.java),
                    tenantId = rs.getObject("tenant_id", UUID::class.java),
                    tenantUserId = rs.getObject("user_id", UUID::class.java),
                    deviceId = rs.getObject("device_id", UUID::class.java),
                    propertyId = rs.getObject("property_id", UUID::class.java),
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
