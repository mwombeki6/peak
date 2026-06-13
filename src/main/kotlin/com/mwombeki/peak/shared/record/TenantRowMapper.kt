package com.mwombeki.peak.shared.record

import org.springframework.jdbc.core.RowMapper
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

/**
 * Enterprise Abstract Row Mapper Core.
 * Provides consistent, optimized extractions for multi-tenant database records.
 */
abstract class TenantRowMapper<T> : RowMapper<T> {

    /**
     * Helper tool to safely pull a nullable UUID out of a PostgreSQL database column.
     */
    protected fun getUUID(rs: ResultSet, columnName: String): UUID? {
        val value = rs.getString(columnName) ?: return null
        return try {
            UUID.fromString(value)
        } catch (e: IllegalArgumentException) {
            throw DataMappingException("Invalid UUID format found in column [$columnName]: $value", e)
        }
    }

    /**
     * Helper tool to safely pull a mandatory UUID out of a PostgreSQL database column.
     */
    protected fun getRequiredUUID(rs: ResultSet, columnName: String): UUID {
        return getUUID(rs, columnName)
            ?: throw DataMappingException("Data Integrity Violation: Required column [$columnName] is missing or null.")
    }

    /**
     * Helper tool to safely convert a PostgreSQL Timestamp with Timezone into a Kotlin Instant.
     */
    protected fun getInstant(rs: ResultSet, columnName: String): Instant? {
        val timestamp = rs.getTimestamp(columnName) ?: return null
        return timestamp.toInstant()
    }

    /**
     * Helper tool to safely convert a mandatory PostgreSQL Timestamp into a Kotlin Instant.
     */
    protected fun getRequiredInstant(rs: ResultSet, columnName: String): Instant {
        return getInstant(rs, columnName)
            ?: throw DataMappingException("Data Integrity Violation: Required timestamp column [$columnName] is missing.")
    }
}