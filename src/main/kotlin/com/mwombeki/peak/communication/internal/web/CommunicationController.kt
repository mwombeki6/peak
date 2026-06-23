package com.mwombeki.peak.communication.internal.web

import com.mwombeki.peak.communication.api.*
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/v1/communication")
class CommunicationController(
    private val communicationPort: CommunicationPort
) {

    @PostMapping("/notifications")
    @PreAuthorize("hasAnyRole('ROLE_TENANT_ADMIN', 'ROLE_PROPERTY_MANAGER', 'ROLE_SYSTEM')")
    fun sendNotification(
        @RequestBody request: EnqueueNotificationRequest
    ): ResponseEntity<Map<String, UUID>> {
        val eventId = communicationPort.enqueue(request)
        return ResponseEntity.ok(mapOf("eventId" to eventId))
    }

    @PostMapping("/contacts")
    @PreAuthorize("hasAnyRole('ROLE_TENANT_ADMIN', 'ROLE_PROPERTY_MANAGER')")
    fun createContact(
        @RequestBody request: CreateContactRequest
    ): ResponseEntity<Map<String, UUID>> {
        val contactId = communicationPort.createContact(request)
        return ResponseEntity.ok(mapOf("contactId" to contactId))
    }

    @GetMapping("/contacts")
    @PreAuthorize("hasAnyRole('ROLE_TENANT_ADMIN', 'ROLE_PROPERTY_MANAGER')")
    fun listContacts(): ResponseEntity<List<ContactResponse>> {
        return ResponseEntity.ok(communicationPort.listContacts())
    }

    @PostMapping("/templates")
    @PreAuthorize("hasAnyRole('ROLE_TENANT_ADMIN')")
    fun createTemplate(
        @RequestBody request: CreateTemplateRequest
    ): ResponseEntity<Map<String, UUID>> {
        val templateId = communicationPort.createTemplate(request)
        return ResponseEntity.ok(mapOf("templateId" to templateId))
    }

    @PostMapping("/channels/{channelId}/request-verification")
    @PreAuthorize("hasAnyRole('ROLE_TENANT_ADMIN', 'ROLE_PROPERTY_MANAGER')")
    fun requestVerification(
        @PathVariable channelId: UUID
    ): ResponseEntity<Void> {
        communicationPort.requestChannelVerification(channelId)
        return ResponseEntity.accepted().build()
    }

    @PostMapping("/channels/{channelId}/verify")
    @PreAuthorize("hasAnyRole('ROLE_TENANT_ADMIN', 'ROLE_PROPERTY_MANAGER')")
    fun verifyChannel(
        @PathVariable channelId: UUID,
        @RequestParam token: String
    ): ResponseEntity<Map<String, Boolean>> {
        val success = communicationPort.verifyChannel(channelId, token)
        return ResponseEntity.ok(mapOf("success" to success))
    }
}
