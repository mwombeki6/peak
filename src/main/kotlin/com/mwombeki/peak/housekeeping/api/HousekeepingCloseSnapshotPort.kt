package com.mwombeki.peak.housekeeping.api

import java.time.LocalDate
import java.util.UUID
import org.springframework.modulith.NamedInterface

@NamedInterface("api")
interface HousekeepingCloseSnapshotPort {
    fun closeSnapshotSummary(
        tenantId: UUID,
        propertyId: UUID,
        businessDate: LocalDate,
    ): HousekeepingCloseSnapshotSummary
}

@NamedInterface("api")
data class HousekeepingCloseSnapshotSummary(
    val states: Map<String, Int>,
    val openTasks: Int,
)
