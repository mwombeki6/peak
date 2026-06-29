package com.mwombeki.peak.communication.internal

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Duration
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import tools.jackson.databind.ObjectMapper

class HttpNotificationDeliveryProviderTests {

    @Test
    fun sendsAuthenticatedIdempotentProviderRequest() {
        var authorization: String? = null
        var idempotencyKey: String? = null
        var requestBody = ""
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/v1/messages") { exchange ->
                authorization = exchange.requestHeaders.getFirst("Authorization")
                idempotencyKey = exchange.requestHeaders.getFirst("Idempotency-Key")
                requestBody = exchange.requestBody.bufferedReader().readText()
                val response = """{"messageId":"provider-message-001"}""".toByteArray()
                exchange.sendResponseHeaders(202, response.size.toLong())
                exchange.responseBody.use { it.write(response) }
            }
            start()
        }

        try {
            val command = NotificationDeliveryCommand(
                deliveryRequestId = UUID.randomUUID(),
                outboxEventId = UUID.randomUUID(),
                tenantId = UUID.randomUUID(),
                propertyId = UUID.randomUUID(),
                channel = "email",
                recipient = "ops@example.com",
                subject = "Operational alert",
                content = "Test content",
            )
            val provider = HttpNotificationDeliveryProvider(
                properties = HttpNotificationDeliveryProperties(
                    enabled = true,
                    baseUrl = "http://127.0.0.1:${server.address.port}",
                    apiKey = "test-provider-key",
                    connectTimeout = Duration.ofSeconds(1),
                    requestTimeout = Duration.ofSeconds(2),
                ),
                objectMapper = ObjectMapper(),
            )

            val result = provider.send(command)

            assertEquals("provider-message-001", result.providerMessageId)
            assertEquals("Bearer test-provider-key", authorization)
            assertEquals(command.outboxEventId.toString(), idempotencyKey)
            assertTrue(requestBody.contains("\"recipient\":\"ops@example.com\""))
            assertTrue(requestBody.contains("\"content\":\"Test content\""))
        } finally {
            server.stop(0)
        }
    }
}
