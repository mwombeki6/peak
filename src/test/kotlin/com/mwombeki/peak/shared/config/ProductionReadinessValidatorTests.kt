package com.mwombeki.peak.shared.config

import com.mwombeki.peak.shared.context.RequestContextProperties
import com.mwombeki.peak.shared.security.HttpSecurityProperties
import com.mwombeki.peak.shared.security.StepUpProperties
import com.mwombeki.peak.shared.secrets.SecretReferenceResolver
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.springframework.mock.env.MockEnvironment

class ProductionReadinessValidatorTests {

    /**
     * Step-up enforcement used to be implied by `allow-header-identity`, which production
     * already refuses. Separating them would have quietly removed that guarantee unless the
     * new flag was refused on its own terms, so this asserts it directly.
     */
    @Test
    fun rejectsAssumingCeremonyEvidenceIsUnavailableInProduction() {
        val error = assertFailsWith<IllegalStateException> {
            validator(
                environment = prodEnvironment()
                    .withProperty("spring.datasource.username", "peak_migrator")
                    .withProperty("spring.datasource.password", "peak_migrator"),
                stepUpProperties = StepUpProperties(assumeUnavailable = true),
            ).afterSingletonsInstantiated()
        }

        assertTrue(
            requireNotNull(error.message).contains("step-up.assume-unavailable must be false"),
            "production must refuse a runtime that skips privileged ceremony checks: " +
                error.message,
        )
    }

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
        assertTrue(message.contains("server.forward-headers-strategy must be native"))
    }

    @Test
    fun allowsProductionMigrationRuntimeWithMigratorRole() {
        validator(
            environment = prodEnvironment()
                .withProperty("peak.security.runtime-route-boundary.enabled", "true")
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
    fun rejectsInsecureCommunicationGatewayForProductionWorker() {
        val error = assertFailsWith<IllegalStateException> {
            validator(
                environment = secureProdEnvironment()
                    .withProperty("spring.datasource.username", "peak_worker")
                    .withProperty("spring.datasource.password", "not-local-secret")
                    .withProperty("spring.flyway.enabled", "false")
                    .withProperty("spring.main.web-application-type", "none")
                    .withProperty("peak.reliability.outbox.worker.enabled", "true")
                    .withProperty(
                        "peak.communication.delivery.http-provider.base-url",
                        "http://communications.internal",
                    ),
                runtimeProperties = PeakRuntimeProperties(PeakRuntimeMode.WORKER),
                httpSecurityProperties = secureHttpProperties(),
                requestContextProperties = secureRequestContextProperties(),
            ).afterSingletonsInstantiated()
        }

        assertTrue(
            requireNotNull(error.message)
                .contains("communication.delivery.http-provider.base-url must use https"),
        )
    }

    @Test
    fun allowsProductionApiRuntimeOnlyWhenFlywayIsDisabled() {
        validator(
            environment = secureProdEnvironment()
                .withProperty("spring.datasource.username", "peak_app")
                .withProperty("spring.datasource.password", "not-local-secret")
                .withProperty("spring.flyway.enabled", "false"),
            runtimeProperties = PeakRuntimeProperties(PeakRuntimeMode.API),
            httpSecurityProperties = secureHttpProperties(),
            requestContextProperties = RequestContextProperties(
                allowHeaderIdentity = false,
                allowTrustedJwtIdentityClaims = false,
            ),
        ).afterSingletonsInstantiated()
    }

    @Test
    fun allowsOnlyIsolatedDatabaseLoginForPlatformRuntime() {
        validator(
            environment = secureProdEnvironment()
                .withProperty("spring.datasource.username", "peak_platform")
                .withProperty("spring.datasource.password", "not-local-platform-secret")
                .withProperty("spring.flyway.enabled", "false"),
            runtimeProperties = PeakRuntimeProperties(PeakRuntimeMode.PLATFORM),
            httpSecurityProperties = secureHttpProperties(),
            requestContextProperties = secureRequestContextProperties(),
        ).afterSingletonsInstantiated()

        val error = assertFailsWith<IllegalStateException> {
            validator(
                environment = secureProdEnvironment()
                    .withProperty("spring.datasource.username", "peak_app")
                    .withProperty("spring.datasource.password", "not-local-platform-secret")
                    .withProperty("spring.flyway.enabled", "false"),
                runtimeProperties = PeakRuntimeProperties(PeakRuntimeMode.PLATFORM),
                httpSecurityProperties = secureHttpProperties(),
                requestContextProperties = secureRequestContextProperties(),
            ).afterSingletonsInstantiated()
        }
        assertTrue(requireNotNull(error.message).contains("isolated peak_platform"))
    }

    @Test
    fun rejectsInvitationTokenExposureInProduction() {
        val error = assertFailsWith<IllegalStateException> {
            validator(
                environment = secureProdEnvironment()
                    .withProperty("spring.datasource.username", "peak_app")
                    .withProperty("spring.datasource.password", "not-local-secret")
                    .withProperty("spring.flyway.enabled", "false")
                    .withProperty(
                        "peak.usermanagement.invitation.expose-token-in-response",
                        "true",
                    ),
                runtimeProperties = PeakRuntimeProperties(PeakRuntimeMode.API),
                httpSecurityProperties = secureHttpProperties(),
                requestContextProperties = secureRequestContextProperties(),
            ).afterSingletonsInstantiated()
        }

        assertTrue(
            requireNotNull(error.message).contains(
                "peak.usermanagement.invitation.expose-token-in-response must be false in prod",
            ),
        )
    }

    @Test
    fun rejectsDisabledRuntimeRouteBoundaryInProduction() {
        val error = assertFailsWith<IllegalStateException> {
            validator(
                environment = secureProdEnvironment()
                    .withProperty("spring.datasource.username", "peak_app")
                    .withProperty("spring.datasource.password", "not-local-secret")
                    .withProperty("spring.flyway.enabled", "false")
                    .withProperty("peak.security.runtime-route-boundary.enabled", "false"),
                runtimeProperties = PeakRuntimeProperties(PeakRuntimeMode.API),
                httpSecurityProperties = secureHttpProperties(),
                requestContextProperties = secureRequestContextProperties(),
            ).afterSingletonsInstantiated()
        }

        assertTrue(
            requireNotNull(error.message)
                .contains("runtime-route-boundary.enabled must be true in prod"),
        )
    }

    @Test
    fun rejectsUnsafeOutboundProviderHostAllowlist() {
        val error = assertFailsWith<IllegalStateException> {
            validator(
                environment = secureProdEnvironment()
                    .withProperty("spring.datasource.username", "peak_app")
                    .withProperty("spring.datasource.password", "not-local-secret")
                    .withProperty("spring.flyway.enabled", "false")
                    .withProperty(
                        "peak.security.outbound.allowed-provider-hosts",
                        "payments.example.com,localhost",
                    ),
                runtimeProperties = PeakRuntimeProperties(PeakRuntimeMode.API),
                httpSecurityProperties = secureHttpProperties(),
                requestContextProperties = secureRequestContextProperties(),
            ).afterSingletonsInstantiated()
        }

        assertTrue(
            requireNotNull(error.message)
                .contains("allowed-provider-hosts must contain exact external DNS hostnames"),
        )
    }

    @Test
    fun allowsOneShotProductionBootstrapWithTwoCustodians() {
        validator(
            environment = bootstrapProdEnvironment(),
            runtimeProperties = PeakRuntimeProperties(PeakRuntimeMode.BOOTSTRAP),
            httpSecurityProperties = secureHttpProperties(),
            requestContextProperties = secureRequestContextProperties(),
        ).afterSingletonsInstantiated()
    }

    /**
     * Production provisions two Platform Emergency Administrator custodians so
     * dual control holds from the first minute. A single-custodian production
     * bootstrap would leave one account able to appoint another root
     * unilaterally, so it must fail startup rather than proceed.
     */
    @Test
    fun rejectsProductionBootstrapWithOnlyOneCustodian() {
        val error = assertFailsWith<IllegalStateException> {
            validator(
                environment = bootstrapProdEnvironment(withSecondCustodian = false),
                runtimeProperties = PeakRuntimeProperties(PeakRuntimeMode.BOOTSTRAP),
                httpSecurityProperties = secureHttpProperties(),
                requestContextProperties = secureRequestContextProperties(),
            ).afterSingletonsInstantiated()
        }

        assertTrue(
            requireNotNull(error.message).contains("second-"),
            "a single-custodian production bootstrap must be rejected",
        )
    }

    private fun bootstrapProdEnvironment(
        withSecondCustodian: Boolean = true,
    ): MockEnvironment {
        val environment = secureProdEnvironment()
            .withProperty("spring.datasource.username", "peak_migrator")
            .withProperty("spring.datasource.password", "not-local-secret")
            .withProperty("spring.flyway.enabled", "false")
            .withProperty("spring.main.web-application-type", "none")
            .withProperty("peak.bootstrap.platform.enabled", "true")
            .withProperty("peak.bootstrap.platform.full-name", "Platform Root")
            .withProperty("peak.bootstrap.platform.email", "root@peak.example.com")
            .withProperty(
                "peak.bootstrap.platform.issuer",
                "https://auth.peak.example.com/realms/peak",
            )
            .withProperty("peak.bootstrap.platform.subject", UUID.randomUUID().toString())
        if (!withSecondCustodian) {
            return environment
        }
        return environment
            .withProperty("peak.bootstrap.platform.second-full-name", "Platform Root Two")
            .withProperty("peak.bootstrap.platform.second-email", "root2@peak.example.com")
            .withProperty(
                "peak.bootstrap.platform.second-issuer",
                "https://auth.peak.example.com/realms/peak",
            )
            .withProperty(
                "peak.bootstrap.platform.second-subject",
                UUID.randomUUID().toString(),
            )
    }

    @Test
    fun rejectsBootstrapRuntimeWithoutExplicitIdentity() {
        val error = assertFailsWith<IllegalStateException> {
            validator(
                environment = secureProdEnvironment()
                    .withProperty("spring.datasource.username", "peak_migrator")
                    .withProperty("spring.datasource.password", "not-local-secret")
                    .withProperty("spring.flyway.enabled", "false")
                    .withProperty("spring.main.web-application-type", "none")
                    .withProperty("peak.bootstrap.platform.enabled", "false"),
                runtimeProperties = PeakRuntimeProperties(PeakRuntimeMode.BOOTSTRAP),
                httpSecurityProperties = secureHttpProperties(),
                requestContextProperties = secureRequestContextProperties(),
            ).afterSingletonsInstantiated()
        }

        assertTrue(requireNotNull(error.message).contains("bootstrap.platform.enabled must be true"))
        assertTrue(requireNotNull(error.message).contains("bootstrap.platform.subject is required"))
    }

    private fun validator(
        environment: MockEnvironment,
        runtimeProperties: PeakRuntimeProperties = PeakRuntimeProperties(),
        httpSecurityProperties: HttpSecurityProperties = HttpSecurityProperties(),
        requestContextProperties: RequestContextProperties = RequestContextProperties(
            allowHeaderIdentity = true,
            allowTrustedJwtIdentityClaims = true,
        ),
        stepUpProperties: StepUpProperties = StepUpProperties(assumeUnavailable = false),
    ): ProductionReadinessValidator {
        return ProductionReadinessValidator(
            environment = environment,
            runtimeProperties = runtimeProperties,
            httpSecurityProperties = httpSecurityProperties,
            requestContextProperties = requestContextProperties,
            stepUpProperties = stepUpProperties,
            secretReferenceResolver = SecretReferenceResolver(environment),
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
            .withProperty("peak.security.runtime-route-boundary.enabled", "true")
            .withProperty("springdoc.api-docs.enabled", "false")
            .withProperty("springdoc.swagger-ui.enabled", "false")
            .withProperty("server.forward-headers-strategy", "native")
            .withProperty(
                "peak.security.outbound.allowed-provider-hosts",
                "payments.example.com,fiscal.example.com",
            )
            .withProperty("peak.realtime.websocket.allowed-origins[0]", "https://app.peak.example.com")
            .withProperty("peak.communication.delivery.local-provider.enabled", "false")
            .withProperty("peak.reliability.outbox.worker.health-required", "true")
            .withProperty("peak.communication.delivery.http-provider.enabled", "true")
            .withProperty(
                "peak.communication.delivery.http-provider.base-url",
                "https://communications.peak.example.com",
            )
            .withProperty(
                "peak.communication.delivery.http-provider.api-key",
                "secure-communication-provider-key",
            )
            .withProperty(
                "peak.security.envelope.key-reference",
                "env:PEAK_ENVELOPE_KEY",
            )
            .withProperty(
                "PEAK_ENVELOPE_KEY",
                "cGVhay1sb2NhbC1lbnZlbG9wZS1rZXktMzItYnl0ZSE=",
            )
            .withProperty(
                "peak.communication.invitation.acceptance-base-url",
                "https://app.peak.example.com/invitations/accept",
            )
    }
}
