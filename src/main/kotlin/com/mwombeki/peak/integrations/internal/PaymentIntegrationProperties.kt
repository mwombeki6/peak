package com.mwombeki.peak.integrations.internal

import org.springframework.boot.context.properties.ConfigurationProperties

@ConfigurationProperties(prefix = "peak.integrations.payment")
data class PaymentIntegrationProperties(
    val providers: Map<String, ProviderConfig> = emptyMap()
)

data class ProviderConfig(
    val baseUrl: String,
    val apiKey: String?,
    val apiSecret: String?
)
