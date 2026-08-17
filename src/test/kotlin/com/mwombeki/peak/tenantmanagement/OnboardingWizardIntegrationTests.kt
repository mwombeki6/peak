package com.mwombeki.peak.tenantmanagement

import com.jayway.jsonpath.JsonPath
import com.mwombeki.peak.TestcontainersConfiguration
import com.mwombeki.peak.shared.context.PeakRequestHeaders
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import org.hamcrest.Matchers.hasItem
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
class OnboardingWizardIntegrationTests {

    @Autowired
    private lateinit var mockMvc: MockMvc

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun walksRegisterAdminBootstrapInventoryAndActivateWithOneNextActionEachTime() {
        val platformUserId = insertPlatformActor(
            "platform.tenants.manage",
            "platform.tenants.view",
            "platform.security.manage",
        )
        val planId = insertPlan()
        val slug = "wizard-${UUID.randomUUID().toString().take(8)}"

        val register = mockMvc.perform(
            post("/api/v1/platform/tenants")
                .secureJson(
                    """
                    {
                      "name": "Wizard Hotel Co",
                      "slug": "$slug",
                      "planId": "$planId",
                      "legalName": "Wizard Hotel Limited",
                      "businessEmail": "ops-$slug@example.com",
                      "businessPhone": "+255712345678",
                      "registeredAddress": {"city": "Dar es Salaam", "countryCode": "TZ"}
                    }
                    """.trimIndent(),
                )
                .platform(platformUserId, "corr-wizard-register", "idem-wizard-register-$slug"),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.nextAction.step").value("strong_admin"))
            .andExpect(jsonPath("$.nextAction.method").value("POST"))
            .andExpect(jsonPath("$.nextAction.path").value(org.hamcrest.Matchers.containsString("/administrators")))
            .andReturn()
        val tenantId = UUID.fromString(JsonPath.read(register.response.contentAsString, "$.id"))

        mockMvc.perform(
            get("/api/v1/platform/tenants/$tenantId/onboarding")
                .secure(true)
                .platform(platformUserId, "corr-wizard-platform-onboarding"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.canCreateProperties").value(false))
            .andExpect(jsonPath("$.nextAction.step").value("strong_admin"))
            .andExpect(jsonPath("$.steps[?(@.key == 'registered')].status", hasItem("satisfied")))
            .andExpect(jsonPath("$.steps[?(@.key == 'strong_admin')].status", hasItem("blocked")))

        val subject = "wizard-admin-$tenantId"
        val provision = mockMvc.perform(
            post("/api/v1/platform/tenants/$tenantId/administrators")
                .secureJson(
                    """
                    {
                      "fullName": "Wizard GM",
                      "email": "gm-$slug@example.com",
                      "issuer": "https://auth.peak.test/realms/peak",
                      "subject": "$subject"
                    }
                    """.trimIndent(),
                )
                .platform(platformUserId, "corr-wizard-admin", "idem-wizard-admin-$tenantId"),
        )
            .andExpect(status().isCreated)
            .andReturn()
        val tenantUserId = UUID.fromString(
            JsonPath.read(provision.response.contentAsString, "$.tenantUserId"),
        )

        mockMvc.perform(
            get("/api/v1/tenants/$tenantId/onboarding")
                .secure(true)
                .tenant(tenantId, tenantUserId, "corr-wizard-tenant-onboarding"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.canCreateProperties").value(true))
            .andExpect(jsonPath("$.nextAction.step").value("can_create_properties"))
            .andExpect(jsonPath("$.nextAction.method").value("POST"))
            .andExpect(jsonPath("$.nextAction.path").value("/api/v1/tenants/$tenantId/modules"))
            .andExpect(jsonPath("$.steps[?(@.key == 'pay_peak')].status", hasItem("satisfied")))
            .andExpect(jsonPath("$.nextAction.path", org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("/staff/sessions"))))
            .andExpect(jsonPath("$.nextAction.path", org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("provider-accounts"))))
            .andExpect(jsonPath("$.nextAction.title", org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Snippe"))))

        mockMvc.perform(
            post("/api/v1/tenants/$tenantId/modules")
                .secureJson("""{"moduleId": "property"}""")
                .tenant(tenantId, tenantUserId, "corr-wizard-property-module", "idem-wizard-property-module-$tenantId"),
        )
            .andExpect(status().isOk)

        mockMvc.perform(
            get("/api/v1/tenants/$tenantId/onboarding")
                .secure(true)
                .tenant(tenantId, tenantUserId, "corr-wizard-tenant-bootstrap-next"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.nextAction.path").value("/api/v1/properties/bootstrap"))

        val bootstrap = mockMvc.perform(
            post("/api/v1/properties/bootstrap")
                .secureJson(
                    """
                    {
                      "name": "Wizard Inn",
                      "code": "WIZ1"
                    }
                    """.trimIndent(),
                )
                .tenant(tenantId, tenantUserId, "corr-wizard-bootstrap", "idem-wizard-bootstrap-$tenantId"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("draft"))
            .andExpect(jsonPath("$.nextAction.step").value("inventory_ready"))
            .andExpect(jsonPath("$.nextAction.path").value(org.hamcrest.Matchers.containsString("/buildings")))
            .andReturn()
        val propertyId = UUID.fromString(JsonPath.read(bootstrap.response.contentAsString, "$.propertyId"))
        assertNotEquals(tenantId, propertyId)
        assertEquals(
            0,
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM rooms WHERE tenant_id = ? AND property_id = ?",
                Int::class.java,
                tenantId,
                propertyId,
            ),
        )

        mockMvc.perform(
            post("/api/v1/properties/$propertyId/activate")
                .secure(true)
                .tenant(tenantId, tenantUserId, "corr-wizard-activate-early", "idem-wizard-activate-early-$tenantId"),
        )
            .andExpect(status().isConflict)
            .andExpect(jsonPath("$.nextAction.step").value("inventory_ready"))
            .andExpect(jsonPath("$.nextAction.path").value(org.hamcrest.Matchers.containsString("/buildings")))

        val buildingId = postChild(
            tenantId,
            tenantUserId,
            "/api/v1/properties/$propertyId/buildings",
            """{"name": "Main building"}""",
            "building",
        )
        expectNextActionPath(tenantId, tenantUserId, propertyId, "/floors")

        postChild(
            tenantId,
            tenantUserId,
            "/api/v1/properties/$propertyId/floors",
            """{"buildingId": "$buildingId", "floorNumber": 1, "name": "Ground"}""",
            "floor",
        )
        expectNextActionPath(tenantId, tenantUserId, propertyId, "/room-types")

        val roomTypeId = postChild(
            tenantId,
            tenantUserId,
            "/api/v1/properties/$propertyId/room-types",
            """{"name": "Standard", "code": "STD", "basePrice": 80000}""",
            "room-type",
        )
        expectNextActionPath(tenantId, tenantUserId, propertyId, "/rooms")

        postChild(
            tenantId,
            tenantUserId,
            "/api/v1/properties/$propertyId/rooms",
            """
            {
              "buildingId": "$buildingId",
              "roomNumber": "101",
              "roomTypeId": "$roomTypeId",
              "floorNumber": 1
            }
            """.trimIndent(),
            "room",
        )
        expectNextActionPath(tenantId, tenantUserId, propertyId, "/revenue-centers")

        postChild(
            tenantId,
            tenantUserId,
            "/api/v1/properties/$propertyId/revenue-centers",
            """{"name": "Rooms", "code": "RMS", "centerType": "rooms", "isRoomsRevenue": true}""",
            "revenue",
        )
        expectNextActionPath(tenantId, tenantUserId, propertyId, "/taxes")

        mockMvc.perform(
            post("/api/v1/properties/taxes")
                .secureJson(
                    """{"name": "VAT", "code": "VAT-$slug", "rate": 0.18, "taxType": "vat"}""",
                )
                .tenant(tenantId, tenantUserId, "corr-wizard-tax", "idem-wizard-tax-$tenantId"),
        )
            .andExpect(status().isOk)

        mockMvc.perform(
            post("/api/v1/tenants/$tenantId/modules")
                .secureJson("""{"moduleId": "communications"}""")
                .tenant(tenantId, tenantUserId, "corr-wizard-comms", "idem-wizard-comms-$tenantId"),
        )
            .andExpect(status().isOk)

        createAndVerifyBusinessContact(tenantId, tenantUserId, slug)

        mockMvc.perform(
            get("/api/v1/properties/$propertyId/onboarding")
                .secure(true)
                .tenant(tenantId, tenantUserId, "corr-wizard-activate-next"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.nextAction.step").value("go_live"))
            .andExpect(jsonPath("$.nextAction.path").value("/api/v1/properties/$propertyId/activate"))
            .andExpect(jsonPath("$.isReady").value(true))
            .andExpect(jsonPath("$.steps[?(@.key == 'guest_rail_configured')].required", hasItem(false)))
            .andExpect(jsonPath("$.blockers[*].code", org.hamcrest.Matchers.not(hasItem("guest_rail_configured"))))
            .andExpect(jsonPath("$.nextAction.title", org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Snippe"))))
            .andExpect(jsonPath("$.collectionEnabled").value(false))

        mockMvc.perform(
            get("/api/v1/properties/$propertyId/readiness")
                .secure(true)
                .tenant(tenantId, tenantUserId, "corr-wizard-ready"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.isReady").value(true))
            .andExpect(jsonPath("$.nextAction.step").value("go_live"))
            .andExpect(jsonPath("$.nextAction.method").value("POST"))
            .andExpect(jsonPath("$.nextAction.path").value("/api/v1/properties/$propertyId/activate"))
            .andExpect(jsonPath("$.collectionEnabled").value(false))

        mockMvc.perform(
            post("/api/v1/properties/$propertyId/activate")
                .secure(true)
                .tenant(tenantId, tenantUserId, "corr-wizard-activate", "idem-wizard-activate-$tenantId"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.workflowStatus").value("activated"))
            .andExpect(jsonPath("$.collectionEnabled").value(false))
    }

    @Test
    fun unpaidStandingNextActionIsPayPeakNotHotelSnippe() {
        val platformUserId = insertPlatformActor(
            "platform.tenants.manage",
            "platform.tenants.view",
            "platform.security.manage",
        )
        val planId = insertPlan()
        val slug = "paypeak-${UUID.randomUUID().toString().take(8)}"

        val register = mockMvc.perform(
            post("/api/v1/platform/tenants")
                .secureJson(
                    """
                    {
                      "name": "Pay Peak Co",
                      "slug": "$slug",
                      "planId": "$planId",
                      "legalName": "Pay Peak Limited",
                      "businessEmail": "ops-$slug@example.com",
                      "businessPhone": "+255712345678",
                      "registeredAddress": {"city": "Dar es Salaam", "countryCode": "TZ"}
                    }
                    """.trimIndent(),
                )
                .platform(platformUserId, "corr-paypeak-register", "idem-paypeak-register-$slug"),
        )
            .andExpect(status().isCreated)
            .andReturn()
        val tenantId = UUID.fromString(JsonPath.read(register.response.contentAsString, "$.id"))

        val provision = mockMvc.perform(
            post("/api/v1/platform/tenants/$tenantId/administrators")
                .secureJson(
                    """
                    {
                      "fullName": "Pay Peak GM",
                      "email": "gm-$slug@example.com",
                      "issuer": "https://auth.peak.test/realms/peak",
                      "subject": "paypeak-admin-$tenantId"
                    }
                    """.trimIndent(),
                )
                .platform(platformUserId, "corr-paypeak-admin", "idem-paypeak-admin-$tenantId"),
        )
            .andExpect(status().isCreated)
            .andReturn()
        val tenantUserId = UUID.fromString(
            JsonPath.read(provision.response.contentAsString, "$.tenantUserId"),
        )

        jdbcTemplate.update(
            "UPDATE tenant_subscriptions SET status = 'cancelled' WHERE tenant_id = ?",
            tenantId,
        )

        mockMvc.perform(
            get("/api/v1/tenants/$tenantId/onboarding")
                .secure(true)
                .tenant(tenantId, tenantUserId, "corr-paypeak-unpaid"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.canCreateProperties").value(false))
            .andExpect(jsonPath("$.currentStep").value("pay_peak"))
            .andExpect(jsonPath("$.nextAction.step").value("pay_peak"))
            .andExpect(jsonPath("$.nextAction.title").value("Pay Peak"))
            .andExpect(jsonPath("$.nextAction.method").value("POST"))
            .andExpect(jsonPath("$.nextAction.path").value("/api/v1/tenants/$tenantId/billing/purchases"))
            .andExpect(jsonPath("$.nextAction.path", org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("/staff/sessions"))))
            .andExpect(jsonPath("$.nextAction.path", org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("provider-accounts"))))
            .andExpect(jsonPath("$.nextAction.title", org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("Snippe onboarding"))))
            .andExpect(jsonPath("$.steps[?(@.key == 'pay_peak')].status", hasItem("blocked")))
    }

    private fun expectNextActionPath(
        tenantId: UUID,
        tenantUserId: UUID,
        propertyId: UUID,
        pathFragment: String,
    ) {
        mockMvc.perform(
            get("/api/v1/properties/$propertyId/onboarding")
                .secure(true)
                .tenant(tenantId, tenantUserId, "corr-wizard-next-${pathFragment.trim('/')}"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.nextAction.step").value("inventory_ready"))
            .andExpect(jsonPath("$.nextAction.path").value(org.hamcrest.Matchers.containsString(pathFragment)))
            .andExpect(jsonPath("$.isReady").value(false))
    }

    private fun postChild(
        tenantId: UUID,
        tenantUserId: UUID,
        path: String,
        json: String,
        key: String,
    ): UUID {
        val result = mockMvc.perform(
            post(path)
                .secureJson(json)
                .tenant(tenantId, tenantUserId, "corr-wizard-$key", "idem-wizard-$key-$tenantId"),
        )
            .andExpect(status().isOk)
            .andReturn()
        return UUID.fromString(JsonPath.read(result.response.contentAsString, "$.resourceId"))
    }

    private fun createAndVerifyBusinessContact(tenantId: UUID, tenantUserId: UUID, slug: String) {
        val contactResult = mockMvc.perform(
            post("/api/v1/communication/contacts")
                .secureJson(
                    """
                    {
                      "fullName": "Operations Contact",
                      "jobTitle": "General Manager",
                      "email": "ops-contact-$slug@example.com",
                      "phone": "+255700000001"
                    }
                    """.trimIndent(),
                )
                .tenant(tenantId, tenantUserId, "corr-wizard-contact", "idem-wizard-contact-$tenantId"),
        )
            .andExpect(status().isOk)
            .andReturn()
        val channelIds: List<String> = JsonPath.read(contactResult.response.contentAsString, "$.channelIds")
        val channelId = UUID.fromString(channelIds.first())
        val verificationResult = mockMvc.perform(
            post("/api/v1/communication/channels/$channelId/request-verification")
                .secure(true)
                .tenant(tenantId, tenantUserId, "corr-wizard-verify-req", "idem-wizard-verify-req-$tenantId"),
        )
            .andExpect(status().isAccepted)
            .andReturn()
        val notificationEventId = UUID.fromString(
            JsonPath.read(verificationResult.response.contentAsString, "$.notificationEventId"),
        )
        val content = requireNotNull(
            jdbcTemplate.queryForObject(
                "SELECT payload ->> 'content' FROM outbox_events WHERE id = ?",
                String::class.java,
                notificationEventId,
            ),
        )
        val token = content.substringAfterLast(": ").trim()
        mockMvc.perform(
            post("/api/v1/communication/channels/$channelId/verify")
                .secureJson("""{"token": "$token"}""")
                .tenant(tenantId, tenantUserId, "corr-wizard-verify", "idem-wizard-verify-$tenantId"),
        )
            .andExpect(status().isOk)
    }

    private fun insertPlan(): UUID {
        val planId = UUID.randomUUID()
        jdbcTemplate.update(
            "INSERT INTO plans (id, name, code) VALUES (?, ?, ?)",
            planId,
            "Wizard Plan $planId",
            "wizard-${planId.toString().take(8)}",
        )
        listOf("property", "communications", "pos").forEach { moduleId ->
            jdbcTemplate.update(
                """
                INSERT INTO plan_entitlements (
                    plan_id, entitlement_code, entitlement_value, is_enabled
                ) VALUES (?, ?, jsonb_build_object('moduleId', ?), true)
                """.trimIndent(),
                planId,
                "module.$moduleId",
                moduleId,
            )
        }
        return planId
    }

    private fun insertPlatformActor(vararg permissionCodes: String): UUID {
        val platformUserId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO platform_users (id, full_name, email, status)
            VALUES (?, ?, ?, 'active')
            """.trimIndent(),
            platformUserId,
            "Wizard Operator",
            "wizard-ops-$platformUserId@example.com",
        )
        val roleId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO platform_roles (id, code, name, is_system, is_active)
            VALUES (?, ?, ?, false, true)
            """.trimIndent(),
            roleId,
            "wizard-ops-${roleId.toString().take(8)}",
            "Wizard Operator",
        )
        permissionCodes.forEach { permissionCode ->
            val permissionId = jdbcTemplate.queryForObject(
                "SELECT id FROM platform_permissions WHERE code = ?",
                UUID::class.java,
                permissionCode,
            )
            jdbcTemplate.update(
                """
                INSERT INTO platform_role_permissions (platform_role_id, platform_permission_id)
                VALUES (?, ?)
                """.trimIndent(),
                roleId,
                permissionId,
            )
        }
        jdbcTemplate.update(
            """
            INSERT INTO platform_user_roles (platform_user_id, platform_role_id)
            VALUES (?, ?)
            """.trimIndent(),
            platformUserId,
            roleId,
        )
        return platformUserId
    }

    private fun MockHttpServletRequestBuilder.secureJson(json: String): MockHttpServletRequestBuilder {
        return secure(true)
            .contentType(MediaType.APPLICATION_JSON)
            .content(json)
    }

    private fun MockHttpServletRequestBuilder.platform(
        platformUserId: UUID,
        correlationId: String,
        idempotencyKey: String? = null,
    ): MockHttpServletRequestBuilder {
        header(PeakRequestHeaders.PLATFORM_USER_ID, platformUserId.toString())
        header(PeakRequestHeaders.CORRELATION_ID, correlationId)
        idempotencyKey?.let { header(PeakRequestHeaders.IDEMPOTENCY_KEY, it) }
        return this
    }

    private fun MockHttpServletRequestBuilder.tenant(
        tenantId: UUID,
        tenantUserId: UUID,
        correlationId: String,
        idempotencyKey: String? = null,
    ): MockHttpServletRequestBuilder {
        header(PeakRequestHeaders.TENANT_ID, tenantId.toString())
        header(PeakRequestHeaders.TENANT_USER_ID, tenantUserId.toString())
        header(PeakRequestHeaders.CORRELATION_ID, correlationId)
        idempotencyKey?.let { header(PeakRequestHeaders.IDEMPOTENCY_KEY, it) }
        return this
    }
}
