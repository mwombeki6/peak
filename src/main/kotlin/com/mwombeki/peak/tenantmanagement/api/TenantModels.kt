package com.mwombeki.peak.tenantmanagement.api

import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Pattern
import java.time.Instant
import java.util.UUID

data class Tenant(
    val id: UUID,
    val name: String,
    val slug: String,
    val status: TenantStatus,
    val schemaName: String,
    val planId: UUID,
    val countryCode: String?,
    val currencyCode: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class TenantProfile(
    val tenantId: UUID,
    val legalName: String,
    val tradingName: String?,
    val entityType: String,
    val businessRegistrationNumber: String?,
    val businessEmail: String,
    val businessPhone: String,
    val registeredAddress: Map<String, Any?>,
    val registrationCountryCode: String,
    val updatedAt: Instant,
)

enum class TenantStatus(val databaseValue: String) {
    TRIAL("trial"),
    ACTIVE("active"),
    SUSPENDED("suspended"),
    FROZEN("frozen"),
    ARCHIVED("archived"),
    TERMINATED("terminated"),
    CANCELLED("cancelled");

    companion object {
        fun fromDatabase(value: String): TenantStatus {
            return entries.firstOrNull { it.databaseValue == value }
                ?: throw IllegalArgumentException("Unsupported tenant status: $value")
        }
    }
}

data class TenantRegisterRequest(
    @field:NotBlank
    val name: String,
    @field:NotBlank
    @field:Pattern(regexp = "^[a-z0-9][a-z0-9-]{1,98}[a-z0-9]$")
    val slug: String,
    @field:NotNull
    val planId: UUID,
    @field:NotBlank
    val legalName: String,
    val tradingName: String? = null,
    @field:NotBlank
    val entityType: String = "limited_company",
    val businessRegistrationNumber: String? = null,
    @field:NotBlank
    @field:Email
    val businessEmail: String,
    @field:NotBlank
    @field:Pattern(regexp = "^\\+[1-9][0-9]{7,14}$")
    val businessPhone: String,
    val registeredAddress: Map<String, Any?> = emptyMap(),
    @field:Pattern(regexp = "^[A-Z]{2}$")
    val countryCode: String = "TZ",
    @field:Pattern(regexp = "^[A-Z]{3}$")
    val currencyCode: String = "TZS",
)

data class TenantResponse(
    val id: UUID,
    val name: String,
    val slug: String,
    val status: TenantStatus,
    val planId: UUID,
    val businessEmail: String,
)
