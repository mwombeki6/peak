package com.mwombeki.peak.communication.api

import java.time.OffsetDateTime
import java.time.LocalTime
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
    fun assignContactRole(contactId: UUID, request: AssignContactRoleRequest): ContactRoleMutationReceipt
    fun recordConsent(
        contactId: UUID,
        channelId: UUID,
        request: RecordCommunicationConsentRequest,
    ): CommunicationConsentReceipt
    fun configureReportRecipient(request: ConfigureReportRecipientRequest): ReportRecipientMutationReceipt
    fun listReportRecipients(): List<ReportRecipientResponse>
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
    val roles: List<ContactRoleResponse> = emptyList(),
    val consents: List<ContactConsentResponse> = emptyList(),
)

data class ContactChannelResponse(
    val id: UUID,
    val channelType: String,
    val address: String,
    val verificationStatus: String,
    val isPrimary: Boolean,
)

data class AssignContactRoleRequest(
    val roleCode: String,
    val propertyId: UUID? = null,
    val primary: Boolean = false,
)

data class ContactRoleResponse(
    val id: UUID,
    val roleCode: String,
    val propertyId: UUID?,
    val primary: Boolean,
)

data class ContactConsentResponse(
    val id: UUID,
    val channelId: UUID,
    val purpose: String,
    val status: String,
    val policyVersion: String,
    val capturedAt: OffsetDateTime,
    val expiresAt: OffsetDateTime?,
)

data class RecordCommunicationConsentRequest(
    val purpose: String,
    val policyVersion: String,
    val status: String = "active",
    val expiresAt: OffsetDateTime? = null,
)

data class ConfigureReportRecipientRequest(
    val contactId: UUID,
    val channelId: UUID,
    val reportCode: String = "monthly_executive_summary",
    val subscriptionName: String,
    val propertyId: UUID? = null,
    val frequency: String = "monthly",
    val scheduleTime: LocalTime? = null,
    val timezone: String = "Africa/Dar_es_Salaam",
    val deliveryFormat: String = "pdf",
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

data class ContactRoleMutationReceipt(
    val contactId: UUID,
    val roleAssignmentId: UUID,
    val roleCode: String,
    val propertyId: UUID?,
    val primary: Boolean,
    val changed: Boolean,
    val replayed: Boolean,
)

data class CommunicationConsentReceipt(
    val consentId: UUID,
    val contactId: UUID,
    val channelId: UUID,
    val purpose: String,
    val status: String,
    val replayed: Boolean,
)

data class ReportRecipientMutationReceipt(
    val subscriptionId: UUID,
    val recipientId: UUID,
    val contactId: UUID,
    val channelId: UUID,
    val changed: Boolean,
    val replayed: Boolean,
)

data class ReportRecipientResponse(
    val subscriptionId: UUID,
    val recipientId: UUID,
    val reportCode: String,
    val subscriptionName: String,
    val propertyId: UUID?,
    val frequency: String,
    val timezone: String,
    val contactId: UUID,
    val contactName: String,
    val channelId: UUID,
    val channelType: String,
    val maskedAddress: String,
    val deliveryFormat: String,
    val enabled: Boolean,
    val hasActiveConsent: Boolean,
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
