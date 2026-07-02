package com.mwombeki.peak.tenantmanagement.internal

import com.mwombeki.peak.tenantmanagement.api.Tenant
import com.mwombeki.peak.tenantmanagement.api.TenantProfile
import com.mwombeki.peak.tenantmanagement.api.TenantStatus
import java.sql.ResultSet
import java.sql.Timestamp
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper

@Repository
class TenantRepository(
    private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
) {

    fun save(tenant: Tenant) {
        jdbcTemplate.update(
            """
            INSERT INTO tenants (
                id,
                name,
                slug,
                status,
                schema_name,
                country_code,
                currency_code,
                plan_id,
                created_at,
                updated_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            tenant.id,
            tenant.name,
            tenant.slug,
            tenant.status.databaseValue,
            tenant.schemaName,
            tenant.countryCode,
            tenant.currencyCode,
            tenant.planId,
            Timestamp.from(tenant.createdAt),
            Timestamp.from(tenant.updatedAt),
        )
    }

    fun existsBySlug(slug: String): Boolean {
        return jdbcTemplate.queryForObject(
            """
            SELECT EXISTS (
                SELECT 1
                FROM tenants
                WHERE lower(slug) = lower(?)
                  AND deleted_at IS NULL
            )
            """.trimIndent(),
            Boolean::class.java,
            slug,
        ) == true
    }

    fun planExists(planId: UUID): Boolean {
        return jdbcTemplate.queryForObject(
            "SELECT EXISTS (SELECT 1 FROM plans WHERE id = ? AND is_active = true)",
            Boolean::class.java,
            planId,
        ) == true
    }

    fun findById(id: UUID): Tenant? {
        return jdbcTemplate.query(
            """
            SELECT id, name, slug, status, schema_name, country_code, currency_code,
                   plan_id, created_at, updated_at
            FROM tenants
            WHERE id = ?
              AND deleted_at IS NULL
            """.trimIndent(),
            ::mapTenant,
            id,
        ).firstOrNull()
    }

    fun recordLifecycleEvent(
        tenantId: UUID,
        eventType: String,
        reason: String?,
        platformUserId: UUID,
        metadata: Map<String, Any?> = emptyMap(),
    ) {
        jdbcTemplate.update(
            """
            INSERT INTO tenant_lifecycle_events (
                tenant_id,
                event_type,
                status,
                reason,
                metadata,
                performed_by_platform_user_id
            )
            VALUES (?, ?, 'completed', ?, ?::jsonb, ?)
            """.trimIndent(),
            tenantId,
            eventType,
            reason,
            objectMapper.writeValueAsString(metadata),
            platformUserId,
        )
    }

    @Suppress("UNUSED_PARAMETER")
    private fun mapTenant(rs: ResultSet, rowNumber: Int): Tenant {
        return Tenant(
            id = rs.getObject("id", UUID::class.java),
            name = rs.getString("name"),
            slug = rs.getString("slug"),
            status = TenantStatus.fromDatabase(rs.getString("status")),
            schemaName = rs.getString("schema_name"),
            countryCode = rs.getString("country_code"),
            currencyCode = rs.getString("currency_code"),
            planId = rs.getObject("plan_id", UUID::class.java),
            createdAt = rs.getTimestamp("created_at").toInstant(),
            updatedAt = rs.getTimestamp("updated_at").toInstant(),
        )
    }
}

@Repository
class TenantProfileRepository(
    private val jdbcTemplate: JdbcTemplate,
    private val objectMapper: ObjectMapper,
) {

    fun save(profile: TenantProfile) {
        val addressJson = objectMapper.writeValueAsString(profile.registeredAddress)
        jdbcTemplate.update(
            """
            INSERT INTO tenant_profiles (
                tenant_id,
                legal_name,
                trading_name,
                entity_type,
                registration_country_code,
                business_registration_number,
                business_phone,
                business_email,
                registered_address,
                billing_address,
                updated_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?)
            """.trimIndent(),
            profile.tenantId,
            profile.legalName,
            profile.tradingName,
            profile.entityType,
            profile.registrationCountryCode,
            profile.businessRegistrationNumber,
            profile.businessPhone,
            profile.businessEmail,
            addressJson,
            addressJson,
            Timestamp.from(profile.updatedAt),
        )
    }

    fun findByTenantId(tenantId: UUID): TenantProfile? {
        return jdbcTemplate.query(
            """
            SELECT tenant_id,
                   legal_name,
                   trading_name,
                   entity_type,
                   registration_country_code,
                   business_registration_number,
                   business_phone,
                   business_email,
                   registered_address::text AS registered_address,
                   updated_at
            FROM tenant_profiles
            WHERE tenant_id = ?
            """.trimIndent(),
            ::mapProfile,
            tenantId,
        ).firstOrNull()
    }

    @Suppress("UNUSED_PARAMETER")
    private fun mapProfile(rs: ResultSet, rowNumber: Int): TenantProfile {
        return TenantProfile(
            tenantId = rs.getObject("tenant_id", UUID::class.java),
            legalName = rs.getString("legal_name"),
            tradingName = rs.getString("trading_name"),
            entityType = rs.getString("entity_type"),
            businessRegistrationNumber = rs.getString("business_registration_number"),
            businessEmail = rs.getString("business_email"),
            businessPhone = rs.getString("business_phone"),
            registeredAddress = readJsonMap(rs.getString("registered_address")),
            registrationCountryCode = rs.getString("registration_country_code"),
            updatedAt = rs.getTimestamp("updated_at").toInstant(),
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun readJsonMap(rawJson: String): Map<String, Any?> {
        return objectMapper.readValue(rawJson, Map::class.java) as Map<String, Any?>
    }
}
