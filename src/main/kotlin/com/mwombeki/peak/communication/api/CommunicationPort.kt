package com.mwombeki.peak.communication.api

import java.util.UUID

data class EnqueueNotificationRequest(
   val propertyId: UUID,
   val channel: String,     // "EMAIL", "SMS", "WHATSAPP"
   val recipient: String,   // e.g., "manager@mbeyapeak.com" or "255712345678"
   val subject: String?,
   val content: String,
)

interface CommunicationPort {
    fun enqueue(request: EnqueueNotificationRequest): UUID
    fun createContact(request: CreateContactRequest): UUID
    fun listContacts(): List<ContactResponse>
    fun createTemplate(request: CreateTemplateRequest): UUID
    fun verifyChannel(channelId: UUID, token: String): Boolean
    fun requestChannelVerification(channelId: UUID)
}

data class CreateContactRequest(
    val fullName: String,
    val jobTitle: String? = null,
    val email: String,
    val phone: String? = null,
    val whatsapp: String? = null
)

data class ContactResponse(
    val id: UUID,
    val fullName: String,
    val jobTitle: String?,
    val status: String,
    val isPrimary: Boolean
)

data class CreateTemplateRequest(
    val name: String,
    val subject: String?,
    val content: String,
    val type: String // EMAIL, SMS, WHATSAPP
)

