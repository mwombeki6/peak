package com.mwombeki.peak.communication.internal

import com.mwombeki.peak.TestcontainersConfiguration
import com.mwombeki.peak.communication.api.GuestNotificationCommand
import com.mwombeki.peak.communication.api.GuestNotificationPort
import com.mwombeki.peak.communication.api.GuestNotificationPurposes
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertNull
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.testcontainers.junit.jupiter.Testcontainers

@Import(TestcontainersConfiguration::class)
@SpringBootTest(
    properties = [
        "peak.security.request-context.allow-header-identity=true",
    ],
)
@Testcontainers(disabledWithoutDocker = true)
class GuestWhatsAppUnroutedIntegrationTests {

    @Autowired
    private lateinit var guestNotificationPort: GuestNotificationPort

    @Test
    fun anUnroutedWhatsAppChannelIsSilenceNotAFailedHospitalityCommand() {
        val receipt = guestNotificationPort.notifyIfReachable(
            GuestNotificationCommand(
                tenantId = UUID.randomUUID(),
                propertyId = UUID.randomUUID(),
                guestId = UUID.randomUUID(),
                purpose = GuestNotificationPurposes.RESERVATION,
                aggregateType = "reservations",
                aggregateId = UUID.randomUUID(),
            ),
        )
        assertNull(receipt)
    }
}
