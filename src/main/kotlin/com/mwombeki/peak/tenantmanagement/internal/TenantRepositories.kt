package com.mwombeki.peak.tenantmanagement.internal

import com.mwombeki.peak.tenantmanagement.api.Tenant
import com.mwombeki.peak.tenantmanagement.api.TenantProfile
import com.mwombeki.peak.tenantmanagement.api.TenantStatus
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
class TenantRepository(private val jdbcTemplate: JdbcTemplate) {

    fun save(tenant: Tenant) {
        val sql = """
            INSERT INTO tenants (id, name, unique_slug, status, created_at, updated_at)
            VALUES (?, ?, ?, ?, ?, ?)
        """.trimIndent()

        jdbcTemplate.update(
            sql,
            tenant.id,
            tenant.name,
            tenant.uniqueSlug,
            tenant.status.name,
            tenant.createdAt,
            tenant.updatedAt
        )
    }

    fun findBySlug(slug: String): Tenant? {
        val sql = "SELECT id, name, unique_slug, status, created_at, updated_at FROM tenants WHERE unique_slug = ?"

        return jdbcTemplate.query(sql, arrayOf(slug)) { rs, _ ->
            Tenant(
                id = UUID.fromString(rs.getString("id")),
                name = rs.getString("name"),
                uniqueSlug = rs.getString("unique_slug"),
                status = TenantStatus.valueOf(rs.getString("status")),
                createdAt = rs.getTimestamp("created_at").toInstant(),
                updatedAt = rs.getTimestamp("updated_at").toInstant()
            )
        }.firstOrNull()
    }

    fun findById(id: UUID): Tenant? {
        val sql = "SELECT id, name, unique_slug, status, created_at, updated_at FROM tenants WHERE id = ?"

        return jdbcTemplate.query(sql, arrayOf(id)) { rs, _ ->
            Tenant(
                id = UUID.fromString(rs.getString("id")),
                name = rs.getString("name"),
                uniqueSlug = rs.getString("unique_slug"),
                status = TenantStatus.valueOf(rs.getString("status")),
                createdAt = rs.getTimestamp("created_at").toInstant(),
                updatedAt = rs.getTimestamp("updated_at").toInstant()
            )
        }.firstOrNull()
    }

    fun updateStatus(id: UUID, status: TenantStatus) {
        val sql = "UPDATE tenants SET status = ?, updated_at = NOW() WHERE id = ?"
        jdbcTemplate.update(sql, status.name, id)
    }
}

@Repository
class TenantProfileRepository(private val jdbcTemplate: JdbcTemplate) {

    fun save(profile: TenantProfile) {
        val sql = """
            INSERT INTO tenant_profiles (id, tenant_id, business_registration_number, primary_email, primary_phone, physical_address, country, city, timezone, currency, updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()

        jdbcTemplate.update(
            sql,
            profile.id,
            profile.tenantId,
            profile.businessRegistrationNumber,
            profile.primaryEmail,
            profile.primaryPhone,
            profile.physicalAddress,
            profile.country,
            profile.city,
            profile.timezone,
            profile.currency,
            profile.updatedAt
        )
    }

    fun findByTenantId(tenantId: UUID): TenantProfile? {
        val sql = "SELECT id, tenant_id, business_registration_number, primary_email, primary_phone, physical_address, country, city, timezone, currency FROM tenant_profiles WHERE tenant_id = ?"

        return jdbcTemplate.query(sql, arrayOf(tenantId)) { rs, _ ->
            TenantProfile(
                id = UUID.fromString(rs.getString("id")),
                tenantId = UUID.fromString(rs.getString("tenant_id")),
                businessRegistrationNumber = rs.getString("business_registration_number"),
                primaryEmail = rs.getString("primary_email"),
                primaryPhone = rs.getString("primary_phone"),
                physicalAddress = rs.getString("physical_address"),
                country = rs.getString("country"),
                city = rs.getString("city"),
                timezone = rs.getString("timezone"),
                currency = rs.getString("currency"),
                updatedAt = java.time.Instant.now() // temporary placeholder or read from DB if added
            )
        }.firstOrNull()
    }
}