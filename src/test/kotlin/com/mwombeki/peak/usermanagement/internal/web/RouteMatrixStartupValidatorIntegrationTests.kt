package com.mwombeki.peak.usermanagement.internal.web

import com.mwombeki.peak.TestcontainersConfiguration
import kotlin.test.Test
import kotlin.test.assertTrue
import org.springframework.boot.DefaultApplicationArguments
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.mock.env.MockEnvironment
import org.testcontainers.junit.jupiter.Testcontainers

@Import(TestcontainersConfiguration::class)
@SpringBootTest(
    properties = [
        "peak.security.route-guard.validate-route-matrix-on-startup=true",
    ],
)
@Testcontainers(disabledWithoutDocker = true)
class RouteMatrixStartupValidatorIntegrationTests {
    @Test
    fun contextStartsWhenAllApiRoutesHaveMatrixContracts() {
        assertTrue(true)
    }

    @Test
    fun validatorSkipsDatabaseLookupOutsideApiRuntime() {
        val repository = object : RouteAccessRuleRepository {
            override fun findEnabledRules(): List<RouteAccessRule> {
                error("worker runtime must not query route matrix")
            }
        }
        val validator = RouteMatrixStartupValidator(
            handlerMappings = emptyList(),
            routeAccessRuleRepository = repository,
            properties = RouteGuardProperties(validateRouteMatrixOnStartup = true),
            environment = MockEnvironment().withProperty("peak.runtime.mode", "worker"),
        )

        validator.run(DefaultApplicationArguments())
    }
}
