package com.mwombeki.peak.shared.security

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlin.test.assertEquals
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.springframework.boot.health.contributor.Status
import tools.jackson.databind.json.JsonMapper

class KeycloakDiscoveryHealthIndicatorTests {
    private var server: HttpServer? = null

    @AfterEach
    fun stopServer() {
        server?.stop(0)
    }

    @Test
    fun `reports up only for matching issuer and jwks contract`() {
        val runningServer = HttpServer.create(InetSocketAddress(0), 0)
        server = runningServer
        val issuer = "http://127.0.0.1:${runningServer.address.port}/realms/peak"
        runningServer.createContext("/realms/peak/.well-known/openid-configuration") { exchange ->
            val body =
                """{"issuer":"$issuer","jwks_uri":"$issuer/protocol/openid-connect/certs"}"""
                    .toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        runningServer.start()

        val indicator = KeycloakDiscoveryHealthIndicator(
            HttpSecurityProperties(
                jwt = HttpSecurityProperties.Jwt(
                    enabled = true,
                    issuerUri = issuer,
                    audience = "peak-api",
                ),
            ),
            JsonMapper.builder().build(),
        )

        assertEquals(Status.UP, indicator.health().status)
    }

    @Test
    fun `reports down when discovery issuer does not match`() {
        val runningServer = HttpServer.create(InetSocketAddress(0), 0)
        server = runningServer
        val issuer = "http://127.0.0.1:${runningServer.address.port}/realms/peak"
        runningServer.createContext("/realms/peak/.well-known/openid-configuration") { exchange ->
            val body =
                """{"issuer":"https://attacker.example/realms/peak","jwks_uri":"$issuer/certs"}"""
                    .toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        runningServer.start()

        val indicator = KeycloakDiscoveryHealthIndicator(
            HttpSecurityProperties(
                jwt = HttpSecurityProperties.Jwt(
                    enabled = true,
                    issuerUri = issuer,
                    audience = "peak-api",
                ),
            ),
            JsonMapper.builder().build(),
        )

        assertEquals(Status.DOWN, indicator.health().status)
    }

    @Test
    fun `reports down when discovery document exceeds limit`() {
        val runningServer = HttpServer.create(InetSocketAddress(0), 0)
        server = runningServer
        val issuer = "http://127.0.0.1:${runningServer.address.port}/realms/peak"
        runningServer.createContext("/realms/peak/.well-known/openid-configuration") { exchange ->
            val body = ByteArray(65 * 1024) { 'a'.code.toByte() }
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        runningServer.start()

        val indicator = KeycloakDiscoveryHealthIndicator(
            HttpSecurityProperties(
                jwt = HttpSecurityProperties.Jwt(
                    enabled = true,
                    issuerUri = issuer,
                    audience = "peak-api",
                ),
            ),
            JsonMapper.builder().build(),
        )

        assertEquals(Status.DOWN, indicator.health().status)
    }
}
