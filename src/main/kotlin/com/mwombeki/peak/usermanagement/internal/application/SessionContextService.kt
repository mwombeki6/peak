package com.mwombeki.peak.usermanagement.internal.application

import com.mwombeki.peak.shared.context.DatabaseSessionContext
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import com.mwombeki.peak.usermanagement.api.HospitalityPropertyAccess
import com.mwombeki.peak.usermanagement.api.HospitalitySessionResponse
import com.mwombeki.peak.usermanagement.api.PlatformSessionResponse
import com.mwombeki.peak.usermanagement.api.SessionContextPort
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.server.ResponseStatusException

@Service
class SessionContextService(
    private val requestContextHolder: RequestContextHolder,
    private val databaseSessionContext: DatabaseSessionContext,
    private val jdbcTemplate: JdbcTemplate,
    private val transactionTemplate: TransactionTemplate,
) : SessionContextPort {

    override fun hospitalitySession(): HospitalitySessionResponse {
        val identity = requestContextHolder.current().identity as? RequestIdentity.Tenant
            ?: throw ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "Authenticated hospitality identity is required",
            )

        return transactionTemplate.execute {
            databaseSessionContext.bind(identity)
            val user = loadTenantUser(identity)
            val propertyRows = loadProperties(identity)
            val propertyIds = propertyRows.map(PropertyRow::propertyId).toSet()
            val propertyRoles = loadPropertyValues(identity, PROPERTY_ROLES_SQL, propertyIds)
            val propertyPermissions = loadPropertyValues(
                identity,
                PROPERTY_PERMISSIONS_SQL,
                propertyIds,
            )
            val propertyModules = loadPropertyValues(identity, PROPERTY_MODULES_SQL, propertyIds)

            HospitalitySessionResponse(
                identityMode = TENANT_IDENTITY_MODE,
                tenantId = identity.tenantId,
                userId = identity.tenantUserId,
                fullName = user.fullName,
                email = user.email,
                languagePreference = user.languagePreference,
                tenantRoleCodes = loadTenantValues(identity, TENANT_ROLES_SQL),
                tenantPermissionCodes = loadTenantValues(identity, TENANT_PERMISSIONS_SQL),
                enabledTenantModules = loadTenantModules(identity),
                properties = propertyRows.map { property ->
                    HospitalityPropertyAccess(
                        propertyId = property.propertyId,
                        name = property.name,
                        code = property.code,
                        status = property.status,
                        roleNames = propertyRoles[property.propertyId].orEmpty(),
                        permissionCodes = propertyPermissions[property.propertyId].orEmpty(),
                        enabledModules = propertyModules[property.propertyId].orEmpty(),
                    )
                },
            )
        }
    }

    override fun platformSession(): PlatformSessionResponse {
        val identity = requestContextHolder.current().identity as? RequestIdentity.Platform
            ?: throw ResponseStatusException(
                HttpStatus.UNAUTHORIZED,
                "Authenticated platform identity is required",
            )

        return transactionTemplate.execute {
            databaseSessionContext.bind(identity)
            val user = jdbcTemplate.query(
                """
                SELECT full_name, email
                FROM platform_users
                WHERE id = ?
                  AND status = 'active'
                  AND deleted_at IS NULL
                  AND (locked_until IS NULL OR locked_until <= now())
                """.trimIndent(),
                { rs, _ ->
                    PlatformUserRow(
                        fullName = rs.getString("full_name"),
                        email = rs.getString("email"),
                    )
                },
                identity.platformUserId,
            ).singleOrNull() ?: unavailableSession()

            val permissions = jdbcTemplate.queryForList(
                """
                SELECT code
                FROM permission_catalog
                WHERE is_platform_permission = true
                  AND platform_user_has_permission(?, code)
                ORDER BY code
                """.trimIndent(),
                String::class.java,
                identity.platformUserId,
            ).filterNotNull()

            PlatformSessionResponse(
                identityMode = PLATFORM_IDENTITY_MODE,
                platformUserId = identity.platformUserId,
                fullName = user.fullName,
                email = user.email,
                permissionCodes = permissions,
            )
        }
    }

    private fun loadTenantUser(identity: RequestIdentity.Tenant): TenantUserRow {
        return jdbcTemplate.query(
            """
            SELECT full_name, email, language_preference
            FROM users
            WHERE tenant_id = ?
              AND id = ?
              AND status = 'active'
              AND is_active = true
              AND deleted_at IS NULL
              AND (locked_until IS NULL OR locked_until <= now())
            """.trimIndent(),
            { rs, _ ->
                TenantUserRow(
                    fullName = rs.getString("full_name"),
                    email = rs.getString("email"),
                    languagePreference = rs.getString("language_preference"),
                )
            },
            identity.tenantId,
            identity.tenantUserId,
        ).singleOrNull() ?: unavailableSession()
    }

    private fun loadProperties(identity: RequestIdentity.Tenant): List<PropertyRow> {
        return jdbcTemplate.query(
            """
            SELECT DISTINCT p.id, p.name, p.code, p.status
            FROM properties p
            JOIN user_property_roles assignment
              ON assignment.tenant_id = p.tenant_id
             AND assignment.property_id = p.id
             AND assignment.user_id = ?
            WHERE p.tenant_id = ?
              AND p.deleted_at IS NULL
              AND p.is_active = true
            ORDER BY p.name, p.id
            """.trimIndent(),
            { rs, _ ->
                PropertyRow(
                    propertyId = rs.getObject("id", UUID::class.java),
                    name = rs.getString("name"),
                    code = rs.getString("code"),
                    status = rs.getString("status"),
                )
            },
            identity.tenantUserId,
            identity.tenantId,
        )
    }

    private fun loadTenantValues(
        identity: RequestIdentity.Tenant,
        sql: String,
    ): List<String> {
        return jdbcTemplate.queryForList(
            sql,
            String::class.java,
            identity.tenantUserId,
            identity.tenantId,
        ).filterNotNull()
    }

    private fun loadTenantModules(identity: RequestIdentity.Tenant): List<String> {
        return jdbcTemplate.queryForList(
            TENANT_MODULES_SQL,
            String::class.java,
            identity.tenantId,
        ).filterNotNull()
    }

    private fun loadPropertyValues(
        identity: RequestIdentity.Tenant,
        sql: String,
        allowedPropertyIds: Set<UUID>,
    ): Map<UUID, List<String>> {
        return jdbcTemplate.query(
            sql,
            { rs, _ ->
                PropertyValue(
                    propertyId = rs.getObject("property_id", UUID::class.java),
                    value = rs.getString("value"),
                )
            },
            identity.tenantUserId,
            identity.tenantId,
        )
            .filter { value -> value.propertyId in allowedPropertyIds }
            .groupBy(PropertyValue::propertyId, PropertyValue::value)
            .mapValues { (_, values) -> values.distinct().sorted() }
    }

    private fun unavailableSession(): Nothing {
        throw ResponseStatusException(
            HttpStatus.UNAUTHORIZED,
            "Authenticated identity is no longer available",
        )
    }

    private data class TenantUserRow(
        val fullName: String?,
        val email: String,
        val languagePreference: String?,
    )

    private data class PlatformUserRow(
        val fullName: String,
        val email: String,
    )

    private data class PropertyRow(
        val propertyId: UUID,
        val name: String,
        val code: String?,
        val status: String,
    )

    private data class PropertyValue(
        val propertyId: UUID,
        val value: String,
    )

    private companion object {
        const val TENANT_IDENTITY_MODE = "TENANT"
        const val PLATFORM_IDENTITY_MODE = "PLATFORM"

        val TENANT_ROLES_SQL = """
            SELECT DISTINCT role.code
            FROM user_tenant_roles assignment
            JOIN tenant_roles role
              ON role.tenant_id = assignment.tenant_id
             AND role.id = assignment.tenant_role_id
            WHERE assignment.user_id = ?
              AND assignment.tenant_id = ?
              AND role.is_active = true
            ORDER BY role.code
        """.trimIndent()

        val TENANT_PERMISSIONS_SQL = """
            SELECT DISTINCT permission.code
            FROM user_tenant_roles assignment
            JOIN tenant_roles role
              ON role.tenant_id = assignment.tenant_id
             AND role.id = assignment.tenant_role_id
            JOIN tenant_role_permissions role_permission
              ON role_permission.tenant_role_id = role.id
            JOIN permissions permission
              ON permission.tenant_id = assignment.tenant_id
             AND permission.id = role_permission.permission_id
            WHERE assignment.user_id = ?
              AND assignment.tenant_id = ?
              AND role.is_active = true
            ORDER BY permission.code
        """.trimIndent()

        val TENANT_MODULES_SQL = """
            SELECT module_id
            FROM tenant_modules
            WHERE tenant_id = ?
              AND is_enabled = true
            ORDER BY module_id
        """.trimIndent()

        val PROPERTY_ROLES_SQL = """
            SELECT assignment.property_id, role.name AS value
            FROM user_property_roles assignment
            JOIN roles role
              ON role.tenant_id = assignment.tenant_id
             AND role.id = assignment.role_id
            WHERE assignment.user_id = ?
              AND assignment.tenant_id = ?
              AND role.is_active = true
            ORDER BY assignment.property_id, role.name
        """.trimIndent()

        val PROPERTY_PERMISSIONS_SQL = """
            SELECT DISTINCT assignment.property_id, permission.code AS value
            FROM user_property_roles assignment
            JOIN roles role
              ON role.tenant_id = assignment.tenant_id
             AND role.id = assignment.role_id
            JOIN role_permissions role_permission
              ON role_permission.role_id = role.id
            JOIN permissions permission
              ON permission.tenant_id = assignment.tenant_id
             AND permission.id = role_permission.permission_id
            WHERE assignment.user_id = ?
              AND assignment.tenant_id = ?
              AND role.is_active = true
            ORDER BY assignment.property_id, permission.code
        """.trimIndent()

        val PROPERTY_MODULES_SQL = """
            SELECT assignment.property_id, module.module_id AS value
            FROM user_property_roles assignment
            JOIN property_modules module
              ON module.tenant_id = assignment.tenant_id
             AND module.property_id = assignment.property_id
            WHERE assignment.user_id = ?
              AND assignment.tenant_id = ?
              AND module.is_enabled = true
            ORDER BY assignment.property_id, module.module_id
        """.trimIndent()
    }
}
