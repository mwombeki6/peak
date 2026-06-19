package com.mwombeki.peak.usermanagement.internal.web

import com.mwombeki.peak.TestcontainersConfiguration
import com.mwombeki.peak.shared.context.PeakRequestHeaders
import java.util.UUID
import kotlin.test.Test
import org.hamcrest.Matchers.containsString
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.junit.jupiter.Testcontainers

@Import(TestcontainersConfiguration::class)
@SpringBootTest(
    properties = [
        "peak.security.request-context.allow-header-identity=true",
    ],
)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class RouteGuardBoundaryControllerIntegrationTests {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Test
    fun deniesTenantIdentityOnPlatformControllerRoute() {
        mockMvc.perform(
            get("/api/v1/platform/tenants/${UUID.randomUUID()}")
                .secure(true)
                .header(PeakRequestHeaders.CORRELATION_ID, "corr-route-platform-as-tenant")
                .header(PeakRequestHeaders.TENANT_ID, UUID.randomUUID().toString())
                .header(PeakRequestHeaders.TENANT_USER_ID, UUID.randomUUID().toString()),
        )
            .andExpect(status().isForbidden)
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(content().string(containsString("Platform identity is required")))
    }

    @Test
    fun deniesPlatformIdentityOnTenantControllerRoute() {
        mockMvc.perform(
            get("/api/v1/tenants/${UUID.randomUUID()}/roles")
                .secure(true)
                .header(PeakRequestHeaders.CORRELATION_ID, "corr-route-tenant-as-platform")
                .header(PeakRequestHeaders.PLATFORM_USER_ID, UUID.randomUUID().toString()),
        )
            .andExpect(status().isForbidden)
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(content().string(containsString("Tenant identity is required")))
    }

    @Test
    fun deniesTenantIdentityOnPublicPropertyControllerRoute() {
        mockMvc.perform(
            post("/api/v1/public/properties/${UUID.randomUUID()}/booking-engine/payments/initiate")
                .secure(true)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}")
                .header(PeakRequestHeaders.CORRELATION_ID, "corr-route-public-as-tenant")
                .header(PeakRequestHeaders.TENANT_ID, UUID.randomUUID().toString())
                .header(PeakRequestHeaders.TENANT_USER_ID, UUID.randomUUID().toString()),
        )
            .andExpect(status().isForbidden)
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(content().string(containsString("Public identity is required")))
    }

}
