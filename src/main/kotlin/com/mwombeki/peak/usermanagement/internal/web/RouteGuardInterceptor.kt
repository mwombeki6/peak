package com.mwombeki.peak.usermanagement.internal.web

import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.security.SecurityProblemWriter
import com.mwombeki.peak.usermanagement.api.AuthorizationDecision
import com.mwombeki.peak.usermanagement.api.AuthorizationPort
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
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
    private val problemWriter: SecurityProblemWriter,
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
        if (context == null) {
            deny(
                response = response,
                status = HttpStatus.INTERNAL_SERVER_ERROR,
                title = "Route guard misconfigured",
                detail = "Request context is not bound",
            )
            return false
        }

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
            deny(
                response = response,
                status = HttpStatus.BAD_REQUEST,
                title = "Invalid route parameters",
                detail = ex.message ?: "Route parameters are invalid",
            )
            return false
        } catch (ex: RouteAccessConfigurationException) {
            deny(
                response = response,
                status = HttpStatus.INTERNAL_SERVER_ERROR,
                title = "Route guard misconfigured",
                detail = ex.message ?: "Route access contracts are ambiguous",
            )
            return false
        }

        if (decision.allowed) {
            return true
        }

        deny(
            response = response,
            status = HttpStatus.FORBIDDEN,
            title = "Forbidden",
            detail = decision.reason ?: "Request is not authorized",
        )
        return false
    }

    private fun deny(
        response: HttpServletResponse,
        status: HttpStatus,
        title: String,
        detail: String,
    ) {
        problemWriter.write(
            response = response,
            status = status,
            title = title,
            detail = detail,
        )
    }
}
