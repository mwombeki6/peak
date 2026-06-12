package com.mwombeki.peak.usermanagement.internal

import com.mwombeki.peak.shared.context.ExternalIdentityPrincipal
import com.mwombeki.peak.shared.context.ExternalIdentityResolver
import com.mwombeki.peak.shared.context.RequestContextException
import com.mwombeki.peak.shared.context.ResolvedExternalIdentity
import java.sql.ResultSet
import java.util.UUID
import org.springframework.dao.EmptyResultDataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

@Component
class JdbcExternalIdentityResolver(
    private val jdbcTemplate: JdbcTemplate,
) : ExternalIdentityResolver {
    override fun resolve(principal: ExternalIdentityPrincipal): ResolvedExternalIdentity? {
        if (principal.provider != OIDC_PROVIDER) {
            return null
        }

        return try {
            jdbcTemplate.queryForObject(
                """
                SELECT identity_mode, tenant_id, user_id, platform_user_id
                FROM resolve_oidc_identity_link(?, ?)
                """.trimIndent(),
                { rs, _ -> mapResolvedIdentity(rs) },
                principal.issuer,
                principal.subject,
            )
        } catch (ex: EmptyResultDataAccessException) {
            null
        }
    }

    private fun mapResolvedIdentity(rs: ResultSet): ResolvedExternalIdentity {
        return when (val mode = rs.getString("identity_mode")) {
            "tenant" -> ResolvedExternalIdentity.Tenant(
                tenantId = rs.getObject("tenant_id", UUID::class.java),
                tenantUserId = rs.getObject("user_id", UUID::class.java),
            )

            "platform" -> ResolvedExternalIdentity.Platform(
                platformUserId = rs.getObject("platform_user_id", UUID::class.java),
            )

            else -> throw RequestContextException("Unsupported resolved identity mode: $mode")
        }
    }

    private companion object {
        const val OIDC_PROVIDER = "oidc"
    }
}
