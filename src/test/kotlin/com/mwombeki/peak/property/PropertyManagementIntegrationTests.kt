package com.mwombeki.peak.property

import com.jayway.jsonpath.JsonPath
import com.mwombeki.peak.TestcontainersConfiguration
import com.mwombeki.peak.shared.context.PeakRequestHeaders
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.hamcrest.Matchers.hasItem
import org.hamcrest.Matchers.hasSize
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.content
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
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
class PropertyManagementIntegrationTests {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun configuresPropertyThroughSecuredApisAndActivatesAfterReadinessPasses() {
        val fixture = propertyFixture()
        insertAuthorizedFixture(fixture)

        enableTenantModule(fixture, "property")
        enableTenantModule(fixture, "communications")

        val createPropertyBody = """
            {
              "name": "Peak Test Hotel",
              "location": "Arusha",
              "code": "PTH-${fixture.tenantId.toString().take(8)}",
              "type": "HOTEL"
            }
        """.trimIndent()

        val createResponse = mockMvc.perform(
            post("/api/v1/properties")
                .secureJson(createPropertyBody)
                .headersFor(fixture, "corr-property-create", "idem-property-create-${fixture.tenantId}"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("draft"))
            .andExpect(jsonPath("$.changed").value(true))
            .andExpect(jsonPath("$.replayed").value(false))
            .andReturn()

        val propertyId = UUID.fromString(JsonPath.read(createResponse.response.contentAsString, "$.propertyId"))

        mockMvc.perform(
            post("/api/v1/properties")
                .secureJson(createPropertyBody)
                .headersFor(fixture, "corr-property-create-replay", "idem-property-create-${fixture.tenantId}"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.propertyId").value(propertyId.toString()))
            .andExpect(jsonPath("$.replayed").value(true))

        mockMvc.perform(
            get("/api/v1/properties/$propertyId")
                .secure(true)
                .headersFor(fixture, "corr-property-get"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("draft"))
            .andExpect(jsonPath("$.isActive").value(false))

        mockMvc.perform(
            get("/api/v1/properties/$propertyId/readiness")
                .secure(true)
                .headersFor(fixture, "corr-property-readiness-initial"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.isReady").value(false))
            .andExpect(jsonPath("$.missingRequirements").isArray)


        val buildingId = postForResourceId(
            fixture = fixture,
            path = "/api/v1/properties/$propertyId/buildings",
            idempotencyKey = "idem-building-create-${fixture.tenantId}",
            json = """
                {
                  "name": "Main Building",
                  "description": "Front office and guest rooms"
                }
            """.trimIndent(),
        )

        val floorId = postForResourceId(
            fixture = fixture,
            path = "/api/v1/properties/$propertyId/floors",
            idempotencyKey = "idem-floor-create-${fixture.tenantId}",
            json = """
                {
                  "buildingId": "$buildingId",
                  "floorNumber": 1,
                  "name": "Ground Floor",
                  "capacity": 30
                }
            """.trimIndent(),
        )

        val roomTypeId = postForResourceId(
            fixture = fixture,
            path = "/api/v1/properties/$propertyId/room-types",
            idempotencyKey = "idem-room-type-create-${fixture.tenantId}",
            json = """
                {
                  "name": "Deluxe King",
                  "code": "DLX-${fixture.tenantId.toString().take(6)}",
                  "description": "Deluxe king room",
                  "basePrice": 0,
                  "maxAdults": 2,
                  "maxChildren": 1,
                  "maxOccupancy": 3
                }
            """.trimIndent(),
        )

        postForResourceId(
            fixture = fixture,
            path = "/api/v1/properties/$propertyId/revenue-centers",
            idempotencyKey = "idem-revenue-center-create-${fixture.tenantId}",
            json = """
                {
                  "name": "Rooms Revenue",
                  "code": "ROOMS-${fixture.tenantId.toString().take(6)}",
                  "centerType": "rooms",
                  "isRoomsRevenue": true,
                  "displayOrder": 1
                }
            """.trimIndent(),
        )

        postForResourceId(
            fixture = fixture,
            path = "/api/v1/properties/$propertyId/departments",
            idempotencyKey = "idem-department-create-${fixture.tenantId}",
            json = """
                {
                  "name": "Front Office",
                  "code": "FO-${fixture.tenantId.toString().take(6)}"
                }
            """.trimIndent(),
        )

        postForResourceId(
            fixture = fixture,
            path = "/api/v1/properties/taxes",
            idempotencyKey = "idem-tax-create-${fixture.tenantId}",
            json = """
                {
                  "name": "VAT",
                  "code": "VAT-${fixture.tenantId.toString().take(6)}",
                  "rate": 0.18,
                  "taxType": "vat",
                  "isCompound": false,
                  "isInclusive": false
                }
            """.trimIndent(),
        )

        mockMvc.perform(
            post("/api/v1/properties/$propertyId/rates")
                .secureJson(
                    """
                    {
                      "roomTypeId": "$roomTypeId",
                      "amount": 250000.0,
                      "currency": "TZS"
                    }
                    """.trimIndent(),
                )
                .headersFor(fixture, "corr-rate-set", "idem-rate-set-${fixture.tenantId}"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.resourceId").value(roomTypeId.toString()))

        val roomId = postForResourceId(
            fixture = fixture,
            path = "/api/v1/properties/$propertyId/rooms",
            idempotencyKey = "idem-room-create-${fixture.tenantId}",
            json = """
                {
                  "buildingId": "$buildingId",
                  "roomNumber": "101",
                  "roomTypeId": "$roomTypeId",
                  "floorNumber": 1,
                  "isSmoking": false,
                  "isAccessible": true
                }
            """.trimIndent(),
        )

        mockMvc.perform(
            put("/api/v1/properties/$propertyId/rooms/$roomId/status")
                .secureJson("""{"status": "maintenance"}""")
                .headersFor(fixture, "corr-room-status", "idem-room-status-${fixture.tenantId}"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("maintenance"))

        mockMvc.perform(
            put("/api/v1/properties/$propertyId/rooms/$roomId/status")
                .secureJson("""{"status": "available"}""")
                .headersFor(fixture, "corr-room-status-available", "idem-room-status-available-${fixture.tenantId}"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("vacant_clean"))

        createAndVerifyBusinessContact(fixture)

        mockMvc.perform(
            get("/api/v1/properties/$propertyId/readiness")
                .secure(true)
                .headersFor(fixture, "corr-property-readiness-ready"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.isReady").value(true))
            .andExpect(jsonPath("$.missingRequirements", hasSize<Any>(0)))

        mockMvc.perform(
            post("/api/v1/properties/$propertyId/activate")
                .secure(true)
                .headersFor(fixture, "corr-property-activate", "idem-property-activate-${fixture.tenantId}"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.isReady").value(true))

        mockMvc.perform(
            get("/api/v1/properties/$propertyId")
                .secure(true)
                .headersFor(fixture, "corr-property-active"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("active"))
            .andExpect(jsonPath("$.isActive").value(true))
            .andExpect(jsonPath("$.totalRooms").value(1))

        mockMvc.perform(
            get("/api/v1/properties/$propertyId/buildings")
                .secure(true)
                .headersFor(fixture, "corr-buildings-list"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[*].id", hasItem(buildingId.toString())))

        mockMvc.perform(
            get("/api/v1/properties/$propertyId/rooms")
                .secure(true)
                .headersFor(fixture, "corr-rooms-list"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[*].id", hasItem(roomId.toString())))

        assertEquals(1, auditCount(fixture.tenantId, "property.created", propertyId))
        assertEquals(1, outboxCount(fixture.tenantId, "property.activated", propertyId))
        assertTrue(propertyRoleAssigned(fixture.tenantId, fixture.tenantUserId, propertyId))
        assertEquals(floorId, requireFloorId(fixture.tenantId, buildingId, 1))
    }

    @Test
    fun deniesPropertyScopedRouteWithoutPropertyRole() {
        val fixture = propertyFixture()
        insertAuthorizedFixture(fixture)
        enableTenantModule(fixture, "property")

        val createResponse = mockMvc.perform(
            post("/api/v1/properties")
                .secureJson("""{"name": "Denied Hotel", "code": "DEN-${fixture.tenantId.toString().take(6)}"}""")
                .headersFor(fixture, "corr-property-denied-create", "idem-property-denied-create-${fixture.tenantId}"),
        )
            .andExpect(status().isOk)
            .andReturn()

        val propertyId = UUID.fromString(JsonPath.read(createResponse.response.contentAsString, "$.propertyId"))
        val otherUserId = UUID.randomUUID()
        insertTenantUser(fixture.tenantId, otherUserId, "other-${fixture.tenantId}@example.com")
        jdbcTemplate.update(
            """
            INSERT INTO user_tenant_roles (user_id, tenant_id, tenant_role_id)
            VALUES (?, ?, ?)
            """.trimIndent(),
            otherUserId,
            fixture.tenantId,
            fixture.tenantRoleId,
        )

        mockMvc.perform(
            get("/api/v1/properties/$propertyId")
                .secure(true)
                .headersFor(
                    fixture.copy(tenantUserId = otherUserId),
                    "corr-property-without-property-role",
                ),
        )
            .andExpect(status().isForbidden)
            .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
    }

    private fun enableTenantModule(fixture: PropertyFixture, moduleId: String) {
        mockMvc.perform(
            post("/api/v1/tenants/${fixture.tenantId}/modules")
                .secureJson("""{"moduleId": "$moduleId"}""")
                .headersFor(fixture, "corr-tenant-module-$moduleId", "idem-tenant-module-$moduleId-${fixture.tenantId}"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.moduleId").value(moduleId))
            .andExpect(jsonPath("$.enabled").value(true))
    }

    private fun postForResourceId(
        fixture: PropertyFixture,
        path: String,
        idempotencyKey: String,
        json: String,
    ): UUID {
        val result = mockMvc.perform(
            post(path)
                .secureJson(json)
                .headersFor(fixture, "corr-$idempotencyKey", idempotencyKey),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.changed").value(true))
            .andExpect(jsonPath("$.replayed").value(false))
            .andReturn()

        return UUID.fromString(JsonPath.read(result.response.contentAsString, "$.resourceId"))
    }

    private fun createAndVerifyBusinessContact(fixture: PropertyFixture) {
        val contactResult = mockMvc.perform(
            post("/api/v1/communication/contacts")
                .secureJson(
                    """
                    {
                      "fullName": "Operations Contact",
                      "jobTitle": "General Manager",
                      "email": "ops-${fixture.tenantId}@example.com",
                      "phone": "+255700000001"
                    }
                    """.trimIndent(),
                )
                .headersFor(fixture, "corr-contact-create", "idem-contact-create-${fixture.tenantId}"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.channelIds").isArray)
            .andReturn()

        val channelIds: List<String> = JsonPath.read(contactResult.response.contentAsString, "$.channelIds")
        val channelId = UUID.fromString(channelIds.first())

        val verificationResult = mockMvc.perform(
            post("/api/v1/communication/channels/$channelId/request-verification")
                .secure(true)
                .headersFor(fixture, "corr-contact-verification", "idem-contact-verification-${fixture.tenantId}"),
        )
            .andExpect(status().isAccepted)
            .andReturn()

        val notificationEventId = UUID.fromString(
            JsonPath.read(verificationResult.response.contentAsString, "$.notificationEventId"),
        )
        val token = verificationToken(notificationEventId)

        mockMvc.perform(
            post("/api/v1/communication/channels/$channelId/verify")
                .secureJson("""{"token": "$token"}""")
                .headersFor(fixture, "corr-contact-verify", "idem-contact-verify-${fixture.tenantId}"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.verified").value(true))
    }

    private fun MockHttpServletRequestBuilder.secureJson(json: String): MockHttpServletRequestBuilder {
        return secure(true)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json)
    }

    private fun MockHttpServletRequestBuilder.headersFor(
        fixture: PropertyFixture,
        correlationId: String,
        idempotencyKey: String? = null,
    ): MockHttpServletRequestBuilder {
        header(PeakRequestHeaders.CORRELATION_ID, correlationId)
        header(PeakRequestHeaders.TENANT_ID, fixture.tenantId.toString())
        header(PeakRequestHeaders.TENANT_USER_ID, fixture.tenantUserId.toString())
        idempotencyKey?.let { header(PeakRequestHeaders.IDEMPOTENCY_KEY, it) }
        return this
    }

    private fun propertyFixture(): PropertyFixture {
        return PropertyFixture(
            planId = UUID.randomUUID(),
            tenantId = UUID.randomUUID(),
            tenantUserId = UUID.randomUUID(),
            tenantRoleId = UUID.randomUUID(),
        )
    }

    private fun insertAuthorizedFixture(fixture: PropertyFixture) {
        jdbcTemplate.update(
            """
            INSERT INTO plans (id, name, code)
            VALUES (?, ?, ?)
            """.trimIndent(),
            fixture.planId,
            "Property Plan ${fixture.planId}",
            "property-${fixture.planId}",
        )
        jdbcTemplate.update(
            """
            INSERT INTO tenants (id, name, slug, status, schema_name, plan_id)
            VALUES (?, ?, ?, 'active', ?, ?)
            """.trimIndent(),
            fixture.tenantId,
            "Property Tenant ${fixture.tenantId}",
            "property-${fixture.tenantId}",
            "tenant_${fixture.tenantId}".replace("-", "_"),
            fixture.planId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO tenant_modules (tenant_id, module_id, is_enabled, is_configured)
            VALUES (?, 'tenant_admin', true, true)
            """.trimIndent(),
            fixture.tenantId,
        )
        insertTenantUser(fixture.tenantId, fixture.tenantUserId, "property-admin-${fixture.tenantId}@example.com")
        jdbcTemplate.update(
            """
            INSERT INTO tenant_roles (id, tenant_id, name, code)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
            fixture.tenantRoleId,
            fixture.tenantId,
            "Property Admin ${fixture.tenantRoleId}",
            "property-admin-${fixture.tenantRoleId}",
        )
        jdbcTemplate.update(
            """
            INSERT INTO user_tenant_roles (user_id, tenant_id, tenant_role_id)
            VALUES (?, ?, ?)
            """.trimIndent(),
            fixture.tenantUserId,
            fixture.tenantId,
            fixture.tenantRoleId,
        )
        listOf(
            "module.manage",
            "property.manage",
            "property.view",
            "property.lifecycle",
            "communications.manage",
        ).forEach { grantPermissionToActor(fixture, it) }
    }

    private fun insertTenantUser(
        tenantId: UUID,
        tenantUserId: UUID,
        email: String,
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO users (id, tenant_id, full_name, email, status, is_active)
            VALUES (?, ?, ?, ?, 'active', true)
            """.trimIndent(),
            tenantUserId,
            tenantId,
            "Property User $tenantUserId",
            email,
        )
    }

    private fun grantPermissionToActor(
        fixture: PropertyFixture,
        permissionCode: String,
    ) {
        val permissionId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO permissions (id, tenant_id, code, description)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
            permissionId,
            fixture.tenantId,
            permissionCode,
            "Permission $permissionCode",
        )
        jdbcTemplate.update(
            """
            INSERT INTO tenant_role_permissions (tenant_role_id, permission_id)
            VALUES (?, ?)
            """.trimIndent(),
            fixture.tenantRoleId,
            permissionId,
        )
    }

    private fun verificationToken(notificationEventId: UUID): String {
        val content = requireNotNull(
            jdbcTemplate.queryForObject(
                "SELECT payload ->> 'content' FROM outbox_events WHERE id = ?",
                String::class.java,
                notificationEventId,
            ),
        )
        return content.substringAfterLast(": ").trim()
    }

    private fun auditCount(tenantId: UUID, action: String, entityId: UUID): Int {
        return requireNotNull(
            jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM audit_logs
                WHERE tenant_id = ?
                  AND action = ?
                  AND entity_id = ?
                """.trimIndent(),
                Int::class.java,
                tenantId,
                action,
                entityId,
            ),
        )
    }

    private fun outboxCount(tenantId: UUID, eventType: String, aggregateId: UUID): Int {
        return requireNotNull(
            jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM outbox_events
                WHERE tenant_id = ?
                  AND event_type = ?
                  AND aggregate_id = ?
                """.trimIndent(),
                Int::class.java,
                tenantId,
                eventType,
                aggregateId,
            ),
        )
    }

    private fun propertyRoleAssigned(
        tenantId: UUID,
        tenantUserId: UUID,
        propertyId: UUID,
    ): Boolean {
        return jdbcTemplate.queryForObject(
            """
            SELECT EXISTS(
                SELECT 1
                FROM user_property_roles
                WHERE tenant_id = ?
                  AND user_id = ?
                  AND property_id = ?
            )
            """.trimIndent(),
            Boolean::class.java,
            tenantId,
            tenantUserId,
            propertyId,
        ) == true
    }

    private fun requireFloorId(
        tenantId: UUID,
        buildingId: UUID,
        floorNumber: Int,
    ): UUID {
        return requireNotNull(
            jdbcTemplate.queryForObject(
                """
                SELECT id
                FROM floors
                WHERE tenant_id = ?
                  AND building_id = ?
                  AND floor_number = ?
                """.trimIndent(),
                UUID::class.java,
                tenantId,
                buildingId,
                floorNumber,
            ),
        )
    }

    private data class PropertyFixture(
        val planId: UUID,
        val tenantId: UUID,
        val tenantUserId: UUID,
        val tenantRoleId: UUID,
    )
}
