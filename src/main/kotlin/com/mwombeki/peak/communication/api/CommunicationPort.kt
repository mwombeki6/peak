package com.mwombeki.peak.communication.api

import java.time.OffsetDateTime
import java.util.UUID

data class EnqueueNotificationRequest(
   val propertyId: UUID? = null,
   val channel: String,     // "EMAIL", "SMS", "WHATSAPP"
   val recipient: String,   // e.g., "manager@mbeyapeak.com" or "255712345678"
   val subject: String?,
   val content: String,
)

interface CommunicationPort {
    fun enqueue(request: EnqueueNotificationRequest): NotificationEnqueueReceipt
    fun createContact(request: CreateContactRequest): ContactMutationReceipt
    fun listContacts(): List<ContactResponse>
    fun createTemplate(request: CreateTemplateRequest): TemplateMutationReceipt
    fun verifyChannel(channelId: UUID, token: String): ChannelVerificationReceipt
    fun requestChannelVerification(channelId: UUID): ChannelVerificationRequestReceipt
    fun listDeliveryRequests(): List<DeliveryRequestResponse>
    fun getDeliveryRequest(deliveryRequestId: UUID): DeliveryRequestResponse
    fun listDeliveryAttempts(deliveryRequestId: UUID): List<DeliveryAttemptResponse>
    fun retryDelivery(deliveryRequestId: UUID): DeliveryRetryReceipt
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
    val isPrimary: Boolean,
    val channels: List<ContactChannelResponse> = emptyList(),
)

data class ContactChannelResponse(
    val id: UUID,
    val channelType: String,
    val address: String,
    val verificationStatus: String,
    val isPrimary: Boolean,
)

data class CreateTemplateRequest(
    val name: String,
    val subject: String?,
    val content: String,
    val type: String // EMAIL, SMS, WHATSAPP
)

data class NotificationEnqueueReceipt(
    val eventId: UUID,
    val deliveryRequestId: UUID? = null,
    val replayed: Boolean,
)

data class ContactMutationReceipt(
    val contactId: UUID,
    val channelIds: List<UUID>,
    val replayed: Boolean,
)

data class TemplateMutationReceipt(
    val templateId: UUID,
    val replayed: Boolean,
)

data class ChannelVerificationRequestReceipt(
    val channelId: UUID,
    val notificationEventId: UUID,
    val deliveryRequestId: UUID? = null,
    val replayed: Boolean,
)

data class ChannelVerificationReceipt(
    val channelId: UUID,
    val verified: Boolean,
    val changed: Boolean,
    val replayed: Boolean,
)

data class DeliveryRequestResponse(
    val id: UUID,
    val propertyId: UUID?,
    val originalOutboxEventId: UUID,
    val currentOutboxEventId: UUID,
    val channel: String,
    val recipientFingerprint: String,
    val subjectPresent: Boolean,
    val status: String,
    val attemptCount: Int,
    val maxAttempts: Int,
    val requestedAt: OffsetDateTime,
    val deliveredAt: OffsetDateTime?,
    val failedAt: OffsetDateTime?,
    val lastError: String?,
)

data class DeliveryAttemptResponse(
    val id: UUID,
    val deliveryRequestId: UUID,
    val outboxEventId: UUID,
    val attemptNumber: Int,
    val provider: String,
    val status: String,
    val providerMessageId: String?,
    val errorMessage: String?,
    val startedAt: OffsetDateTime,
    val completedAt: OffsetDateTime?,
)

data class DeliveryRetryReceipt(
    val deliveryRequestId: UUID,
    val eventId: UUID,
    val replayed: Boolean,
)
