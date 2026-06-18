package com.mwombeki.peak.integrations.internal

import com.mwombeki.peak.shared.context.RequestContext
import com.mwombeki.peak.shared.context.RequestIdentity
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

data class PublicRequestScope(
    val tenantId: UUID,
    val propertyId: UUID,
)

@Component
class PublicRequestScopeResolver(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun resolve(
        context: RequestContext,
        propertyId: UUID,
        moduleId: String,
    ): PublicRequestScope {
        val publicIdentity = context.identity as? RequestIdentity.Public
            ?: throw IllegalArgumentException("Public tenant/property context is required")

        if (publicIdentity.propertyId != null && publicIdentity.propertyId != propertyId) {
            throw IllegalArgumentException("Route property does not match public request context")
        }

        val rows = jdbcTemplate.query(
            """
            SELECT tenant_id, property_id
            FROM resolve_public_property_scope(?, ?)
            """.trimIndent(),
            { rs, _ ->
                PublicRequestScope(
                    tenantId = rs.getObject("tenant_id", UUID::class.java),
                    propertyId = rs.getObject("property_id", UUID::class.java),
                )
            },
            propertyId,
            moduleId,
        )

        return rows.singleOrNull()
            ?: throw IllegalStateException("Public module is not accessible for this property")
    }
}
