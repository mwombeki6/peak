package com.mwombeki.peak.usermanagement.internal.web

import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping

@Component
class RouteMatrixStartupValidator(
    private val handlerMappings: List<RequestMappingHandlerMapping>,
    private val routeAccessRuleRepository: RouteAccessRuleRepository,
    private val properties: RouteGuardProperties,
    private val environment: Environment,
) : ApplicationRunner {
    override fun run(args: ApplicationArguments) {
        if (!properties.enabled ||
            !properties.validateRouteMatrixOnStartup ||
            environment.getProperty("peak.runtime.mode", "api").trim().lowercase() != "api"
        ) {
            return
        }

        val rules = routeAccessRuleRepository.findEnabledRules()
        val ambiguous = rules
            .groupBy { rule ->
                RouteKey(rule.httpMethod.uppercase(), rule.apiPattern)
            }
            .mapNotNull { (key, matchingRules) ->
                val contracts = matchingRules
                    .map { rule ->
                        AccessContract(
                            moduleId = rule.moduleId,
                            permissionCode = rule.permissionCode,
                            routeScope = rule.routeScope.name,
                            guardMode = rule.guardMode.name,
                        )
                    }
                    .distinct()
                if (contracts.size > 1) key to contracts else null
            }
            .sortedWith(
                compareBy<Pair<RouteKey, List<AccessContract>>> { it.first.apiPattern }
                    .thenBy { it.first.httpMethod },
            )
        check(ambiguous.isEmpty()) {
            "Ambiguous module_access_matrix contracts: " +
                ambiguous.joinToString(", ") { (key, contracts) ->
                    "${key.httpMethod} ${key.apiPattern} -> ${contracts.joinToString(" | ")}"
                }
        }

        val missing = handlerMappings
            .flatMap { mapping ->
                mapping.handlerMethods.keys.flatMap { info ->
                    val paths = info.pathPatternsCondition?.patternValues
                        ?: info.patternsCondition?.patterns
                        ?: emptySet()
                    val methods = info.methodsCondition.methods
                        .takeIf { it.isNotEmpty() }
                        ?: RequestMethod.entries.toSet()
                    paths.flatMap { path ->
                        methods.map { method -> method.name to path }
                    }
                }
            }
            .filter { (_, path) -> path.startsWith("/api/") || path == "/api" }
            .filterNot { (_, path) -> properties.startupValidationExclusions.any { path.matchesAntLike(it) } }
            .map { (method, path) -> method to path.normalizedApiPath() }
            .distinct()
            .filterNot { (method, path) -> rules.any { it.matches(method, path) } }
            .sortedWith(compareBy<Pair<String, String>> { it.second }.thenBy { it.first })

        check(missing.isEmpty()) {
            "API routes missing module_access_matrix entries: " +
                    missing.joinToString(", ") { (method, path) -> "$method $path" }
        }
    }

    private fun RouteAccessRule.matches(method: String, path: String): Boolean {
        if (httpMethod != "ANY" && !httpMethod.equals(method, ignoreCase = true)) {
            return false
        }
        return apiPattern.matchesRoutePattern(path)
    }

    private fun String.matchesRoutePattern(path: String): Boolean {
        val patternSegments = trim('/').split('/').filter { it.isNotBlank() }
        val pathSegments = path.trim('/').split('/').filter { it.isNotBlank() }
        var pathIndex = 0

        for ((patternIndex, patternSegment) in patternSegments.withIndex()) {
            if (patternSegment == "*" || patternSegment == "**") {
                return true
            }

            val pathSegment = pathSegments.getOrNull(pathIndex) ?: return false

            when {
                patternSegment.endsWith("*") -> {
                    val prefix = patternSegment.dropLast(1)
                    if (!pathSegment.startsWith(prefix)) {
                        return false
                    }
                    if (patternIndex == patternSegments.lastIndex) {
                        return true
                    }
                }

                patternSegment.startsWith(":") -> {
                    // Matrix variables match both MVC variables like {tenantId} and literals.
                }

                pathSegment.startsWith("{") && pathSegment.endsWith("}") -> {
                    // MVC variables match literal matrix segments during startup coverage.
                }

                patternSegment != pathSegment -> return false
            }

            pathIndex += 1
        }

        return pathIndex == pathSegments.size
    }

    private fun String.matchesAntLike(pattern: String): Boolean {
        if (pattern.endsWith("/**")) {
            return startsWith(pattern.removeSuffix("/**"))
        }
        return this == pattern
    }

    private fun String.normalizedApiPath(): String {
        val match = API_VERSION_PATTERN.matchEntire(this) ?: return this
        return "/api${match.groupValues[1]}"
    }

    private companion object {
        val API_VERSION_PATTERN = Regex("^/api/v\\d+(/.*)$")
    }

    private data class RouteKey(
        val httpMethod: String,
        val apiPattern: String,
    )

    private data class AccessContract(
        val moduleId: String,
        val permissionCode: String?,
        val routeScope: String,
        val guardMode: String,
    )
}
