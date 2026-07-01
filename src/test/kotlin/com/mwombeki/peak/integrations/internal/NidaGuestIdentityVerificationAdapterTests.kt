package com.mwombeki.peak.integrations.internal

import com.mwombeki.peak.reservations.api.GuestIdentityDocumentType
import com.mwombeki.peak.reservations.api.GuestIdentityProviderCommand
import com.mwombeki.peak.reservations.api.GuestIdentityProviderResult
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import org.springframework.mock.env.MockEnvironment

class NidaGuestIdentityVerificationAdapterTests {

    @Test
    fun disabledModeReportsProviderUnavailable() {
        val adapter = NidaGuestIdentityVerificationAdapter(
            NidaIntegrationProperties(mode = NidaMode.DISABLED),
        )

        assertIs<GuestIdentityProviderResult.Unavailable>(adapter.verify(command("12345678")))
    }

    @Test
    fun simulatorIsDeterministicForSuccessAndRejection() {
        val adapter = NidaGuestIdentityVerificationAdapter(
            NidaIntegrationProperties(mode = NidaMode.SIMULATOR),
        )

        assertIs<GuestIdentityProviderResult.Verified>(adapter.verify(command("12345678")))
        assertIs<GuestIdentityProviderResult.Rejected>(adapter.verify(command("12340000")))
    }

    @Test
    fun productionRejectsSimulatorAndIncompleteCigModes() {
        val environment = MockEnvironment().apply { setActiveProfiles("prod") }

        assertFailsWith<IllegalArgumentException> {
            NidaIntegrationReadinessValidator(
                environment,
                NidaIntegrationProperties(mode = NidaMode.SIMULATOR),
            ).afterSingletonsInstantiated()
        }
        assertFailsWith<IllegalStateException> {
            NidaIntegrationReadinessValidator(
                environment,
                NidaIntegrationProperties(mode = NidaMode.CIG),
            ).afterSingletonsInstantiated()
        }
    }

    private fun command(number: String): GuestIdentityProviderCommand {
        return GuestIdentityProviderCommand(
            documentType = GuestIdentityDocumentType.NIDA,
            documentNumber = number,
            fullName = "Synthetic Guest",
            dateOfBirth = LocalDate.of(1990, 1, 1),
            nationality = "TZ",
            correlationId = "test-correlation",
        )
    }
}
