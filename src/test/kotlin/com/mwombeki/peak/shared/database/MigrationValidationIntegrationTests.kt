package com.mwombeki.peak.shared.database

import com.mwombeki.peak.TestcontainersConfiguration
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.testcontainers.junit.jupiter.Testcontainers

@SpringBootTest
@Import(TestcontainersConfiguration::class)
@Testcontainers(disabledWithoutDocker = true)
class MigrationValidationIntegrationTests @Autowired constructor(
    private val flyway: Flyway,
) {
    @Test
    fun `validates the complete migration chain at the expected version`() {
        flyway.validate()

        assertEquals("91", flyway.info().current().version.version)
    }
}
