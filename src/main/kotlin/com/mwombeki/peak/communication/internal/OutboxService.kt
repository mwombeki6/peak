package com.mwombeki.peak.communication.internal

import com.mwombeki.peak.audit.api.AuditPort
import com.mwombeki.peak.audit.api.AuditResource
import com.mwombeki.peak.audit.api.TenantAuditEvent
import com.mwombeki.peak.communication.api.ChannelVerificationReceipt
import com.mwombeki.peak.communication.api.ChannelVerificationRequestReceipt
import com.mwombeki.peak.communication.api.CommunicationPort
import com.mwombeki.peak.communication.api.ContactChannelResponse
import com.mwombeki.peak.communication.api.ContactMutationReceipt
import com.mwombeki.peak.communication.api.ContactResponse
import com.mwombeki.peak.communication.api.CreateContactRequest
import com.mwombeki.peak.communication.api.CreateTemplateRequest
import com.mwombeki.peak.communication.api.EnqueueNotificationRequest
import com.mwombeki.peak.communication.api.NotificationEnqueueReceipt
import com.mwombeki.peak.communication.api.TemplateMutationReceipt
import com.mwombeki.peak.reliability.api.IdempotencyCommand
import com.mwombeki.peak.reliability.api.IdempotencyPort
import com.mwombeki.peak.reliability.api.IdempotencyReservation
import com.mwombeki.peak.reliability.api.OutboxDestination
import com.mwombeki.peak.reliability.api.OutboxEventCommand
import com.mwombeki.peak.reliability.api.OutboxPort
import com.mwombeki.peak.shared.context.DatabaseSessionContext
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.OffsetDateTime
import java.util.Base64
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper

@Service
class OutboxService(
    private val jdbcTemplate: JdbcTemplate,
    private val requestContextHolder: RequestContextHolder,
    private val databaseSessionContext: DatabaseSessionContext,
    private val idempotencyPort: IdempotencyPort,
    private val auditPort: AuditPort,
    private val outboxPort: OutboxPort,
    private val objectMapper: ObjectMapper,
) : CommunicationPort {

    @Transactional
    override fun enqueue(request: EnqueueNotificationRequest): NotificationEnqueueReceipt {
        val tenantId = bindTenantContext()
        val channel = request.channel.canonicalChannel()
        val recipient = request.recipient.normalizedRequired("recipient")
        val content = request.content.normalizedRequired("content")
        request.propertyId?.let { requirePropertyBelongsToTenant(tenantId, it) }

        return withIdempotency(
            operationType = "communication.notification.enqueue",
            requestPayload = mapOf(
                "propertyId" to request.propertyId,
                "channel" to channel,
                "recipient" to recipient,
                "subject" to request.subject?.trim(),
                "content" to content,
            ),
            resourceType = "communication_notification",
            replayType = NotificationEnqueueReceipt::class.java,
        ) { idempotencyKeyId ->
            val eventId = outboxPort.enqueue(
                OutboxEventCommand(
                    aggregateType = "communication_notification",
                    aggregateId = request.propertyId ?: tenantId,
                    eventType = "communication.notification.$channel",
                    destination = OutboxDestination.NOTIFICATION,
                    tenantId = tenantId,
                    propertyId = request.propertyId,
                    payload = mapOf(
                        "channel" to channel,
                        "recipient" to recipient,
                        "subject" to request.subject?.trim(),
                        "content" to content,
                    ),
                    idempotencyKeyId = idempotencyKeyId,
                    priority = 4,
                ),
            )

            auditPort.recordTenantEvent(
                TenantAuditEvent(
                    tenantId = tenantId,
                    action = "communication.notification.enqueued",
                    resource = AuditResource("outbox_events", eventId),
                    after = mapOf(
                        "eventId" to eventId,
                        "propertyId" to request.propertyId,
                        "channel" to channel,
                        "recipientFingerprint" to sha256Hex(recipient),
                        "subjectPresent" to !request.subject.isNullOrBlank(),
                    ),
                ),
            )

            NotificationEnqueueReceipt(eventId = eventId, replayed = false)
        }
    }

    @Transactional
    override fun createContact(request: CreateContactRequest): ContactMutationReceipt {
        val tenantId = bindTenantContext()
        val fullName = request.fullName.normalizedRequired("fullName")
        val email = request.email.normalizedRequired("email")

        return withIdempotency(
            operationType = "communication.contact.create",
            requestPayload = mapOf(
                "fullName" to fullName,
                "jobTitle" to request.jobTitle?.trim(),
                "email" to email,
                "phone" to request.phone?.trim(),
                "whatsapp" to request.whatsapp?.trim(),
            ),
            resourceType = "tenant_contacts",
            replayType = ContactMutationReceipt::class.java,
        ) { idempotencyKeyId ->
            val contactId = UUID.randomUUID()
            jdbcTemplate.update(
                """
                INSERT INTO tenant_contacts (id, tenant_id, full_name, job_title, status)
                VALUES (?, ?, ?, ?, 'active')
                """.trimIndent(),
                contactId,
                tenantId,
                fullName,
                request.jobTitle?.trim()?.takeIf { it.isNotEmpty() },
            )

            val channelIds = buildList {
                add(addChannel(tenantId, contactId, "email", email, true))
                request.phone?.trim()?.takeIf { it.isNotEmpty() }
                    ?.let { add(addChannel(tenantId, contactId, "sms", it, false)) }
                request.whatsapp?.trim()?.takeIf { it.isNotEmpty() }
                    ?.let { add(addChannel(tenantId, contactId, "whatsapp", it, false)) }
            }

            auditPort.recordTenantEvent(
                TenantAuditEvent(
                    tenantId = tenantId,
                    action = "communication.contact.created",
                    resource = AuditResource("tenant_contacts", contactId),
                    after = mapOf(
                        "contactId" to contactId,
                        "channelCount" to channelIds.size,
                        "hasJobTitle" to !request.jobTitle.isNullOrBlank(),
                    ),
                ),
            )

            outboxPort.enqueue(
                OutboxEventCommand(
                    aggregateType = "tenant_contacts",
                    aggregateId = contactId,
                    tenantId = tenantId,
                    eventType = "communication.contact.created",
                    destination = OutboxDestination.PLATFORM,
                    payload = mapOf(
                        "tenantId" to tenantId,
                        "contactId" to contactId,
                        "channelIds" to channelIds,
                    ),
                    idempotencyKeyId = idempotencyKeyId,
                    priority = 5,
                ),
            )

            ContactMutationReceipt(contactId = contactId, channelIds = channelIds, replayed = false)
        }
    }

    @Transactional
    override fun listContacts(): List<ContactResponse> {
        val tenantId = bindTenantContext()
        val contacts = jdbcTemplate.query(
            """
            SELECT id, full_name, job_title, status, is_primary_contact
            FROM tenant_contacts
            WHERE tenant_id = ? AND deleted_at IS NULL
            ORDER BY full_name
            """.trimIndent(),
            { rs, _ ->
                ContactResponse(
                    id = rs.getObject("id", UUID::class.java),
                    fullName = rs.getString("full_name"),
                    jobTitle = rs.getString("job_title"),
                    status = rs.getString("status"),
                    isPrimary = rs.getBoolean("is_primary_contact"),
                )
            },
            tenantId,
        )
        if (contacts.isEmpty()) {
            return contacts
        }

        val channelsByContactId = jdbcTemplate.query(
            """
            SELECT id, contact_id, channel_type, address, verification_status, is_primary
            FROM contact_channels
            WHERE tenant_id = ?
              AND deleted_at IS NULL
            ORDER BY is_primary DESC, channel_type, address
            """.trimIndent(),
            { rs, _ ->
                rs.getObject("contact_id", UUID::class.java) to ContactChannelResponse(
                    id = rs.getObject("id", UUID::class.java),
                    channelType = rs.getString("channel_type"),
                    address = rs.getString("address"),
                    verificationStatus = rs.getString("verification_status"),
                    isPrimary = rs.getBoolean("is_primary"),
                )
            },
            tenantId,
        ).groupBy({ it.first }, { it.second })

        return contacts.map { contact ->
            contact.copy(channels = channelsByContactId[contact.id].orEmpty())
        }
    }

    @Transactional
    override fun createTemplate(request: CreateTemplateRequest): TemplateMutationReceipt {
        val tenantId = bindTenantContext()
        val name = request.name.normalizedRequired("name")
        val content = request.content.normalizedRequired("content")
        val channel = request.type.canonicalChannel()

        return withIdempotency(
            operationType = "communication.template.create",
            requestPayload = mapOf(
                "name" to name,
                "subject" to request.subject?.trim(),
                "content" to content,
                "type" to channel,
            ),
            resourceType = "communication_templates",
            replayType = TemplateMutationReceipt::class.java,
        ) { idempotencyKeyId ->
            val templateId = UUID.randomUUID()
            jdbcTemplate.update(
                """
                INSERT INTO communication_templates (id, tenant_id, name, subject, content, channel_type)
                VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                templateId,
                tenantId,
                name,
                request.subject?.trim()?.takeIf { it.isNotEmpty() },
                content,
                channel,
            )

            auditPort.recordTenantEvent(
                TenantAuditEvent(
                    tenantId = tenantId,
                    action = "communication.template.created",
                    resource = AuditResource("communication_templates", templateId),
                    after = mapOf(
                        "templateId" to templateId,
                        "channel" to channel,
                        "subjectPresent" to !request.subject.isNullOrBlank(),
                    ),
                ),
            )

            outboxPort.enqueue(
                OutboxEventCommand(
                    aggregateType = "communication_templates",
                    aggregateId = templateId,
                    tenantId = tenantId,
                    eventType = "communication.template.created",
                    destination = OutboxDestination.PLATFORM,
                    payload = mapOf(
                        "tenantId" to tenantId,
                        "templateId" to templateId,
                        "channel" to channel,
                    ),
                    idempotencyKeyId = idempotencyKeyId,
                    priority = 5,
                ),
            )

            TemplateMutationReceipt(templateId = templateId, replayed = false)
        }
    }

    @Transactional
    override fun requestChannelVerification(channelId: UUID): ChannelVerificationRequestReceipt {
        val tenantId = bindTenantContext()

        return withIdempotency(
            operationType = "communication.channel.verification.request",
            requestPayload = mapOf("channelId" to channelId),
            resourceType = "contact_channels",
            replayType = ChannelVerificationRequestReceipt::class.java,
        ) { idempotencyKeyId ->
            val token = verificationToken()
            val tokenHash = sha256Hex(token)

            ensureUpdated(
                jdbcTemplate.update(
                    """
                    UPDATE contact_channels
                    SET verification_status = 'pending',
                        verification_token_hash = ?,
                        verification_expires_at = now() + interval '24 hours',
                        updated_at = now()
                    WHERE id = ? AND tenant_id = ? AND deleted_at IS NULL AND is_active = true
                    """.trimIndent(),
                    tokenHash,
                    channelId,
                    tenantId,
                ),
                "Contact channel not found or access denied.",
            )

            val channelInfo = contactChannelInfo(tenantId, channelId)
            val eventId = outboxPort.enqueue(
                OutboxEventCommand(
                    aggregateType = "contact_channels",
                    aggregateId = channelId,
                    tenantId = tenantId,
                    eventType = "communication.channel.verification.requested",
                    destination = OutboxDestination.NOTIFICATION,
                    payload = mapOf(
                        "channel" to channelInfo.channelType,
                        "recipient" to channelInfo.address,
                        "subject" to "Verify your contact channel",
                        "content" to "Your verification token is: $token",
                    ),
                    idempotencyKeyId = idempotencyKeyId,
                    priority = 3,
                ),
            )

            auditPort.recordTenantEvent(
                TenantAuditEvent(
                    tenantId = tenantId,
                    action = "communication.channel.verification_requested",
                    resource = AuditResource("contact_channels", channelId),
                    after = mapOf(
                        "channelId" to channelId,
                        "channel" to channelInfo.channelType,
                        "recipientFingerprint" to sha256Hex(channelInfo.address),
                    ),
                ),
            )

            ChannelVerificationRequestReceipt(
                channelId = channelId,
                notificationEventId = eventId,
                replayed = false,
            )
        }
    }

    @Transactional
    override fun verifyChannel(channelId: UUID, token: String): ChannelVerificationReceipt {
        val tenantId = bindTenantContext()
        val cleanToken = token.normalizedRequired("token")

        return withIdempotency(
            operationType = "communication.channel.verify",
            requestPayload = mapOf(
                "channelId" to channelId,
                "tokenHash" to sha256Hex(cleanToken),
            ),
            resourceType = "contact_channels",
            replayType = ChannelVerificationReceipt::class.java,
        ) {
            val stored = jdbcTemplate.query(
                """
                SELECT verification_token_hash, verification_expires_at
                FROM contact_channels
                WHERE id = ?
                  AND tenant_id = ?
                  AND verification_status = 'pending'
                  AND deleted_at IS NULL
                  AND is_active = true
                """.trimIndent(),
                { rs, _ ->
                    PendingVerification(
                        tokenHash = rs.getString("verification_token_hash"),
                        expiresAt = rs.getObject("verification_expires_at", OffsetDateTime::class.java),
                    )
                },
                channelId,
                tenantId,
            ).firstOrNull()

            val changed = if (stored == null ||
                stored.tokenHash.isNullOrBlank() ||
                stored.expiresAt == null ||
                !stored.expiresAt.isAfter(OffsetDateTime.now()) ||
                !constantTimeEquals(stored.tokenHash, sha256Hex(cleanToken))
            ) {
                false
            } else {
                jdbcTemplate.update(
                    """
                    UPDATE contact_channels
                    SET verification_status = 'verified',
                        verified_at = now(),
                        verification_method = 'token',
                        verification_token_hash = NULL,
                        verification_expires_at = NULL,
                        updated_at = now()
                    WHERE id = ?
                      AND tenant_id = ?
                      AND verification_status = 'pending'
                      AND verification_token_hash = ?
                    """.trimIndent(),
                    channelId,
                    tenantId,
                    stored.tokenHash,
                ) > 0
            }

            if (changed) {
                auditPort.recordTenantEvent(
                    TenantAuditEvent(
                        tenantId = tenantId,
                        action = "communication.channel.verified",
                        resource = AuditResource("contact_channels", channelId),
                        after = mapOf("channelId" to channelId),
                    ),
                )
            }

            ChannelVerificationReceipt(
                channelId = channelId,
                verified = changed,
                changed = changed,
                replayed = false,
            )
        }
    }

    private fun bindTenantContext(): UUID {
        val identity = requestContextHolder.current().identity
        require(identity is RequestIdentity.Tenant) {
            "Communication actions require an active tenant identity."
        }
        databaseSessionContext.bind(identity)
        return identity.tenantId
    }

    private fun addChannel(
        tenantId: UUID,
        contactId: UUID,
        type: String,
        address: String,
        isPrimary: Boolean,
    ): UUID {
        val channelId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO contact_channels (
                id,
                tenant_id,
                contact_id,
                channel_type,
                address,
                normalized_address,
                is_primary,
                verification_status
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, 'unverified')
            """.trimIndent(),
            channelId,
            tenantId,
            contactId,
            type.canonicalChannel(),
            address,
            address.trim().lowercase(),
            isPrimary,
        )
        return channelId
    }

    private fun contactChannelInfo(tenantId: UUID, channelId: UUID): ContactChannelInfo {
        return jdbcTemplate.query(
            """
            SELECT channel_type, address
            FROM contact_channels
            WHERE id = ? AND tenant_id = ? AND deleted_at IS NULL AND is_active = true
            """.trimIndent(),
            { rs, _ ->
                ContactChannelInfo(
                    channelType = rs.getString("channel_type"),
                    address = rs.getString("address"),
                )
            },
            channelId,
            tenantId,
        ).firstOrNull() ?: throw NoSuchElementException("Contact channel not found or access denied.")
    }

    private fun requirePropertyBelongsToTenant(tenantId: UUID, propertyId: UUID) {
        val exists = requireNotNull(
            jdbcTemplate.queryForObject(
                """
                SELECT EXISTS(
                    SELECT 1
                    FROM properties
                    WHERE id = ? AND tenant_id = ? AND deleted_at IS NULL
                )
                """.trimIndent(),
                Boolean::class.java,
                propertyId,
                tenantId,
            ),
        )
        if (!exists) {
            throw NoSuchElementException("Property record not found or access denied.")
        }
    }

    private fun ensureUpdated(rowsUpdated: Int, message: String) {
        if (rowsUpdated == 0) {
            throw NoSuchElementException(message)
        }
    }

    private fun <T : Any> withIdempotency(
        operationType: String,
        requestPayload: Any,
        resourceType: String,
        replayType: Class<T>,
        block: (UUID) -> T,
    ): T {
        val reservation = idempotencyPort.reserve(
            IdempotencyCommand(
                operationType = operationType,
                requestPayload = requestPayload,
                resourceType = resourceType,
            ),
        )

        return when (reservation) {
            is IdempotencyReservation.Started -> {
                val receipt = block(reservation.recordId)
                idempotencyPort.markSucceeded(
                    recordId = reservation.recordId,
                    responseCode = 200,
                    responseBody = receipt,
                    resourceId = receipt.resourceIdOrNull(),
                )
                receipt
            }

            is IdempotencyReservation.Replay -> replay(reservation, replayType)
            is IdempotencyReservation.InProgress -> {
                throw IllegalStateException(
                    "Communication command is already being processed for this idempotency key",
                )
            }

            is IdempotencyReservation.Conflict -> {
                throw IllegalArgumentException(
                    "Idempotency key was already used for a different communication request",
                )
            }
        }
    }

    private fun <T : Any> replay(
        reservation: IdempotencyReservation.Replay,
        replayType: Class<T>,
    ): T {
        if (reservation.responseBody.isNullOrBlank()) {
            throw IllegalArgumentException("Communication replay does not contain a stored response body")
        }
        return objectMapper.readValue(reservation.responseBody, replayType).withReplayed()
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> T.withReplayed(): T {
        return when (this) {
            is NotificationEnqueueReceipt -> copy(replayed = true)
            is ContactMutationReceipt -> copy(replayed = true)
            is TemplateMutationReceipt -> copy(replayed = true)
            is ChannelVerificationRequestReceipt -> copy(replayed = true)
            is ChannelVerificationReceipt -> copy(replayed = true)
            else -> this
        } as T
    }

    private fun Any.resourceIdOrNull(): UUID? {
        return when (this) {
            is NotificationEnqueueReceipt -> eventId
            is ContactMutationReceipt -> contactId
            is TemplateMutationReceipt -> templateId
            is ChannelVerificationRequestReceipt -> channelId
            is ChannelVerificationReceipt -> channelId
            else -> null
        }
    }

    private fun String.canonicalChannel(): String {
        val value = trim().lowercase()
        require(value in ALLOWED_CHANNELS) {
            "Unsupported communication channel: $this"
        }
        return value
    }

    private fun String.normalizedRequired(fieldName: String): String {
        return trim().takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("$fieldName is required")
    }

    private fun verificationToken(): String {
        val bytes = ByteArray(32)
        secureRandom.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun sha256Hex(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") {
            "%02x".format(it.toInt() and 0xff)
        }
    }

    private fun constantTimeEquals(left: String, right: String): Boolean {
        return MessageDigest.isEqual(
            left.toByteArray(Charsets.UTF_8),
            right.toByteArray(Charsets.UTF_8),
        )
    }

    private data class ContactChannelInfo(
        val channelType: String,
        val address: String,
    )

    private data class PendingVerification(
        val tokenHash: String?,
        val expiresAt: OffsetDateTime?,
    )

    private companion object {
        private val ALLOWED_CHANNELS = setOf("email", "sms", "whatsapp", "voice_phone")
        private val secureRandom = SecureRandom()
    }
}
