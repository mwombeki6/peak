package com.mwombeki.peak.integrations.internal

import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.springframework.mock.env.MockEnvironment

class PaymentIntegrationReadinessValidatorTests {

    @Test
    fun rejectsProductionApiWithoutPaymentProviders() {
        val error = assertFailsWith<IllegalStateException> {
            validator(
                properties = PaymentIntegrationProperties(),
            ).afterSingletonsInstantiated()
        }

        assertTrue(
            requireNotNull(error.message)
                .contains("At least one payment provider must be configured"),
        )
    }

    @Test
    fun rejectsProductionApiProviderWithoutSecrets() {
        val error = assertFailsWith<IllegalStateException> {
            validator(
                properties = PaymentIntegrationProperties(
                    providers = mapOf(
                        "vodacom-mpesa" to ProviderConfig(
                            baseUrl = "https://payments.example.com/vodacom",
                            apiKey = "",
                            apiSecret = null,
                        ),
                    ),
                ),
            ).afterSingletonsInstantiated()
        }

        val message = requireNotNull(error.message)
        assertTrue(message.contains("api-key is required"))
        assertTrue(message.contains("api-secret is required"))
    }

    @Test
    fun rejectsProductionApiProviderWithoutHttps() {
        val error = assertFailsWith<IllegalStateException> {
            validator(
                properties = PaymentIntegrationProperties(
                    providers = mapOf(
                        "vodacom-mpesa" to provider(
                            baseUrl = "http://payments.example.com/vodacom",
                        ),
                    ),
                ),
            ).afterSingletonsInstantiated()
        }

        assertTrue(requireNotNull(error.message).contains("base-url must use https"))
    }

    @Test
    fun allowsProductionApiWithConfiguredProvider() {
        validator(
            properties = PaymentIntegrationProperties(
                providers = mapOf("vodacom-mpesa" to provider()),
            ),
        ).afterSingletonsInstantiated()
    }

    @Test
    fun skipsProviderValidationOutsideApiRuntime() {
        validator(
            runtimeMode = "migration",
            properties = PaymentIntegrationProperties(),
        ).afterSingletonsInstantiated()
    }

    private fun validator(
        runtimeMode: String = "api",
        properties: PaymentIntegrationProperties,
    ): PaymentIntegrationReadinessValidator {
        return PaymentIntegrationReadinessValidator(
            environment = MockEnvironment().also {
                it.setActiveProfiles("prod")
                it.withProperty("peak.runtime.mode", runtimeMode)
            },
            properties = properties,
        )
    }

    private fun provider(
        baseUrl: String = "https://payments.example.com/vodacom",
    ): ProviderConfig {
        return ProviderConfig(
            baseUrl = baseUrl,
            apiKey = "pk_live_1234567890",
            apiSecret = "sk_live_1234567890",
        )
    }
}
