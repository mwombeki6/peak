package com.mwombeki.peak.integrations.api

import java.util.UUID

interface PublicBookingPort {
    fun createPublicSession(request: PublicBookingSessionRequest): PublicBookingSessionResponse
}