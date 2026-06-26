package com.mwombeki.peak.shared.config

import com.mwombeki.peak.shared.context.RequestContextProperties
import com.mwombeki.peak.shared.security.HttpSecurityProperties
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.springframework.mock.env.MockEnvironment

class ProductionReadinessValidatorTests {

    @Test
    fun rejectsUnsafeProductionDefaults() {
        val error = assertFailsWith<IllegalStateException> {
            validator(
                environment = prodEnvironment()
                    .withProperty("spring.datasource.username", "peak_migrator")
                    .withProperty("spring.datasource.password", "peak_migrator"),
            ).afterSingletonsInstantiated()
        }

        val message = requireNotNull(error.message)
        assertTrue(message.contains("jwt.enabled must be true"))
        assertTrue(message.contains("allow-header-identity must be false"))
        assertTrue(message.contains("allow-trusted-jwt-identity-claims must be false"))
        assertTrue(message.contains("API/worker runtime must not use the migrator"))
        assertTrue(message.contains("spring.flyway.enabled must be false"))
        assertTrue(message.contains("communication.delivery.local-provider.enabled must be false"))
    }

    @Test
    fun allowsProductionMigrationRuntimeWithMigratorRole() {
        validator(
            environment = prodEnvironment()
                .withProperty("spring.datasource.username", "peak_migrator")
                .withProperty("spring.datasource.password", "not-local-secret")
                .withProperty("springdoc.api-docs.enabled", "false")
                .withProperty("springdoc.swagger-ui.enabled", "false")
                .withProperty("spring.flyway.enabled", "true")
                .withProperty("spring.main.web-application-type", "none")
                .withProperty("peak.communication.delivery.local-provider.enabled", "false"),
            runtimeProperties = PeakRuntimeProperties(PeakRuntimeMode.MIGRATION),
            httpSecurityProperties = secureHttpProperties(),
            requestContextProperties = RequestContextProperties(
                allowHeaderIdentity = false,
                allowTrustedJwtIdentityClaims = false,
            ),
        ).afterSingletonsInstantiated()
    }

    @Test
    fun rejectsWorkerRuntimeWithHttpEnabledOrWorkerDisabled() {
        val error = assertFailsWith<IllegalStateException> {
            validator(
                environment = secureProdEnvironment()
                    .withProperty("spring.datasource.username", "peak_worker")
                    .withProperty("spring.datasource.password", "not-local-secret")
                    .withProperty("spring.flyway.enabled", "false"),
                runtimeProperties = PeakRuntimeProperties(PeakRuntimeMode.WORKER),
                httpSecurityProperties = secureHttpProperties(),
                requestContextProperties = secureRequestContextProperties(),
            ).afterSingletonsInstantiated()
        }

        val message = requireNotNull(error.message)
        assertTrue(message.contains("outbox.worker.enabled must be true for worker runtime"))
        assertTrue(message.contains("web-application-type must be none for worker runtime"))
    }

    @Test
    fun rejectsMigrationRuntimeWithWorkerEnabled() {
        val error = assertFailsWith<IllegalStateException> {
            validator(
                environment = secureProdEnvironment()
                    .withProperty("spring.datasource.username", "peak_migrator")
                    .withProperty("spring.datasource.password", "not-local-secret")
                    .withProperty("spring.flyway.enabled", "true")
                    .withProperty("spring.main.web-application-type", "none")
                    .withProperty("peak.reliability.outbox.worker.enabled", "true"),
                runtimeProperties = PeakRuntimeProperties(PeakRuntimeMode.MIGRATION),
                httpSecurityProperties = secureHttpProperties(),
                requestContextProperties = secureRequestContextProperties(),
            ).afterSingletonsInstantiated()
        }

        assertTrue(
            requireNotNull(error.message)
                .contains("outbox.worker.enabled must be false for migration runtime"),
        )
    }

    @Test
    fun allowsProductionWorkerRuntimeOnlyWhenHttpIsDisabledAndWorkerIsEnabled() {
        validator(
            environment = secureProdEnvironment()
                .withProperty("spring.datasource.username", "peak_worker")
                .withProperty("spring.datasource.password", "not-local-secret")
                .withProperty("spring.flyway.enabled", "false")
                .withProperty("spring.main.web-application-type", "none")
                .withProperty("peak.reliability.outbox.worker.enabled", "true"),
            runtimeProperties = PeakRuntimeProperties(PeakRuntimeMode.WORKER),
            httpSecurityProperties = secureHttpProperties(),
            requestContextProperties = secureRequestContextProperties(),
        ).afterSingletonsInstantiated()
    }

    @Test
    fun allowsProductionApiRuntimeOnlyWhenFlywayIsDisabled() {
        validator(
            environment = prodEnvironment()
                .withProperty("spring.datasource.username", "peak_app")
                .withProperty("spring.datasource.password", "not-local-secret")
                .withProperty("springdoc.api-docs.enabled", "false")
                .withProperty("springdoc.swagger-ui.enabled", "false")
                .withProperty("peak.realtime.websocket.allowed-origins[0]", "https://app.peak.example.com")
                .withProperty("spring.flyway.enabled", "false")
                .withProperty("peak.communication.delivery.local-provider.enabled", "false"),
            runtimeProperties = PeakRuntimeProperties(PeakRuntimeMode.API),
            httpSecurityProperties = secureHttpProperties(),
            requestContextProperties = RequestContextProperties(
                allowHeaderIdentity = false,
                allowTrustedJwtIdentityClaims = false,
            ),
        ).afterSingletonsInstantiated()
    }

    private fun validator(
        environment: MockEnvironment,
        runtimeProperties: PeakRuntimeProperties = PeakRuntimeProperties(),
        httpSecurityProperties: HttpSecurityProperties = HttpSecurityProperties(),
        requestContextProperties: RequestContextProperties = RequestContextProperties(
            allowHeaderIdentity = true,
            allowTrustedJwtIdentityClaims = true,
        ),
    ): ProductionReadinessValidator {
        return ProductionReadinessValidator(
            environment = environment,
            runtimeProperties = runtimeProperties,
            httpSecurityProperties = httpSecurityProperties,
            requestContextProperties = requestContextProperties,
        )
    }

    private fun prodEnvironment(): MockEnvironment {
        return MockEnvironment().also { env ->
            env.setActiveProfiles("prod")
        }
    }

    private fun secureHttpProperties(): HttpSecurityProperties {
        return HttpSecurityProperties(
            jwt = HttpSecurityProperties.Jwt(
                enabled = true,
                issuerUri = "https://auth.peak.example.com/realms/peak",
                audience = "peak-api",
            ),
            cors = HttpSecurityProperties.Cors(
                allowedOrigins = listOf("https://app.peak.example.com"),
            ),
        )
    }

    private fun secureRequestContextProperties(): RequestContextProperties {
        return RequestContextProperties(
            allowHeaderIdentity = false,
            allowTrustedJwtIdentityClaims = false,
        )
    }

    private fun secureProdEnvironment(): MockEnvironment {
        return prodEnvironment()
            .withProperty("springdoc.api-docs.enabled", "false")
            .withProperty("springdoc.swagger-ui.enabled", "false")
            .withProperty("peak.realtime.websocket.allowed-origins[0]", "https://app.peak.example.com")
            .withProperty("peak.communication.delivery.local-provider.enabled", "false")
    }
}
