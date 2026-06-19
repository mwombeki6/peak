package com.mwombeki.peak.shared.context

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.modulith.NamedInterface

@NamedInterface("context")
@ConfigurationProperties(prefix = "peak.security.request-context")
data class RequestContextProperties(
    val allowHeaderIdentity: Boolean = false,
    val allowTrustedJwtIdentityClaims: Boolean = false,
)
