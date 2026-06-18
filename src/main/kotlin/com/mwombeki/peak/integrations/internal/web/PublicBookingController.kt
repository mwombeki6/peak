package com.mwombeki.peak.integrations.internal.web

import com.mwombeki.peak.integrations.api.PublicBookingPort
import com.mwombeki.peak.integrations.api.PublicBookingSessionRequest
import com.mwombeki.peak.integrations.api.PublicBookingSessionResponse
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/v1/public/bookings")
class PublicBookingController(
    private val publicBookingPort: PublicBookingPort,
){
    @PostMapping("/sessions")
    fun startBooking(
        @RequestBody request: PublicBookingSessionRequest,
    ): ResponseEntity<PublicBookingSessionResponse>{
        val response = publicBookingPort.createPublicSession(request)
        return ResponseEntity.ok(response)
    }
}

