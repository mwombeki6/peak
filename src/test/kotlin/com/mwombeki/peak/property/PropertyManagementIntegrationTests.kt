package com.mwombeki.peak.property

import com.mwombeki.peak.TestcontainersConfiguration
import com.mwombeki.peak.shared.context.PeakRequestHeaders
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.testcontainers.junit.jupiter.Testcontainers
import java.util.UUID
import com.jayway.jsonpath.JsonPath
import org.hamcrest.Matchers.hasSize

@Import(TestcontainersConfiguration::class)
@SpringBootTest(
    properties = [
        "peak.security.request-context.allow-header-identity=true",
    ],
)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class PropertyManagementIntegrationTests {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `should perform full property lifecycle and readiness checks`() {
        val tenantId = UUID.randomUUID()
        val tenantUserId = UUID.randomUUID()
        
        // Setup Tenant Fixture
        setupTenantFixture(tenantId, tenantUserId)

        // 1. Create Property
        val createResponse = mockMvc.perform(
            post("/api/v1/properties")
                .header(PeakRequestHeaders.TENANT_ID, tenantId.toString())
                .header(PeakRequestHeaders.TENANT_USER_ID, tenantUserId.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "name": "Test Hotel",
                        "location": "Arusha",
                        "code": "TH-001",
                        "type": "HOTEL"
                    }
                """.trimIndent())
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.propertyId").exists())
            .andReturn()

        val propertyId = UUID.fromString(JsonPath.read(createResponse.response.contentAsString, "$.propertyId"))

        // 2. Get Property
        mockMvc.perform(
            get("/api/v1/properties/$propertyId")
                .header(PeakRequestHeaders.TENANT_ID, tenantId.toString())
                .header(PeakRequestHeaders.TENANT_USER_ID, tenantUserId.toString())
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.name").value("Test Hotel"))
            .andExpect(jsonPath("$.status").value("draft"))

        // 3. Check Readiness (should be NOT ready initially)
        mockMvc.perform(
            get("/api/v1/properties/$propertyId/readiness")
                .header(PeakRequestHeaders.TENANT_ID, tenantId.toString())
                .header(PeakRequestHeaders.TENANT_USER_ID, tenantUserId.toString())
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.isReady").value(false))
            .andExpect(jsonPath("$.missingRequirements").isArray)

        // 4. Try to activate (should fail)
        mockMvc.perform(
            post("/api/v1/properties/$propertyId/activate")
                .header(PeakRequestHeaders.TENANT_ID, tenantId.toString())
                .header(PeakRequestHeaders.TENANT_USER_ID, tenantUserId.toString())
        )
            .andExpect(status().isInternalServerError) // IllegalStateException maps to 500 usually

        // 5. Complete Configuration to pass readiness
        val buildingId = UUID.randomUUID()
        jdbcTemplate.update("INSERT INTO buildings (id, tenant_id, property_id, name) VALUES (?, ?, ?, ?)", buildingId, tenantId, propertyId, "Main Building")
        
        val floorId = UUID.randomUUID()
        jdbcTemplate.update("INSERT INTO floors (id, tenant_id, property_id, building_id, floor_number) VALUES (?, ?, ?, ?, ?)", floorId, tenantId, propertyId, buildingId, 1)
        
        val roomTypeId = UUID.randomUUID()
        jdbcTemplate.update("INSERT INTO room_types (id, tenant_id, property_id, name, code, base_capacity) VALUES (?, ?, ?, ?, ?, ?)", roomTypeId, tenantId, propertyId, "Deluxe", "DLX", 2)
        
        jdbcTemplate.update("INSERT INTO rooms (id, tenant_id, property_id, building_id, room_number, room_type_id, floor_number, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?)", UUID.randomUUID(), tenantId, propertyId, buildingId, "101", roomTypeId, 1, "AVAILABLE")
        
        jdbcTemplate.update("INSERT INTO revenue_centers (id, tenant_id, property_id, code, name) VALUES (?, ?, ?, ?, ?)", UUID.randomUUID(), tenantId, propertyId, "REV-01", "Front Desk")
        
        jdbcTemplate.update("INSERT INTO tax_rates (id, tenant_id, name, code, rate, tax_type, is_active) VALUES (?, ?, ?, ?, ?, ?, ?)", UUID.randomUUID(), tenantId, "VAT", "VAT18", 0.18, "vat", true)
        
        jdbcTemplate.update("INSERT INTO room_type_rates (tenant_id, property_id, room_type_id, base_amount, currency) VALUES (?, ?, ?, ?, ?)", tenantId, propertyId, roomTypeId, 100.0, "USD")
        
        jdbcTemplate.update("INSERT INTO tenant_contacts (id, tenant_id, full_name, email) VALUES (?, ?, ?, ?)", UUID.randomUUID(), tenantId, "John Doe", "john@example.com")

        // 6. Check Readiness again (should be ready now)
        mockMvc.perform(
            get("/api/v1/properties/$propertyId/readiness")
                .header(PeakRequestHeaders.TENANT_ID, tenantId.toString())
                .header(PeakRequestHeaders.TENANT_USER_ID, tenantUserId.toString())
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.isReady").value(true))
            .andExpect(jsonPath("$.missingRequirements", hasSize<Any>(0)))

        // 7. Activate Property
        mockMvc.perform(
            post("/api/v1/properties/$propertyId/activate")
                .header(PeakRequestHeaders.TENANT_ID, tenantId.toString())
                .header(PeakRequestHeaders.TENANT_USER_ID, tenantUserId.toString())
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.isReady").value(true))

        // Verify status is now ACTIVE
        mockMvc.perform(
            get("/api/v1/properties/$propertyId")
                .header(PeakRequestHeaders.TENANT_ID, tenantId.toString())
                .header(PeakRequestHeaders.TENANT_USER_ID, tenantUserId.toString())
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("ACTIVE"))
    }

    private fun setupTenantFixture(tenantId: UUID, tenantUserId: UUID) {
        // Insert tenant
        jdbcTemplate.update(
            "INSERT INTO tenants (id, name, slug, status, schema_name) VALUES (?, ?, ?, ?, ?)",
            tenantId, "Test Tenant", "test-tenant-${tenantId.toString().take(8)}", "trial", "public"
        )
        
        // Insert user (if needed by some other security check, but we are using allow-header-identity)
        // For now, let's just assume the header identity is enough for the RequestContext.
    }
}
