package com.mwombeki.peak.shared.context

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.springframework.jdbc.core.JdbcTemplate

class DatabaseSessionContextTests {

    @Test
    fun rejectsBindingOutsideTransaction() {
        val context = DatabaseSessionContext(
            JdbcTemplate(),
        )

        val error = assertFailsWith<IllegalArgumentException> {
            context.bind(
                RequestIdentity.Platform(
                    platformUserId = UUID.randomUUID(),
                    correlationId = "corr",
                ),
            )
        }

        assertEquals(
            "Database session context must be bound inside an active transaction",
            error.message,
        )
    }
}
