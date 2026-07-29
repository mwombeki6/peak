package com.mwombeki.peak.usermanagement.internal.bootstrap

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "peak.bootstrap.platform")
data class PlatformBootstrapProperties(
    val enabled: Boolean = false,
    val recoveryEnabled: Boolean = false,
    val fullName: String? = null,
    val email: String? = null,
    val issuer: String? = null,
    val subject: String? = null,
    /**
     * Second Platform Emergency Administrator custodian.
     *
     * Production provisions two custodians in one transaction so dual control
     * is true from the first minute and there is never a window in which a
     * single account can unilaterally appoint another root. Development may
     * bootstrap a single custodian; production readiness validation rejects
     * that configuration.
     */
    val secondFullName: String? = null,
    val secondEmail: String? = null,
    val secondIssuer: String? = null,
    val secondSubject: String? = null,
) {
    /** True when any second-custodian field is supplied, complete or not. */
    val hasSecondCustodian: Boolean
        get() = listOf(secondFullName, secondEmail, secondIssuer, secondSubject)
            .any { !it.isNullOrBlank() }
}
