package com.mwombeki.peak.payment.internal

import com.mwombeki.peak.TestcontainersConfiguration
import com.mwombeki.peak.payment.api.CollectCashPaymentRequest
import com.mwombeki.peak.payment.api.InitiateMobileMoneyRequest
import com.mwombeki.peak.payment.api.OpenCashSessionRequest
import com.mwombeki.peak.payment.api.PaymentPort
import com.mwombeki.peak.payment.api.PaymentRejectedException
import com.mwombeki.peak.shared.context.RequestContext
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import java.math.BigDecimal
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * Configuring a provider is not taking mobile money.
 *
 * An active `payment_provider_accounts` row used to be enough to push USSD. That collapsed
 * "the adapter exists" into "this hotel may collect guest money". Cash never needed a PSP;
 * a sibling hotel's merchant must never be inferred. The lifecycle on the account is what
 * separates those facts.
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class PropertyPaymentRailLifecycleIntegrationTests {

    @Autowired private lateinit var paymentPort: PaymentPort
    @Autowired private lateinit var requestContextHolder: RequestContextHolder
    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    @AfterTest
    fun clearContext() {
        requestContextHolder.clear()
        jdbcTemplate.execute("RESET ALL")
    }

    @Test
    fun configuringAProviderDoesNotLetTheDeskPushUssd() {
        val hotel = hotelWithConfiguredAccount()
        requestContextHolder.set(hotel.context("idem-mm-too-soon"))

        val ex = assertFailsWith<PaymentRejectedException> {
            paymentPort.initiateMobileMoney(
                hotel.propertyId,
                InitiateMobileMoneyRequest(
                    folioId = hotel.folioId,
                    providerAccountId = hotel.providerAccountId,
                    phoneNumber = "0754123456",
                    amount = BigDecimal("180000.00"),
                    mobileNetwork = "Mpesa",
                ),
            )
        }
        assertTrue(ex.message!!.contains("not enabled"))
    }

    @Test
    fun sandboxCollectionWorksOnlyAfterVerifyAndEnable() {
        val hotel = hotelWithConfiguredAccount()
        requestContextHolder.set(hotel.context("idem-verify"))
        assertEquals(
            "verified",
            paymentPort.verifyProvider(hotel.propertyId, hotel.providerAccountId).lifecycleStatus,
        )

        requestContextHolder.set(hotel.context("idem-enable"))
        val enabled = paymentPort.enableProvider(hotel.propertyId, hotel.providerAccountId)
        assertEquals("enabled", enabled.lifecycleStatus)
        assertTrue(enabled.eligibleForCollection)

        requestContextHolder.set(hotel.context("idem-collect"))
        val transaction = paymentPort.initiateMobileMoney(
            hotel.propertyId,
            InitiateMobileMoneyRequest(
                folioId = hotel.folioId,
                providerAccountId = hotel.providerAccountId,
                phoneNumber = "0754123456",
                amount = BigDecimal("180000.00"),
                mobileNetwork = "Mpesa",
            ),
        )
        assertEquals("created", transaction.status.databaseValue)
    }

    @Test
    fun cashDoesNotNeedAProvider() {
        val hotel = hotelWithFolio()
        requestContextHolder.set(hotel.context("idem-cash-open"))
        val session = paymentPort.openCashSession(hotel.propertyId, OpenCashSessionRequest())
        requestContextHolder.set(hotel.context("idem-cash-collect"))
        val posted = paymentPort.collectCash(
            hotel.propertyId,
            CollectCashPaymentRequest(
                folioId = hotel.folioId,
                cashSessionId = session.id,
                amount = BigDecimal("180000.00"),
            ),
        )
        assertEquals("posted", posted.status.databaseValue)
    }

    @Test
    fun aSiblingPropertyAccountCannotCollectHere() {
        val hotel = hotelWithConfiguredAccount(enabled = true)
        val siblingPropertyId = UUID.randomUUID()
        val siblingFolioId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO properties (id, tenant_id, name, code, type, status, is_active)
            VALUES (?, ?, 'Sibling', ?, 'HOTEL', 'active', true)
            """.trimIndent(),
            siblingPropertyId, hotel.tenantId, "SIB-${siblingPropertyId.toString().take(6)}",
        )
        jdbcTemplate.update(
            """
            INSERT INTO folios (id, tenant_id, property_id, folio_type, status)
            VALUES (?, ?, ?, 'guest', 'open')
            """.trimIndent(),
            siblingFolioId, hotel.tenantId, siblingPropertyId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO folio_charges (
                id, tenant_id, property_id, folio_id, charge_type, description,
                quantity, unit_price, subtotal, tax_rate, tax_amount, amount,
                posted_by, status
            ) VALUES (?, ?, ?, ?, 'ROOM', 'Night', 1, 180000, 180000, 0, 0, 180000,
                      ?, 'POSTED')
            """.trimIndent(),
            UUID.randomUUID(), hotel.tenantId, siblingPropertyId, siblingFolioId, hotel.userId,
        )
        jdbcTemplate.queryForList("SELECT recalculate_folio_totals(?)", siblingFolioId)
        requestContextHolder.set(
            hotel.copy(propertyId = siblingPropertyId, folioId = siblingFolioId)
                .context("idem-cross"),
        )

        assertFailsWith<Exception> {
            paymentPort.initiateMobileMoney(
                siblingPropertyId,
                InitiateMobileMoneyRequest(
                    folioId = siblingFolioId,
                    providerAccountId = hotel.providerAccountId,
                    phoneNumber = "0754123456",
                    amount = BigDecimal("180000.00"),
                    mobileNetwork = "Mpesa",
                ),
            )
        }
    }

    @Test
    fun disablingStopsFurtherPushes() {
        val hotel = hotelWithConfiguredAccount(enabled = true)
        requestContextHolder.set(hotel.context("idem-disable"))
        assertEquals(
            "verified",
            paymentPort.disableProvider(hotel.propertyId, hotel.providerAccountId).lifecycleStatus,
        )
        requestContextHolder.set(hotel.context("idem-after-disable"))
        assertFailsWith<PaymentRejectedException> {
            paymentPort.initiateMobileMoney(
                hotel.propertyId,
                InitiateMobileMoneyRequest(
                    folioId = hotel.folioId,
                    providerAccountId = hotel.providerAccountId,
                    phoneNumber = "0754123456",
                    amount = BigDecimal("180000.00"),
                    mobileNetwork = "Mpesa",
                ),
            )
        }
    }

    @Test
    fun productionCannotEnableWithoutCertification() {
        val hotel = hotelWithConfiguredAccount(environment = "production")
        requestContextHolder.set(hotel.context("idem-prod-verify"))
        paymentPort.verifyProvider(hotel.propertyId, hotel.providerAccountId)
        requestContextHolder.set(hotel.context("idem-prod-enable"))
        assertFailsWith<Exception> {
            paymentPort.enableProvider(hotel.propertyId, hotel.providerAccountId)
        }
    }

    private fun hotelWithConfiguredAccount(
        enabled: Boolean = false,
        environment: String = "sandbox",
    ): Hotel {
        val hotel = hotelWithFolio()
        val providerId = UUID.randomUUID()
        val providerAccountId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO payment_providers (
                id, tenant_id, provider_code, name, provider_type, is_active
            ) VALUES (?, ?, 'clickpesa', 'ClickPesa', 'mobile_money', true)
            """.trimIndent(),
            providerId, hotel.tenantId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO payment_provider_accounts (
                id, tenant_id, property_id, provider_id, account_name, client_id,
                secret_ref, api_key_secret_ref, checksum_key_secret_ref, endpoint_url,
                is_default, is_active, environment, lifecycle_status, verified_at, enabled_at
            ) VALUES (?, ?, ?, ?, 'Hotel Account', 'MERCHANT-001',
                      'literal:api-secret', 'literal:api-secret', 'literal:checksum-secret',
                      'https://api.clickpesa.com/third-parties', true, true, ?, ?, ?, ?)
            """.trimIndent(),
            providerAccountId, hotel.tenantId, hotel.propertyId, providerId,
            environment,
            if (enabled) "enabled" else "configured",
            if (enabled) java.sql.Timestamp.from(java.time.Instant.now()) else null,
            if (enabled) java.sql.Timestamp.from(java.time.Instant.now()) else null,
        )
        return hotel.copy(providerAccountId = providerAccountId)
    }

    private fun hotelWithFolio(): Hotel {
        val planId = UUID.randomUUID()
        val tenantId = UUID.randomUUID()
        val propertyId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val folioId = UUID.randomUUID()
        jdbcTemplate.update(
            "INSERT INTO plans (id, name, code) VALUES (?, ?, ?)",
            planId, "Plan $planId", "plan-$planId",
        )
        jdbcTemplate.update(
            "INSERT INTO tenants (id, name, slug, schema_name, plan_id) VALUES (?, ?, ?, ?, ?)",
            tenantId, "Tenant $tenantId", "tenant-$tenantId",
            "tenant_$tenantId".replace("-", "_"), planId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO properties (id, tenant_id, name, code, type, status, is_active)
            VALUES (?, ?, 'Rail Hotel', ?, 'HOTEL', 'active', true)
            """.trimIndent(),
            propertyId, tenantId, "RH-${propertyId.toString().take(6)}",
        )
        jdbcTemplate.update(
            """
            INSERT INTO users (id, tenant_id, full_name, email, status, is_active)
            VALUES (?, ?, 'Front Desk', ?, 'active', true)
            """.trimIndent(),
            userId, tenantId, "desk-$userId@example.com",
        )
        jdbcTemplate.update(
            """
            INSERT INTO folios (id, tenant_id, property_id, folio_type, status)
            VALUES (?, ?, ?, 'guest', 'open')
            """.trimIndent(),
            folioId, tenantId, propertyId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO folio_charges (
                id, tenant_id, property_id, folio_id, charge_type, description,
                quantity, unit_price, subtotal, tax_rate, tax_amount, amount,
                posted_by, status
            ) VALUES (?, ?, ?, ?, 'ROOM', 'Two nights', 1, 180000, 180000, 0, 0, 180000,
                      ?, 'POSTED')
            """.trimIndent(),
            UUID.randomUUID(), tenantId, propertyId, folioId, userId,
        )
        jdbcTemplate.queryForList("SELECT recalculate_folio_totals(?)", folioId)
        return Hotel(tenantId, propertyId, userId, folioId, UUID(0, 0))
    }

    private data class Hotel(
        val tenantId: UUID,
        val propertyId: UUID,
        val userId: UUID,
        val folioId: UUID,
        val providerAccountId: UUID,
    ) {
        fun context(idempotencyKey: String) = RequestContext(
            identity = RequestIdentity.Tenant(
                tenantId = tenantId,
                tenantUserId = userId,
                correlationId = "corr-$idempotencyKey",
            ),
            correlationId = "corr-$idempotencyKey",
            idempotencyKey = idempotencyKey,
            httpMethod = "POST",
            requestPath = "/api/v1/properties/$propertyId/payments/mobile-money",
        )
    }
}
