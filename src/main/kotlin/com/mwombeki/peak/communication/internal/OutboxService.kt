package com.mwombeki.peak.communication.internal

import com.mwombeki.peak.communication.api.CommunicationPort
import com.mwombeki.peak.communication.api.ContactResponse
import com.mwombeki.peak.communication.api.CreateContactRequest
import com.mwombeki.peak.communication.api.CreateTemplateRequest
import com.mwombeki.peak.communication.api.EnqueueNotificationRequest
import com.mwombeki.peak.reliability.api.OutboxDestination
import com.mwombeki.peak.reliability.api.OutboxEventCommand
import com.mwombeki.peak.reliability.api.OutboxPort
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

@Service
class OutboxService(
    private val jdbcTemplate: JdbcTemplate,
    private val requestContextHolder: RequestContextHolder,
    private val outboxPort: OutboxPort,
) : CommunicationPort {

    @Transactional
    override fun enqueue(request: EnqueueNotificationRequest): UUID {
        val tenantId = resolveActiveTenantId()
        val channel = request.channel.canonicalChannel()
        request.propertyId?.let { requirePropertyBelongsToTenant(tenantId, it) }

        return outboxPort.enqueue(
            OutboxEventCommand(
                aggregateType = "communication_notification",
                aggregateId = request.propertyId,
                eventType = "communication.notification.$channel",
                destination = OutboxDestination.NOTIFICATION,
                tenantId = tenantId,
                propertyId = request.propertyId,
                payload = mapOf(
                    "channel" to channel,
                    "recipient" to request.recipient,
                    "subject" to request.subject,
                    "content" to request.content,
                ),
            ),
        )
    }

    @Transactional
    override fun createContact(request: CreateContactRequest): UUID {
        val tenantId = resolveActiveTenantId()
        val contactId = UUID.randomUUID()

        jdbcTemplate.update(
            """
            INSERT INTO tenant_contacts (id, tenant_id, full_name, job_title, status)
            VALUES (?, ?, ?, ?, 'active')
            """.trimIndent(),
            contactId,
            tenantId,
            request.fullName,
            request.jobTitle,
        )

        addChannel(tenantId, contactId, "email", request.email, true)
        request.phone?.let { addChannel(tenantId, contactId, "sms", it, false) }
        request.whatsapp?.let { addChannel(tenantId, contactId, "whatsapp", it, false) }

        return contactId
    }

    @Transactional(readOnly = true)
    override fun listContacts(): List<ContactResponse> {
        val tenantId = resolveActiveTenantId()
        return jdbcTemplate.query(
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
    }

    @Transactional
    override fun createTemplate(request: CreateTemplateRequest): UUID {
        val tenantId = resolveActiveTenantId()
        val templateId = UUID.randomUUID()

        jdbcTemplate.update(
            """
            INSERT INTO communication_templates (id, tenant_id, name, subject, content, channel_type)
            VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            templateId,
            tenantId,
            request.name,
            request.subject,
            request.content,
            request.type.canonicalChannel(),
        )
        return templateId
    }

    @Transactional
    override fun requestChannelVerification(channelId: UUID) {
        val tenantId = resolveActiveTenantId()
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
        enqueue(
            EnqueueNotificationRequest(
                channel = channelInfo.channelType,
                recipient = channelInfo.address,
                subject = "Verify your contact channel",
                content = "Your verification token is: $token",
            ),
        )
    }

    @Transactional
    override fun verifyChannel(channelId: UUID, token: String): Boolean {
        val tenantId = resolveActiveTenantId()
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
        ).firstOrNull() ?: return false

        if (stored.tokenHash.isNullOrBlank() || stored.expiresAt == null || !stored.expiresAt.isAfter(OffsetDateTime.now())) {
            return false
        }

        if (!constantTimeEquals(stored.tokenHash, sha256Hex(token))) {
            return false
        }

        return jdbcTemplate.update(
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

    private fun resolveActiveTenantId(): UUID {
        val context = requestContextHolder.current()
        return when (val identity = context.identity) {
            is RequestIdentity.Tenant -> identity.tenantId
            else -> throw IllegalStateException("Communication actions require an active tenant identity.")
        }
    }

    private fun addChannel(
        tenantId: UUID,
        contactId: UUID,
        type: String,
        address: String,
        isPrimary: Boolean,
    ) {
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
            UUID.randomUUID(),
            tenantId,
            contactId,
            type.canonicalChannel(),
            address,
            address.trim().lowercase(),
            isPrimary,
        )
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

    private fun String.canonicalChannel(): String {
        val value = trim().lowercase()
        require(value in ALLOWED_CHANNELS) {
            "Unsupported communication channel: $this"
        }
        return value
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
