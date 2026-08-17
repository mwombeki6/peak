package com.mwombeki.peak.onboarding.internal

import com.mwombeki.peak.onboarding.api.OnboardingSessionReceipt
import com.mwombeki.peak.onboarding.api.OnboardingVerificationFailedException
import com.mwombeki.peak.onboarding.api.RequestAccessCommand
import com.mwombeki.peak.onboarding.api.RequestAccessReceipt
import com.mwombeki.peak.onboarding.api.VerifyOnboardingPhoneCommand
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
                val token = TOKEN_PREFIX + randomToken(32)
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

    private fun randomToken(bytes: Int): String {
        val buffer = ByteArray(bytes)
        random.nextBytes(buffer)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer)
    }

    private companion object {
        const val TOKEN_PREFIX = "onb_"

        fun sha256Hex(value: String): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
            return HexFormat.of().formatHex(digest)
        }
    }
}
