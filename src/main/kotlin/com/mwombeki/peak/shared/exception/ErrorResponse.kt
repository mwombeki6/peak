package com.mwombeki.peak.shared.exception

import java.time.Instant

/**
 * This guarantees a predictable, uniform JSON response contract whenever an error occurs.
 */

data class ErrorResponse(
    val status: Int,
    val error: String,
    val errorCode: String,
    val message: String,
    val path: String,
    val traceId: String,
    val timestamp: Instant = Instant.now(),
)
