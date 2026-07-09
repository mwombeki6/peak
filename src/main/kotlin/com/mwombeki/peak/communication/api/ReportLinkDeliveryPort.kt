package com.mwombeki.peak.communication.api

import java.time.Instant
import java.util.UUID
import org.springframework.modulith.NamedInterface

@NamedInterface("api")
interface ReportLinkDeliveryPort {
    fun deliver(command: DeliverReportLinkCommand): ReportLinkDeliveryResult
}

@NamedInterface("api")
data class DeliverReportLinkCommand(
    val tenantId: UUID,
    val propertyId: UUID,
    val reportDeliveryId: UUID,
    val contactId: UUID,
    val contactChannelId: UUID,
    val reportCode: String,
    val businessDate: java.time.LocalDate,
    val signedUrl: String,
    val expiresAt: Instant,
)

@NamedInterface("api")
data class ReportLinkDeliveryResult(
    val channelType: String,
    val destinationMasked: String,
    val providerCode: String,
    val providerMessageId: String,
)
