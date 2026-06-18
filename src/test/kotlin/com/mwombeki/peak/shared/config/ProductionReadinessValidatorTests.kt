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
        assertTrue(message.contains("API/worker runtime must not use the migrator"))
    }

    @Test
    fun allowsProductionMigrationRuntimeWithMigratorRole() {
        validator(
            environment = prodEnvironment()
                .withProperty("spring.datasource.username", "peak_migrator")
                .withProperty("spring.datasource.password", "not-local-secret")
                .withProperty("springdoc.api-docs.enabled", "false")
                .withProperty("springdoc.swagger-ui.enabled", "false"),
            runtimeProperties = PeakRuntimeProperties(PeakRuntimeMode.MIGRATION),
            httpSecurityProperties = secureHttpProperties(),
            requestContextProperties = RequestContextProperties(
                allowHeaderIdentity = false,
            ),
        ).afterSingletonsInstantiated()
    }

    private fun validator(
        environment: MockEnvironment,
        runtimeProperties: PeakRuntimeProperties = PeakRuntimeProperties(),
        httpSecurityProperties: HttpSecurityProperties = HttpSecurityProperties(),
        requestContextProperties: RequestContextProperties = RequestContextProperties(
            allowHeaderIdentity = true,
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
}
