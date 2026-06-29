package com.mwombeki.peak.shared.exception

import com.mwombeki.peak.shared.context.PeakRequestHeaders
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.async.AsyncRequestTimeoutException
import org.springframework.web.server.ResponseStatusException
import java.util.UUID

/**
 *
 * Captures all controller layer failures, safely prevents internal system leaks,
 * and normalizes error serialization back to the calling client.
 */
@RestControllerAdvice
class GlobalExceptionHandler {

    private val log = LoggerFactory.getLogger(GlobalExceptionHandler::class.java)

    /**
     * Handles explicit domain-driven business rule violations.
     */
    @ExceptionHandler(BusinessException::class)
    fun handleBusinessException(
        ex: BusinessException,
        request: HttpServletRequest
    ): ResponseEntity<ErrorResponse> {
        val traceId = traceId(request)
        log.warn("Business exception occurred [Trace: {}]: {} - Code: {}", traceId, ex.message, ex.errorCode)

        val errorResponse = ErrorResponse(
            status = ex.status.value(),
            error = ex.status.reasonPhrase,
            errorCode = ex.errorCode,
            message = ex.message,
            path = request.requestURI,
            traceId = traceId
        )
        return ResponseEntity(errorResponse, ex.status)
    }

    /**
     * Catches and formats standard JSR-383 / Spring @Valid annotation validation failures.
     */
    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handleValidationException(
        ex: MethodArgumentNotValidException,
        request: HttpServletRequest
    ): ResponseEntity<ErrorResponse> {
        val traceId = traceId(request)
        val firstFieldError = ex.bindingResult.fieldError
        val customizedMessage = firstFieldError?.let { "[${it.field}] ${it.defaultMessage}" } ?: "Input request validation failed."

        log.info("Request payload validation rejected [Trace: {}]: {}", traceId, customizedMessage)

        val errorResponse = ErrorResponse(
            status = HttpStatus.UNPROCESSABLE_ENTITY.value(),
            error = HttpStatus.UNPROCESSABLE_ENTITY.reasonPhrase,
            errorCode = "INVALID_REQUEST_PAYLOAD",
            message = customizedMessage,
            path = request.requestURI,
            traceId = traceId
        )
        return ResponseEntity(errorResponse, HttpStatus.UNPROCESSABLE_ENTITY)
    }

    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatusException(
        ex: ResponseStatusException,
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> {
        val traceId = traceId(request)
        val problem = ProblemDetail.forStatusAndDetail(
            ex.statusCode,
            ex.reason ?: "Request failed",
        )
        problem.title = HttpStatus.resolve(ex.statusCode.value())?.reasonPhrase
            ?: "Request failed"
        problem.setProperty("traceId", traceId)
        problem.setProperty("path", request.requestURI)

        return ResponseEntity.status(ex.statusCode).body(problem)
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgumentException(
        ex: IllegalArgumentException,
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> {
        return problem(
            status = HttpStatus.BAD_REQUEST,
            title = "Invalid request",
            detail = ex.message ?: "Request is invalid",
            request = request,
        )
    }

    @ExceptionHandler(IllegalStateException::class)
    fun handleIllegalStateException(
        ex: IllegalStateException,
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> {
        return problem(
            status = HttpStatus.CONFLICT,
            title = "Request conflict",
            detail = ex.message ?: "Request cannot be completed in the current state",
            request = request,
        )
    }

    @ExceptionHandler(AsyncRequestTimeoutException::class)
    fun handleAsyncRequestTimeout(
        request: HttpServletRequest,
    ): ResponseEntity<Void> {
        log.debug("Async request completed after timeout [Trace: {}]", traceId(request))
        return ResponseEntity.noContent().build()
    }

    /**
     * Catch-all fallback guard for unexpected low-level errors (NullPointerExceptions, DB drops, etc.).
     */
    @ExceptionHandler(Exception::class)
    fun handleGenericException(
        ex: Exception,
        request: HttpServletRequest
    ): ResponseEntity<ErrorResponse> {
        val traceId = traceId(request)
        // CRITICAL: We log the full stack trace to the internal server console for developers,
        // but we hide the dirty details from the API client so hackers can't see schema details.
        log.error("Fatal unhandled internal server execution error [Trace: {}]", traceId, ex)

        val errorResponse = ErrorResponse(
            status = HttpStatus.INTERNAL_SERVER_ERROR.value(),
            error = HttpStatus.INTERNAL_SERVER_ERROR.reasonPhrase,
            errorCode = "INTERNAL_SERVER_FAILURE",
            message = "An unexpected error occurred. Please contact system support with your trace identifier.",
            path = request.requestURI,
            traceId = traceId
        )
        return ResponseEntity(errorResponse, HttpStatus.INTERNAL_SERVER_ERROR)
    }

    private fun traceId(request: HttpServletRequest): String {
        return MDC.get("correlation_id")
            ?: request.getHeader(PeakRequestHeaders.CORRELATION_ID)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            ?: UUID.randomUUID().toString()
    }

    private fun problem(
        status: HttpStatus,
        title: String,
        detail: String,
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> {
        val traceId = traceId(request)
        val problem = ProblemDetail.forStatusAndDetail(status, detail)
        problem.title = title
        problem.setProperty("traceId", traceId)
        problem.setProperty("path", request.requestURI)
        return ResponseEntity.status(status).body(problem)
    }
}
