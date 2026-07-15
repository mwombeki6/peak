package com.mwombeki.peak.usermanagement.api

import java.util.UUID
import org.springframework.modulith.NamedInterface

@NamedInterface("api")
interface PropertyStaffDirectoryPort {
    fun isActivePropertyStaff(
        tenantId: UUID,
        propertyId: UUID,
        userId: UUID,
    ): Boolean
}
