package com.mwombeki.peak.shared.dto

import java.time.Instant

/**
 *
 * Guarantees that every single successful endpoint returns data using
 * the exact same JSON format root key wrapper.
 */
data class ApiResponse<T>(
    val success: Boolean = true,
    val message: String = "Operation executed successfully",
    val data: T,
    val timestamp: Instant = Instant.now()
){
    companion object {
        fun <T> success(data: T, message: String="Operation exexuted successfully"): ApiResponse<T> {
            return ApiResponse(success = true, message = message, data = data)
        }
    }
}