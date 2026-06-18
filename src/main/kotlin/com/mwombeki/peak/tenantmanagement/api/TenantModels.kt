package com.mwombeki.peak.tenantmanagement.api

import java.time.Instant
import java.util.UUID

// --- CORE ENTITIES ---
data class Tenant(
    val id: UUID = UUID.randomUUID(),
    val name: String,
    val uniqueSlug: String, //e.g mbeya-peak.main
    val status: TenantStatus = TenantStatus.PENDING_VERIFICATION,
    val createdAt: Instant = Instant.now(),
    val updatedAt: Instant = Instant.now(),

)

data class TenantProfile(
    val id: UUID = UUID.randomUUID(),
    val tenantId: UUID,
    val businessRegistrationNumber: String,
    val primaryEmail: String,
    val primaryPhone: String,
    val physicalAddress: String,
    val country: String,
    val city: String,
    val timezone: String = "Africa/Dar es Salaam",
    val currency: String = "TZS",
    val updatedAt: Instant = Instant.now(),
)

enum class TenantStatus {
    PENDING_VERIFICATION,
    ACTIVE,
    SUSPENDED,
    DEACTIVATED
}

// --- DATA TRANSFER OBJECTS (DTOs) ---
data class TenantRegisterRequest(
    val name: String,
    val uniqueSlug: String,
    val businessRegistrationNumber: String,
    val primaryEmail: String,
    val primaryPhone: String,
    val physicalAddress: String,
    val city: String,
    val country: String,

)

data class TenantResponse(
    val id: UUID,
    val name: String,
    val uniqueSlug: String,
    val status: TenantStatus,
    val primaryEmail: String
)