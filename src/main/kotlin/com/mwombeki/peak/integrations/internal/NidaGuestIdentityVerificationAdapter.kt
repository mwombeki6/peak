package com.mwombeki.peak.integrations.internal

import com.mwombeki.peak.reservations.api.GuestIdentityDocumentType
import com.mwombeki.peak.reservations.api.GuestIdentityProviderCommand
import com.mwombeki.peak.reservations.api.GuestIdentityProviderResult
import com.mwombeki.peak.reservations.api.GuestIdentityVerificationProvider
import java.util.UUID
import org.springframework.stereotype.Component

@Component
class NidaGuestIdentityVerificationAdapter(
    private val properties: NidaIntegrationProperties,
) : GuestIdentityVerificationProvider {

    override val providerId: String = "nida"

    override fun verify(command: GuestIdentityProviderCommand): GuestIdentityProviderResult {
        require(command.documentType == GuestIdentityDocumentType.NIDA) {
            "NIDA online verification only supports NIDA documents"
        }
        return when (properties.mode) {
            NidaMode.DISABLED -> GuestIdentityProviderResult.Unavailable("NIDA_NOT_CONFIGURED")
            NidaMode.CIG -> GuestIdentityProviderResult.Unavailable("NIDA_CIG_CONTRACT_PENDING")
            NidaMode.SIMULATOR -> simulate(command)
        }
    }

    private fun simulate(command: GuestIdentityProviderCommand): GuestIdentityProviderResult {
        val normalized = command.documentNumber.filter(Char::isDigit)
        return if (normalized.endsWith("0000")) {
            GuestIdentityProviderResult.Rejected(
                failureCode = "IDENTITY_NOT_CONFIRMED",
                providerReference = "SIM-${UUID.randomUUID()}",
            )
        } else {
            GuestIdentityProviderResult.Verified(
                providerReference = "SIM-${UUID.randomUUID()}",
            )
        }
    }
}
