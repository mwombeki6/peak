package com.mwombeki.peak.pos.internal.web

import com.mwombeki.peak.pos.api.PosRoomChargeCandidateResponse
import com.mwombeki.peak.pos.internal.PosRoomChargeService
import java.util.UUID
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/properties/{propertyId}/pos")
class PosRoomChargeController(
    private val roomCharge: PosRoomChargeService,
) {
    @GetMapping("/room-charge-candidates")
    fun listCandidates(
        @PathVariable propertyId: UUID,
        @RequestParam(required = false) query: String?,
    ): List<PosRoomChargeCandidateResponse> = roomCharge.listCandidates(propertyId, query)
}
