package com.mwombeki.peak.reliability.api

import java.util.UUID

interface IdempotencyPort {
    fun reserve(command: IdempotencyCommand): IdempotencyReservation

    fun markSucceeded(
        recordId: UUID,
        responseCode: Int,
        responseBody: Any?,
        resourceId: UUID? = null,
    )

    fun markFailed(
        recordId: UUID,
        responseCode: Int,
        responseBody: Any?,
        resourceId: UUID? = null,
    )
}
