package com.mwombeki.peak.platformbilling

import com.jayway.jsonpath.JsonPath
import com.mwombeki.peak.TestcontainersConfiguration
import com.mwombeki.peak.payment.api.PaymentProvider
import com.mwombeki.peak.payment.api.ProviderCollectionCommand
import com.mwombeki.peak.payment.api.ProviderCollectionResult
import com.mwombeki.peak.payment.api.ProviderWebhookNotification
import com.mwombeki.peak.platformbilling.internal.EntitlementReconciler
import com.mwombeki.peak.platformbilling.internal.PurchaseSettlementOutboxHandler
import com.mwombeki.peak.platformbilling.internal.RenewalOfferService
import com.mwombeki.peak.platformbilling.internal.SubscriptionLifecycleService
import com.mwombeki.peak.reliability.api.ClaimedOutboxEvent
import com.mwombeki.peak.reliability.api.OutboxDestination
import com.mwombeki.peak.reliability.api.OutboxStatus
import com.mwombeki.peak.shared.context.PeakRequestHeaders
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * The commercial acceptance test: a suspended hotel pays and gets its business back.
 *
 * Every state in this lifecycle has been proved in isolation. That is not the same as
 * proving the loop closes — restriction is only defensible if recovery actually works, and
 * recovery crosses HTTP, a provider, a webhook, the outbox, the reconciler and the lifecycle
 * loop. A break anywhere leaves a paying customer suspended, which is the worst outcome this
 * system can produce.
 *
 * The route assertions bracket the whole thing: the same request that is refused at the
 * start succeeds at the end, with nothing changed but payment.
 */
@Import(TestcontainersConfiguration::class, SuspendedTenantRecoveryIntegrationTests.StubProvider::class)
@SpringBootTest(
    properties = [
        "peak.security.request-context.allow-header-identity=true",
        "peak.platformbilling.primary-provider=stub_recovery",
        "peak.platformbilling.endpoint-url=https://stub.invalid",
        "peak.platformbilling.client-id-secret-ref=literal:stub-client",
        "peak.platformbilling.api-key-secret-ref=literal:stub-key",
        "peak.platformbilling.checksum-key-secret-ref=literal:stub-checksum",
    ],
)
@AutoConfigureMockMvc
@Testcontainers(disabledWithoutDocker = true)
class SuspendedTenantRecoveryIntegrationTests {

    @Autowired private lateinit var mockMvc: MockMvc
    @Autowired private lateinit var jdbcTemplate: JdbcTemplate
    @Autowired private lateinit var renewalOfferService: RenewalOfferService
    @Autowired private lateinit var lifecycleService: SubscriptionLifecycleService
    @Autowired private lateinit var reconciler: EntitlementReconciler
    @Autowired private lateinit var settlementHandler: PurchaseSettlementOutboxHandler

    @AfterTest
    fun resetSession() {
        jdbcTemplate.execute("RESET ALL")
    }

    @Test
    fun aSuspendedTenantRenewsPaysAndGetsItsBusinessBack() {
        val fixture = tenantWithLapsedCover()

        // ---- 1. The lapse suspends them, and growth is refused ----
        assertEquals(
            SubscriptionLifecycleService.BillingLifecycleState.SUSPENDED,
            lifecycleService.advance(fixture.tenantId, "corr-recovery-suspend"),
        )
        assertEquals(
            403,
            inviteStatus(fixture, "idem-invite-while-suspended"),
            "a suspended tenant must not be able to grow",
        )

        // ---- 2. But the way out stays open ----
        renewalOfferService.offerDueRenewals(noticeDays = 400, limit = 100)
        val offerId = jdbcTemplate.queryForObject(
            "SELECT id FROM peak_renewal_offers WHERE tenant_id = ? AND status = 'offered'",
            UUID::class.java,
            fixture.tenantId,
        )
        assertTrue(offerId != null, "a suspended tenant must still be offered a way back")

        // ---- 3. Accepting prices at today's catalog and creates a real purchase ----
        val acceptBody = perform(
            post(url(fixture, "/billing/renewal-offers/$offerId/accept")),
            fixture,
            "idem-accept-renewal",
        )
        val purchaseId: String = JsonPath.read(acceptBody, "$.id")
        val total = BigDecimal(JsonPath.read<Any>(acceptBody, "$.totalAmount").toString())
        assertTrue(total > BigDecimal.ZERO, "the renewal must carry a real price")

        // ---- 4. Payment is started by an explicit customer action ----
        perform(
            post(url(fixture, "/billing/purchases/$purchaseId/payments"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"payerMsisdn":"0755000111","channel":"Mpesa"}"""),
            fixture,
            "idem-pay-renewal",
        )
        val reference = jdbcTemplate.queryForObject(
            "SELECT internal_reference FROM peak_payment_attempts WHERE purchase_id = ?::uuid",
            String::class.java,
            purchaseId,
        )

        // ---- 5. The provider confirms ----
        mockMvc.perform(
            post("/api/v1/platform-billing/webhooks/stub_recovery")
                .secure(true)
                .contentType(MediaType.APPLICATION_JSON)
                .header(PeakRequestHeaders.CORRELATION_ID, "corr-recovery-webhook")
                .content("""{"reference":"$reference","amount":"${total.toPlainString()}"}"""),
        ).andReturn().response.let { response ->
            assertEquals(200, response.status, "callback rejected: ${response.contentAsString}")
        }
        assertEquals("paid", purchaseStatus(purchaseId))

        // ---- 6. The worker applies it ----
        runBlocking { settlementHandler.handle(enqueuedSettlement(fixture.tenantId, purchaseId)) }
        reconciler.reconcileTenant(fixture.tenantId, "corr-recovery-reconcile")

        assertEquals(
            SubscriptionLifecycleService.BillingLifecycleState.ACTIVE,
            lifecycleService.advance(fixture.tenantId, "corr-recovery-restore"),
            "paying must lift the suspension",
        )

        // ---- 7. The same request that was refused now succeeds ----
        assertEquals(
            201,
            inviteStatus(fixture, "idem-invite-after-paying"),
            "recovery is the point of restriction; a tenant who paid must get their " +
                "business back, or suspension is indistinguishable from termination",
        )
    }

    @Test
    fun acceptingTheSameOfferTwiceYieldsOnePurchase() {
        val fixture = tenantWithLapsedCover()
        renewalOfferService.offerDueRenewals(noticeDays = 400, limit = 100)
        val offerId = jdbcTemplate.queryForObject(
            "SELECT id FROM peak_renewal_offers WHERE tenant_id = ? AND status = 'offered'",
            UUID::class.java,
            fixture.tenantId,
        )

        // A double-click, or a retried request. The offer is the idempotency anchor.
        val first: String = JsonPath.read(
            perform(post(url(fixture, "/billing/renewal-offers/$offerId/accept")), fixture, "idem-a"),
            "$.id",
        )
        val second: String = JsonPath.read(
            perform(post(url(fixture, "/billing/renewal-offers/$offerId/accept")), fixture, "idem-b"),
            "$.id",
        )

        assertEquals(first, second, "two clicks must not produce two purchases")
        assertEquals(
            1,
            jdbcTemplate.queryForObject(
                "SELECT count(*) FROM peak_purchases WHERE tenant_id = ? AND status = 'quoted'",
                Int::class.java,
                fixture.tenantId,
            ),
        )
    }

    private fun enqueuedSettlement(tenantId: UUID, purchaseId: String): ClaimedOutboxEvent {
        // Read the event the webhook actually enqueued, so this proves the webhook did its
        // job rather than fabricating the handoff.
        val event = jdbcTemplate.queryForMap(
            """
            SELECT id, event_type, payload::text AS payload
            FROM outbox_events
            WHERE tenant_id = ? AND destination = 'platform_billing'
            ORDER BY created_at DESC
            LIMIT 1
            """.trimIndent(),
            tenantId,
        )
        assertEquals("platform.purchase.paid", event["event_type"])

        val now = Instant.now()
        return ClaimedOutboxEvent(
            id = event["id"] as UUID,
            tenantId = tenantId,
            propertyId = null,
            aggregateType = "peak_purchase",
            aggregateId = UUID.fromString(purchaseId),
            eventType = "platform.purchase.paid",
            destination = OutboxDestination.PLATFORM_BILLING,
            payload = event["payload"].toString(),
            headers = "{}",
            correlationId = "corr-recovery-settle",
            idempotencyKeyId = null,
            status = OutboxStatus.LOCKED,
            priority = 5,
            attemptCount = 1,
            maxAttempts = 10,
            nextAttemptAt = now,
            lockedBy = "test",
            lockedAt = now,
            deliveredAt = null,
            failedAt = null,
            errorMessage = null,
            createdAt = now,
            updatedAt = now,
        )
    }

    private fun purchaseStatus(purchaseId: String): String? =
        jdbcTemplate.queryForObject(
            "SELECT status FROM peak_purchases WHERE id = ?::uuid",
            String::class.java,
            purchaseId,
        )

    private fun url(fixture: RecoveryFixture, suffix: String) =
        "/api/v1/tenants/${fixture.tenantId}$suffix"

    private fun inviteStatus(fixture: RecoveryFixture, idempotencyKey: String): Int {
        val builder = post(url(fixture, "/users/invitations"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                """
                {"email":"${idempotencyKey}-${fixture.tenantId}@example.com",
                 "fullName":"Recovery Invite",
                 "tenantRoleId":"${fixture.invitableRoleId}",
                 "expiresInHours":24}
                """.trimIndent(),
            )
        return performRaw(builder, fixture, idempotencyKey).status
    }

    private fun perform(
        builder: MockHttpServletRequestBuilder,
        fixture: RecoveryFixture,
        idempotencyKey: String,
    ): String {
        val response = performRaw(builder, fixture, idempotencyKey)
        assertTrue(
            response.status in 200..299,
            "${response.status}: ${response.contentAsString}",
        )
        return response.contentAsString
    }

    private fun performRaw(
        builder: MockHttpServletRequestBuilder,
        fixture: RecoveryFixture,
        idempotencyKey: String,
    ) = mockMvc.perform(
        builder.secure(true)
            .header(PeakRequestHeaders.CORRELATION_ID, "corr-${UUID.randomUUID()}")
            .header(PeakRequestHeaders.IDEMPOTENCY_KEY, idempotencyKey)
            .header(PeakRequestHeaders.TENANT_ID, fixture.tenantId.toString())
            .header(PeakRequestHeaders.TENANT_USER_ID, fixture.userId.toString()),
    ).andReturn().response

    private fun tenantWithLapsedCover(): RecoveryFixture {
        // Registering an adapter is not enough to make a rail usable: its capabilities have
        // to be declared. That gate is deliberate — a provider whose limits and payer
        // requirements nobody has stated should not be collectable — so the stub declares
        // its own.
        jdbcTemplate.update(
            """
            INSERT INTO peak_payment_method_capabilities (
                provider, payment_method, currency, min_amount, max_amount,
                requires_msisdn, supports_status_query, is_enabled, notes
            ) VALUES ('stub_recovery', 'mobile_money', 'TZS', 1000, 5000000,
                      true, true, true, 'Test stub')
            ON CONFLICT DO NOTHING
            """.trimIndent(),
        )

        val planId = UUID.randomUUID()
        val tenantId = UUID.randomUUID()
        val userId = UUID.randomUUID()
        val adminRoleId = UUID.randomUUID()
        val invitableRoleId = UUID.randomUUID()
        val purchaseId = UUID.randomUUID()

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
            INSERT INTO tenant_control_states (
                tenant_id, lifecycle_status, verification_status, provisioning_status,
                subscription_status, service_status, offboarding_status
            ) VALUES (?, 'active', 'verified', 'ready', 'active', 'operational', 'none')
            """.trimIndent(),
            tenantId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO tenant_subscriptions (
                tenant_id, plan_id, status, billing_cycle, billing_currency,
                provider, current_period_starts_at
            ) VALUES (?, ?, 'active', 'monthly', 'TZS', 'manual', now() - interval '90 days')
            """.trimIndent(),
            tenantId, planId,
        )
        jdbcTemplate.update(
            "INSERT INTO tenant_modules (tenant_id, module_id, is_enabled, is_configured) VALUES (?, 'tenant_admin', true, true)",
            tenantId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO users (id, tenant_id, full_name, email, status, is_active)
            VALUES (?, ?, ?, ?, 'active', true)
            """.trimIndent(),
            userId, tenantId, "Owner $userId", "owner-$userId@example.com",
        )
        jdbcTemplate.update(
            "INSERT INTO tenant_roles (id, tenant_id, name, code, is_system) VALUES (?, ?, 'Tenant Administrator', 'tenant_admin', true)",
            adminRoleId, tenantId,
        )
        jdbcTemplate.update(
            "INSERT INTO tenant_roles (id, tenant_id, name, code, is_system) VALUES (?, ?, 'Front Desk', 'front_desk', false)",
            invitableRoleId, tenantId,
        )
        jdbcTemplate.update(
            "INSERT INTO user_tenant_roles (user_id, tenant_id, tenant_role_id) VALUES (?, ?, ?)",
            userId, tenantId, adminRoleId,
        )
        listOf(
            "tenant.subscription.view",
            "tenant.subscription.purchase",
            "tenant.users.manage",
            "tenant.roles.view",
        ).forEach { code ->
            val permissionId = UUID.randomUUID()
            jdbcTemplate.update(
                "INSERT INTO permissions (id, tenant_id, code, description) VALUES (?, ?, ?, ?)",
                permissionId, tenantId, code, code,
            )
            jdbcTemplate.update(
                "INSERT INTO tenant_role_permissions (tenant_role_id, permission_id) VALUES (?, ?)",
                adminRoleId, permissionId,
            )
        }

        // Cover that ran out well past the suspension threshold.
        jdbcTemplate.update(
            """
            INSERT INTO peak_purchases (
                id, tenant_id, status, currency, term_months, total_amount,
                period_starts_at, period_ends_at, quote_expires_at
            ) VALUES (?, ?, 'paid', 'TZS', 1, 30000.00,
                      now() - interval '90 days', now() - interval '40 days',
                      now() - interval '89 days')
            """.trimIndent(),
            purchaseId, tenantId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO peak_purchase_lines (
                purchase_id, tenant_id, product_code, term_months, quantity,
                covered_property_ids, unit_amount, amount, entitlement_snapshot
            ) VALUES (?, ?, 'peak_core', 1, 1, '[]'::jsonb, 30000.00, 30000.00,
                      '{"module.frontdesk": {"is_enabled": true, "auto_activate": true, "value": {}}}'::jsonb)
            """.trimIndent(),
            purchaseId, tenantId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO peak_product_grants (
                tenant_id, product_code, source, source_purchase_id, status,
                starts_at, ends_at, granted_entitlements
            ) VALUES (?, 'peak_core', 'purchase', ?, 'active',
                      now() - interval '90 days', now() - interval '40 days',
                      '{"module.frontdesk": {"is_enabled": true, "auto_activate": true, "value": {}}}'::jsonb)
            """.trimIndent(),
            tenantId, purchaseId,
        )

        return RecoveryFixture(tenantId, userId, invitableRoleId)
    }

    private data class RecoveryFixture(
        val tenantId: UUID,
        val userId: UUID,
        val invitableRoleId: UUID,
    )

    /**
     * Stands in for a mobile money provider. Confirms whatever it is asked about, so the
     * test exercises Peak's handling of a success rather than any provider's wire format —
     * those are covered by the adapter's own tests.
     */
    @TestConfiguration
    class StubProvider {
        @Bean
        fun stubRecoveryProvider(): PaymentProvider = object : PaymentProvider {
            override val providerCode = "stub_recovery"

            override fun initiate(command: ProviderCollectionCommand) = ProviderCollectionResult(
                providerReference = "STUB-${command.internalReference}",
                status = "pending",
            )

            override fun parseWebhook(payload: String) = notification(payload)

            override fun verifyAndParseWebhook(
                payload: String,
                checksumKey: String,
                checksumRequired: Boolean,
            ) = notification(payload)

            private fun notification(payload: String): ProviderWebhookNotification {
                val reference: String = JsonPath.read(payload, "$.reference")
                val amount: String = JsonPath.read<Any>(payload, "$.amount").toString()
                return ProviderWebhookNotification(
                    eventKey = "STUB-EVENT-$reference",
                    eventType = "collection.updated",
                    internalReference = reference,
                    providerReference = "STUB-$reference",
                    status = "succeeded",
                    amount = BigDecimal(amount),
                    currency = "TZS",
                    clientId = null,
                    providerTimestamp = Instant.now(),
                    checksumMethod = "stub",
                )
            }
        }
    }
}
