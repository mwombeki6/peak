package com.mwombeki.peak.pos.internal.web

import com.mwombeki.peak.pos.api.KitchenTicketReasonRequest
import com.mwombeki.peak.pos.internal.PosKitchenService
import jakarta.validation.Valid
import java.util.UUID
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/properties/{propertyId}/kitchen-tickets")
class KitchenTicketController(private val service: PosKitchenService) {
    @GetMapping
    fun list(@PathVariable propertyId: UUID) = service.listTickets(propertyId)

    @PostMapping("/{ticketId}/prepare")
    fun prepare(@PathVariable propertyId: UUID, @PathVariable ticketId: UUID) =
        service.transition(propertyId, ticketId, "prepare", null)

    @PostMapping("/{ticketId}/ready")
    fun ready(@PathVariable propertyId: UUID, @PathVariable ticketId: UUID) =
        service.transition(propertyId, ticketId, "ready", null)

    @PostMapping("/{ticketId}/deliver")
    fun deliver(@PathVariable propertyId: UUID, @PathVariable ticketId: UUID) =
        service.transition(propertyId, ticketId, "deliver", null)

    @PostMapping("/{ticketId}/void")
    fun void(
        @PathVariable propertyId: UUID,
        @PathVariable ticketId: UUID,
        @Valid @RequestBody request: KitchenTicketReasonRequest,
    ) = service.transition(propertyId, ticketId, "void", request)
}
