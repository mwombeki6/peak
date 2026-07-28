package com.mwombeki.peak.usermanagement.api

import java.util.UUID

interface SessionContextPort {
    fun hospitalitySession(): HospitalitySessionResponse

    fun platformSession(): PlatformSessionResponse
}

data class HospitalitySessionResponse(
    val identityMode: String,
    val tenantId: UUID,
    val userId: UUID,
    val fullName: String?,
    val email: String,
    val languagePreference: String?,
    val tenantRoleCodes: List<String>,
    val tenantPermissionCodes: List<String>,
    val enabledTenantModules: List<String>,
    val properties: List<HospitalityPropertyAccess>,
)

data class HospitalityPropertyAccess(
    val propertyId: UUID,
    val name: String,
    val code: String?,
    val status: String,
    val roleNames: List<String>,
    val permissionCodes: List<String>,
    val enabledModules: List<String>,
)

data class PlatformSessionResponse(
    val identityMode: String,
    val platformUserId: UUID,
    val fullName: String,
    val email: String,
    val permissionCodes: List<String>,
)
