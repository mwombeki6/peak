package com.mwombeki.peak.verification.internal.web

import com.mwombeki.peak.TestcontainersConfiguration
import com.mwombeki.peak.shared.context.PeakRequestHeaders
import com.mwombeki.peak.verification.api.RequestVerificationCommand
import com.mwombeki.peak.verification.api.VerificationPurpose
import com.mwombeki.peak.verification.internal.VerificationService
import java.util.UUID
import kotlin.test.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.testcontainers.junit.jupiter.Testcontainers

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class VerificationControllerIntegrationTests {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var jdbcTemplate: JdbcTemplate
    @Autowired private lateinit var verification: VerificationService

    @Test
    fun anUnauthenticatedCallerCanRequestOverHttp() {
        val destination = phone()

        mockMvc.perform(
            post("/api/v1/verifications")
                .secure(true)
                .contentType(MediaType.APPLICATION_JSON)
                .header(PeakRequestHeaders.CORRELATION_ID, correlationId())
                .content("""{"purpose":"PHONE_VERIFICATION","destination":"$destination"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").isString)
            .andExpect(jsonPath("$.expiresAt").isString)
    }

    @Test
    fun confirmOverHttpAcceptsTheGenuineCodeAndRejectsAnother() {
        val destination = phone()
        // The HTTP layer never returns the plaintext code — that's the point, it only ever
        // travels to the destination (SMS, once wired). Reading it here via the internal
        // service (not the HTTP boundary) is how the test knows what a real recipient would
        // have received, so it can prove the HTTP confirm endpoint accepts the genuine code.
        val code = verification.request(
            RequestVerificationCommand(VerificationPurpose.PHONE_VERIFICATION, destination),
        ).code

        mockMvc.perform(
            post("/api/v1/verifications/confirm")
                .secure(true)
                .contentType(MediaType.APPLICATION_JSON)
                .header(PeakRequestHeaders.CORRELATION_ID, correlationId())
                .content("""{"purpose":"PHONE_VERIFICATION","destination":"$destination","code":"000000"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.verified").value(false))

        mockMvc.perform(
            post("/api/v1/verifications/confirm")
                .secure(true)
                .contentType(MediaType.APPLICATION_JSON)
                .header(PeakRequestHeaders.CORRELATION_ID, correlationId())
                .content("""{"purpose":"PHONE_VERIFICATION","destination":"$destination","code":"$code"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.verified").value(true))
    }

    @Test
    fun sendingTooOftenIsRejectedAs429() {
        val destination = phone()
        val body = """{"purpose":"PHONE_VERIFICATION","destination":"$destination"}"""

        mockMvc.perform(
            post("/api/v1/verifications")
                .secure(true)
                .contentType(MediaType.APPLICATION_JSON)
                .header(PeakRequestHeaders.CORRELATION_ID, correlationId())
                .content(body),
        )
            .andExpect(status().isOk)

        mockMvc.perform(
            post("/api/v1/verifications")
                .secure(true)
                .contentType(MediaType.APPLICATION_JSON)
                .header(PeakRequestHeaders.CORRELATION_ID, correlationId())
                .content(body),
        )
            .andExpect(status().isTooManyRequests)
    }

    private fun phone(): String = "+2557" + (10_000_000..99_999_999).random().toString()

    private fun correlationId(): String = "corr-verify-" + UUID.randomUUID()
}
