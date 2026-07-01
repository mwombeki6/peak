package com.mwombeki.peak.shared.exception

import java.time.Instant
import org.springframework.http.HttpStatus
import org.springframework.modulith.NamedInterface

/**
 * Base domain exception for predictable operational or business failures.
 */
@NamedInterface("exception")
open class BusinessException(
    override val message: String,
    val status: HttpStatus = HttpStatus.BAD_REQUEST,
    val errorCode: String = "Business Rule Violation",
    val timestamp: Instant = Instant.now(),
    cause: Throwable? = null,
) : RuntimeException(message, cause)
