package com.mwombeki.peak.shared.idempotency

import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.ResponseStatus

/**
 * Thrown when a duplicate request is detected.
 * Returns a HTTP 409 Conflict status back to the client.
 */

@ResponseStatus(HttpStatus.CONFLICT)
class IdempotencyException(message : String) : RuntimeException(message)