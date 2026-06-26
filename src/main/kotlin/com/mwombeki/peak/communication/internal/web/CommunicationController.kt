package com.mwombeki.peak.communication.internal.web

import com.mwombeki.peak.communication.api.ChannelVerificationReceipt
import com.mwombeki.peak.communication.api.ChannelVerificationRequestReceipt
import com.mwombeki.peak.communication.api.CommunicationPort
import com.mwombeki.peak.communication.api.ContactMutationReceipt
import com.mwombeki.peak.communication.api.ContactResponse
import com.mwombeki.peak.communication.api.CreateContactRequest
import com.mwombeki.peak.communication.api.CreateTemplateRequest
import com.mwombeki.peak.communication.api.EnqueueNotificationRequest
import com.mwombeki.peak.communication.api.NotificationEnqueueReceipt
import com.mwombeki.peak.communication.api.TemplateMutationReceipt
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/v1/communication")
class CommunicationController(
    private val communicationPort: CommunicationPort,
) {

    @PostMapping("/notifications")
    fun sendNotification(
        @RequestBody request: EnqueueNotificationRequest,
    ): NotificationEnqueueReceipt {
        return communicationPort.enqueue(request)
    }

    @PostMapping("/contacts")
    fun createContact(
        @RequestBody request: CreateContactRequest,
    ): ContactMutationReceipt {
        return communicationPort.createContact(request)
    }

    @GetMapping("/contacts")
    fun listContacts(): List<ContactResponse> {
        return communicationPort.listContacts()
    }

    @PostMapping("/templates")
    fun createTemplate(
        @RequestBody request: CreateTemplateRequest,
    ): TemplateMutationReceipt {
        return communicationPort.createTemplate(request)
    }

    @PostMapping("/channels/{channelId}/request-verification")
    fun requestVerification(
        @PathVariable channelId: UUID,
    ): ResponseEntity<ChannelVerificationRequestReceipt> {
        return ResponseEntity
            .accepted()
            .body(communicationPort.requestChannelVerification(channelId))
    }

    @PostMapping("/channels/{channelId}/verify")
    fun verifyChannel(
        @PathVariable channelId: UUID,
        @RequestBody request: VerifyChannelHttpRequest,
    ): ChannelVerificationReceipt {
        return communicationPort.verifyChannel(channelId, request.token)
    }

    @ExceptionHandler(NoSuchElementException::class)
    fun handleNotFound(ex: NoSuchElementException): ResponseEntity<ProblemDetail> {
        return problem(HttpStatus.NOT_FOUND, "Communication resource not found", ex.publicMessage())
    }

    @ExceptionHandler(IllegalArgumentException::class)
    fun handleInvalidRequest(ex: IllegalArgumentException): ResponseEntity<ProblemDetail> {
        return problem(HttpStatus.BAD_REQUEST, "Invalid communication request", ex.publicMessage())
    }

    @ExceptionHandler(IllegalStateException::class)
    fun handleConflict(ex: IllegalStateException): ResponseEntity<ProblemDetail> {
        return problem(HttpStatus.CONFLICT, "Communication command conflict", ex.publicMessage())
    }

    private fun problem(
        status: HttpStatus,
        title: String,
        detail: String,
    ): ResponseEntity<ProblemDetail> {
        val problem = ProblemDetail.forStatusAndDetail(status, detail)
        problem.title = title
        return ResponseEntity.status(status).body(problem)
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

data class VerifyChannelHttpRequest(
    val token: String,
)
