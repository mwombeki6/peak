package com.mwombeki.peak.shared.exception

import org.springframework.http.HttpStatus
import java.time.Instant


/**
 * Production Base Domain Exception.
 * All predictable operational or business logic failures across the modular monolith
 * should extend this class to ensure structured error handling.
 */

open class BusinessException(
    override val message: String,
    val status: HttpStatus = HttpStatus.BAD_REQUEST,
    val errorCode: String = "Business Rule Violation",
    val timestamp: Instant = Instant.now()

) : RuntimeException(message)