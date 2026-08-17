package com.mwombeki.peak.realtime.internal

import com.fasterxml.jackson.databind.ObjectMapper
import com.mwombeki.peak.TestcontainersConfiguration
import com.mwombeki.peak.payment.api.InitiatePosMobileMoneyRequest
import com.mwombeki.peak.payment.api.PaymentWebhookPort
import com.mwombeki.peak.payment.internal.PaymentService
import com.mwombeki.peak.pos.api.AddPosOrderItemRequest
import com.mwombeki.peak.reliability.api.IdempotencyCommand
import com.mwombeki.peak.reliability.api.IdempotencyPort
import com.mwombeki.peak.pos.api.CreatePosOrderRequest
import com.mwombeki.peak.pos.api.OpenPosSessionRequest
import com.mwombeki.peak.pos.api.SendPosOrderRequest
import com.mwombeki.peak.pos.api.SettlePosOrderRequest
import com.mwombeki.peak.pos.internal.PosKitchenService
import com.mwombeki.peak.pos.internal.PosOrderService
import com.mwombeki.peak.pos.internal.PosSessionService
import com.mwombeki.peak.shared.context.RequestContext
import com.mwombeki.peak.shared.context.RequestContextHolder
import com.mwombeki.peak.shared.context.RequestIdentity
import java.math.BigDecimal
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.WebSocket
import java.time.Duration
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.junit.jupiter.Testcontainers

/**
 * End-to-end proof of the realtime doctrine with a genuine STOMP client:
 * REST commands commit state + journal events in one transaction, and a real
 * WebSocket client receives the committed-event envelope on the routed
 * destinations. No state is invented for the wire: every frame the client
 * receives was first committed to the journal by the owning business
 * transaction (RealTimeStreamService enforces that).
 */
@Import(TestcontainersConfiguration::class)
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = [
        "peak.realtime.journal.fanout-enabled=true",
        "peak.realtime.journal.poll-interval-ms=50",
    ],
)
@Testcontainers(disabledWithoutDocker = true)
class RealtimePosJourneyIntegrationTests {

    @Autowired
    private lateinit var posOrderService: PosOrderService

    @Autowired
    private lateinit var posSessionService: PosSessionService

    @Autowired
    private lateinit var posKitchenService: PosKitchenService

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Autowired
    private lateinit var requestContextHolder: RequestContextHolder

    @Autowired
    private lateinit var paymentService: PaymentService

    @Autowired
    private lateinit var webhookPort: PaymentWebhookPort

    @Autowired
    private lateinit var transactionTemplate: org.springframework.transaction.support.TransactionTemplate

    @Autowired
    private lateinit var idempotencyPort: IdempotencyPort

    @LocalServerPort
    private var port: Int = 0

    private val createdTenantIds = mutableSetOf<UUID>()

    @AfterTest
    fun cleanUp() {
        createdTenantIds.forEach { tenantId ->
            jdbcTemplate.update("DELETE FROM outbox_events WHERE tenant_id = ?", tenantId)
            jdbcTemplate.update("DELETE FROM realtime_event_journal WHERE tenant_id = ?", tenantId)
        }
        createdTenantIds.clear()
        requestContextHolder.clear()
    }

    @Test
    fun `STOMP client receives committed envelopes across the full POS journey`() {
        val fixture = insertFixture()
        val socket = connect(fixture.tenantId, fixture.userId)
        socket.subscribe("operations", "/topic/properties/${fixture.propertyId}/operations")
        socket.subscribe("orders", "/topic/outlets/${fixture.outletId}/orders")
        socket.subscribe("kitchen", "/topic/outlets/${fixture.outletId}/kitchen")

        bind(fixture, "journey-open")
        val session = posSessionService.openSession(
            fixture.propertyId,
            OpenPosSessionRequest(
                outletId = fixture.outletId,
                openingFloat = BigDecimal("100.00"),
            ),
        )

        bind(fixture, "journey-create")
        val order = posOrderService.createOrder(
            fixture.propertyId,
            CreatePosOrderRequest(
                sessionId = session.id,
                orderType = "dine_in",
                tableNumber = "T12",
                clientOperationId = "journey-create",
            ),
        )

        bind(fixture, "journey-item")
        posOrderService.addItem(
            fixture.propertyId,
            order.id,
            AddPosOrderItemRequest(
                menuItemId = fixture.menuItemId,
                quantity = BigDecimal("2"),
                clientOperationId = "journey-item",
            ),
        )

        bind(fixture, "journey-send")
        val ticket = posKitchenService.send(
            fixture.propertyId,
            order.id,
            SendPosOrderRequest(clientOperationId = "journey-send"),
        )

        bind(fixture, "journey-prepare")
        posKitchenService.transition(fixture.propertyId, ticket.id, "prepare", null)
        bind(fixture, "journey-ready")
        posKitchenService.transition(fixture.propertyId, ticket.id, "ready", null)
        bind(fixture, "journey-deliver")
        posKitchenService.transition(fixture.propertyId, ticket.id, "deliver", null)

        bind(fixture, "journey-settle")
        val settled = posOrderService.settleOrder(
            fixture.propertyId,
            order.id,
            SettlePosOrderRequest(paymentMethod = "cash"),
        )

        val opened = socket.awaitEvent("pos.session.opened")
        assertEquals(session.id.toString(), opened["aggregateId"])
        assertEquals(fixture.outletId.toString(), opened["outletId"])

        val created = socket.awaitEvent("pos.order.created")
        assertEquals("POS_ORDER", created["aggregateType"])
        assertEquals(order.id.toString(), created["aggregateId"])
        assertEquals(fixture.tenantId.toString(), created["tenantId"])
        assertEquals(fixture.propertyId.toString(), created["propertyId"])
        assertEquals(fixture.outletId.toString(), created["outletId"])
        assertEquals(1, created["schemaVersion"])
        assertNotNull(created["occurredAt"])
        assertNotNull(created["eventId"])
        @Suppress("UNCHECKED_CAST")
        val createdPayload = created["payload"] as Map<String, Any?>
        assertEquals(order.id.toString(), createdPayload["orderId"])
        assertEquals("T12", createdPayload["tableNumber"])

        socket.awaitEvent("pos.kitchen_ticket.created")
        socket.awaitEvent("pos.kitchen_ticket.preparing")
        socket.awaitEvent("pos.kitchen_ticket.ready")
        socket.awaitEvent("pos.kitchen_ticket.delivered")

        val settledEvent = socket.awaitEvent("pos.order.settled")
        assertEquals(order.id.toString(), settledEvent["aggregateId"])
        assertEquals("cash", (settledEvent["payload"] as Map<*, *>)["settlementMethod"])
        assertEquals("confirmed", (settledEvent["payload"] as Map<*, *>)["settlementStatus"])
        assertEquals("closed", settled.status)
        assertNotNull(settledEvent["sequenceId"], "live envelopes carry the committed journal position")

        // REST backfill: the same committed events are replayable after a cursor, so a client
        // connecting after the fact sees no gap and can deduplicate live envelopes by position.
        val replayResponse = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build()
            .send(
                HttpRequest.newBuilder(
                    URI.create(
                        "http://localhost:$port/api/v1/properties/" +
                            "${fixture.propertyId}/realtime/events?after=0",
                    ),
                )
                    .header("X-Peak-Tenant-Id", fixture.tenantId.toString())
                    .header("X-Peak-Tenant-User-Id", fixture.userId.toString())
                    .GET()
                    .build(),
                HttpResponse.BodyHandlers.ofString(),
            )
        assertEquals(200, replayResponse.statusCode())
        @Suppress("UNCHECKED_CAST")
        val replayBody = objectMapper.readValue(
            replayResponse.body(),
            Map::class.java,
        ) as Map<String, Any?>
        @Suppress("UNCHECKED_CAST")
        val replayEvents = replayBody["events"] as List<Map<String, Any?>>
        assertEquals(
            listOf(
                "pos.session.opened",
                "pos.order.created",
                "pos.order.updated",
                "pos.order.sent",
                "pos.kitchen_ticket.created",
                "pos.kitchen_ticket.preparing",
                "pos.kitchen_ticket.ready",
                "pos.kitchen_ticket.delivered",
                "payments.pos.cash.collected",
                "pos.order.settled",
            ),
            replayEvents.map { it["type"] },
            "backfill must replay every committed event in order",
        )
        val replayPositions = replayEvents.map { (it["sequenceId"] as Number).toLong() }
        assertEquals(replayPositions.sorted(), replayPositions)
        assertEquals(replayPositions.last(), (settledEvent["sequenceId"] as Number).toLong())
        assertEquals(
            replayPositions.last(),
            (replayBody["nextCursor"] as Number).toLong(),
            "nextCursor resumes exactly after the last replayed event",
        )

        socket.closeQuietly()
    }

    @Test
    fun `STOMP client receives the canonical payment envelopes end to end`() {
        val fixture = insertFixture()
        val socket = connect(fixture.tenantId, fixture.userId)
        socket.subscribe("operations", "/topic/properties/${fixture.propertyId}/operations")

        bind(fixture, "pay-open")
        val session = posSessionService.openSession(
            fixture.propertyId,
            OpenPosSessionRequest(
                outletId = fixture.outletId,
                openingFloat = BigDecimal("100.00"),
            ),
        )
        bind(fixture, "pay-order")
        val order = posOrderService.createOrder(
            fixture.propertyId,
            CreatePosOrderRequest(
                sessionId = session.id,
                orderType = "dine_in",
                tableNumber = "T12",
                clientOperationId = "pay-order",
            ),
        )
        bind(fixture, "pay-item")
        posOrderService.addItem(
            fixture.propertyId,
            order.id,
            AddPosOrderItemRequest(
                menuItemId = fixture.menuItemId,
                quantity = BigDecimal("2"),
                clientOperationId = "pay-item",
            ),
        )

        val providerId = UUID.randomUUID()
        val providerAccountId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO payment_providers (id, tenant_id, provider_code, name, provider_type, is_active)
            VALUES (?, ?, 'snippe', 'Snippe', 'mobile_money', true)
            """.trimIndent(),
            providerId,
            fixture.tenantId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO payment_provider_accounts (
                id, tenant_id, property_id, provider_id, account_name, client_id,
                secret_ref, api_key_secret_ref, checksum_key_secret_ref, endpoint_url,
                is_default, is_active, environment, lifecycle_status
            ) VALUES (?, ?, ?, ?, 'Hotel Account', 'MERCHANT-001',
                      'literal:api-secret', 'literal:api-secret', 'literal:checksum-secret',
                      'https://example.test', true, true, 'sandbox', 'enabled')
            """.trimIndent(),
            providerAccountId,
            fixture.tenantId,
            fixture.propertyId,
            providerId,
        )

        bind(fixture, "pay-initiate")
        val payment = transactionTemplate.execute {
            val reservation = idempotencyPort.reserve(
                IdempotencyCommand(
                    operationType = "payments.pos.mobile_money.initiated",
                    requestPayload = mapOf("posOrderId" to order.id, "amount" to 5900),
                ),
            )
            paymentService.initiatePosMobileMoney(
                tenantId = fixture.tenantId,
                propertyId = fixture.propertyId,
                request = InitiatePosMobileMoneyRequest(
                    posOrderId = order.id,
                    providerAccountId = providerAccountId,
                    phoneNumber = "255754123456",
                    amount = BigDecimal("5900.00"),
                    mobileNetwork = "Mpesa",
                ),
                idempotencyKeyId = reservation.recordId,
            )
        }

        val created = socket.awaitEvent("payment.created")
        assertEquals("PAYMENT_TRANSACTION", created["aggregateType"])
        assertEquals(payment.id.toString(), created["aggregateId"])
        assertEquals(fixture.tenantId.toString(), created["tenantId"])
        assertEquals(fixture.propertyId.toString(), created["propertyId"])
        assertEquals(1, created["schemaVersion"])

        // The provider confirms the collection; the per-payment destination gets its own
        // canonical envelope, and the operations stream gets it too.
        socket.subscribe("payment", "/topic/payments/${payment.id}")
        // In production the outbox worker performs this transition when it pushes the
        // collection prompt to the provider; the lifecycle guard refuses created -> posted.
        jdbcTemplate.update(
            """
            UPDATE payment_transactions
            SET status = 'initiated', initiated_at = now(), updated_at = now()
            WHERE tenant_id = ? AND id = ? AND status = 'created'
            """.trimIndent(),
            fixture.tenantId,
            payment.id,
        )
        val now = "\"" + Instant.now().truncatedTo(ChronoUnit.SECONDS) + "\""
        webhookPort.receive(
            providerAccountId = providerAccountId,
            payload = """
                {"id":"evt_journey_payment","type":"payment.completed",
                 "api_version":"2026-01-25","created_at":$now,
                 "data":{"reference":"sess_journey_payment",
                   "external_reference":"SEL123456789","status":"completed",
                   "amount":{"value":5900,"currency":"TZS"},
                   "settlement":{"fees":{"value":100,"currency":"TZS"}},
                   "channel":{"type":"mobile_money","provider":"mpesa"},
                   "customer":{"phone":"+255754123456"},
                   "metadata":{"external_reference":"${payment.internalReference}"},
                   "completed_at":$now}}
            """.trimIndent(),
        )

        val onPaymentDestination = socket.awaitFrameOn(
            "/topic/payments/${payment.id}",
            "payment.succeeded",
        )
        assertNotNull(onPaymentDestination, "payment.succeeded must arrive on its own destination")
        val succeeded = onPaymentDestination.bodyMap()
        assertEquals("PAYMENT_TRANSACTION", succeeded["aggregateType"])
        assertEquals(payment.id.toString(), succeeded["aggregateId"])
        assertEquals(fixture.tenantId.toString(), succeeded["tenantId"])
        @Suppress("UNCHECKED_CAST")
        val succeededPayload = succeeded["payload"] as Map<String, Any?>
        assertEquals("snippe", succeededPayload["provider"])
        assertEquals(5900.0, (succeededPayload["amount"] as Number).toDouble())

        val onOperations = socket.awaitFrameOn(
            "/topic/properties/${fixture.propertyId}/operations",
            "payment.succeeded",
        )
        assertNotNull(onOperations, "payment.succeeded must also arrive on the property stream")
        assertEquals(payment.id.toString(), onOperations.bodyMap()["aggregateId"])

        socket.closeQuietly()
    }

    @Test
    fun `subscription to another tenant outlet is denied with an ERROR frame`() {
        val fixture = insertFixture()
        val otherFixture = insertFixture()
        val socket = connect(fixture.tenantId, fixture.userId)
        socket.subscribe(
            "forbidden",
            "/topic/outlets/${otherFixture.outletId}/orders",
        )
        val error = socket.awaitCommand("ERROR")
        assertNotNull(error)
        assertTrue(
            socket.closed || error.headers.keys.any { it.contains("message") },
            "Expected connection to be rejected for a cross-tenant subscription",
        )
        socket.closeQuietly()
    }

    // --- STOMP over raw java.net.http.WebSocket ---

    private fun connect(tenantId: UUID, userId: UUID): StompSocket {
        val socket = StompSocket(objectMapper)
        socket.delegate = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build()
            .newWebSocketBuilder()
            .header("X-Peak-Tenant-Id", tenantId.toString())
            .header("X-Peak-Tenant-User-Id", userId.toString())
            .buildAsync(URI.create("ws://localhost:$port/ws-connect"), socket.listener())
            .get(10, TimeUnit.SECONDS)
        socket.send("CONNECT", mapOf("accept-version" to "1.2", "heart-beat" to "0,0"))
        socket.awaitCommand("CONNECTED")
        return socket
    }

    private class StompFrame(val command: String, val headers: Map<String, String>, val body: String)

    private inner class StompSocket(private val mapper: ObjectMapper) {
        var delegate: WebSocket? = null
        val frames = ConcurrentLinkedQueue<StompFrame>()
        var closed = false
            private set

        fun listener(): WebSocket.Listener {
            var partial = StringBuilder()
            return object : WebSocket.Listener {
                override fun onText(
                    webSocket: WebSocket,
                    data: CharSequence,
                    last: Boolean,
                ): CompletionStage<*>? {
                    partial.append(data)
                    if (!last) return null
                    val message = partial.toString()
                    partial = StringBuilder()
                    message.split('\u0000').forEach { raw ->
                        val frame = parseFrame(raw)
                        if (frame != null) {
                            frames.add(frame)
                            // Fire any waiting latch unconditionally: an await may have been
                            // registered before or after this frame arrived.
                            when (frame.command) {
                                "MESSAGE" -> messageLatches[awaitingType(frame)]?.countDown()
                                else -> commandLatches[frame.command]?.countDown()
                            }
                        }
                    }
                    webSocket.request(1)
                    return null
                }

                override fun onClose(
                    webSocket: WebSocket,
                    statusCode: Int,
                    reason: String,
                ): CompletionStage<*>? {
                    closed = true
                    return null
                }
            }
        }

        fun send(command: String, headers: Map<String, String>, body: String = "") {
            val frame = buildString {
                append(command).append('\n')
                headers.forEach { (k, v) -> append(k).append(':').append(v).append('\n') }
                append('\n')
                if (body.isNotEmpty()) append(body)
                append('\u0000')
            }
            delegate?.sendText(frame, true)
        }

        fun subscribe(id: String, destination: String) {
            send("SUBSCRIBE", mapOf("id" to id, "destination" to destination))
        }

        fun awaitCommand(command: String, timeoutSeconds: Long = 10): StompFrame? {
            frames.firstOrNull { it.command == command }?.let { return it }
            val latch = commandLatches.getOrPut(command) { CountDownLatch(1) }
            frames.firstOrNull { it.command == command }?.let { return it }
            return if (latch.await(timeoutSeconds, TimeUnit.SECONDS)) {
                frames.firstOrNull { it.command == command }
            } else null
        }

        fun awaitEvent(type: String, timeoutSeconds: Long = 10): Map<String, Any?> {
            frames.firstOrNull { awaitingType(it) == type }?.let { return it.bodyMap() }
            val latch = messageLatches.getOrPut(type) { CountDownLatch(1) }
            frames.firstOrNull { awaitingType(it) == type }?.let { return it.bodyMap() }
            check(latch.await(timeoutSeconds, TimeUnit.SECONDS)) {
                "Timed out waiting for realtime event '$type'; frames seen: " +
                    frames.map { "${it.command} hdrs=${it.headers} body=${it.body.take(300)}" }
            }
            return frames.first { awaitingType(it) == type }.bodyMap()
        }

        fun awaitFrameOn(
            destination: String,
            type: String,
            timeoutSeconds: Long = 10,
        ): StompFrame? {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds)
            while (System.nanoTime() < deadline) {
                frames.firstOrNull {
                    it.command == "MESSAGE" &&
                        it.headers["destination"] == destination &&
                        awaitingType(it) == type
                }?.let { return it }
                Thread.sleep(20)
            }
            return null
        }

        fun closeQuietly() {
            runCatching { delegate?.sendClose(WebSocket.NORMAL_CLOSURE, "test done") }
        }
    }

    private val commandLatches = mutableMapOf<String, CountDownLatch>()
    private val messageLatches = mutableMapOf<String, CountDownLatch>()

    private fun awaitingType(frame: StompFrame): String? {
        if (frame.command != "MESSAGE") return null
        return (frame.bodyMap()["type"] as? String)
    }

    private fun StompFrame.bodyMap(): Map<String, Any?> {
        @Suppress("UNCHECKED_CAST")
        return objectMapper.readValue(body, Map::class.java) as Map<String, Any?>
    }

    private fun parseFrame(raw: String): StompFrame? {
        val text = raw.trim('\u0000').trim()
        if (text.isBlank()) return null
        val lines = text.lines()
        val command = lines.first()
        val headers = mutableMapOf<String, String>()
        var bodyStart = -1
        for (i in 1 until lines.size) {
            if (lines[i].isEmpty()) {
                bodyStart = i + 1
                break
            }
            val separator = lines[i].indexOf(':')
            if (separator > 0) {
                headers[lines[i].substring(0, separator).lowercase()] =
                    lines[i].substring(separator + 1)
            }
        }
        val body = if (bodyStart >= 0 && bodyStart < lines.size) {
            lines.subList(bodyStart, lines.size).joinToString("\n")
        } else ""
        return StompFrame(command, headers, body)
    }

    // --- fixture (mirrors PosOrderServiceIntegrationTests.insertFixture) ---

    private data class PosFixture(
        val planId: UUID,
        val tenantId: UUID,
        val userId: UUID,
        val propertyId: UUID,
        val outletId: UUID,
        val categoryId: UUID,
        val menuItemId: UUID,
        val taxRateId: UUID,
    )

    private fun insertFixture(): PosFixture {
        val fixture = PosFixture(
            planId = UUID.randomUUID(),
            tenantId = UUID.randomUUID(),
            userId = UUID.randomUUID(),
            propertyId = UUID.randomUUID(),
            outletId = UUID.randomUUID(),
            categoryId = UUID.randomUUID(),
            menuItemId = UUID.randomUUID(),
            taxRateId = UUID.randomUUID(),
        )
        createdTenantIds += fixture.tenantId
        jdbcTemplate.update(
            "INSERT INTO plans (id, name, code) VALUES (?, ?, ?)",
            fixture.planId,
            "POS Plan ${fixture.planId}",
            "pos-${fixture.planId}",
        )
        jdbcTemplate.update(
            """
            INSERT INTO tenants (id, name, slug, status, schema_name, plan_id)
            VALUES (?, ?, ?, 'active', ?, ?)
            """.trimIndent(),
            fixture.tenantId,
            "POS Tenant ${fixture.tenantId}",
            "pos-${fixture.tenantId}",
            "tenant_${fixture.tenantId}".replace("-", "_"),
            fixture.planId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO users (id, tenant_id, full_name, email, status, is_active)
            VALUES (?, ?, 'POS Operator', ?, 'active', true)
            """.trimIndent(),
            fixture.userId,
            fixture.tenantId,
            "pos-${fixture.userId}@example.com",
        )
        jdbcTemplate.update(
            """
            INSERT INTO properties (id, tenant_id, name, status, is_active, total_rooms)
            VALUES (?, ?, 'POS Property', 'active', true, 0)
            """.trimIndent(),
            fixture.propertyId,
            fixture.tenantId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO outlets (id, tenant_id, property_id, name, type, is_active)
            VALUES (?, ?, ?, 'Restaurant', 'RESTAURANT', true)
            """.trimIndent(),
            fixture.outletId,
            fixture.tenantId,
            fixture.propertyId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO menu_categories (id, tenant_id, outlet_id, name)
            VALUES (?, ?, ?, 'Food')
            """.trimIndent(),
            fixture.categoryId,
            fixture.tenantId,
            fixture.outletId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO tax_rates (
                id, tenant_id, name, code, rate, tax_type, applies_to,
                is_inclusive, is_active
            )
            VALUES (?, ?, 'VAT', ?, 0.18, 'vat', ARRAY['food'], false, true)
            """.trimIndent(),
            fixture.taxRateId,
            fixture.tenantId,
            "VAT-${fixture.taxRateId}",
        )
        jdbcTemplate.update(
            """
            INSERT INTO menu_items (
                id, tenant_id, category_id, name, price, vat_rate,
                is_available, tax_rate_id
            )
            VALUES (?, ?, ?, 'Lunch Plate', 10.00, 18.00, true, ?)
            """.trimIndent(),
            fixture.menuItemId,
            fixture.tenantId,
            fixture.categoryId,
            fixture.taxRateId,
        )
        grantRealtimeStream(fixture)
        return fixture
    }

    /** Tenant module + permission entitlements the subscription authorizer checks. */
    private fun grantRealtimeStream(fixture: PosFixture) {
        jdbcTemplate.update(
            """
            INSERT INTO permissions (id, tenant_id, code, description)
            SELECT gen_random_uuid(), ?, pc.code, pc.description
            FROM permission_catalog pc
            WHERE pc.code = ?
            ON CONFLICT (tenant_id, code) DO UPDATE SET
                description = EXCLUDED.description,
                updated_at = now()
            """.trimIndent(),
            fixture.tenantId,
            "realtime.stream",
        )
        jdbcTemplate.update(
            """
            INSERT INTO tenant_modules (tenant_id, module_id, is_enabled, is_configured)
            VALUES (?, 'realtime', true, true)
            ON CONFLICT ON CONSTRAINT tenant_modules_tenant_id_module_id_key
            DO UPDATE SET is_enabled = true, is_configured = true
            """.trimIndent(),
            fixture.tenantId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO property_modules (tenant_id, property_id, module_id, is_enabled, is_configured)
            VALUES (?, ?, 'realtime', true, true)
            ON CONFLICT ON CONSTRAINT property_modules_tenant_id_property_id_module_id_key
            DO UPDATE SET is_enabled = true, is_configured = true
            """.trimIndent(),
            fixture.tenantId,
            fixture.propertyId,
        )
        val roleId = UUID.randomUUID()
        jdbcTemplate.update(
            """
            INSERT INTO roles (id, tenant_id, name, is_active)
            VALUES (?, ?, 'Realtime Stream', true)
            """.trimIndent(),
            roleId,
            fixture.tenantId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO role_permissions (role_id, permission_id)
            SELECT ?, id
            FROM permissions
            WHERE tenant_id = ?
              AND code = 'realtime.stream'
            """.trimIndent(),
            roleId,
            fixture.tenantId,
        )
        jdbcTemplate.update(
            """
            INSERT INTO user_property_roles (user_id, property_id, role_id, tenant_id)
            VALUES (?, ?, ?, ?)
            """.trimIndent(),
            fixture.userId,
            fixture.propertyId,
            roleId,
            fixture.tenantId,
        )
    }

    private fun bind(fixture: PosFixture, idempotencyKey: String) {
        requestContextHolder.set(
            RequestContext(
                identity = RequestIdentity.Tenant(fixture.tenantId, fixture.userId),
                correlationId = "corr-$idempotencyKey",
                idempotencyKey = idempotencyKey,
                httpMethod = "POST",
                requestPath = "/api/v1/properties/${fixture.propertyId}/pos",
            ),
        )
    }

    private companion object {
        val objectMapper = ObjectMapper()
    }
}