package com.mwombeki.peak.usermanagement.internal.web

import com.mwombeki.peak.usermanagement.api.GuardMode
import com.mwombeki.peak.usermanagement.api.RouteScope
import java.time.Clock
import java.time.Instant
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository

interface RouteAccessRuleRepository {
    fun findEnabledRules(): List<RouteAccessRule>
}

@Repository
class JdbcRouteAccessRuleRepository(
    private val jdbcTemplate: JdbcTemplate,
    private val properties: RouteGuardProperties,
) : RouteAccessRuleRepository {
    @Volatile
    private var cachedRules: CachedRules? = null

    override fun findEnabledRules(): List<RouteAccessRule> {
        val now = Instant.now(clock)
        val cached = cachedRules
        if (cached != null && cached.expiresAt.isAfter(now)) {
            return cached.rules
        }

        val rules = loadEnabledRules()
        cachedRules = CachedRules(
            rules = rules,
            expiresAt = now.plus(properties.ruleCacheTtl),
        )
        return rules
    }

    private fun loadEnabledRules(): List<RouteAccessRule> {
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

    private data class CachedRules(
        val rules: List<RouteAccessRule>,
        val expiresAt: Instant,
    )

    private companion object {
        val clock: Clock = Clock.systemUTC()
    }
}
