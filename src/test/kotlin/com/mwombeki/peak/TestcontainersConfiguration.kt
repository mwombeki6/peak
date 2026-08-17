package com.mwombeki.peak

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.containers.MinIOContainer
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

    /**
     * Opt-in per test class (`peak.testcontainers.minio.enabled=true`) rather than universal
     * like Postgres — most of this suite has nothing to do with document storage, and starting
     * a MinIO container for every test would tax the whole run for the sake of a few.
     */
    @Bean
    @ConditionalOnProperty(
        prefix = "peak.testcontainers.minio",
        name = ["enabled"],
        havingValue = "true",
    )
    fun minioContainer(): MinIOContainer {
        return sharedMinioContainer
    }

    companion object {
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

        /**
         * Exposed (not private) so a test class that needs real KYC document storage can wire
         * `peak.verification.storage.*` to it via a static `@DynamicPropertySource` — the
         * container's host port is only known once it starts, so those properties can't be
         * static config, and `@DynamicPropertySource` must live with the test class rather than
         * here to be picked up at all.
         */
        val sharedMinioContainer: MinIOContainer by lazy {
            MinIOContainer(
                DockerImageName.parse(
                    System.getenv("PEAK_TEST_MINIO_IMAGE")
                        ?.trim()
                        ?.takeIf { it.isNotEmpty() }
                        ?: "minio/minio:RELEASE.2025-04-22T22-12-26Z",
                ),
            )
        }
    }
}
