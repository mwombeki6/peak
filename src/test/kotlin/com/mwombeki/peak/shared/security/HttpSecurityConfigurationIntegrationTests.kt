package com.mwombeki.peak.shared.security

import com.mwombeki.peak.TestcontainersConfiguration
import kotlin.test.Test
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.nullValue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.junit.jupiter.Testcontainers

@Import(TestcontainersConfiguration::class)
@SpringBootTest(
    properties = [
        "peak.security.http.jwt.enabled=false",
    ],
)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class HttpSecurityConfigurationIntegrationTests {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun exposesHealthWithoutBasicChallenge() {
        mockMvc.perform(get("/actuator/health").secure(true))
            .andExpect(status().isOk)
            .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, nullValue()))
            .andExpect(header().string("X-Frame-Options", "DENY"))
            .andExpect(header().string("Referrer-Policy", "no-referrer"))
            .andExpect(
                header().string(
                    "Content-Security-Policy",
                    containsString("frame-ancestors 'none'"),
                ),
            )
            .andExpect(
                header().string(
                    "Strict-Transport-Security",
                    containsString("max-age=31536000"),
                ),
            )
    }

    @Test
    fun leavesApiAuthorizationToRouteGuardWithoutBasicChallenge() {
        mockMvc.perform(get("/api/v1/unregistered").secure(true))
            .andExpect(status().isForbidden)
            .andExpect(content().contentType("application/problem+json"))
            .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, nullValue()))
            .andExpect(content().string(containsString("Route is not registered")))
    }

    @Test
    fun deniesNonApiEndpointsByDefault() {
        mockMvc.perform(get("/actuator/prometheus").secure(true))
            .andExpect(status().isUnauthorized)
            .andExpect(content().contentType("application/problem+json"))
            .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, nullValue()))
    }
}
