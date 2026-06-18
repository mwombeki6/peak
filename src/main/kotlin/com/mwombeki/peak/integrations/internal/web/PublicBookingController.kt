package com.mwombeki.peak.integrations.internal.web

import com.mwombeki.peak.integrations.api.PublicBookingPort
import com.mwombeki.peak.integrations.api.PublicBookingSessionRequest
import com.mwombeki.peak.integrations.api.PublicBookingSessionResponse
import java.util.UUID
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/public/properties/{propertyId}/booking-engine")
class PublicBookingController(
    private val publicBookingPort: PublicBookingPort,
) {
    @PostMapping("/sessions")
    fun startBooking(
        @PathVariable propertyId: UUID,
        @RequestBody request: PublicBookingSessionRequest,
    ): ResponseEntity<PublicBookingSessionResponse> {
        val response = publicBookingPort.createPublicSession(propertyId, request)
        return ResponseEntity.ok(response)
    }
}
