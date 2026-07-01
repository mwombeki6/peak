package com.mwombeki.peak.usermanagement.internal.application

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "peak.communication.invitation")
data class TenantInvitationSecurityProperties(
    val exposeTokenInResponse: Boolean = false,
)
