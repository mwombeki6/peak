package com.mwombeki.peak.integrations.internal

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.context.annotation.Configuration

@ConfigurationProperties(prefix = "peak.integrations.payment")
data class PaymentIntegrationProperties(
    val providers: Map<String, ProviderConfig> = emptyMap()
)

data class ProviderConfig(
    val baseUrl: String,
    val apiKey: String?,
    val apiSecret: String?
)
