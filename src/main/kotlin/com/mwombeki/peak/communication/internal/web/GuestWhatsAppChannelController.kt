package com.mwombeki.peak.communication.internal.web

import com.mwombeki.peak.communication.api.CommunicationPort
import com.mwombeki.peak.communication.api.GuestWhatsAppChannelReceipt
import com.mwombeki.peak.communication.api.RegisterGuestWhatsAppRequest
import com.mwombeki.peak.shared.exception.ApiProblemFactory
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/properties/{propertyId}/guests/{guestId}")
class GuestWhatsAppChannelController(
    private val communicationPort: CommunicationPort,
    private val apiProblemFactory: ApiProblemFactory,
) {
    @PostMapping("/whatsapp-channel")
    fun register(
        @PathVariable propertyId: UUID,
        @PathVariable guestId: UUID,
        @RequestBody request: RegisterGuestWhatsAppRequest,
    ): GuestWhatsAppChannelReceipt {
        return communicationPort.registerGuestWhatsAppChannel(propertyId, guestId, request)
    }

    @ExceptionHandler(NoSuchElementException::class)
    fun handleNotFound(ex: NoSuchElementException): ResponseEntity<ProblemDetail> {
        return problem(HttpStatus.NOT_FOUND, "Communication resource not found", ex.publicMessage())
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleInvalidRequest(ex: IllegalArgumentException): ResponseEntity<ProblemDetail> {
        return problem(HttpStatus.BAD_REQUEST, "Invalid communication request", ex.publicMessage())
    }

    private fun problem(
        status: HttpStatus,
        title: String,
        detail: String,
    ): ResponseEntity<ProblemDetail> {
        return apiProblemFactory.response(status, title, detail)
    }

    private fun RuntimeException.publicMessage(): String {
        val message = message.orEmpty()
        return if (message.startsWith("ERROR:")) {
            message.removePrefix("ERROR:").lineSequence().first().trim()
        } else {
            message
        }
    }
}
