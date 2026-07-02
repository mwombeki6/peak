package com.mwombeki.peak.pos.internal.web

import com.mwombeki.peak.pos.api.ApprovePosVarianceRequest
import com.mwombeki.peak.pos.api.ClosePosSessionRequest
import com.mwombeki.peak.pos.api.OpenPosSessionRequest
import com.mwombeki.peak.pos.api.PosSessionResponse
import com.mwombeki.peak.pos.api.PosSessionSummaryResponse
import com.mwombeki.peak.pos.internal.PosSessionService
import jakarta.validation.Valid
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/properties/{propertyId}/pos-sessions")
class PosSessionController(
    private val posSessionService: PosSessionService,
) {
    @PostMapping("/open")
    @ResponseStatus(HttpStatus.CREATED)
    fun openSession(
        @PathVariable propertyId: UUID,
        @Valid @RequestBody request: OpenPosSessionRequest,
    ): PosSessionResponse = posSessionService.openSession(propertyId, request)

    @PostMapping("/{sessionId}/close")
    fun closeSession(
        @PathVariable propertyId: UUID,
        @PathVariable sessionId: UUID,
        @Valid @RequestBody request: ClosePosSessionRequest,
    ): PosSessionResponse = posSessionService.closeSession(propertyId, sessionId, request)

    @PostMapping("/{sessionId}/variance-approve")
    fun approveVariance(
        @PathVariable propertyId: UUID,
        @PathVariable sessionId: UUID,
        @Valid @RequestBody request: ApprovePosVarianceRequest,
    ): PosSessionResponse = posSessionService.approveVariance(propertyId, sessionId, request)

    @GetMapping("/{sessionId}")
    fun getSessionSummary(
        @PathVariable propertyId: UUID,
        @PathVariable sessionId: UUID,
    ): PosSessionSummaryResponse = posSessionService.getSessionSummary(propertyId, sessionId)
}
