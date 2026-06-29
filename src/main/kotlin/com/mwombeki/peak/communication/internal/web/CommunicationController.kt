package com.mwombeki.peak.communication.internal.web

import com.mwombeki.peak.communication.api.ChannelVerificationReceipt
import com.mwombeki.peak.communication.api.ChannelVerificationRequestReceipt
import com.mwombeki.peak.communication.api.CommunicationPort
import com.mwombeki.peak.communication.api.CommunicationConsentReceipt
import com.mwombeki.peak.communication.api.ConfigureReportRecipientRequest
import com.mwombeki.peak.communication.api.ContactMutationReceipt
import com.mwombeki.peak.communication.api.ContactResponse
import com.mwombeki.peak.communication.api.ContactRoleMutationReceipt
import com.mwombeki.peak.communication.api.CreateContactRequest
import com.mwombeki.peak.communication.api.CreateTemplateRequest
import com.mwombeki.peak.communication.api.DeliveryAttemptResponse
import com.mwombeki.peak.communication.api.DeliveryRequestResponse
import com.mwombeki.peak.communication.api.DeliveryRetryReceipt
import com.mwombeki.peak.communication.api.EnqueueNotificationRequest
import com.mwombeki.peak.communication.api.NotificationEnqueueReceipt
import com.mwombeki.peak.communication.api.RecordCommunicationConsentRequest
import com.mwombeki.peak.communication.api.ReportRecipientMutationReceipt
import com.mwombeki.peak.communication.api.ReportRecipientResponse
import com.mwombeki.peak.communication.api.AssignContactRoleRequest
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

    @PostMapping("/contacts/{contactId}/roles")
    fun assignContactRole(
        @PathVariable contactId: UUID,
        @RequestBody request: AssignContactRoleRequest,
    ): ContactRoleMutationReceipt {
        return communicationPort.assignContactRole(contactId, request)
    }

    @PostMapping("/contacts/{contactId}/channels/{channelId}/consents")
    fun recordConsent(
        @PathVariable contactId: UUID,
        @PathVariable channelId: UUID,
        @RequestBody request: RecordCommunicationConsentRequest,
    ): CommunicationConsentReceipt {
        return communicationPort.recordConsent(contactId, channelId, request)
    }

    @PostMapping("/report-recipients")
    fun configureReportRecipient(
        @RequestBody request: ConfigureReportRecipientRequest,
    ): ReportRecipientMutationReceipt {
        return communicationPort.configureReportRecipient(request)
    }

    @GetMapping("/report-recipients")
    fun listReportRecipients(): List<ReportRecipientResponse> {
        return communicationPort.listReportRecipients()
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

    @GetMapping("/delivery-requests")
    fun listDeliveryRequests(): List<DeliveryRequestResponse> {
        return communicationPort.listDeliveryRequests()
    }

    @GetMapping("/delivery-requests/{deliveryRequestId}")
    fun getDeliveryRequest(
        @PathVariable deliveryRequestId: UUID,
    ): DeliveryRequestResponse {
        return communicationPort.getDeliveryRequest(deliveryRequestId)
    }

    @GetMapping("/delivery-requests/{deliveryRequestId}/attempts")
    fun listDeliveryAttempts(
        @PathVariable deliveryRequestId: UUID,
    ): List<DeliveryAttemptResponse> {
        return communicationPort.listDeliveryAttempts(deliveryRequestId)
    }

    @PostMapping("/delivery-requests/{deliveryRequestId}/retry")
    fun retryDelivery(
        @PathVariable deliveryRequestId: UUID,
    ): DeliveryRetryReceipt {
        return communicationPort.retryDelivery(deliveryRequestId)
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
