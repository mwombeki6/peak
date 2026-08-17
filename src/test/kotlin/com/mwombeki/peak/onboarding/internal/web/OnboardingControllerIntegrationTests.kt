package com.mwombeki.peak.onboarding.internal.web

import com.mwombeki.peak.TestcontainersConfiguration
import com.mwombeki.peak.shared.context.PeakRequestHeaders
import com.mwombeki.peak.shared.ephemeral.RateLimitScope
import com.mwombeki.peak.shared.ephemeral.RateLimitStore
import com.mwombeki.peak.verification.api.RequestVerificationCommand
import com.mwombeki.peak.verification.api.VerificationPort
import com.mwombeki.peak.verification.api.VerificationPurpose
import java.util.UUID
import kotlin.test.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.MvcResult
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.junit.jupiter.Testcontainers

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class OnboardingControllerIntegrationTests {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var verification: VerificationPort
    @Autowired private lateinit var rateLimitStore: RateLimitStore

    @Test
    fun aProspectRequestsAccessVerifiesPhoneAndManagesTheirOwnKybCaseOverHttp() {
        val phone = phone()
        val requested = mockMvc.perform(
            post("/api/v1/onboarding/request-access")
                .secure(true)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"representativeFullName":"Amina Hassan","representativePhone":"$phone",
                     "businessName":"Zanzibar Beach Lodge"}
                    """.trimIndent(),
                ),
        ).andExpect(status().isCreated).andReturn()
        val applicationId = readString(requested, "$.applicationId")

        rateLimitStore.reset(RateLimitScope.OTP_SEND_COOLDOWN, phone)
        val code = verification.request(
            RequestVerificationCommand(VerificationPurpose.PHONE_VERIFICATION, phone),
        ).code

        val verified = mockMvc.perform(
            post("/api/v1/onboarding/verify-phone")
                .secure(true)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"applicationId":"$applicationId","code":"$code"}"""),
        ).andExpect(status().isOk).andReturn()
        val token = readString(verified, "$.token")

        val created = mockMvc.perform(
            post("/api/v1/onboarding/me/verification-cases")
                .secure(true)
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .header(PeakRequestHeaders.IDEMPOTENCY_KEY, "create-case-$applicationId")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"caseType":"initial_onboarding","requiredLevel":"standard"}"""),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.tenantId").value(org.hamcrest.Matchers.nullValue()))
            .andReturn()
        val caseId = readString(created, "$.caseId")

        mockMvc.perform(
            post("/api/v1/onboarding/me/verification-cases/$caseId/documents")
                .secure(true)
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .header(PeakRequestHeaders.IDEMPOTENCY_KEY, "add-document-$caseId")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"documentType":"business_registration","documentNumberMasked":"***1234",
                     "storageObjectKey":"verification/$caseId.pdf",
                     "contentHash":"${"a".repeat(64)}","mimeType":"application/pdf"}
                    """.trimIndent(),
                ),
        ).andExpect(status().isCreated)

        mockMvc.perform(
            post("/api/v1/onboarding/me/verification-cases/$caseId/submit")
                .secure(true)
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token")
                .header(PeakRequestHeaders.IDEMPOTENCY_KEY, "submit-case-$caseId"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("submitted"))

        mockMvc.perform(
            get("/api/v1/onboarding/me/verification-cases")
                .secure(true)
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token"),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$[0].caseId").value(caseId))
    }

    @Test
    fun anOnboardingSessionCannotReachTenantScopedRoutes() {
        val token = issuedSessionToken()

        mockMvc.perform(
            get("/api/v1/tenants/${UUID.randomUUID()}/verification-cases")
                .secure(true)
                .header(HttpHeaders.AUTHORIZATION, "Bearer $token"),
        ).andExpect(status().isForbidden)
    }

    private fun issuedSessionToken(): String {
        val phone = phone()
        val requested = mockMvc.perform(
            post("/api/v1/onboarding/request-access")
                .secure(true)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{"representativeFullName":"Juma Ali","representativePhone":"$phone"}""",
                ),
        ).andReturn()
        val applicationId = readString(requested, "$.applicationId")

        rateLimitStore.reset(RateLimitScope.OTP_SEND_COOLDOWN, phone)
        val code = verification.request(
            RequestVerificationCommand(VerificationPurpose.PHONE_VERIFICATION, phone),
        ).code

        val verified = mockMvc.perform(
            post("/api/v1/onboarding/verify-phone")
                .secure(true)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"applicationId":"$applicationId","code":"$code"}"""),
        ).andReturn()
        return readString(verified, "$.token")
    }

    private fun readString(result: MvcResult, path: String): String =
        com.jayway.jsonpath.JsonPath.read(result.response.contentAsString, path)

    private fun phone(): String = "+2557" + (10_000_000..99_999_999).random().toString()
}
