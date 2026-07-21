package com.mwombeki.peak.shared.exception

import com.mwombeki.peak.shared.context.PeakRequestHeaders
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.slf4j.MDC
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.web.HttpMediaTypeNotAcceptableException
import org.springframework.web.HttpMediaTypeNotSupportedException
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.context.request.async.AsyncRequestTimeoutException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
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
        log.warn(
            "Business exception occurred [Trace: {}]: {} - Code: {}",
            traceId,
            PublicErrorSanitizer.sanitize(ex.message),
            ex.errorCode,
        )

        val errorResponse = ErrorResponse(
            status = ex.status.value(),
            error = ex.status.reasonPhrase,
            errorCode = ex.errorCode,
            message = PublicErrorSanitizer.sanitize(ex.message),
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

        log.info(
            "Request payload validation rejected [Trace: {}]: {}",
            traceId,
            PublicErrorSanitizer.sanitize(customizedMessage),
        )

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

    @ExceptionHandler(HttpMediaTypeNotSupportedException::class)
    fun handleUnsupportedMediaType(
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> {
        return problem(
            status = HttpStatus.UNSUPPORTED_MEDIA_TYPE,
            title = "Unsupported media type",
            detail = "Request Content-Type is not supported",
            request = request,
        )
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException::class)
    fun handleNotAcceptableMediaType(
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> {
        return problem(
            status = HttpStatus.NOT_ACCEPTABLE,
            title = "Not acceptable",
            detail = "Requested response media type is not supported",
            request = request,
        )
    }

    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadableMessage(
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> {
        return problem(
            status = HttpStatus.BAD_REQUEST,
            title = "Malformed request",
            detail = "Request body is not valid JSON",
            request = request,
        )
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleArgumentTypeMismatch(
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> {
        return problem(
            status = HttpStatus.BAD_REQUEST,
            title = "Invalid request parameter",
            detail = "A path or query parameter has an invalid value",
            request = request,
        )
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun handleUnsupportedMethod(
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> {
        return problem(
            status = HttpStatus.METHOD_NOT_ALLOWED,
            title = "Method not allowed",
            detail = "HTTP method is not supported for this route",
            request = request,
        )
    }

    @ExceptionHandler(ResponseStatusException::class)
    fun handleResponseStatusException(
        ex: ResponseStatusException,
        request: HttpServletRequest,
    ): ResponseEntity<ProblemDetail> {
        val traceId = traceId(request)
        val problem = ProblemDetail.forStatusAndDetail(
            ex.statusCode,
            PublicErrorSanitizer.sanitize(ex.reason ?: "Request failed"),
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
            detail = PublicErrorSanitizer.sanitize(ex.message ?: "Request is invalid"),
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
            detail = PublicErrorSanitizer.sanitize(
                ex.message ?: "Request cannot be completed in the current state",
            ),
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
        log.error(
            "Fatal unhandled internal server execution error [Trace: {}] type={} detail={}\n{}",
            traceId,
            ex.javaClass.name,
            PublicErrorSanitizer.sanitize(ex.message),
            sanitizedTrace(ex),
        )

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

    /**
     * Renders the cause chain with class names, sanitized messages, and stack
     * frames so operators get actionable traces without raw payload data in
     * the logs.
     */
    private fun sanitizedTrace(ex: Throwable): String = buildString {
        var current: Throwable? = ex
        var depth = 0
        val seen = mutableSetOf<Throwable>()
        while (current != null && depth < MAX_TRACE_CAUSES && seen.add(current)) {
            if (depth > 0) {
                append("Caused by: ")
            }
            append(current.javaClass.name)
            append(": ")
            append(PublicErrorSanitizer.sanitize(current.message))
            current.stackTrace.take(MAX_TRACE_FRAMES).forEach { frame ->
                append("\n  at ").append(frame)
            }
            if (current.stackTrace.size > MAX_TRACE_FRAMES) {
                append("\n  ... ")
                    .append(current.stackTrace.size - MAX_TRACE_FRAMES)
                    .append(" more")
            }
            append('\n')
            current = current.cause
            depth += 1
        }
    }

    private fun traceId(request: HttpServletRequest): String {
        return MDC.get("correlation_id")
            ?: request.getHeader(PeakRequestHeaders.CORRELATION_ID)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            ?: UUID.randomUUID().toString()
    }

    private companion object {
        const val MAX_TRACE_CAUSES = 5
        const val MAX_TRACE_FRAMES = 15
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
