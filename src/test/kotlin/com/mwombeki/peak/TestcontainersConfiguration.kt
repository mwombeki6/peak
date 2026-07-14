package com.mwombeki.peak

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.grafana.LgtmStackContainer
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    @ConditionalOnProperty(
        prefix = "peak.testcontainers.lgtm",
        name = ["enabled"],
        havingValue = "true",
    )
    fun grafanaLgtmContainer(): LgtmStackContainer {
        return sharedGrafanaLgtmContainer
    }

    @Bean
    @ServiceConnection
    fun postgresContainer(): PostgreSQLContainer {
        return sharedPostgresContainer
    }

    private companion object {
        val sharedGrafanaLgtmContainer: LgtmStackContainer by lazy {
            LgtmStackContainer(DockerImageName.parse("grafana/otel-lgtm:latest"))
        }

        val sharedPostgresContainer: PostgreSQLContainer by lazy {
            PostgreSQLContainer(
                DockerImageName.parse(
                    System.getenv("PEAK_TEST_POSTGRES_IMAGE")
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?: "postgres:18.4",
                ),
            )
        }
    }
}
