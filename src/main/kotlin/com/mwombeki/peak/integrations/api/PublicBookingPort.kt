package com.mwombeki.peak.integrations.api

import java.util.UUID

interface PublicBookingPort {
    fun createPublicSession(
        propertyId: UUID,
        request: PublicBookingSessionRequest,
    ): PublicBookingSessionResponse
}
