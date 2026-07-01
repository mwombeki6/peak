package com.mwombeki.peak.shared.context

import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.modulith.NamedInterface
import org.springframework.stereotype.Component

@NamedInterface("context")
data class TenantActor(
    val tenantId: UUID,
    val tenantUserId: UUID,
)

@NamedInterface("context")
@Component
class TenantRequestContext(
    private val requestContextHolder: RequestContextHolder,
    private val databaseSessionContext: DatabaseSessionContext,
    private val jdbcTemplate: JdbcTemplate,
) {
    fun bind(): TenantActor {
        val identity = requestContextHolder.current().identity
        require(identity is RequestIdentity.Tenant) {
            "Tenant user identity is required"
        }
        databaseSessionContext.bind(identity)
        requireTenantUsable(identity.tenantId)
        return TenantActor(identity.tenantId, identity.tenantUserId)
    }

    fun requireTenantUsable(tenantId: UUID) {
        val exists = jdbcTemplate.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1
                FROM tenants
                WHERE id = ?
                  AND status IN ('trial', 'active')
                  AND deleted_at IS NULL
            )
            """.trimIndent(),
            Boolean::class.java,
            tenantId,
        ) == true
        require(exists) {
            "Active tenant was not found"
        }
    }

    fun requirePropertyUsable(
        tenantId: UUID,
        propertyId: UUID,
        lock: Boolean = false,
    ) {
        val sql = """
            SELECT EXISTS (
                SELECT 1
                FROM properties
                WHERE tenant_id = ?
                  AND id = ?
                  AND status = 'active'
                  AND is_active = true
                  AND deleted_at IS NULL
                ${if (lock) "FOR UPDATE" else ""}
            )
        """.trimIndent()
        val exists = jdbcTemplate.queryForObject(sql, Boolean::class.java, tenantId, propertyId) == true
        require(exists) {
            "Active property was not found"
        }
    }
}
