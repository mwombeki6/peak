package com.mwombeki.peak.usermanagement.internal.web

import com.mwombeki.peak.TestcontainersConfiguration
import com.mwombeki.peak.shared.context.RequestIdentity
import com.mwombeki.peak.usermanagement.api.GuardMode
import com.mwombeki.peak.usermanagement.api.RouteScope
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.web.bind.annotation.RequestMethod
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping
import org.testcontainers.junit.jupiter.Testcontainers

@Import(TestcontainersConfiguration::class)
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class RouteAccessMatrixCoverageIntegrationTests {

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    private lateinit var handlerMapping: RequestMappingHandlerMapping

    @Autowired
    private lateinit var ruleRepository: RouteAccessRuleRepository

    @Autowired
    private lateinit var routeAccessMatcher: RouteAccessMatcher

    @Test
    fun everyApplicationApiRouteHasAnExplicitAccessMatrixContract() {
        val rules = ruleRepository.findEnabledRules()
        val violations = applicationApiRouteSamples().mapNotNull { sample ->
            val authorizationRequest = routeAccessMatcher.match(
                httpMethod = sample.httpMethod.name,
                requestPath = sample.samplePath,
                identity = RequestIdentity.Public(),
                rules = rules,
            )

            when {
                authorizationRequest == null -> {
                    "${sample.httpMethod} ${sample.pattern} is not registered in module_access_matrix"
                }

                authorizationRequest.guardMode != sample.expectedGuardMode -> {
                    "${sample.httpMethod} ${sample.pattern} resolved to guard " +
                            "${authorizationRequest.guardMode}, expected ${sample.expectedGuardMode}"
                }

                authorizationRequest.routeScope != sample.expectedRouteScope -> {
                    "${sample.httpMethod} ${sample.pattern} resolved to scope " +
                            "${authorizationRequest.routeScope}, expected ${sample.expectedRouteScope}"
                }

                else -> null
            }
        }

        assertTrue(
            violations.isEmpty(),
            violations.joinToString(
                separator = "\n",
                prefix = "API routes without correct access contracts:\n",
            ),
        )
    }

    private fun applicationApiRouteSamples(): List<RouteSample> {
        return handlerMapping.handlerMethods.flatMap { (mappingInfo, handlerMethod) ->
            if (!handlerMethod.beanType.name.startsWith("com.mwombeki.peak.")) {
                return@flatMap emptyList()
            }

            val patterns = mappingInfo.pathPatternsCondition
                ?.patterns
                ?.map { it.patternString }
                ?: emptyList()

            patterns
                .filter { it.startsWith("/api/") }
                .flatMap { pattern ->
                    val methods = mappingInfo.methodsCondition.methods
                        .takeIf { it.isNotEmpty() }
                        ?: API_METHODS

                    methods.map { method ->
                        RouteSample(
                            httpMethod = method,
                            pattern = pattern,
                            samplePath = pattern.toSamplePath(),
                            expectedGuardMode = pattern.expectedGuardMode(),
                            expectedRouteScope = pattern.expectedRouteScope(),
                        )
                    }
                }
        }
    }

    private fun String.toSamplePath(): String {
        return PATH_VARIABLE_PATTERN.replace(this) { match ->
            val variableName = match.groupValues[1].substringBefore(":")
            SAMPLE_UUIDS[variableName] ?: DEFAULT_SAMPLE_UUID
        }
    }

    private fun String.expectedGuardMode(): GuardMode {
        return when {
            startsWith("/api/v1/platform/") -> GuardMode.PLATFORM_PERMISSION
            startsWith("/api/v1/public/properties/") -> GuardMode.MODULE_ONLY
            startsWith("/api/v1/payments/webhooks/") -> GuardMode.PUBLIC_TOKEN
            this == "/api/v1/invitations/accept" -> GuardMode.PUBLIC_TOKEN
            startsWith("/api/v1/tenants/") -> GuardMode.STAFF_PERMISSION
            startsWith("/api/v1/properties") -> GuardMode.STAFF_PERMISSION
            startsWith("/api/v1/communication") -> GuardMode.STAFF_PERMISSION
            startsWith("/api/v1/realtime/") -> GuardMode.STAFF_PERMISSION
            else -> error("No expected guard mode for API route pattern $this")
        }
    }

    private fun String.expectedRouteScope(): RouteScope {
        return when {
            startsWith("/api/v1/platform/") -> RouteScope.PLATFORM
            startsWith("/api/v1/public/properties/") -> RouteScope.PUBLIC_PROPERTY
            startsWith("/api/v1/payments/webhooks/") -> RouteScope.PUBLIC
            this == "/api/v1/invitations/accept" -> RouteScope.PUBLIC
            startsWith("/api/v1/tenants/") -> RouteScope.TENANT
            this == "/api/v1/properties" -> RouteScope.TENANT
            startsWith("/api/v1/properties/taxes") -> RouteScope.TENANT
            startsWith("/api/v1/properties/") -> RouteScope.PROPERTY
            startsWith("/api/v1/communication") -> RouteScope.TENANT
            startsWith("/api/v1/realtime/") -> RouteScope.PROPERTY
            else -> error("No expected route scope for API route pattern $this")
        }
    }

    private data class RouteSample(
        val httpMethod: RequestMethod,
        val pattern: String,
        val samplePath: String,
        val expectedGuardMode: GuardMode,
        val expectedRouteScope: RouteScope,
    )

    private companion object {
        val API_METHODS = setOf(
            RequestMethod.GET,
            RequestMethod.POST,
            RequestMethod.PUT,
            RequestMethod.PATCH,
            RequestMethod.DELETE,
        )

        val PATH_VARIABLE_PATTERN = Regex("\\{([^}]+)}")
        val DEFAULT_SAMPLE_UUID =
            UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa").toString()

        val SAMPLE_UUIDS = mapOf(
            "id" to UUID.fromString("11111111-1111-1111-1111-111111111111").toString(),
            "tenantId" to UUID.fromString("22222222-2222-2222-2222-222222222222").toString(),
            "propertyId" to UUID.fromString("33333333-3333-3333-3333-333333333333").toString(),
            "userId" to UUID.fromString("44444444-4444-4444-4444-444444444444").toString(),
            "platformUserId" to UUID.fromString("44444444-4444-4444-4444-444444444444").toString(),
            "identityLinkId" to UUID.fromString("55555555-5555-5555-5555-555555555555").toString(),
            "tenantRoleId" to UUID.fromString("66666666-6666-6666-6666-666666666666").toString(),
            "platformRoleId" to UUID.fromString("66666666-6666-6666-6666-666666666666").toString(),
            "buildingId" to UUID.fromString("77777777-7777-7777-7777-777777777771").toString(),
            "floorId" to UUID.fromString("77777777-7777-7777-7777-777777777772").toString(),
            "roomTypeId" to UUID.fromString("77777777-7777-7777-7777-777777777773").toString(),
            "roomId" to UUID.fromString("77777777-7777-7777-7777-777777777777").toString(),
            "revenueCenterId" to UUID.fromString("77777777-7777-7777-7777-777777777778").toString(),
            "departmentId" to UUID.fromString("77777777-7777-7777-7777-777777777779").toString(),
            "taxRateId" to UUID.fromString("77777777-7777-7777-7777-777777777780").toString(),
            "guestId" to UUID.fromString("77777777-7777-7777-7777-777777777781").toString(),
            "documentId" to UUID.fromString("77777777-7777-7777-7777-777777777788").toString(),
            "reservationId" to UUID.fromString("77777777-7777-7777-7777-777777777782").toString(),
            "stayId" to UUID.fromString("77777777-7777-7777-7777-777777777783").toString(),
            "folioId" to UUID.fromString("77777777-7777-7777-7777-777777777784").toString(),
            "chargeId" to UUID.fromString("77777777-7777-7777-7777-777777777785").toString(),
            "invoiceId" to UUID.fromString("77777777-7777-7777-7777-777777777786").toString(),
            "runId" to UUID.fromString("77777777-7777-7777-7777-777777777787").toString(),
            "cashSessionId" to UUID.fromString("77777777-7777-7777-7777-777777777789").toString(),
            "transactionId" to UUID.fromString("77777777-7777-7777-7777-777777777790").toString(),
            "providerAccountId" to UUID.fromString("77777777-7777-7777-7777-777777777791").toString(),
            "reconciliationId" to UUID.fromString("77777777-7777-7777-7777-777777777792").toString(),
            "receiptId" to UUID.fromString("77777777-7777-7777-7777-777777777793").toString(),
            "contactId" to UUID.fromString("88888888-8888-8888-8888-888888888887").toString(),
            "channelId" to UUID.fromString("88888888-8888-8888-8888-888888888888").toString(),
            "deliveryRequestId" to UUID.fromString("99999999-9999-9999-9999-999999999999").toString(),
            "moduleId" to "booking_engine",
        )
    }
}
