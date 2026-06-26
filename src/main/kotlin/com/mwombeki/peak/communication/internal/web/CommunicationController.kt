package com.mwombeki.peak.communication.internal.web

import com.mwombeki.peak.communication.api.*
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/v1/communication")
class CommunicationController(
    private val communicationPort: CommunicationPort
) {

    @PostMapping("/notifications")
    fun sendNotification(
        @RequestBody request: EnqueueNotificationRequest
    ): ResponseEntity<Map<String, UUID>> {
        val eventId = communicationPort.enqueue(request)
        return ResponseEntity.ok(mapOf("eventId" to eventId))
    }

    @PostMapping("/contacts")
    fun createContact(
        @RequestBody request: CreateContactRequest
    ): ResponseEntity<Map<String, UUID>> {
        val contactId = communicationPort.createContact(request)
        return ResponseEntity.ok(mapOf("contactId" to contactId))
    }

    @GetMapping("/contacts")
    fun listContacts(): ResponseEntity<List<ContactResponse>> {
        return ResponseEntity.ok(communicationPort.listContacts())
    }

    @PostMapping("/templates")
    fun createTemplate(
        @RequestBody request: CreateTemplateRequest
    ): ResponseEntity<Map<String, UUID>> {
        val templateId = communicationPort.createTemplate(request)
        return ResponseEntity.ok(mapOf("templateId" to templateId))
    }

    @PostMapping("/channels/{channelId}/request-verification")
    fun requestVerification(
        @PathVariable channelId: UUID
    ): ResponseEntity<Void> {
        communicationPort.requestChannelVerification(channelId)
        return ResponseEntity.accepted().build()
    }

    @PostMapping("/channels/{channelId}/verify")
    fun verifyChannel(
        @PathVariable channelId: UUID,
        @RequestParam token: String
    ): ResponseEntity<Map<String, Boolean>> {
        val success = communicationPort.verifyChannel(channelId, token)
        return ResponseEntity.ok(mapOf("success" to success))
    }
}
