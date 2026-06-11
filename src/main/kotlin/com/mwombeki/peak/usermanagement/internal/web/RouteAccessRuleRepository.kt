package com.mwombeki.peak.usermanagement.internal.web

import com.mwombeki.peak.usermanagement.api.GuardMode
import com.mwombeki.peak.usermanagement.api.RouteScope
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

interface RouteAccessRuleRepository {
    fun findEnabledRules(): List<RouteAccessRule>
}

@Repository
class JdbcRouteAccessRuleRepository(
    private val jdbcTemplate: JdbcTemplate,
) : RouteAccessRuleRepository {
    override fun findEnabledRules(): List<RouteAccessRule> {
        return jdbcTemplate.query(
            """
            SELECT module_id, http_method, api_pattern, permission_code,
                   route_scope, guard_mode
            FROM module_access_matrix
            WHERE is_enabled_by_default = true
            ORDER BY module_id, screen_key, http_method, api_pattern
            """.trimIndent(),
        ) { rs, _ ->
            RouteAccessRule(
                moduleId = rs.getString("module_id"),
                httpMethod = rs.getString("http_method"),
                apiPattern = rs.getString("api_pattern"),
                permissionCode = rs.getString("permission_code"),
                routeScope = rs.getString("route_scope").toRouteScope(),
                guardMode = rs.getString("guard_mode").toGuardMode(),
            )
        }
    }

    private fun String.toRouteScope(): RouteScope {
        return RouteScope.valueOf(uppercase())
    }

    private fun String.toGuardMode(): GuardMode {
        return GuardMode.valueOf(uppercase())
    }
}
