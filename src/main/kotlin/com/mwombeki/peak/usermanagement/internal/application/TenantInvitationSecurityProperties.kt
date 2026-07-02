package com.mwombeki.peak.usermanagement.internal.application

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "peak.usermanagement.invitation")
data class TenantInvitationSecurityProperties(
    val exposeTokenInResponse: Boolean = false,
)
