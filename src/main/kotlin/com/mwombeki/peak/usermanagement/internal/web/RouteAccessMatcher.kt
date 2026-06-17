package com.mwombeki.peak.usermanagement.internal.web

import com.mwombeki.peak.usermanagement.api.RouteAuthorizationRequest
import java.util.UUID
import com.mwombeki.peak.shared.context.RequestIdentity
import org.springframework.stereotype.Component

@Component
class RouteAccessMatcher {
    fun match(
        httpMethod: String,
        requestPath: String,
        identity: RequestIdentity,
        rules: List<RouteAccessRule>,
    ): RouteAuthorizationRequest? {
        val normalizedPath = requestPath.normalizedApiPath()
        val candidates = rules.mapNotNull { rule ->
            if (!rule.methodMatches(httpMethod)) {
                return@mapNotNull null
            }

            val match = matchPattern(rule.apiPattern, normalizedPath) ?: return@mapNotNull null
            val request = rule.toAuthorizationRequest(match.variables, identity)
            RouteRuleMatch(request, rule.specificityScore())
        }

        return candidates.maxWithOrNull(
            compareBy<RouteRuleMatch> { it.score.methodScore }
                .thenBy { it.score.literalCount }
                .thenBy { it.score.segmentCount }
                .thenByDescending { it.score.wildcardCount },
        )?.request
    }

    private fun RouteAccessRule.methodMatches(httpMethod: String): Boolean {
        return this.httpMethod == "ANY" ||
                this.httpMethod.equals(httpMethod, ignoreCase = true)
    }

    private fun RouteAccessRule.toAuthorizationRequest(
        variables: Map<String, String>,
        identity: RequestIdentity,
    ): RouteAuthorizationRequest {
        return RouteAuthorizationRequest(
            moduleId = moduleId,
            guardMode = guardMode,
            routeScope = routeScope,
            permissionCode = permissionCode,
            tenantId = variables.uuid("tenantId") ?: identity.tenantIdOrNull(),
            propertyId = variables.uuid("propertyId") ?: identity.propertyIdOrNull(),
        )
    }

    private fun matchPattern(
        pattern: String,
        requestPath: String,
    ): PatternMatch? {
        val patternSegments = pattern.trim('/').split('/').filter { it.isNotBlank() }
        val pathSegments = requestPath.trim('/').split('/').filter { it.isNotBlank() }
        val variables = linkedMapOf<String, String>()
        var pathIndex = 0

        for ((patternIndex, patternSegment) in patternSegments.withIndex()) {
            if (patternSegment == "*") {
                return PatternMatch(variables)
            }

            val pathSegment = pathSegments.getOrNull(pathIndex) ?: return null

            when {
                patternSegment.endsWith("*") -> {
                    val prefix = patternSegment.dropLast(1)
                    if (!pathSegment.startsWith(prefix)) {
                        return null
                    }
                    if (patternIndex == patternSegments.lastIndex) {
                        return PatternMatch(variables)
                    }
                }

                patternSegment.startsWith(":") -> {
                    variables[patternSegment.drop(1)] = pathSegment
                }

                patternSegment != pathSegment -> return null
            }

            pathIndex += 1
        }

        return if (pathIndex == pathSegments.size) {
            PatternMatch(variables)
        } else {
            null
        }
    }

    private fun String.normalizedApiPath(): String {
        val match = API_VERSION_PATTERN.matchEntire(this) ?: return this
        return "/api${match.groupValues[1]}"
    }

    private fun Map<String, String>.uuid(name: String): UUID? {
        val value = get(name) ?: return null
        return UUID.fromString(value)
    }

    private fun RequestIdentity.tenantIdOrNull(): UUID? {
        return when (this) {
            is RequestIdentity.Tenant -> tenantId
            is RequestIdentity.Public -> tenantId
            is RequestIdentity.Support -> tenantId
            is RequestIdentity.Platform -> null
        }
    }

    private fun RequestIdentity.propertyIdOrNull(): UUID? {
        return when (this) {
            is RequestIdentity.Public -> propertyId
            is RequestIdentity.Platform,
            is RequestIdentity.Support,
            is RequestIdentity.Tenant -> null
        }
    }

    private data class PatternMatch(
        val variables: Map<String, String>,
    )

    private data class RouteRuleMatch(
        val request: RouteAuthorizationRequest,
        val score: SpecificityScore,
    )

    private data class SpecificityScore(
        val methodScore: Int,
        val literalCount: Int,
        val segmentCount: Int,
        val wildcardCount: Int,
    )

    private fun RouteAccessRule.specificityScore(): SpecificityScore {
        val segments = apiPattern.trim('/').split('/').filter { it.isNotBlank() }
        return SpecificityScore(
            methodScore = if (httpMethod == "ANY") 0 else 1,
            literalCount = segments.count { !it.startsWith(":") && !it.contains("*") },
            segmentCount = segments.size,
            wildcardCount = segments.count { it.contains("*") },
        )
    }

    private companion object {
        val API_VERSION_PATTERN = Regex("^/api/v\\d+(/.*)?$")
    }
}
