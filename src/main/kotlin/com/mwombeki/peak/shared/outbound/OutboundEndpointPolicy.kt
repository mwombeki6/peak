package com.mwombeki.peak.shared.outbound

import java.net.URI
import java.util.Locale
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.modulith.NamedInterface
import org.springframework.stereotype.Component

@ConfigurationProperties(prefix = "peak.security.outbound")
data class OutboundEndpointProperties(
    val allowedProviderHosts: Set<String> = emptySet(),
)

@NamedInterface("outbound")
@Component
class OutboundEndpointPolicy(
    properties: OutboundEndpointProperties,
) {
    private val allowedProviderHosts = properties.allowedProviderHosts
        .map(::normalizeHost)
        .toSet()

    fun requireAllowedProviderEndpoint(endpoint: URI): URI {
        require(endpoint.scheme.equals("https", ignoreCase = true) && endpoint.isAbsolute) {
            "Provider endpoint must be an absolute HTTPS URL"
        }
        require(endpoint.rawUserInfo == null && endpoint.rawFragment == null) {
            "Provider endpoint must not contain user information or a fragment"
        }
        val host = endpoint.host?.let(::normalizeHost)
            ?: throw IllegalArgumentException("Provider endpoint host is required")
        require(host in allowedProviderHosts) {
            "Provider endpoint host is not allowed"
        }
        return endpoint
    }

    private fun normalizeHost(value: String): String {
        val host = value.trim().lowercase(Locale.ROOT).removeSuffix(".")
        require(HOST_PATTERN.matches(host) && host != "localhost" && !host.endsWith(".local")) {
            "Provider host allowlist entries must be DNS hostnames"
        }
        return host
    }

    private companion object {
        val HOST_PATTERN = Regex(
            "^(?=.{1,253}$)(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+" +
                    "[a-z](?:[a-z0-9-]{0,61}[a-z0-9])?$",
        )
    }
}
