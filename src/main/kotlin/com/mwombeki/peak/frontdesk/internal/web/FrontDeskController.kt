package com.mwombeki.peak.frontdesk.internal.web

import com.mwombeki.peak.frontdesk.api.CheckInRequest
import com.mwombeki.peak.frontdesk.api.CheckoutRequest
import com.mwombeki.peak.frontdesk.api.FrontDeskMutationReceipt
import com.mwombeki.peak.frontdesk.api.FrontDeskNotFoundException
import com.mwombeki.peak.frontdesk.api.FrontDeskPort
import com.mwombeki.peak.frontdesk.api.StayResponse
import com.mwombeki.peak.frontdesk.api.WalkInRequest
import java.util.UUID
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/properties/{propertyId}")
class FrontDeskController(
    private val frontDeskPort: FrontDeskPort,
) {
    @PostMapping("/checkins")
    fun checkIn(
        @PathVariable propertyId: UUID,
        @RequestBody request: CheckInRequest,
    ): FrontDeskMutationReceipt {
        return frontDeskPort.checkIn(propertyId, request)
    }

    @PostMapping("/walk-ins")
    fun createWalkIn(
        @PathVariable propertyId: UUID,
        @RequestBody request: WalkInRequest,
    ): FrontDeskMutationReceipt {
        return frontDeskPort.createWalkIn(propertyId, request)
    }

    @GetMapping("/stays")
    fun listStays(@PathVariable propertyId: UUID): List<StayResponse> {
        return frontDeskPort.listStays(propertyId)
    }

    @GetMapping("/stays/{stayId}")
    fun getStay(
        @PathVariable propertyId: UUID,
        @PathVariable stayId: UUID,
    ): StayResponse {
        return frontDeskPort.getStay(propertyId, stayId)
            ?: throw FrontDeskNotFoundException("Stay was not found")
    }

    @PostMapping("/checkouts/{stayId}")
    fun checkOut(
        @PathVariable propertyId: UUID,
        @PathVariable stayId: UUID,
        @RequestBody request: CheckoutRequest,
    ): FrontDeskMutationReceipt {
        return frontDeskPort.checkOut(propertyId, stayId, request)
    }

    @PostMapping("/checkouts/{stayId}/fiscal-override")
    fun checkOutWithFiscalOverride(
        @PathVariable propertyId: UUID,
        @PathVariable stayId: UUID,
        @RequestBody request: CheckoutRequest,
    ): FrontDeskMutationReceipt {
        return frontDeskPort.checkOutWithFiscalOverride(propertyId, stayId, request)
    }
}
