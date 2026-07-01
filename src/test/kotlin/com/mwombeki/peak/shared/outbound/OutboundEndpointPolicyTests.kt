package com.mwombeki.peak.shared.outbound

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OutboundEndpointPolicyTests {
    private val policy = OutboundEndpointPolicy(
        OutboundEndpointProperties(
            allowedProviderHosts = setOf("payments.example.com", "fiscal.example.com"),
        ),
    )

    @Test
    fun allowsExactHttpsProviderHost() {
        val endpoint = URI("https://payments.example.com/v1/collections")

        assertEquals(endpoint, policy.requireAllowedProviderEndpoint(endpoint))
    }

    @Test
    fun rejectsUnapprovedInternalOrLookalikeHosts() {
        listOf(
            "https://127.0.0.1/admin",
            "https://metadata.internal/latest",
            "https://payments.example.com.attacker.test/v1",
            "https://payments.example.com@attacker.test/v1",
            "http://payments.example.com/v1",
        ).forEach { endpoint ->
            assertFailsWith<IllegalArgumentException>(endpoint) {
                policy.requireAllowedProviderEndpoint(URI(endpoint))
            }
        }
    }

    @Test
    fun rejectsUnsafeAllowlistEntriesAtStartup() {
        listOf("localhost", "127.0.0.1", "*.example.com", "gateway.local").forEach { host ->
            assertFailsWith<IllegalArgumentException>(host) {
                OutboundEndpointPolicy(
                    OutboundEndpointProperties(allowedProviderHosts = setOf(host)),
                )
            }
        }
    }
}
