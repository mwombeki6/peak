package com.mwombeki.peak.usermanagement.internal.web

import com.mwombeki.peak.TestcontainersConfiguration
import com.mwombeki.peak.usermanagement.api.GuardMode
import com.mwombeki.peak.usermanagement.api.RouteScope
import kotlin.test.Test
import kotlin.test.assertFailsWith
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

    @Test
    fun validatorRejectsConflictingContractsForTheSameMethodAndPattern() {
        val repository = object : RouteAccessRuleRepository {
            override fun findEnabledRules(): List<RouteAccessRule> {
                return listOf(
                    RouteAccessRule(
                        moduleId = "reports",
                        httpMethod = "POST",
                        apiPattern = "/api/properties/:propertyId/reports/:reportCode/runs",
                        permissionCode = "reports.generate",
                        routeScope = RouteScope.PROPERTY,
                        guardMode = GuardMode.STAFF_PERMISSION,
                    ),
                    RouteAccessRule(
                        moduleId = "reports",
                        httpMethod = "POST",
                        apiPattern = "/api/properties/:propertyId/reports/:reportCode/runs",
                        permissionCode = "reports.manual_generate",
                        routeScope = RouteScope.PROPERTY,
                        guardMode = GuardMode.STAFF_PERMISSION,
                    ),
                )
            }
        }
        val validator = RouteMatrixStartupValidator(
            handlerMappings = emptyList(),
            routeAccessRuleRepository = repository,
            properties = RouteGuardProperties(validateRouteMatrixOnStartup = true),
            environment = MockEnvironment().withProperty("peak.runtime.mode", "api"),
        )

        val error = assertFailsWith<IllegalStateException> {
            validator.run(DefaultApplicationArguments())
        }

        assertTrue(
            requireNotNull(error.message).contains(
                "Ambiguous module_access_matrix contracts",
            ),
        )
    }
}
