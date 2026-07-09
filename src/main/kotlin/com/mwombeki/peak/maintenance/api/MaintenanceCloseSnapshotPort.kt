package com.mwombeki.peak.maintenance.api

import java.util.UUID
import org.springframework.modulith.NamedInterface

@NamedInterface("api")
interface MaintenanceCloseSnapshotPort {
    fun closeSnapshotSummary(
        tenantId: UUID,
        propertyId: UUID,
    ): MaintenanceCloseSnapshotSummary
}

@NamedInterface("api")
data class MaintenanceCloseSnapshotSummary(
    val outOfOrderRooms: Int,
    val outOfServiceRooms: Int,
    val openExceptions: Int,
)
