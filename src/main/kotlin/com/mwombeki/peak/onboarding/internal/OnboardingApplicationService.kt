package com.mwombeki.peak.onboarding.internal

import com.mwombeki.peak.onboarding.api.OnboardingProvisioningException
import com.mwombeki.peak.onboarding.api.OnboardingSessionReceipt
import com.mwombeki.peak.onboarding.api.OnboardingVerificationFailedException
import com.mwombeki.peak.onboarding.api.RequestAccessCommand
import com.mwombeki.peak.onboarding.api.RequestAccessReceipt
import com.mwombeki.peak.onboarding.api.UpdateOnboardingProfileCommand
import com.mwombeki.peak.onboarding.api.VerifyOnboardingPhoneCommand
import com.mwombeki.peak.shared.context.DatabaseSessionContext
import com.mwombeki.peak.shared.context.OnboardingSessionAuthentication
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import com.mwombeki.peak.tenantmanagement.api.TenantOnboardingPort
import com.mwombeki.peak.tenantmanagement.api.TenantRegisterRequest
import com.mwombeki.peak.tenantmanagement.api.TenantResponse
import com.mwombeki.peak.verification.api.ConfirmVerificationCommand
import com.mwombeki.peak.verification.api.RequestVerificationCommand
import com.mwombeki.peak.verification.api.VerificationPort
import com.mwombeki.peak.verification.api.VerificationPurpose
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Duration
import java.util.Base64
import java.util.HexFormat
import java.util.UUID
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

@ConfigurationProperties(prefix = "peak.security.onboarding-sessions")
data class OnboardingSessionProperties(
    val sessionValidity: Duration = Duration.ofDays(14),
)

/**
 * The public front door: a prospect requests access, verifies their phone, and gets a narrow
 * session bound to exactly one application — never tenant authority. Everything before the
 * phone is verified runs through SECURITY DEFINER functions (V146), because there is no
 * session yet for ordinary RLS to scope against.
 *
 * Deep KYB (business details, documents, review) is deliberately not here — it reuses
 * `TenantTrustControlService`, the same engine a tenant uses post-provisioning, scoped to this
 * application instead of a tenant. This service only owns the part that's genuinely new: the
 * pre-tenant identity and how someone gets one.
 */
@Service
class OnboardingApplicationService(
    private val jdbcTemplate: JdbcTemplate,
    private val transactionTemplate: TransactionTemplate,
    private val verificationPort: VerificationPort,
    private val properties: OnboardingSessionProperties,
    private val requestContextHolder: RequestContextHolder,
    private val databaseSessionContext: DatabaseSessionContext,
    private val tenantOnboardingPort: TenantOnboardingPort,
) {
    private val random = SecureRandom()

    fun requestAccess(command: RequestAccessCommand): RequestAccessReceipt {
        val applicationId = requireNotNull(
            transactionTemplate.execute {
                jdbcTemplate.queryForObject(
                    "SELECT create_onboarding_application(?, ?, ?, ?)",
                    UUID::class.java,
                    command.representativeFullName.trim(),
                    command.representativePhone.trim(),
                    command.businessName?.trim()?.takeIf { it.isNotEmpty() },
                    command.countryCode.trim().uppercase(),
                )
            },
        )
        verificationPort.request(
            RequestVerificationCommand(
                purpose = VerificationPurpose.PHONE_VERIFICATION,
                destination = command.representativePhone.trim(),
                subjectRef = applicationId.toString(),
            ),
        )
        return RequestAccessReceipt(applicationId)
    }

    fun verifyPhone(command: VerifyOnboardingPhoneCommand): OnboardingSessionReceipt {
        val phone = requireNotNull(
            jdbcTemplate.queryForObject(
                "SELECT representative_phone FROM onboarding_applications WHERE id = ?",
                String::class.java,
                command.applicationId,
            ),
        ) { "Onboarding application was not found" }

        val outcome = verificationPort.confirm(
            ConfirmVerificationCommand(
                purpose = VerificationPurpose.PHONE_VERIFICATION,
                destination = phone,
                code = command.code,
            ),
        )
        if (!outcome.verified) {
            throw OnboardingVerificationFailedException("The verification code was incorrect or has expired")
        }

        return requireNotNull(
            transactionTemplate.execute {
                jdbcTemplate.queryForList(
                    "SELECT mark_onboarding_phone_verified(?)",
                    command.applicationId,
                )
                val token = OnboardingSessionAuthentication.TOKEN_PREFIX + randomToken(32)
                val row = jdbcTemplate.query(
                    "SELECT * FROM issue_onboarding_session(?, ?, ?)",
                    { rs, _ -> rs.getTimestamp("expires_at").toInstant() },
                    command.applicationId,
                    sha256Hex(token),
                    properties.sessionValidity.toMillis() / 1000.0,
                ).first()
                OnboardingSessionReceipt(token = token, expiresAt = row)
            },
        )
    }

    /**
     * The legal name and business email that request-access never asked for, but tenant
     * provisioning needs. An applicant supplies these any time before provisioning; nothing
     * about the timing is enforced here, only that provisionTenant refuses to run without them.
     */
    fun updateProfile(command: UpdateOnboardingProfileCommand) {
        require(EMAIL_PATTERN.matches(command.businessEmail.trim())) { "Invalid business email" }
        transactionTemplate.execute {
            val identity = requestContextHolder.current().identity
            require(
                identity is RequestIdentity.OnboardingApplicant &&
                    identity.applicationId == command.applicationId,
            ) { "Onboarding session does not match the target application" }
            // Same RLS shape as TenantTrustControlService's Application-subject branch: without
            // this, app.current_onboarding_application_id is never set and the write below is
            // silently rejected by RLS under the real constrained role.
            databaseSessionContext.bind(identity)
            jdbcTemplate.update(
                """
                UPDATE onboarding_applications
                SET legal_name = ?, business_email = ?, updated_at = now()
                WHERE id = ?
                """.trimIndent(),
                command.legalName.trim(),
                command.businessEmail.trim().lowercase(),
                command.applicationId,
            )
        }
    }

    /**
     * The explicit step after KYB approval — never an implicit side effect of reviewing the
     * case (TenantTrustControlService.reviewVerificationCase's APPROVE branch says why). Reuses
     * TenantOnboardingService.registerNewTenant wholesale rather than duplicating tenant
     * creation, so it inherits that method's idempotency, its tenant_number minting, and its
     * platform.tenants.manage authorization — the caller here must already be a platform
     * operator holding that permission.
     */
    fun provisionTenant(applicationId: UUID): TenantResponse = requireNotNull(
        transactionTemplate.execute {
            val application = jdbcTemplate.queryForList(
                """
                SELECT status, business_name, legal_name, business_email,
                       representative_phone, country_code, tenant_id
                FROM onboarding_applications WHERE id = ?
                """.trimIndent(),
                applicationId,
            ).singleOrNull()
                ?: throw OnboardingProvisioningException("Onboarding application was not found")

            if (application["status"] == "TENANT_PROVISIONED") {
                val tenantId = application["tenant_id"] as? UUID
                    ?: throw OnboardingProvisioningException(
                        "Application is marked provisioned but has no tenant",
                    )
                return@execute requireNotNull(tenantOnboardingPort.getTenantById(tenantId)) {
                    "Provisioned tenant $tenantId no longer exists"
                }
            }

            val caseStatus = jdbcTemplate.query(
                """
                SELECT status FROM tenant_verification_cases
                WHERE onboarding_application_id = ?
                ORDER BY created_at DESC LIMIT 1
                """.trimIndent(),
                { rs, _ -> rs.getString("status") },
                applicationId,
            ).firstOrNull()
            if (caseStatus != "approved") {
                throw OnboardingProvisioningException(
                    "Application's verification case must be approved before provisioning",
                )
            }

            val legalName = (application["legal_name"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw OnboardingProvisioningException("Legal name is required before provisioning")
            val businessEmail = (application["business_email"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
                ?: throw OnboardingProvisioningException("Business email is required before provisioning")
            val businessName = (application["business_name"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
                ?: legalName
            val countryCode = (application["country_code"] as? String)?.trim()?.uppercase() ?: "TZ"
            val representativePhone = requireNotNull(application["representative_phone"] as? String)

            val planId = requireNotNull(
                jdbcTemplate.queryForObject(
                    "SELECT id FROM plans WHERE code = 'starter'",
                    UUID::class.java,
                ),
            ) { "The default 'starter' plan is not seeded" }

            val tenant = tenantOnboardingPort.registerNewTenant(
                TenantRegisterRequest(
                    name = businessName,
                    slug = generateSlug(businessName),
                    planId = planId,
                    legalName = legalName,
                    businessEmail = businessEmail,
                    businessPhone = representativePhone,
                    countryCode = countryCode,
                ),
            )

            jdbcTemplate.update(
                "UPDATE onboarding_applications SET status = 'TENANT_PROVISIONED', tenant_id = ? WHERE id = ?",
                tenant.id,
                applicationId,
            )
            tenant
        },
    )

    private fun generateSlug(businessName: String): String {
        val base = businessName.trim().lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .take(90)
            .ifBlank { "tenant" }
        val suffix = (1..6).map { SLUG_SUFFIX_ALPHABET[random.nextInt(SLUG_SUFFIX_ALPHABET.length)] }.joinToString("")
        return "$base-$suffix"
    }

    private fun randomToken(bytes: Int): String {
        val buffer = ByteArray(bytes)
        random.nextBytes(buffer)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer)
    }

    private companion object {
        val EMAIL_PATTERN = Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")
        const val SLUG_SUFFIX_ALPHABET = "0123456789abcdefghijklmnopqrstuvwxyz"

        fun sha256Hex(value: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            return HexFormat.of().formatHex(digest)
        }
    }
}
