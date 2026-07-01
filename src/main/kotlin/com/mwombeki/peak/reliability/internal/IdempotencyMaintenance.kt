package com.mwombeki.peak.reliability.internal

import org.slf4j.LoggerFactory
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Component

@Component
class IdempotencyMaintenance(
    private val jdbcTemplate: JdbcTemplate,
) {
    fun run() {
        val result = jdbcTemplate.queryForMap(
            "SELECT expired_count, deleted_count FROM maintain_idempotency_keys()",
        )
        val expired = (result["expired_count"] as Number).toLong()
        val deleted = (result["deleted_count"] as Number).toLong()
        if (expired > 0 || deleted > 0) {
            logger.info(
                "Idempotency maintenance expired={} deleted={}",
                expired,
                deleted,
            )
        }
    }

    private companion object {
        private val logger = LoggerFactory.getLogger(IdempotencyMaintenance::class.java)
    }
}
