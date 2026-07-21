package com.mwombeki.peak.inventory.internal

import java.sql.SQLException
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import org.springframework.jdbc.UncategorizedSQLException

class InventoryDatabaseConflictClassifierTests {
    @Test
    fun `recognizes only owned inventory trigger conflicts`() {
        val conflict = UncategorizedSQLException(
            "stock movement",
            "INSERT INTO stock_movements",
            SQLException(
                "ERROR: Stock movement would make item item-id negative at location location-id",
                "P0001",
            ),
        )

        assertTrue(InventoryDatabaseConflictClassifier.isConflict(conflict))
    }

    @Test
    fun `does not mask unrelated raised database failures`() {
        val unrelated = UncategorizedSQLException(
            "stock movement",
            "INSERT INTO stock_movements",
            SQLException("ERROR: unexpected function failure", "P0001"),
        )

        assertFalse(InventoryDatabaseConflictClassifier.isConflict(unrelated))
    }

    @Test
    fun `does not mask infrastructure failures`() {
        val unavailable = UncategorizedSQLException(
            "stock movement",
            "INSERT INTO stock_movements",
            SQLException("Connection terminated", "08006"),
        )

        assertFalse(InventoryDatabaseConflictClassifier.isConflict(unavailable))
    }
}
