package com.mwombeki.peak.shared.context.web

import com.mwombeki.peak.shared.context.PeakRequestHeaders
import com.mwombeki.peak.shared.context.RequestContext
import com.mwombeki.peak.shared.context.RequestContextException
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestContextResolver
import com.mwombeki.peak.shared.context.RequestIdentity
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.servlet.HandlerInterceptor

@Component
class RequestContextInterceptor(
    private val resolver: RequestContextResolver,
    private val holder: RequestContextHolder,
) : HandlerInterceptor {

    override fun preHandle(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
    ): Boolean {
        return try {
            val context = resolver.resolve(
                request = request,
                authentication = SecurityContextHolder.getContext().authentication,
            )
            holder.set(context)
            bindLoggingContext(context)
            response.setHeader(PeakRequestHeaders.CORRELATION_ID, context.correlationId)
            true
        } catch (ex: RequestContextException) {
            writeProblem(response, ex.message ?: "Invalid request context")
            false
        }
    }

    override fun afterCompletion(
        request: HttpServletRequest,
        response: HttpServletResponse,
        handler: Any,
        ex: Exception?,
    ) {
        holder.clear()
        clearLoggingContext()
    }

    private fun clearLoggingContext() {
        listOf(
            "correlation_id",
            "idempotency_key",
            "tenant_id",
            "tenant_user_id",
            "platform_user_id",
            "property_id",
        ).forEach(MDC::remove)
    }

    private fun bindLoggingContext(context: RequestContext) {
        MDC.put("correlation_id", context.correlationId)
        context.idempotencyKey?.let { MDC.put("idempotency_key", it) }

        when (val identity = context.identity) {
            is RequestIdentity.Tenant -> {
                MDC.put("tenant_id", identity.tenantId.toString())
                MDC.put("tenant_user_id", identity.tenantUserId.toString())
            }

            is RequestIdentity.Platform -> {
                MDC.put("platform_user_id", identity.platformUserId.toString())
            }

            is RequestIdentity.Support -> {
                MDC.put("tenant_id", identity.tenantId.toString())
                MDC.put("platform_user_id", identity.platformUserId.toString())
            }

            is RequestIdentity.Public -> {
                identity.tenantId?.let { MDC.put("tenant_id", it.toString()) }
                identity.propertyId?.let { MDC.put("property_id", it.toString()) }
            }
        }
    }

    private fun writeProblem(response: HttpServletResponse, detail: String) {
        response.status = HttpStatus.BAD_REQUEST.value()
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        response.writer.write(
            """
            {"type":"about:blank","title":"Invalid request context","status":400,"detail":"${detail.jsonEscaped()}"}
            """.trimIndent(),
        )
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
