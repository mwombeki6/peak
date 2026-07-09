package com.mwombeki.peak.communication.internal

import com.mwombeki.peak.audit.api.AuditPort
import com.mwombeki.peak.audit.api.AuditResource
import com.mwombeki.peak.audit.api.TenantAuditEvent
import com.mwombeki.peak.communication.api.ChannelVerificationReceipt
import com.mwombeki.peak.communication.api.ChannelVerificationRequestReceipt
import com.mwombeki.peak.communication.api.CommunicationPort
import com.mwombeki.peak.communication.api.CommunicationConsentReceipt
import com.mwombeki.peak.communication.api.ContactConsentResponse
import com.mwombeki.peak.communication.api.ContactChannelResponse
import com.mwombeki.peak.communication.api.ContactMutationReceipt
import com.mwombeki.peak.communication.api.ContactResponse
import com.mwombeki.peak.communication.api.ContactRoleMutationReceipt
import com.mwombeki.peak.communication.api.ContactRoleResponse
import com.mwombeki.peak.communication.api.CreateContactRequest
import com.mwombeki.peak.communication.api.CreateTemplateRequest
import com.mwombeki.peak.communication.api.DeliveryAttemptResponse
import com.mwombeki.peak.communication.api.DeliveryRequestResponse
import com.mwombeki.peak.communication.api.DeliveryRetryReceipt
import com.mwombeki.peak.communication.api.EnqueueNotificationRequest
import com.mwombeki.peak.communication.api.NotificationEnqueueReceipt
import com.mwombeki.peak.communication.api.RecordCommunicationConsentRequest
import com.mwombeki.peak.communication.api.AssignContactRoleRequest
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
import java.sql.ResultSet
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
        val purpose = request.purpose.normalizedCode("purpose")
        require(purpose in ALLOWED_CONSENT_PURPOSES) {
            "Unsupported communication purpose."
        }
        val channelInfo = consentedContactChannel(
            tenantId = tenantId,
            channelId = request.contactChannelId,
            purpose = purpose,
        )
        val message = resolveNotificationMessage(
            tenantId = tenantId,
            channel = channelInfo.channelType,
            request = request,
        )
        request.propertyId?.let { requirePropertyBelongsToTenant(tenantId, it) }

        return withIdempotency(
            operationType = "communication.notification.enqueue",
            requestPayload = mapOf(
                "propertyId" to request.propertyId,
                "contactChannelId" to request.contactChannelId,
                "purpose" to purpose,
                "templateId" to request.templateId,
                "variables" to request.variables,
                "subject" to message.subject,
                "content" to message.content,
            ),
            resourceType = "communication_notification",
            replayType = NotificationEnqueueReceipt::class.java,
        ) { idempotencyKeyId ->
            val eventId = outboxPort.enqueue(
                OutboxEventCommand(
                    aggregateType = "communication_notification",
                    aggregateId = request.propertyId ?: tenantId,
                    eventType = "communication.notification.${channelInfo.channelType}",
                    destination = OutboxDestination.NOTIFICATION,
                    tenantId = tenantId,
                    propertyId = request.propertyId,
                    payload = mapOf(
                        "channel" to channelInfo.channelType,
                        "contactChannelId" to request.contactChannelId,
                        "purpose" to purpose,
                        "subject" to message.subject,
                        "content" to message.content,
                    ),
                    idempotencyKeyId = idempotencyKeyId,
                    priority = 4,
                ),
            )
            val deliveryRequestId = insertDeliveryRequest(
                tenantId = tenantId,
                propertyId = request.propertyId,
                originalOutboxEventId = eventId,
                currentOutboxEventId = eventId,
                channel = channelInfo.channelType,
                recipient = channelInfo.address,
                subject = message.subject,
                content = message.content,
            )

            auditPort.recordTenantEvent(
                TenantAuditEvent(
                    tenantId = tenantId,
                    action = "communication.notification.enqueued",
                    resource = AuditResource("outbox_events", eventId),
                    after = mapOf(
                        "eventId" to eventId,
                        "propertyId" to request.propertyId,
                        "channel" to channelInfo.channelType,
                        "contactChannelId" to request.contactChannelId,
                        "purpose" to purpose,
                        "recipientFingerprint" to sha256Hex(channelInfo.address),
                        "subjectPresent" to !message.subject.isNullOrBlank(),
                    ),
                ),
            )

            NotificationEnqueueReceipt(
                eventId = eventId,
                deliveryRequestId = deliveryRequestId,
                replayed = false,
            )
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

        val rolesByContactId = jdbcTemplate.query(
            """
            SELECT id, contact_id, property_id, role_code, is_primary_for_role
            FROM tenant_contact_roles
            WHERE tenant_id = ?
              AND (effective_to IS NULL OR effective_to > now())
            ORDER BY role_code, property_id NULLS FIRST
            """.trimIndent(),
            { rs, _ ->
                rs.getObject("contact_id", UUID::class.java) to ContactRoleResponse(
                    id = rs.getObject("id", UUID::class.java),
                    roleCode = rs.getString("role_code"),
                    propertyId = rs.getObject("property_id", UUID::class.java),
                    primary = rs.getBoolean("is_primary_for_role"),
                )
            },
            tenantId,
        ).groupBy({ it.first }, { it.second })

        val consentsByContactId = jdbcTemplate.query(
            """
            SELECT DISTINCT ON (contact_id, contact_channel_id, purpose)
                   id,
                   contact_id,
                   contact_channel_id,
                   purpose,
                   status,
                   policy_version,
                   captured_at,
                   expires_at
            FROM communication_consents
            WHERE tenant_id = ?
            ORDER BY contact_id,
                     contact_channel_id,
                     purpose,
                     captured_at DESC,
                     created_at DESC,
                     id DESC
            """.trimIndent(),
            { rs, _ ->
                rs.getObject("contact_id", UUID::class.java) to ContactConsentResponse(
                    id = rs.getObject("id", UUID::class.java),
                    channelId = rs.getObject("contact_channel_id", UUID::class.java),
                    purpose = rs.getString("purpose"),
                    status = rs.getString("status"),
                    policyVersion = rs.getString("policy_version"),
                    capturedAt = rs.getObject("captured_at", OffsetDateTime::class.java),
                    expiresAt = rs.getObject("expires_at", OffsetDateTime::class.java),
                )
            },
            tenantId,
        ).groupBy({ it.first }, { it.second })

        return contacts.map { contact ->
            contact.copy(
                channels = channelsByContactId[contact.id].orEmpty(),
                roles = rolesByContactId[contact.id].orEmpty(),
                consents = consentsByContactId[contact.id].orEmpty(),
            )
        }
    }

    @Transactional
    override fun assignContactRole(
        contactId: UUID,
        request: AssignContactRoleRequest,
    ): ContactRoleMutationReceipt {
        val tenantId = bindTenantContext()
        val actorId = currentTenantUserId()
        val roleCode = request.roleCode.normalizedCode("roleCode")

        return withIdempotency(
            operationType = "communication.contact.role.assign",
            requestPayload = mapOf(
                "contactId" to contactId,
                "roleCode" to roleCode,
                "propertyId" to request.propertyId,
                "primary" to request.primary,
            ),
            resourceType = "tenant_contact_roles",
            replayType = ContactRoleMutationReceipt::class.java,
        ) { idempotencyKeyId ->
            requireActiveContact(tenantId, contactId)
            request.propertyId?.let { requirePropertyBelongsToTenant(tenantId, it) }
            requireContactRoleScope(roleCode, request.propertyId)

            if (request.primary) {
                jdbcTemplate.update(
                    """
                    UPDATE tenant_contact_roles
                    SET is_primary_for_role = false
                    WHERE tenant_id = ?
                      AND role_code = ?
                      AND property_id IS NOT DISTINCT FROM ?
                      AND contact_id <> ?
                      AND is_primary_for_role = true
                      AND (effective_to IS NULL OR effective_to > now())
                    """.trimIndent(),
                    tenantId,
                    roleCode,
                    request.propertyId,
                    contactId,
                )
            }

            val existing = jdbcTemplate.query(
                """
                SELECT id, is_primary_for_role
                FROM tenant_contact_roles
                WHERE tenant_id = ?
                  AND contact_id = ?
                  AND role_code = ?
                  AND property_id IS NOT DISTINCT FROM ?
                  AND effective_to IS NULL
                FOR UPDATE
                """.trimIndent(),
                { rs, _ ->
                    rs.getObject("id", UUID::class.java) to
                            rs.getBoolean("is_primary_for_role")
                },
                tenantId,
                contactId,
                roleCode,
                request.propertyId,
            ).singleOrNull()

            val roleAssignmentId: UUID
            val changed: Boolean
            if (existing == null) {
                roleAssignmentId = UUID.randomUUID()
                jdbcTemplate.update(
                    """
                    INSERT INTO tenant_contact_roles (
                        id,
                        tenant_id,
                        contact_id,
                        property_id,
                        role_code,
                        is_primary_for_role,
                        created_by
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    roleAssignmentId,
                    tenantId,
                    contactId,
                    request.propertyId,
                    roleCode,
                    request.primary,
                    actorId,
                )
                changed = true
            } else {
                roleAssignmentId = existing.first
                changed = existing.second != request.primary
                if (changed) {
                    jdbcTemplate.update(
                        """
                        UPDATE tenant_contact_roles
                        SET is_primary_for_role = ?
                        WHERE id = ? AND tenant_id = ?
                        """.trimIndent(),
                        request.primary,
                        roleAssignmentId,
                        tenantId,
                    )
                }
            }

            ContactRoleMutationReceipt(
                contactId = contactId,
                roleAssignmentId = roleAssignmentId,
                roleCode = roleCode,
                propertyId = request.propertyId,
                primary = request.primary,
                changed = changed,
                replayed = false,
            ).also { receipt ->
                if (receipt.changed) {
                    recordCommunicationSideEffects(
                        tenantId = tenantId,
                        action = "communication.contact.role.assigned",
                        resourceType = "tenant_contact_roles",
                        resourceId = roleAssignmentId,
                        payload = mapOf(
                            "contactId" to contactId,
                            "roleAssignmentId" to roleAssignmentId,
                            "roleCode" to roleCode,
                            "propertyId" to request.propertyId,
                            "primary" to request.primary,
                        ),
                        idempotencyKeyId = idempotencyKeyId,
                    )
                }
            }
        }
    }

    @Transactional
    override fun recordConsent(
        contactId: UUID,
        channelId: UUID,
        request: RecordCommunicationConsentRequest,
    ): CommunicationConsentReceipt {
        val tenantId = bindTenantContext()
        val actorId = currentTenantUserId()
        val purpose = request.purpose.normalizedCode("purpose")
        val policyVersion = request.policyVersion.normalizedRequired("policyVersion")
        val status = request.status.normalizedCode("status")
        require(status in ALLOWED_CONSENT_STATUSES) {
            "Consent status must be active, declined, or revoked"
        }
        require(request.expiresAt == null || request.expiresAt.isAfter(OffsetDateTime.now())) {
            "Consent expiry must be in the future"
        }

        return withIdempotency(
            operationType = "communication.contact.consent.record",
            requestPayload = mapOf(
                "contactId" to contactId,
                "channelId" to channelId,
                "purpose" to purpose,
                "policyVersion" to policyVersion,
                "status" to status,
                "expiresAt" to request.expiresAt,
            ),
            resourceType = "communication_consents",
            replayType = CommunicationConsentReceipt::class.java,
        ) { idempotencyKeyId ->
            requireContactChannel(tenantId, contactId, channelId)
            val consentId = UUID.randomUUID()
            jdbcTemplate.update(
                """
                INSERT INTO communication_consents (
                    id,
                    tenant_id,
                    contact_id,
                    contact_channel_id,
                    purpose,
                    status,
                    policy_version,
                    capture_source,
                    captured_by,
                    revoked_at,
                    revoked_by,
                    expires_at,
                    evidence_metadata
                )
                VALUES (
                    ?, ?, ?, ?, ?, ?, ?, 'api', ?,
                    CASE WHEN ? = 'revoked' THEN now() ELSE NULL END,
                    CASE WHEN ? = 'revoked' THEN ? ELSE NULL END,
                    ?,
                    '{"source":"authenticated_api"}'::jsonb
                )
                """.trimIndent(),
                consentId,
                tenantId,
                contactId,
                channelId,
                purpose,
                status,
                policyVersion,
                actorId,
                status,
                status,
                actorId,
                request.expiresAt,
            )

            recordCommunicationSideEffects(
                tenantId = tenantId,
                action = "communication.contact.consent.recorded",
                resourceType = "communication_consents",
                resourceId = consentId,
                payload = mapOf(
                    "consentId" to consentId,
                    "contactId" to contactId,
                    "channelId" to channelId,
                    "purpose" to purpose,
                    "status" to status,
                    "policyVersion" to policyVersion,
                ),
                idempotencyKeyId = idempotencyKeyId,
            )

            CommunicationConsentReceipt(
                consentId = consentId,
                contactId = contactId,
                channelId = channelId,
                purpose = purpose,
                status = status,
                replayed = false,
            )
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
            val deliveryRequestId = insertDeliveryRequest(
                tenantId = tenantId,
                propertyId = null,
                originalOutboxEventId = eventId,
                currentOutboxEventId = eventId,
                channel = channelInfo.channelType,
                recipient = channelInfo.address,
                subject = "Verify your contact channel",
                content = "Your verification token is: $token",
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
                deliveryRequestId = deliveryRequestId,
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

    @Transactional
    override fun listDeliveryRequests(): List<DeliveryRequestResponse> {
        val tenantId = bindTenantContext()
        return jdbcTemplate.query(
            """
            SELECT id,
                   property_id,
                   original_outbox_event_id,
                   current_outbox_event_id,
                   channel_type,
                   recipient_fingerprint,
                   subject,
                   status,
                   attempt_count,
                   max_attempts,
                   requested_at,
                   delivered_at,
                   failed_at,
                   last_error
            FROM communication_delivery_requests
            WHERE tenant_id = ? AND deleted_at IS NULL
            ORDER BY requested_at DESC, id DESC
            LIMIT 200
            """.trimIndent(),
            ::mapDeliveryRequest,
            tenantId,
        )
    }

    @Transactional
    override fun getDeliveryRequest(deliveryRequestId: UUID): DeliveryRequestResponse {
        val tenantId = bindTenantContext()
        return deliveryRequest(tenantId, deliveryRequestId)
    }

    @Transactional
    override fun listDeliveryAttempts(deliveryRequestId: UUID): List<DeliveryAttemptResponse> {
        val tenantId = bindTenantContext()
        ensureDeliveryRequestExists(tenantId, deliveryRequestId)
        return jdbcTemplate.query(
            """
            SELECT id,
                   delivery_request_id,
                   outbox_event_id,
                   attempt_number,
                   provider,
                   status,
                   provider_message_id,
                   error_message,
                   started_at,
                   completed_at
            FROM communication_delivery_attempts
            WHERE tenant_id = ? AND delivery_request_id = ?
            ORDER BY attempt_number DESC, started_at DESC
            """.trimIndent(),
            { rs, _ ->
                DeliveryAttemptResponse(
                    id = rs.getObject("id", UUID::class.java),
                    deliveryRequestId = rs.getObject("delivery_request_id", UUID::class.java),
                    outboxEventId = rs.getObject("outbox_event_id", UUID::class.java),
                    attemptNumber = rs.getInt("attempt_number"),
                    provider = rs.getString("provider"),
                    status = rs.getString("status"),
                    providerMessageId = rs.getString("provider_message_id"),
                    errorMessage = rs.getString("error_message"),
                    startedAt = rs.getObject("started_at", OffsetDateTime::class.java),
                    completedAt = rs.getObject("completed_at", OffsetDateTime::class.java),
                )
            },
            tenantId,
            deliveryRequestId,
        )
    }

    @Transactional
    override fun retryDelivery(deliveryRequestId: UUID): DeliveryRetryReceipt {
        val tenantId = bindTenantContext()
        ensureDeliveryRequestExists(tenantId, deliveryRequestId)

        return withIdempotency(
            operationType = "communication.delivery.retry",
            requestPayload = mapOf("deliveryRequestId" to deliveryRequestId),
            resourceType = "communication_delivery_requests",
            replayType = DeliveryRetryReceipt::class.java,
        ) { idempotencyKeyId ->
            val existing = deliveryRequest(tenantId, deliveryRequestId)
            require(existing.status in RETRYABLE_DELIVERY_STATUSES) {
                "Only failed or dead-lettered communication deliveries can be retried."
            }
            val source = deliveryRetrySource(tenantId, existing.currentOutboxEventId)
            val newEventId = outboxPort.enqueue(
                OutboxEventCommand(
                    aggregateType = source.aggregateType,
                    aggregateId = source.aggregateId,
                    eventType = source.eventType,
                    destination = OutboxDestination.NOTIFICATION,
                    tenantId = tenantId,
                    propertyId = source.propertyId,
                    payload = source.payload,
                    idempotencyKeyId = idempotencyKeyId,
                    priority = 3,
                    maxAttempts = source.maxAttempts,
                ),
            )

            jdbcTemplate.update(
                """
                UPDATE communication_delivery_requests
                SET current_outbox_event_id = ?,
                    status = 'queued',
                    delivered_at = NULL,
                    failed_at = NULL,
                    last_error = NULL,
                    updated_at = now()
                WHERE id = ? AND tenant_id = ?
                """.trimIndent(),
                newEventId,
                deliveryRequestId,
                tenantId,
            )

            auditPort.recordTenantEvent(
                TenantAuditEvent(
                    tenantId = tenantId,
                    action = "communication.delivery.retry_requested",
                    resource = AuditResource("communication_delivery_requests", deliveryRequestId),
                    after = mapOf(
                        "deliveryRequestId" to deliveryRequestId,
                        "outboxEventId" to newEventId,
                        "channel" to existing.channel,
                    ),
                ),
            )

            DeliveryRetryReceipt(
                deliveryRequestId = deliveryRequestId,
                eventId = newEventId,
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

    private fun currentTenantUserId(): UUID {
        val identity = requestContextHolder.current().identity
        require(identity is RequestIdentity.Tenant) {
            "Communication actions require an active tenant identity."
        }
        return identity.tenantUserId
    }

    private fun requireActiveContact(tenantId: UUID, contactId: UUID) {
        val exists = jdbcTemplate.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1
                FROM tenant_contacts
                WHERE tenant_id = ?
                  AND id = ?
                  AND status = 'active'
                  AND deleted_at IS NULL
            )
            """.trimIndent(),
            Boolean::class.java,
            tenantId,
            contactId,
        ) == true
        if (!exists) {
            throw NoSuchElementException("Active tenant contact not found or access denied.")
        }
    }

    private fun requireContactChannel(
        tenantId: UUID,
        contactId: UUID,
        channelId: UUID,
    ) {
        val exists = jdbcTemplate.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1
                FROM contact_channels
                WHERE tenant_id = ?
                  AND contact_id = ?
                  AND id = ?
                  AND is_active = true
                  AND deleted_at IS NULL
            )
            """.trimIndent(),
            Boolean::class.java,
            tenantId,
            contactId,
            channelId,
        ) == true
        if (!exists) {
            throw NoSuchElementException("Active contact channel not found or access denied.")
        }
    }

    private fun requireContactRoleScope(roleCode: String, propertyId: UUID?) {
        val scope = jdbcTemplate.query(
            """
            SELECT scope
            FROM contact_role_catalog
            WHERE role_code = ?
              AND is_active = true
            """.trimIndent(),
            { rs, _ -> rs.getString("scope") },
            roleCode,
        ).singleOrNull()
            ?: throw NoSuchElementException("Active contact role was not found.")

        if (propertyId == null) {
            require(scope in setOf("tenant", "both")) {
                "Contact role requires a property scope."
            }
        } else {
            require(scope in setOf("property", "both")) {
                "Contact role cannot be assigned at property scope."
            }
        }
    }

    private fun recordCommunicationSideEffects(
        tenantId: UUID,
        action: String,
        resourceType: String,
        resourceId: UUID,
        payload: Map<String, Any?>,
        idempotencyKeyId: UUID,
    ) {
        auditPort.recordTenantEvent(
            TenantAuditEvent(
                tenantId = tenantId,
                action = action,
                resource = AuditResource(resourceType, resourceId),
                after = payload,
            ),
        )
        outboxPort.enqueue(
            OutboxEventCommand(
                aggregateType = resourceType,
                aggregateId = resourceId,
                tenantId = tenantId,
                eventType = action,
                destination = OutboxDestination.PLATFORM,
                payload = payload,
                idempotencyKeyId = idempotencyKeyId,
                priority = 5,
            ),
        )
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

    private fun consentedContactChannel(
        tenantId: UUID,
        channelId: UUID,
        purpose: String,
    ): ContactChannelInfo {
        return jdbcTemplate.query(
            """
            SELECT cc.channel_type, cc.address
            FROM contact_channels cc
            JOIN tenant_contacts tc
              ON tc.tenant_id = cc.tenant_id
             AND tc.id = cc.contact_id
             AND tc.status = 'active'
             AND tc.deleted_at IS NULL
            WHERE cc.id = ?
              AND cc.tenant_id = ?
              AND cc.is_active = true
              AND cc.verification_status = 'verified'
              AND cc.deleted_at IS NULL
              AND contact_channel_has_active_consent(
                    cc.tenant_id,
                    cc.contact_id,
                    cc.id,
                    ?
                  )
            """.trimIndent(),
            { rs, _ ->
                ContactChannelInfo(
                    channelType = rs.getString("channel_type"),
                    address = rs.getString("address"),
                )
            },
            channelId,
            tenantId,
            purpose,
        ).firstOrNull()
            ?: throw IllegalStateException(
                "The contact channel is unavailable, unverified, or lacks active consent for this purpose.",
            )
    }

    private fun resolveNotificationMessage(
        tenantId: UUID,
        channel: String,
        request: EnqueueNotificationRequest,
    ): NotificationMessage {
        require(request.variables.size <= MAX_TEMPLATE_VARIABLES) {
            "Too many template variables."
        }
        request.variables.forEach { (key, value) ->
            require(TEMPLATE_VARIABLE_NAME.matches(key) && value.length <= MAX_TEMPLATE_VARIABLE_LENGTH) {
                "Invalid template variable."
            }
        }

        if (request.templateId == null) {
            require(request.variables.isEmpty()) {
                "Template variables require a templateId."
            }
            return NotificationMessage(
                subject = request.subject?.trim()?.takeIf { it.isNotEmpty() },
                content = request.content?.normalizedRequired("content")
                    ?: throw IllegalArgumentException("content is required"),
            ).validated()
        }

        require(request.subject.isNullOrBlank() && request.content.isNullOrBlank()) {
            "Template messages cannot override subject or content."
        }
        val template = jdbcTemplate.query(
            """
            SELECT subject, content
            FROM communication_templates
            WHERE id = ?
              AND tenant_id = ?
              AND channel_type = ?
              AND deleted_at IS NULL
            """.trimIndent(),
            { rs, _ ->
                NotificationMessage(
                    subject = rs.getString("subject"),
                    content = rs.getString("content"),
                )
            },
            request.templateId,
            tenantId,
            channel,
        ).firstOrNull()
            ?: throw NoSuchElementException("Communication template not found for the selected channel.")

        val referencedVariables = buildSet {
            template.subject?.let { subject ->
                TEMPLATE_VARIABLE.findAll(subject).forEach { add(it.groupValues[1]) }
            }
            TEMPLATE_VARIABLE.findAll(template.content).forEach { add(it.groupValues[1]) }
        }
        require(referencedVariables == request.variables.keys) {
            "Template variables do not match the template contract."
        }
        return NotificationMessage(
            subject = template.subject?.let { renderTemplate(it, request.variables) },
            content = renderTemplate(template.content, request.variables),
        ).validated()
    }

    private fun renderTemplate(template: String, variables: Map<String, String>): String {
        return TEMPLATE_VARIABLE.replace(template) { match ->
            requireNotNull(variables[match.groupValues[1]]) {
                "Missing template variable."
            }
        }
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

    private fun insertDeliveryRequest(
        tenantId: UUID,
        propertyId: UUID?,
        originalOutboxEventId: UUID,
        currentOutboxEventId: UUID,
        channel: String,
        recipient: String,
        subject: String?,
        content: String,
    ): UUID {
        val deliveryRequestId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO communication_delivery_requests (
                id,
                tenant_id,
                property_id,
                original_outbox_event_id,
                current_outbox_event_id,
                channel_type,
                recipient,
                recipient_fingerprint,
                subject,
                content_fingerprint
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            deliveryRequestId,
            tenantId,
            propertyId,
            originalOutboxEventId,
            currentOutboxEventId,
            channel,
            recipient,
            sha256Hex(recipient),
            subject?.takeIf { it.isNotBlank() },
            sha256Hex(content),
        )
        return deliveryRequestId
    }

    private fun deliveryRequest(
        tenantId: UUID,
        deliveryRequestId: UUID,
    ): DeliveryRequestResponse {
        return jdbcTemplate.query(
            """
            SELECT id,
                   property_id,
                   original_outbox_event_id,
                   current_outbox_event_id,
                   channel_type,
                   recipient_fingerprint,
                   subject,
                   status,
                   attempt_count,
                   max_attempts,
                   requested_at,
                   delivered_at,
                   failed_at,
                   last_error
            FROM communication_delivery_requests
            WHERE id = ? AND tenant_id = ? AND deleted_at IS NULL
            """.trimIndent(),
            ::mapDeliveryRequest,
            deliveryRequestId,
            tenantId,
        ).firstOrNull() ?: throw NoSuchElementException("Communication delivery request not found or access denied.")
    }

    private fun ensureDeliveryRequestExists(
        tenantId: UUID,
        deliveryRequestId: UUID,
    ) {
        val exists = requireNotNull(
            jdbcTemplate.queryForObject(
                """
                SELECT EXISTS(
                    SELECT 1
                    FROM communication_delivery_requests
                    WHERE id = ? AND tenant_id = ? AND deleted_at IS NULL
                )
                """.trimIndent(),
                Boolean::class.java,
                deliveryRequestId,
                tenantId,
            ),
        )
        if (!exists) {
            throw NoSuchElementException("Communication delivery request not found or access denied.")
        }
    }

    private fun mapDeliveryRequest(rs: ResultSet, rowNumber: Int): DeliveryRequestResponse {
        return DeliveryRequestResponse(
            id = rs.getObject("id", UUID::class.java),
            propertyId = rs.getObject("property_id", UUID::class.java),
            originalOutboxEventId = rs.getObject("original_outbox_event_id", UUID::class.java),
            currentOutboxEventId = rs.getObject("current_outbox_event_id", UUID::class.java),
            channel = rs.getString("channel_type"),
            recipientFingerprint = rs.getString("recipient_fingerprint"),
            subjectPresent = !rs.getString("subject").isNullOrBlank(),
            status = rs.getString("status"),
            attemptCount = rs.getInt("attempt_count"),
            maxAttempts = rs.getInt("max_attempts"),
            requestedAt = rs.getObject("requested_at", OffsetDateTime::class.java),
            deliveredAt = rs.getObject("delivered_at", OffsetDateTime::class.java),
            failedAt = rs.getObject("failed_at", OffsetDateTime::class.java),
            lastError = rs.getString("last_error"),
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun deliveryRetrySource(
        tenantId: UUID,
        outboxEventId: UUID,
    ): DeliveryRetrySource {
        return jdbcTemplate.query(
            """
            SELECT aggregate_type,
                   aggregate_id,
                   event_type,
                   property_id,
                   payload::text AS payload,
                   max_attempts
            FROM outbox_events
            WHERE id = ?
              AND tenant_id = ?
              AND destination = 'notification'
            """.trimIndent(),
            { rs, _ ->
                DeliveryRetrySource(
                    aggregateType = rs.getString("aggregate_type"),
                    aggregateId = rs.getObject("aggregate_id", UUID::class.java),
                    eventType = rs.getString("event_type"),
                    propertyId = rs.getObject("property_id", UUID::class.java),
                    payload = objectMapper.readValue(
                        rs.getString("payload"),
                        Map::class.java,
                    ) as Map<String, Any?>,
                    maxAttempts = rs.getInt("max_attempts"),
                )
            },
            outboxEventId,
            tenantId,
        ).firstOrNull() ?: throw NoSuchElementException("Original notification outbox event not found.")
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
            is DeliveryRetryReceipt -> copy(replayed = true)
            is ContactRoleMutationReceipt -> copy(replayed = true)
            is CommunicationConsentReceipt -> copy(replayed = true)
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
            is DeliveryRetryReceipt -> deliveryRequestId
            is ContactRoleMutationReceipt -> roleAssignmentId
            is CommunicationConsentReceipt -> consentId
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

    private fun String.normalizedCode(fieldName: String): String {
        return normalizedRequired(fieldName).lowercase()
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

    private data class NotificationMessage(
        val subject: String?,
        val content: String,
    ) {
        fun validated(): NotificationMessage {
            require(subject == null || subject.length <= MAX_NOTIFICATION_SUBJECT_LENGTH) {
                "Notification subject is too long."
            }
            require(content.isNotBlank() && content.length <= MAX_NOTIFICATION_CONTENT_LENGTH) {
                "Notification content is empty or too long."
            }
            return this
        }
    }

    private data class PendingVerification(
        val tokenHash: String?,
        val expiresAt: OffsetDateTime?,
    )

    private data class DeliveryRetrySource(
        val aggregateType: String,
        val aggregateId: UUID?,
        val eventType: String,
        val propertyId: UUID?,
        val payload: Map<String, Any?>,
        val maxAttempts: Int,
    )

    private companion object {
        private val ALLOWED_CHANNELS = setOf("email", "sms", "whatsapp", "voice_phone")
        private val ALLOWED_CONSENT_PURPOSES = setOf(
            "operational_reports",
            "critical_operational_alerts",
            "billing_communications",
            "security_notifications",
            "service_notifications",
            "marketing",
        )
        private val TEMPLATE_VARIABLE = Regex("""\{\{([A-Za-z][A-Za-z0-9_.]*)}}""")
        private val TEMPLATE_VARIABLE_NAME = Regex("""[A-Za-z][A-Za-z0-9_.]*""")
        private val ALLOWED_CONSENT_STATUSES = setOf("active", "declined", "revoked")
        private val RETRYABLE_DELIVERY_STATUSES = setOf("failed", "dead_letter")
        private const val MAX_TEMPLATE_VARIABLES = 50
        private const val MAX_TEMPLATE_VARIABLE_LENGTH = 4000
        private const val MAX_NOTIFICATION_SUBJECT_LENGTH = 500
        private const val MAX_NOTIFICATION_CONTENT_LENGTH = 100_000
        private val secureRandom = SecureRandom()
    }
}
