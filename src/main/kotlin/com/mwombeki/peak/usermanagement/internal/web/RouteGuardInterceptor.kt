package com.mwombeki.peak.usermanagement.internal.web

import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.usermanagement.api.AuthorizationDecision
import com.mwombeki.peak.usermanagement.api.AuthorizationPort
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import org.springframework.web.servlet.HandlerInterceptor

@Component
class RouteGuardInterceptor(
    private val properties: RouteGuardProperties,
    private val ruleRepository: RouteAccessRuleRepository,
    private val routeAccessMatcher: RouteAccessMatcher,
    private val authorizationPort: AuthorizationPort,
    private val requestContextHolder: RequestContextHolder,
    private val transactionTemplate: TransactionTemplate,
) : HandlerInterceptor {

    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        if (!properties.enabled) {
            return true
        }

        val context = requestContextHolder.currentOrNull()
            ?: return deny(
                response = response,
                status = HttpStatus.INTERNAL_SERVER_ERROR,
                title = "Route guard misconfigured",
                detail = "Request context is not bound",
            )

        val decision = try {
            transactionTemplate.execute {
                val authorizationRequest = routeAccessMatcher.match(
                    httpMethod = request.method,
                    requestPath = request.requestURI,
                    identity = context.identity,
                    rules = ruleRepository.findEnabledRules(),
                )

                if (authorizationRequest == null) {
                    if (properties.denyUnregisteredApiRoutes) {
                        AuthorizationDecision.denied("Route is not registered in module access matrix")
                    } else {
                        AuthorizationDecision.allowed()
                    }
                } else {
                    authorizationPort.authorize(authorizationRequest)
                }
            }
        } catch (ex: IllegalArgumentException) {
            return deny(
                response = response,
                status = HttpStatus.BAD_REQUEST,
                title = "Invalid route parameters",
                detail = ex.message ?: "Route parameters are invalid",
            )
        }

        if (decision.allowed) {
            return true
        }

        return deny(
            response = response,
            status = HttpStatus.FORBIDDEN,
            title = "Forbidden",
            detail = decision.reason ?: "Request is not authorized",
        )
    }

    private fun deny(
        response: HttpServletResponse,
        status: HttpStatus,
        title: String,
        detail: String,
    ): Boolean {
        response.status = status.value()
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        response.writer.write(
            """
            {"type":"about:blank","title":"${title.jsonEscaped()}","status":${status.value()},"detail":"${detail.jsonEscaped()}"}
            """.trimIndent(),
        )
        return false
    }

    private fun String.jsonEscaped(): String {
        return buildString {
            for (char in this@jsonEscaped) {
                when (char) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> append(char)
                }
            }
        }
    }
}
