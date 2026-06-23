package com.mwombeki.peak.communication.internal

import com.mwombeki.peak.communication.api.*
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
class OutboxService(
    private val jdbcTemplate: JdbcTemplate,
    private val requestContextHolder: RequestContextHolder
) : CommunicationPort {

    private fun resolveActiveTenantId(): UUID {
        val context = requestContextHolder.current()
        return when (val identity = context.identity) {
            is RequestIdentity.Tenant -> identity.tenantId
            else -> throw IllegalStateException("Security Violation: Outbox actions require an active Tenant context.")
        }
    }

    @Transactional
    override fun enqueue(request: EnqueueNotificationRequest): UUID {
        val tenantId = resolveActiveTenantId()
        val eventId = UUID.randomUUID()

        // Insert into outbox_events table according to schema
        jdbcTemplate.update(
            """
            INSERT INTO outbox_events (
                id, tenant_id, property_id, aggregate_type, event_type, 
                destination, payload, status, attempt_count, max_attempts, next_attempt_at
            )
            VALUES (?, ?, ?, 'NOTIFICATION', ?, 'notification', ?::jsonb, 'pending', 0, 10, NOW())
            """.trimIndent(),
            eventId,
            tenantId,
            request.propertyId,
            "SEND_${request.channel.uppercase()}",
            // Payload as JSONB
            """{"recipient": "${request.recipient}", "subject": "${request.subject ?: ""}", "content": "${request.content}"}"""
        )

        println("[Outbox Registry] Notification staged under Event ID: $eventId")
        return eventId
    }

    @Transactional
    override fun createContact(request: CreateContactRequest): UUID {
        val tenantId = resolveActiveTenantId()
        val contactId = UUID.randomUUID()
        
        jdbcTemplate.update(
            "INSERT INTO tenant_contacts (id, tenant_id, full_name, job_title, status) VALUES (?, ?, ?, ?, 'active')",
            contactId, tenantId, request.fullName, request.jobTitle
        )
        
        // Create channels
        addChannel(tenantId, contactId, "email", request.email, true)
        request.phone?.let { addChannel(tenantId, contactId, "sms", it, false) }
        request.whatsapp?.let { addChannel(tenantId, contactId, "whatsapp", it, false) }
        
        return contactId
    }

    private fun addChannel(tenantId: UUID, contactId: UUID, type: String, address: String, isPrimary: Boolean) {
        jdbcTemplate.update(
            """
            INSERT INTO contact_channels (id, tenant_id, contact_id, channel_type, address, normalized_address, is_primary, verification_status)
            VALUES (?, ?, ?, ?, ?, ?, ?, 'unverified')
            """.trimIndent(),
            UUID.randomUUID(), tenantId, contactId, type, address, address.lowercase(), isPrimary
        )
    }

    override fun listContacts(): List<ContactResponse> {
        val tenantId = resolveActiveTenantId()
        return jdbcTemplate.query(
            "SELECT id, full_name, job_title, status, is_primary_contact FROM tenant_contacts WHERE tenant_id = ? AND deleted_at IS NULL",
            { rs, _ ->
                ContactResponse(
                    id = rs.getObject("id") as UUID,
                    fullName = rs.getString("full_name"),
                    jobTitle = rs.getString("job_title"),
                    status = rs.getString("status"),
                    isPrimary = rs.getBoolean("is_primary_contact")
                )
            },
            tenantId
        )
    }

    override fun createTemplate(request: CreateTemplateRequest): UUID {
        val tenantId = resolveActiveTenantId()
        val templateId = UUID.randomUUID()
        
        jdbcTemplate.update(
            """
            INSERT INTO communication_templates (id, tenant_id, name, subject, content, channel_type)
            VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            templateId, tenantId, request.name, request.subject, request.content, request.type.lowercase()
        )
        return templateId
    }

    @Transactional
    override fun requestChannelVerification(channelId: UUID) {
        val tenantId = resolveActiveTenantId()
        val token = UUID.randomUUID().toString().replace("-", "").take(32)
        
        // Update channel with token and status
        jdbcTemplate.update(
            """
            UPDATE contact_channels 
            SET verification_status = 'pending', 
                verification_token_hash = ?, 
                verification_expires_at = NOW() + interval '24 hours' 
            WHERE id = ? AND tenant_id = ?
            """.trimIndent(),
            token, channelId, tenantId
        )
        
        // Get channel info to send notification
        val channelInfo = jdbcTemplate.queryForMap(
            "SELECT channel_type, address FROM contact_channels WHERE id = ?", channelId
        )
        
        // Enqueue a notification to send the token
        enqueue(EnqueueNotificationRequest(
            propertyId = UUID.fromString("00000000-0000-0000-0000-000000000000"), // System scoped
            channel = channelInfo["channel_type"] as String,
            recipient = channelInfo["address"] as String,
            subject = "Verify your contact channel",
            content = "Your verification token is: $token"
        ))
    }

    @Transactional
    override fun verifyChannel(channelId: UUID, token: String): Boolean {
        val tenantId = resolveActiveTenantId()
        
        val updated = jdbcTemplate.update(
            """
            UPDATE contact_channels 
            SET verification_status = 'verified', 
                verified_at = NOW(), 
                verification_token_hash = NULL, 
                verification_expires_at = NULL 
            WHERE id = ? AND tenant_id = ? AND verification_token_hash = ? AND verification_expires_at > NOW()
            """.trimIndent(),
            channelId, tenantId, token
        )
        
        return updated > 0
    }
}