package com.mwombeki.peak.reservations.internal

import com.mwombeki.peak.reservations.api.GuestResponse
import java.sql.ResultSet
import java.time.LocalDate
import java.util.UUID
import org.springframework.jdbc.core.RowMapper

internal object GuestResponseRowMapper : RowMapper<GuestResponse> {
    override fun mapRow(rs: ResultSet, rowNum: Int): GuestResponse {
        return GuestResponse(
            id = rs.getObject("id", UUID::class.java),
            tenantId = rs.getObject("tenant_id", UUID::class.java),
            fullName = rs.getString("full_name"),
            firstName = rs.getString("first_name"),
            lastName = rs.getString("last_name"),
            email = rs.getString("email"),
            phonePrimary = rs.getString("phone_primary"),
            dateOfBirth = rs.getObject("date_of_birth", LocalDate::class.java),
            nationality = rs.getString("nationality"),
            vipLevel = rs.getString("vip_level"),
            blacklisted = rs.getBoolean("blacklisted"),
        )
    }
}
