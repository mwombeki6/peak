package com.mwombeki.peak.pos.api

import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID
import org.springframework.modulith.NamedInterface

@NamedInterface("api")
interface PosStatusPort {
    fun nightAuditSummary(tenantId: UUID, propertyId: UUID): PosNightAuditSummary
    fun closeSnapshotSummary(
        tenantId: UUID,
        propertyId: UUID,
        businessDate: LocalDate,
    ): PosCloseSnapshotSummary
}

@NamedInterface("api")
data class PosNightAuditSummary(
    val openOrUnapprovedSessions: Int,
)

@NamedInterface("api")
data class PosCloseSnapshotSummary(
    val closedUnsettledOrders: Int,
    val revenue: BigDecimal,
)
