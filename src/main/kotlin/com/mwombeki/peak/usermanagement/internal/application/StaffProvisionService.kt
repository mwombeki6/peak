package com.mwombeki.peak.usermanagement.internal.application

import com.mwombeki.peak.audit.api.AuditPort
import com.mwombeki.peak.audit.api.AuditResource
import com.mwombeki.peak.audit.api.TenantAuditEvent
import com.mwombeki.peak.reliability.api.IdempotencyCommand
import com.mwombeki.peak.reliability.api.IdempotencyPort
import com.mwombeki.peak.reliability.api.IdempotencyReservation
import com.mwombeki.peak.reliability.api.OutboxDestination
import com.mwombeki.peak.reliability.api.OutboxEventCommand
import com.mwombeki.peak.reliability.api.OutboxPort
import com.mwombeki.peak.shared.context.DatabaseSessionContext
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import com.mwombeki.peak.shared.secrets.SecretEnvelopeService
import com.mwombeki.peak.usermanagement.api.TenantPermissionAccessPort
import com.mwombeki.peak.usermanagement.api.TenantPermissionAccessRequest
import java.time.Instant
import java.util.UUID
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper

/**
 * Hires frontline staff without inventing an email address.
 *
 * A Keycloak invitation still requires email because acceptance is an OIDC identity with a
 * verified address. Waiters, cashiers and housekeepers never take that path. They exist as a
 * `users` row with a staff number, an operational property role, and a one-time activation
 * secret. When a phone is present the secret leaves through SMS, not email. When it is not,
 * the manager hands the secret over in person — still never a PIN.
 */
@Component
class StaffProvisionService(
    private val jdbcTemplate: JdbcTemplate,
    private val requestContextHolder: RequestContextHolder,
    private val databaseSessionContext: DatabaseSessionContext,
    private val tenantPermissionAccessPort: TenantPermissionAccessPort,
    private val idempotencyPort: IdempotencyPort,
    private val auditPort: AuditPort,
    private val outboxPort: OutboxPort,
    private val transactionTemplate: TransactionTemplate,
    private val objectMapper: ObjectMapper,
    private val secretEnvelopeService: SecretEnvelopeService,
    private val invitationSecurityProperties: TenantInvitationSecurityProperties,
    private val credentials: StaffCredentialService,
) {
    data class StaffMember(
        val userId: UUID,
        val fullName: String,
        val staffNumber: String?,
        val phoneNumber: String?,
        val status: String,
        val isActive: Boolean,
        val propertyId: UUID?,
        val propertyRoleId: UUID?,
    )

    data class ProvisionCommand(
        val tenantId: UUID,
        val fullName: String,
        val phoneNumber: String? = null,
        val propertyId: UUID,
        val propertyRoleId: UUID,
    )

    data class ProvisionReceipt(
        val userId: UUID,
        val tenantId: UUID,
        val staffNumber: String,
        val phoneNumber: String?,
        val propertyId: UUID,
        val propertyRoleId: UUID,
        val activationSecret: String?,
        val activationExpiresAt: Instant,
        val replayed: Boolean,
    )

    data class ActivateCommand(
        val tenantId: UUID,
        val staffNumber: String,
        val secret: String,
        val pin: String,
    )

    fun provision(command: ProvisionCommand): ProvisionReceipt {
        return requireNotNull(
            transactionTemplate.execute {
                provisionInsideTransaction(command.normalized())
            },
        )
    }

    fun activate(command: ActivateCommand) {
        transactionTemplate.executeWithoutResult {
            databaseSessionContext.bind(RequestIdentity.Public(tenantId = command.tenantId))
            credentials.activate(
                tenantId = command.tenantId,
                staffNumber = command.staffNumber,
                secret = command.secret,
                pin = command.pin,
            )
        }
    }

    /**
     * The tenant's staff, newest first, optionally narrowed to one property.
     *
     * The onboarding wizard's manager step needs a real user id and has no other way to find
     * one, which is why this exists. Disabled staff are included and carry their status, since
     * a manager checking why someone cannot log in needs to see them.
     *
     * Left joined to the property role: a user created but not yet assigned to a property is
     * exactly the half-finished state a directory should surface rather than hide.
     */
    fun listStaff(tenantId: UUID, propertyId: UUID?): List<StaffMember> =
        transactionTemplate.execute {
            val identity = requestContextHolder.current().identity
            require(identity is RequestIdentity.Tenant) {
                "Tenant user identity is required to read the staff directory"
            }
            databaseSessionContext.bind(RequestIdentity.Tenant(tenantId, identity.tenantUserId))
            jdbcTemplate.query(
                """
                SELECT u.id, u.full_name, u.phone_number, u.status, u.is_active,
                       upr.property_id, upr.role_id, psn.staff_number
                FROM users u
                LEFT JOIN user_property_roles upr
                       ON upr.user_id = u.id
                      AND upr.tenant_id = u.tenant_id
                LEFT JOIN property_staff_numbers psn
                       ON psn.tenant_id = u.tenant_id
                      AND psn.user_id = u.id
                      AND psn.property_id = upr.property_id
                      AND psn.status = 'ACTIVE'
                WHERE u.tenant_id = ?
                  AND (CAST(? AS uuid) IS NULL OR upr.property_id = CAST(? AS uuid))
                ORDER BY u.created_at DESC, u.id
                """.trimIndent(),
                { rs, _ ->
                    StaffMember(
                        userId = rs.getObject("id", UUID::class.java),
                        fullName = rs.getString("full_name"),
                        staffNumber = rs.getString("staff_number"),
                        phoneNumber = rs.getString("phone_number"),
                        status = rs.getString("status"),
                        isActive = rs.getBoolean("is_active"),
                        propertyId = rs.getObject("property_id", UUID::class.java),
                        propertyRoleId = rs.getObject("role_id", UUID::class.java),
                    )
                },
                tenantId,
                propertyId,
                propertyId,
            )
        }

    private fun provisionInsideTransaction(command: NormalizedProvision): ProvisionReceipt {
        val actorUserId = tenantPermissionAccessPort.requireAuthorized(
            TenantPermissionAccessRequest(command.tenantId, TENANT_USER_MANAGE_PERMISSION),
        )
        val identity = requestContextHolder.current().identity
        require(identity is RequestIdentity.Tenant) {
            "Tenant user identity is required to provision staff"
        }

        val reservation = idempotencyPort.reserve(
            IdempotencyCommand(
                operationType = "tenant.staff.provision",
                requestPayload = mapOf(
                    "tenantId" to command.tenantId,
                    "fullName" to command.fullName,
                    "phoneNumber" to command.phoneNumber,
                    "propertyId" to command.propertyId,
                    "propertyRoleId" to command.propertyRoleId,
                ),
                resourceType = "users",
            ),
        )

        return when (reservation) {
            is IdempotencyReservation.Started ->
                createStaff(command, actorUserId, reservation.recordId)
            is IdempotencyReservation.Replay -> replay(reservation)
            is IdempotencyReservation.InProgress -> throw StaffProvisionInProgressException(
                "Staff provision is already being processed for this idempotency key",
            )
            is IdempotencyReservation.Conflict -> throw StaffProvisionConflictException(
                "Idempotency key was already used for a different staff provision request",
            )
        }
    }

    private fun createStaff(
        command: NormalizedProvision,
        actorUserId: UUID,
        idempotencyKeyId: UUID,
    ): ProvisionReceipt {
        requirePropertyBelongsToTenant(command.tenantId, command.propertyId)
        requireOperationalPropertyRole(command.tenantId, command.propertyRoleId)
        if (command.phoneNumber != null) {
            requireNoExistingUserWithPhone(command.tenantId, command.phoneNumber)
        }
        jdbcTemplate.queryForList(
            "SELECT assert_tenant_capacity(?, 'limit.users')",
            command.tenantId,
        )

        val userId = UUID.randomUUID()

        try {
            jdbcTemplate.update(
                """
                INSERT INTO users (
                    id, tenant_id, full_name, phone_number, status, is_active
                ) VALUES (?, ?, ?, ?, 'active', true)
                """.trimIndent(),
                userId,
                command.tenantId,
                command.fullName,
                command.phoneNumber,
            )
        } catch (ex: DuplicateKeyException) {
            throw StaffProvisionConflictException(
                "A staff member already exists for this phone number",
            )
        }

        jdbcTemplate.update(
            """
            INSERT INTO user_property_roles (user_id, property_id, role_id, tenant_id)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
            userId,
            command.propertyId,
            command.propertyRoleId,
            command.tenantId,
        )

        // Property-membership scoped, not a tenant-wide human attribute: this person gets a
        // different number at every property they're assigned to, so moving/adding a property
        // assignment always allocates fresh rather than reusing a number tied to a different job.
        val staffNumber = requireNotNull(
            jdbcTemplate.queryForObject(
                "SELECT allocate_property_staff_number(?, ?, ?)",
                String::class.java,
                command.tenantId,
                command.propertyId,
                userId,
            ),
        )

        val activation = credentials.issueActivation(command.tenantId, userId, actorUserId)
        val snapshot = ProvisionSnapshot(
            userId = userId,
            tenantId = command.tenantId,
            staffNumber = staffNumber,
            phoneNumber = command.phoneNumber,
            propertyId = command.propertyId,
            propertyRoleId = command.propertyRoleId,
            activationExpiresAt = activation.expiresAt,
        )

        auditPort.recordTenantEvent(
            TenantAuditEvent(
                tenantId = command.tenantId,
                action = "tenant.staff.provision",
                resource = AuditResource("users", userId),
                after = mapOf(
                    "staffNumber" to staffNumber,
                    "phoneNumber" to command.phoneNumber,
                    "propertyId" to command.propertyId,
                    "propertyRoleId" to command.propertyRoleId,
                ),
            ),
        )

        if (command.phoneNumber != null) {
            outboxPort.enqueue(
                OutboxEventCommand(
                    aggregateType = "users",
                    aggregateId = userId,
                    tenantId = command.tenantId,
                    propertyId = command.propertyId,
                    eventType = ACTIVATION_EVENT,
                    destination = OutboxDestination.NOTIFICATION,
                    payload = mapOf(
                        "userId" to userId,
                        "tenantId" to command.tenantId,
                        "staffNumber" to staffNumber,
                        "fullName" to command.fullName,
                        "phoneNumber" to command.phoneNumber,
                        "expiresAt" to activation.expiresAt.toString(),
                        "secretEnvelope" to secretEnvelopeService.encrypt(
                            plaintext = activation.plaintext,
                            associatedData = userId.toString(),
                        ),
                    ),
                    idempotencyKeyId = idempotencyKeyId,
                    priority = 4,
                ),
            )
        }

        idempotencyPort.markSucceeded(
            recordId = idempotencyKeyId,
            responseCode = 201,
            responseBody = snapshot,
            resourceId = userId,
        )

        return snapshot.toReceipt(
            activationSecret = activation.plaintext.takeIf {
                invitationSecurityProperties.exposeTokenInResponse || command.phoneNumber == null
            },
            replayed = false,
        )
    }

    private fun replay(reservation: IdempotencyReservation.Replay): ProvisionReceipt {
        if (reservation.responseBody.isNullOrBlank()) {
            throw StaffProvisionConflictException(
                "Staff provision replay does not contain a stored response body",
            )
        }
        val snapshot = objectMapper.readValue(
            reservation.responseBody,
            ProvisionSnapshot::class.java,
        )
        return snapshot.toReceipt(activationSecret = null, replayed = true)
    }

    private fun requirePropertyBelongsToTenant(tenantId: UUID, propertyId: UUID) {
        val exists = jdbcTemplate.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1 FROM properties
                WHERE tenant_id = ? AND id = ? AND deleted_at IS NULL
            )
            """.trimIndent(),
            Boolean::class.java,
            tenantId,
            propertyId,
        ) == true
        require(exists) { "Property was not found for tenant" }
    }

    private fun requireOperationalPropertyRole(tenantId: UUID, propertyRoleId: UUID) {
        val role = jdbcTemplate.query(
            """
            SELECT is_system, is_active
            FROM roles
            WHERE tenant_id = ? AND id = ?
            """.trimIndent(),
            { rs, _ -> rs.getBoolean("is_system") to rs.getBoolean("is_active") },
            tenantId,
            propertyRoleId,
        ).singleOrNull()
        require(role != null) { "Active property role was not found" }
        require(role.second) { "Active property role was not found" }
        require(!role.first) {
            "System property roles cannot be assigned when provisioning staff"
        }

        val classes = jdbcTemplate.query(
            """
            SELECT p.code, coalesce(pc.minimum_session_class, 'strong') AS session_class
            FROM role_permissions rp
            JOIN permissions p
              ON p.id = rp.permission_id
             AND p.tenant_id = ?
            LEFT JOIN permission_catalog pc
              ON pc.code = p.code
            WHERE rp.role_id = ?
            """.trimIndent(),
            { rs, _ -> rs.getString("code") to rs.getString("session_class") },
            tenantId,
            propertyRoleId,
        )
        require(classes.isNotEmpty()) {
            "Operational staff must be assigned a property role with operational permissions"
        }
        val strong = classes.filter { it.second != "operational" }.map { it.first }
        require(strong.isEmpty()) {
            "Operational staff cannot be assigned permissions that require a strong session: " +
                strong.sorted().joinToString(", ")
        }
    }

    private fun requireNoExistingUserWithPhone(tenantId: UUID, phoneNumber: String) {
        val exists = jdbcTemplate.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1 FROM users
                WHERE tenant_id = ?
                  AND phone_number = ?
                  AND deleted_at IS NULL
            )
            """.trimIndent(),
            Boolean::class.java,
            tenantId,
            phoneNumber,
        ) == true
        require(!exists) { "A staff member already exists for this phone number" }
    }

    private fun ProvisionCommand.normalized(): NormalizedProvision {
        val fullName = fullName.trim()
        require(fullName.isNotEmpty()) { "Staff full name is required" }
        val phone = phoneNumber?.trim()?.takeIf { it.isNotEmpty() }
        if (phone != null) {
            require(E164.matches(phone)) {
                "Staff phone must be E.164, for example +255712345678"
            }
        }
        return NormalizedProvision(
            tenantId = tenantId,
            fullName = fullName,
            phoneNumber = phone,
            propertyId = propertyId,
            propertyRoleId = propertyRoleId,
        )
    }

    private fun ProvisionSnapshot.toReceipt(
        activationSecret: String?,
        replayed: Boolean,
    ) = ProvisionReceipt(
        userId = userId,
        tenantId = tenantId,
        staffNumber = staffNumber,
        phoneNumber = phoneNumber,
        propertyId = propertyId,
        propertyRoleId = propertyRoleId,
        activationSecret = activationSecret,
        activationExpiresAt = activationExpiresAt,
        replayed = replayed,
    )

    private data class NormalizedProvision(
        val tenantId: UUID,
        val fullName: String,
        val phoneNumber: String?,
        val propertyId: UUID,
        val propertyRoleId: UUID,
    )

    private data class ProvisionSnapshot(
        val userId: UUID,
        val tenantId: UUID,
        val staffNumber: String,
        val phoneNumber: String?,
        val propertyId: UUID,
        val propertyRoleId: UUID,
        val activationExpiresAt: Instant,
    )

    private companion object {
        const val TENANT_USER_MANAGE_PERMISSION = "tenant.users.manage"
        const val ACTIVATION_EVENT = "staff.credential.activation.issued"
        val E164 = Regex("^\\+[1-9][0-9]{7,14}$")
    }
}

class StaffProvisionConflictException(message: String) : RuntimeException(message)

class StaffProvisionInProgressException(message: String) : RuntimeException(message)
