package com.mwombeki.peak.reservations.internal

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "peak.guest-identity")
data class GuestIdentityProperties(
    val hashKey: String = "",
    val hashKeyVersion: String = "v1",
    val previousHashKey: String = "",
    val previousHashKeyVersion: String = "",
)
