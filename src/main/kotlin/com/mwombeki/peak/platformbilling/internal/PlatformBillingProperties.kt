package com.mwombeki.peak.platformbilling.internal

import java.math.BigDecimal
import java.time.Duration
import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * Peak's own merchant configuration, which is not a tenant's provider account.
 *
 * Credentials are held as secret references and resolved through
 * `SecretReferenceResolver`, never inline. They deliberately do not live in
 * `payment_provider_accounts`: that table is tenant-scoped under row-level security, and
 * `production_provider_readiness_counts` would count Peak's own account among a tenant's
 * and refuse to start the runtime.
 */
@ConfigurationProperties(prefix = "peak.platformbilling")
data class PlatformBillingProperties(
    val enabled: Boolean = true,
    /** Provider used for new collections. */
    val primaryProvider: String = "snippe",
    /** Tried when the primary refuses to initiate; blank disables failover. */
    val fallbackProvider: String = "",
    val appName: String = "",
    val clientIdSecretRef: String = "",
    val apiKeySecretRef: String = "",
    val checksumKeySecretRef: String = "",
    val endpointUrl: String = "",
    /**
     * Largest amount a single collection may carry.
     *
     * Mobile money caps a transaction — AzamPay documents 5,000,000 TZS — and a purchase
     * above it cannot be paid at all. Enforced at quote time so the customer is told
     * before they commit, rather than meeting an opaque provider rejection afterwards.
     * Configurable because the ceiling is the provider's, not Peak's.
     */
    val maxCollectableAmount: BigDecimal = BigDecimal("5000000.00"),
    /** How long a quoted price is honoured before it must be re-quoted. */
    val quoteValidity: Duration = Duration.ofHours(2),
    /** Attempts allowed against one purchase before it must be re-quoted. */
    val maxPaymentAttempts: Int = 5,
) {
    init {
        require(maxCollectableAmount > BigDecimal.ZERO) {
            "peak.platformbilling.max-collectable-amount must be positive"
        }
        require(!quoteValidity.isNegative && !quoteValidity.isZero) {
            "peak.platformbilling.quote-validity must be positive"
        }
        require(maxPaymentAttempts in 1..20) {
            "peak.platformbilling.max-payment-attempts must be between 1 and 20"
        }
    }
}
