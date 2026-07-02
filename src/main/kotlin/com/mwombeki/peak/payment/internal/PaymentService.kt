package com.mwombeki.peak.payment.internal

import com.mwombeki.peak.audit.api.AuditPort
import com.mwombeki.peak.audit.api.AuditResource
import com.mwombeki.peak.audit.api.TenantAuditEvent
import com.mwombeki.peak.billing.api.BillingPort
import com.mwombeki.peak.billing.api.ConfirmedPaymentRequest
import com.mwombeki.peak.billing.api.ConfirmedPaymentRefundRequest
import com.mwombeki.peak.billing.api.ConfirmedPaymentReversalRequest
import com.mwombeki.peak.payment.api.CashSessionResponse
import com.mwombeki.peak.payment.api.CloseCashSessionRequest
import com.mwombeki.peak.payment.api.CollectCashPaymentRequest
import com.mwombeki.peak.payment.api.CollectPosCashPaymentRequest
import com.mwombeki.peak.payment.api.ConfigurePaymentProviderRequest
import com.mwombeki.peak.payment.api.CreatePaymentReconciliationRequest
import com.mwombeki.peak.payment.api.InitiateMobileMoneyRequest
import com.mwombeki.peak.payment.api.InitiatePosMobileMoneyRequest
import com.mwombeki.peak.payment.api.OpenCashSessionRequest
import com.mwombeki.peak.payment.api.PaymentConflictException
import com.mwombeki.peak.payment.api.PaymentNotFoundException
import com.mwombeki.peak.payment.api.PaymentPort
import com.mwombeki.peak.payment.api.PaymentProviderAccountResponse
import com.mwombeki.peak.payment.api.PaymentProvider
import com.mwombeki.peak.payment.api.PaymentReconciliationResponse
import com.mwombeki.peak.payment.api.PaymentRejectedException
import com.mwombeki.peak.payment.api.PaymentStatus
import com.mwombeki.peak.payment.api.PaymentTransactionResponse
import com.mwombeki.peak.payment.api.PaymentNightAuditSummary
import com.mwombeki.peak.payment.api.PaymentStatusPort
import com.mwombeki.peak.payment.api.RecordManualMobileMoneyPaymentRequest
import com.mwombeki.peak.payment.api.RefundPaymentRequest
import com.mwombeki.peak.payment.api.ReversePaymentRequest
import com.mwombeki.peak.payment.api.ImportPaymentReconciliationRequest
import com.mwombeki.peak.payment.api.PaymentReconciliationImportResponse
import com.mwombeki.peak.reliability.api.IdempotencyCommand
import com.mwombeki.peak.reliability.api.IdempotencyPort
import com.mwombeki.peak.reliability.api.IdempotencyReservation
import com.mwombeki.peak.reliability.api.OutboxDestination
import com.mwombeki.peak.reliability.api.OutboxEventCommand
import com.mwombeki.peak.reliability.api.OutboxPort
import com.mwombeki.peak.shared.context.TenantActor
import com.mwombeki.peak.shared.context.TenantRequestContext
import com.mwombeki.peak.shared.outbound.OutboundEndpointPolicy
import com.mwombeki.peak.shared.secrets.SecretReferenceResolver
import java.math.BigDecimal
import java.math.RoundingMode
import java.net.URI
import java.sql.ResultSet
import java.sql.Timestamp
import java.util.UUID
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.core.env.Environment
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper

@Service
class PaymentService(
    private val jdbcTemplate: JdbcTemplate,
    private val tenantRequestContext: TenantRequestContext,
    private val idempotencyPort: IdempotencyPort,
    private val auditPort: AuditPort,
    private val outboxPort: OutboxPort,
    private val billingPort: BillingPort,
    private val transactionTemplate: TransactionTemplate,
    private val objectMapper: ObjectMapper,
    private val secretResolver: SecretReferenceResolver,
    private val outboundEndpointPolicy: OutboundEndpointPolicy,
    private val meterRegistry: MeterRegistry,
    private val environment: Environment,
    adapters: List<PaymentProvider>,
) : PaymentPort, PaymentStatusPort {
    private val providerCodes = adapters.mapTo(mutableSetOf()) { it.providerCode }

    override fun nightAuditSummary(
        tenantId: UUID,
        propertyId: UUID,
    ): PaymentNightAuditSummary {
        val count = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
            FROM payment_transactions
            WHERE tenant_id = ?
              AND property_id = ?
              AND status IN ('created', 'initiated', 'pending')
            """.trimIndent(),
            Int::class.java,
            tenantId,
            propertyId,
        ) ?: 0
        return PaymentNightAuditSummary(nonTerminalTransactions = count)
    }

    override fun openCashSession(
        propertyId: UUID,
        request: OpenCashSessionRequest,
    ): CashSessionResponse {
        return mutate(
            propertyId = propertyId,
            operationType = "payments.cash_session.open",
            requestPayload = request,
            resourceType = CASH_SESSIONS,
            replayType = CashSessionResponse::class.java,
        ) { actor, idempotencyKeyId ->
            val openingFloat = request.openingFloat.nonNegativeMoney("openingFloat")
            val id = UUID.randomUUID()
            try {
                jdbcTemplate.update(
                    """
                    INSERT INTO cash_sessions (
                        id, tenant_id, property_id, cashier_id, opening_float,
                        expected_cash, notes
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    id,
                    actor.tenantId,
                    propertyId,
                    actor.tenantUserId,
                    openingFloat,
                    openingFloat,
                    request.notes.trimmedOrNull(),
                )
            } catch (ex: DuplicateKeyException) {
                throw PaymentConflictException(
                    "Cashier already has an open cash session for this property",
                )
            }
            requireCashSession(actor.tenantId, propertyId, id, lock = false)
                .also {
                    recordSideEffects(
                        actor = actor,
                        propertyId = propertyId,
                        action = "payments.cash_session.opened",
                        aggregateType = CASH_SESSIONS,
                        aggregateId = id,
                        payload = mapOf(
                            "cashSessionId" to id,
                            "openingFloat" to openingFloat,
                        ),
                        idempotencyKeyId = idempotencyKeyId,
                    )
                }
        }
    }

    override fun currentCashSession(propertyId: UUID): CashSessionResponse? {
        return read(propertyId) { actor ->
            jdbcTemplate.query(
                """
                SELECT *
                FROM cash_sessions
                WHERE tenant_id = ?
                  AND property_id = ?
                  AND cashier_id = ?
                  AND status = 'open'
                ORDER BY opened_at DESC
                LIMIT 1
                """.trimIndent(),
                ::mapCashSession,
                actor.tenantId,
                propertyId,
                actor.tenantUserId,
            ).singleOrNull()
        }
    }

    override fun closeCashSession(
        propertyId: UUID,
        cashSessionId: UUID,
        request: CloseCashSessionRequest,
    ): CashSessionResponse {
        return mutate(
            propertyId = propertyId,
            operationType = "payments.cash_session.close",
            requestPayload = mapOf("cashSessionId" to cashSessionId, "request" to request),
            resourceType = CASH_SESSIONS,
            replayType = CashSessionResponse::class.java,
        ) { actor, idempotencyKeyId ->
            val actualCash = request.actualCash.nonNegativeMoney("actualCash")
            val session = requireCashSession(
                actor.tenantId,
                propertyId,
                cashSessionId,
                lock = true,
            )
            require(session.status == "open") {
                "Cash session is already closed"
            }
            require(session.cashierId == actor.tenantUserId) {
                "Cashier can close only their own cash session"
            }
            val variance = actualCash.subtract(session.expectedCash).money()
            jdbcTemplate.update(
                """
                UPDATE cash_sessions
                SET status = 'closed',
                    actual_cash = ?,
                    variance = ?,
                    closed_at = now(),
                    closed_by = ?,
                    notes = COALESCE(?, notes),
                    updated_at = now()
                WHERE tenant_id = ?
                  AND property_id = ?
                  AND id = ?
                  AND status = 'open'
                """.trimIndent(),
                actualCash,
                variance,
                actor.tenantUserId,
                request.notes.trimmedOrNull(),
                actor.tenantId,
                propertyId,
                cashSessionId,
            )
            requireCashSession(actor.tenantId, propertyId, cashSessionId, lock = false)
                .also {
                    recordSideEffects(
                        actor = actor,
                        propertyId = propertyId,
                        action = "payments.cash_session.closed",
                        aggregateType = CASH_SESSIONS,
                        aggregateId = cashSessionId,
                        payload = mapOf(
                            "cashSessionId" to cashSessionId,
                            "expectedCash" to session.expectedCash,
                            "actualCash" to actualCash,
                            "variance" to variance,
                        ),
                        idempotencyKeyId = idempotencyKeyId,
                    )
                }
        }
    }

    override fun collectCash(
        propertyId: UUID,
        request: CollectCashPaymentRequest,
    ): PaymentTransactionResponse {
        return mutate(
            propertyId = propertyId,
            operationType = "payments.cash.collect",
            requestPayload = request,
            resourceType = PAYMENT_TRANSACTIONS,
            replayType = PaymentTransactionResponse::class.java,
        ) { actor, idempotencyKeyId ->
            val amount = request.amount.positiveMoney("amount")
            val session = requireCashSession(
                actor.tenantId,
                propertyId,
                request.cashSessionId,
                lock = true,
            )
            require(session.status == "open") {
                "Cash payment requires an open cash session"
            }
            require(session.cashierId == actor.tenantUserId) {
                "Cash payment must use the active cashier's session"
            }
            val folio = requirePayableFolio(
                actor.tenantId,
                propertyId,
                request.folioId,
                amount,
            )
            val transactionId = UUID.randomUUID()
            val internalReference = paymentReference(transactionId)
            jdbcTemplate.update(
                """
                INSERT INTO payment_transactions (
                    id, tenant_id, property_id, folio_id, initiated_by,
                    idempotency_key_id, transaction_direction, transaction_type,
                    internal_reference, amount, currency, status, posted_at,
                    confirmed_at,
                    metadata
                )
                VALUES (?, ?, ?, ?, ?, ?, 'inbound', 'collection', ?, ?, ?,
                        'posted', now(), now(), ?::jsonb)
                """.trimIndent(),
                transactionId,
                actor.tenantId,
                propertyId,
                request.folioId,
                actor.tenantUserId,
                idempotencyKeyId,
                internalReference,
                amount,
                folio.currency,
                objectMapper.writeValueAsString(
                    mapOf("method" to "cash", "cashSessionId" to request.cashSessionId),
                ),
            )
            val folioPaymentId = billingPort.postConfirmedPayment(
                tenantId = actor.tenantId,
                propertyId = propertyId,
                request = ConfirmedPaymentRequest(
                    folioId = request.folioId,
                    paymentMethod = "cash",
                    amount = amount,
                    paymentTransactionId = transactionId,
                    cashSessionId = request.cashSessionId,
                    processedBy = actor.tenantUserId,
                    referenceNumber = internalReference,
                    idempotencyKey = idempotencyKeyId.toString(),
                    notes = request.notes,
                ),
                idempotencyKeyId = idempotencyKeyId,
            )
            jdbcTemplate.update(
                """
                UPDATE payment_transactions
                SET folio_payment_id = ?,
                    updated_at = now()
                WHERE tenant_id = ? AND id = ?
                """.trimIndent(),
                folioPaymentId,
                actor.tenantId,
                transactionId,
            )
            jdbcTemplate.update(
                """
                UPDATE cash_sessions
                SET expected_cash = expected_cash + ?,
                    updated_at = now()
                WHERE tenant_id = ? AND id = ? AND status = 'open'
                """.trimIndent(),
                amount,
                actor.tenantId,
                request.cashSessionId,
            )
            requireTransaction(actor.tenantId, propertyId, transactionId, lock = false)
                .also {
                    recordSideEffects(
                        actor = actor,
                        propertyId = propertyId,
                        action = "payments.cash.collected",
                        aggregateType = PAYMENT_TRANSACTIONS,
                        aggregateId = transactionId,
                        payload = mapOf(
                            "transactionId" to transactionId,
                            "folioId" to request.folioId,
                            "cashSessionId" to request.cashSessionId,
                            "amount" to amount,
                        ),
                        idempotencyKeyId = idempotencyKeyId,
                    )
                }
        }
    }

    override fun initiateMobileMoney(
        propertyId: UUID,
        request: InitiateMobileMoneyRequest,
    ): PaymentTransactionResponse {
        return mutate(
            propertyId = propertyId,
            operationType = "payments.mobile_money.initiate",
            requestPayload = request,
            resourceType = PAYMENT_TRANSACTIONS,
            replayType = PaymentTransactionResponse::class.java,
        ) { actor, idempotencyKeyId ->
            val amount = request.amount.positiveMoney("amount")
            val folio = requirePayableFolio(
                actor.tenantId,
                propertyId,
                request.folioId,
                amount,
            )
            requireProviderAccount(
                actor.tenantId,
                propertyId,
                request.providerAccountId,
                lock = false,
            )
            val transactionId = UUID.randomUUID()
            val internalReference = paymentReference(transactionId)
            jdbcTemplate.update(
                """
                INSERT INTO payment_transactions (
                    id, tenant_id, property_id, folio_id, provider_account_id,
                    initiated_by, idempotency_key_id, transaction_direction,
                    transaction_type, internal_reference, payer_identifier,
                    amount, currency, status, expires_at, next_status_check_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, 'inbound', 'collection', ?, ?, ?, ?,
                        'created', now() + interval '15 minutes',
                        now() + interval '15 seconds')
                """.trimIndent(),
                transactionId,
                actor.tenantId,
                propertyId,
                request.folioId,
                request.providerAccountId,
                actor.tenantUserId,
                idempotencyKeyId,
                internalReference,
                request.phoneNumber.tanzanianE164(),
                amount,
                folio.currency,
            )
            outboxPort.enqueue(
                OutboxEventCommand(
                    aggregateType = PAYMENT_TRANSACTIONS,
                    aggregateId = transactionId,
                    tenantId = actor.tenantId,
                    propertyId = propertyId,
                    eventType = PAYMENT_COLLECTION_REQUESTED,
                    destination = OutboxDestination.PAYMENT,
                    payload = mapOf(
                        "transactionId" to transactionId,
                        "providerAccountId" to request.providerAccountId,
                    ),
                    idempotencyKeyId = idempotencyKeyId,
                    priority = 2,
                ),
            )
            requireTransaction(actor.tenantId, propertyId, transactionId, lock = false)
                .also {
                    auditPort.recordTenantEvent(
                        TenantAuditEvent(
                            tenantId = actor.tenantId,
                            action = "payments.mobile_money.initiated",
                            resource = AuditResource(PAYMENT_TRANSACTIONS, transactionId),
                            after = mapOf(
                                "transactionId" to transactionId,
                                "folioId" to request.folioId,
                                "providerAccountId" to request.providerAccountId,
                                "amount" to amount,
                            ),
                        ),
                    )
                }
        }
    }

    override fun recordManualMobileMoney(
        propertyId: UUID,
        request: RecordManualMobileMoneyPaymentRequest,
    ): PaymentTransactionResponse {
        return mutate(
            propertyId = propertyId,
            operationType = "payments.mobile_money.manual_reference",
            requestPayload = request,
            resourceType = PAYMENT_TRANSACTIONS,
            replayType = PaymentTransactionResponse::class.java,
        ) { actor, idempotencyKeyId ->
            val amount = request.amount.positiveMoney("amount")
            val reference = request.referenceNumber.normalizedProviderReference()
            val folio = requirePayableFolio(
                tenantId = actor.tenantId,
                propertyId = propertyId,
                folioId = request.folioId,
                amount = amount,
            )
            requireProviderAccount(
                tenantId = actor.tenantId,
                propertyId = propertyId,
                providerAccountId = request.providerAccountId,
                lock = false,
            )
            val transactionId = UUID.randomUUID()
            val internalReference = paymentReference(transactionId)
            try {
                jdbcTemplate.update(
                    """
                    INSERT INTO payment_transactions (
                        id, tenant_id, property_id, folio_id, provider_account_id,
                        initiated_by, idempotency_key_id, transaction_direction,
                        transaction_type, provider_reference, internal_reference,
                        payer_identifier, amount, currency, status, posted_at,
                        confirmed_at, metadata
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, 'inbound', 'collection', ?, ?, ?, ?, ?,
                            'posted', now(), now(), ?::jsonb)
                    """.trimIndent(),
                    transactionId,
                    actor.tenantId,
                    propertyId,
                    request.folioId,
                    request.providerAccountId,
                    actor.tenantUserId,
                    idempotencyKeyId,
                    reference,
                    internalReference,
                    request.phoneNumber?.tanzanianE164(),
                    amount,
                    folio.currency,
                    objectMapper.writeValueAsString(
                        mapOf("method" to "manual_mobile_money_reference"),
                    ),
                )
            } catch (ex: DuplicateKeyException) {
                throw PaymentConflictException(
                    "The provider reference has already been recorded for this account",
                )
            }
            val folioPaymentId = billingPort.postConfirmedPayment(
                tenantId = actor.tenantId,
                propertyId = propertyId,
                request = ConfirmedPaymentRequest(
                    folioId = request.folioId,
                    paymentMethod = "mobile_money",
                    amount = amount,
                    paymentTransactionId = transactionId,
                    processedBy = actor.tenantUserId,
                    referenceNumber = reference,
                    idempotencyKey = idempotencyKeyId.toString(),
                    notes = request.notes,
                ),
                idempotencyKeyId = idempotencyKeyId,
            )
            jdbcTemplate.update(
                """
                UPDATE payment_transactions
                SET folio_payment_id = ?, updated_at = now()
                WHERE tenant_id = ? AND id = ?
                """.trimIndent(),
                folioPaymentId,
                actor.tenantId,
                transactionId,
            )
            requireTransaction(actor.tenantId, propertyId, transactionId, lock = false)
                .also {
                    recordSideEffects(
                        actor = actor,
                        propertyId = propertyId,
                        action = "payments.mobile_money.manual_reference_recorded",
                        aggregateType = PAYMENT_TRANSACTIONS,
                        aggregateId = transactionId,
                        payload = mapOf(
                            "transactionId" to transactionId,
                            "folioId" to request.folioId,
                            "providerAccountId" to request.providerAccountId,
                            "providerReference" to reference,
                            "amount" to amount,
                        ),
                        idempotencyKeyId = idempotencyKeyId,
                    )
                }
        }
    }

    override fun collectPosCash(
        tenantId: UUID,
        propertyId: UUID,
        request: CollectPosCashPaymentRequest,
        idempotencyKeyId: UUID,
    ): PaymentTransactionResponse {
        val actor = bindActor(propertyId, lockProperty = false)
        require(actor.tenantId == tenantId) {
            "POS payment tenant must match the active tenant context"
        }
        val amount = request.amount.positiveMoney("amount")
        val transactionId = UUID.randomUUID()
        val internalReference = paymentReference(transactionId)
        jdbcTemplate.update(
            """
            INSERT INTO payment_transactions (
                id, tenant_id, property_id, pos_order_id, initiated_by,
                idempotency_key_id, transaction_direction, transaction_type,
                internal_reference, amount, currency, status, posted_at,
                confirmed_at,
                metadata
            )
            VALUES (?, ?, ?, ?, ?, ?, 'inbound', 'collection', ?, ?, 'TZS',
                    'posted', now(), now(), ?::jsonb)
            """.trimIndent(),
            transactionId,
            actor.tenantId,
            propertyId,
            request.posOrderId,
            actor.tenantUserId,
            idempotencyKeyId,
            internalReference,
            amount,
            objectMapper.writeValueAsString(mapOf("method" to "pos_cash")),
        )
        return requireTransaction(actor.tenantId, propertyId, transactionId, lock = false)
            .also {
                recordSideEffects(
                    actor = actor,
                    propertyId = propertyId,
                    action = "payments.pos.cash.collected",
                    aggregateType = PAYMENT_TRANSACTIONS,
                    aggregateId = transactionId,
                    payload = mapOf(
                        "transactionId" to transactionId,
                        "posOrderId" to request.posOrderId,
                        "amount" to amount,
                    ),
                    idempotencyKeyId = idempotencyKeyId,
                )
            }
    }

    override fun initiatePosMobileMoney(
        tenantId: UUID,
        propertyId: UUID,
        request: InitiatePosMobileMoneyRequest,
        idempotencyKeyId: UUID,
    ): PaymentTransactionResponse {
        val actor = bindActor(propertyId, lockProperty = false)
        require(actor.tenantId == tenantId) {
            "POS payment tenant must match the active tenant context"
        }
        val amount = request.amount.positiveMoney("amount")
        requireProviderAccount(
            actor.tenantId,
            propertyId,
            request.providerAccountId,
            lock = false,
        )
        val transactionId = UUID.randomUUID()
        val internalReference = paymentReference(transactionId)
        jdbcTemplate.update(
            """
            INSERT INTO payment_transactions (
                id, tenant_id, property_id, pos_order_id, provider_account_id,
                initiated_by, idempotency_key_id, transaction_direction,
                transaction_type, internal_reference, payer_identifier,
                amount, currency, status, expires_at, next_status_check_at,
                metadata
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, 'inbound', 'collection', ?, ?, ?,
                    'TZS', 'created', now() + interval '15 minutes',
                    now() + interval '15 seconds', ?::jsonb)
            """.trimIndent(),
            transactionId,
            actor.tenantId,
            propertyId,
            request.posOrderId,
            request.providerAccountId,
            actor.tenantUserId,
            idempotencyKeyId,
            internalReference,
            request.phoneNumber.tanzanianE164(),
            amount,
            objectMapper.writeValueAsString(mapOf("method" to "pos_mobile_money")),
        )
        outboxPort.enqueue(
            OutboxEventCommand(
                aggregateType = PAYMENT_TRANSACTIONS,
                aggregateId = transactionId,
                tenantId = actor.tenantId,
                propertyId = propertyId,
                eventType = PAYMENT_COLLECTION_REQUESTED,
                destination = OutboxDestination.PAYMENT,
                payload = mapOf(
                    "transactionId" to transactionId,
                    "posOrderId" to request.posOrderId,
                    "providerAccountId" to request.providerAccountId,
                ),
                idempotencyKeyId = idempotencyKeyId,
                priority = 2,
            ),
        )
        return requireTransaction(actor.tenantId, propertyId, transactionId, lock = false)
            .also {
                auditPort.recordTenantEvent(
                    TenantAuditEvent(
                        tenantId = actor.tenantId,
                        action = "payments.pos.mobile_money.initiated",
                        resource = AuditResource(PAYMENT_TRANSACTIONS, transactionId),
                        after = mapOf(
                            "transactionId" to transactionId,
                            "posOrderId" to request.posOrderId,
                            "providerAccountId" to request.providerAccountId,
                            "amount" to amount,
                        ),
                    ),
                )
            }
    }

    override fun reversePayment(
        propertyId: UUID,
        transactionId: UUID,
        request: ReversePaymentRequest,
    ): PaymentTransactionResponse {
        return mutate(
            propertyId = propertyId,
            operationType = "payments.transaction.reverse",
            requestPayload = mapOf("transactionId" to transactionId, "request" to request),
            resourceType = PAYMENT_TRANSACTIONS,
            replayType = PaymentTransactionResponse::class.java,
        ) { actor, idempotencyKeyId ->
            val reason = request.reason.normalizedRequired("reason")
            require(reason.length in MIN_REVERSAL_REASON_LENGTH..MAX_REVERSAL_REASON_LENGTH) {
                "Reversal reason must be between $MIN_REVERSAL_REASON_LENGTH and " +
                        "$MAX_REVERSAL_REASON_LENGTH characters"
            }
            val original = requireTransaction(
                tenantId = actor.tenantId,
                propertyId = propertyId,
                transactionId = transactionId,
                lock = true,
            )
            require(
                original.transactionType == "collection" &&
                        original.status == PaymentStatus.POSTED,
            ) {
                "Only an unreconciled posted collection can be reversed"
            }
            require(original.folioId != null && original.posOrderId == null) {
                "POS payments must be reversed through a dedicated POS void/refund workflow"
            }
            val existingReversal = jdbcTemplate.queryForObject(
                """
                SELECT EXISTS (
                    SELECT 1
                    FROM payment_transactions
                    WHERE tenant_id = ?
                      AND reversal_of_transaction_id = ?
                )
                """.trimIndent(),
                Boolean::class.java,
                actor.tenantId,
                transactionId,
            ) == true
            if (existingReversal) {
                throw PaymentConflictException("Payment has already been reversed")
            }

            val isCash = original.providerAccountId == null
            val cashSession = if (isCash) {
                val sessionId = requireNotNull(request.cashSessionId) {
                    "A current cash session is required for a cash reversal"
                }
                requireCashSession(actor.tenantId, propertyId, sessionId, lock = true)
                    .also {
                        require(it.status == "open" && it.cashierId == actor.tenantUserId) {
                            "Cash reversal must use the current cashier's open session"
                        }
                        require(it.expectedCash >= original.amount) {
                            "Cash session does not contain enough expected cash for this reversal"
                        }
                    }
            } else {
                require(request.cashSessionId == null) {
                    "Mobile-money reversal cannot use a cash session"
                }
                null
            }
            val externalReference = request.externalReference?.trimmedOrNull()
            if (!isCash) {
                requireNotNull(externalReference) {
                    "Mobile-money reversal requires a provider or manual refund reference"
                }.normalizedProviderReference()
            }

            val reversalId = UUID.randomUUID()
            val reversalReference = paymentReference(reversalId)
            jdbcTemplate.update(
                """
                INSERT INTO payment_transactions (
                    id, tenant_id, property_id, folio_id, provider_account_id,
                    initiated_by, idempotency_key_id, reversal_of_transaction_id,
                    transaction_direction, transaction_type, provider_reference,
                    internal_reference, amount, currency, status, posted_at,
                    confirmed_at, metadata
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'outbound', 'reversal', ?, ?, ?, ?,
                        'posted', now(), now(), ?::jsonb)
                """.trimIndent(),
                reversalId,
                actor.tenantId,
                propertyId,
                original.folioId,
                original.providerAccountId,
                actor.tenantUserId,
                idempotencyKeyId,
                transactionId,
                externalReference,
                reversalReference,
                original.amount,
                original.currency,
                objectMapper.writeValueAsString(
                    mapOf(
                        "reason" to reason,
                        "approvedBy" to actor.tenantUserId,
                        "cashSessionId" to cashSession?.id,
                    ),
                ),
            )
            val reversalFolioPaymentId = billingPort.reverseConfirmedPayment(
                tenantId = actor.tenantId,
                propertyId = propertyId,
                request = ConfirmedPaymentReversalRequest(
                    originalPaymentTransactionId = transactionId,
                    reversalPaymentTransactionId = reversalId,
                    processedBy = actor.tenantUserId,
                    reason = reason,
                    referenceNumber = externalReference ?: reversalReference,
                    cashSessionId = cashSession?.id,
                ),
                idempotencyKeyId = idempotencyKeyId,
            )
            jdbcTemplate.update(
                """
                UPDATE payment_transactions
                SET status = 'reversed',
                    reversed_at = now(),
                    updated_at = now()
                WHERE tenant_id = ? AND id = ? AND status = 'posted'
                """.trimIndent(),
                actor.tenantId,
                transactionId,
            )
            jdbcTemplate.update(
                """
                UPDATE payment_transactions
                SET folio_payment_id = ?, updated_at = now()
                WHERE tenant_id = ? AND id = ?
                """.trimIndent(),
                reversalFolioPaymentId,
                actor.tenantId,
                reversalId,
            )
            if (cashSession != null) {
                jdbcTemplate.update(
                    """
                    UPDATE cash_sessions
                    SET expected_cash = expected_cash - ?,
                        updated_at = now()
                    WHERE tenant_id = ? AND id = ? AND status = 'open'
                    """.trimIndent(),
                    original.amount,
                    actor.tenantId,
                    cashSession.id,
                )
            }
            requireTransaction(actor.tenantId, propertyId, reversalId, lock = false)
                .also {
                    recordSideEffects(
                        actor = actor,
                        propertyId = propertyId,
                        action = "payments.transaction.reversed",
                        aggregateType = PAYMENT_TRANSACTIONS,
                        aggregateId = reversalId,
                        payload = mapOf(
                            "transactionId" to reversalId,
                            "reversalOfTransactionId" to transactionId,
                            "amount" to original.amount,
                            "reason" to reason,
                            "externalReference" to externalReference,
                        ),
                        idempotencyKeyId = idempotencyKeyId,
                    )
                }
        }
    }

    override fun refundPayment(
        propertyId: UUID,
        transactionId: UUID,
        request: RefundPaymentRequest,
    ): PaymentTransactionResponse {
        return mutate(
            propertyId = propertyId,
            operationType = "payments.transaction.refund",
            requestPayload = mapOf(
                "transactionId" to transactionId,
                "request" to request,
            ),
            resourceType = PAYMENT_TRANSACTIONS,
            replayType = PaymentTransactionResponse::class.java,
        ) { actor, idempotencyKeyId ->
            val amount = request.amount.positiveMoney("amount")
            val reason = request.reason.normalizedRequired("reason")
            require(reason.length in MIN_REFUND_REASON_LENGTH..MAX_REFUND_REASON_LENGTH) {
                "Refund reason must be between $MIN_REFUND_REASON_LENGTH and " +
                        "$MAX_REFUND_REASON_LENGTH characters"
            }
            val original = requireTransaction(
                actor.tenantId,
                propertyId,
                transactionId,
                lock = true,
            )
            require(original.transactionType == "collection") {
                "Only collection transactions can be refunded"
            }
            require(
                original.status in setOf(
                    PaymentStatus.POSTED,
                    PaymentStatus.RECONCILED,
                    PaymentStatus.PARTIALLY_REFUNDED,
                ),
            ) {
                "Payment is not refundable in its current state"
            }
            require(original.folioId != null && original.posOrderId == null) {
                "POS refunds require the dedicated POS refund workflow"
            }
            val remaining = original.amount.subtract(original.refundedAmount).money()
            require(amount <= remaining) {
                "Refund amount exceeds the remaining refundable amount"
            }

            val isCash = original.providerAccountId == null
            val cashSession = if (isCash) {
                val cashSessionId = requireNotNull(request.cashSessionId) {
                    "Cash refunds require an open cashier session"
                }
                require(request.providerEvidence.isNullOrBlank()) {
                    "Cash refunds do not accept provider evidence"
                }
                requireCashSession(
                    actor.tenantId,
                    propertyId,
                    cashSessionId,
                    lock = true,
                ).also {
                    require(
                        it.status == "open" &&
                                it.cashierId == actor.tenantUserId,
                    ) {
                        "Cash refund must use the current cashier's open session"
                    }
                    require(it.expectedCash >= amount) {
                        "Cash session does not contain enough expected cash"
                    }
                }
            } else {
                require(request.cashSessionId == null) {
                    "Mobile-money refunds cannot use a cash session"
                }
                null
            }
            val providerEvidence = if (isCash) {
                null
            } else {
                requireNotNull(request.providerEvidence) {
                    "Mobile-money refunds require external provider evidence"
                }
                    .normalizedRequired("providerEvidence")
                    .normalizedProviderReference()
            }

            val refundId = UUID.randomUUID()
            val refundReference = paymentReference(refundId)
            jdbcTemplate.update(
                """
                INSERT INTO payment_transactions (
                    id, tenant_id, property_id, folio_id, provider_account_id,
                    initiated_by, idempotency_key_id, refund_of_transaction_id,
                    transaction_direction, transaction_type, provider_reference,
                    internal_reference, amount, currency, status, posted_at,
                    confirmed_at, external_refund_evidence, refund_reason,
                    metadata
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'outbound', 'refund', ?, ?, ?,
                        ?, 'posted', now(), now(), ?, ?, ?::jsonb)
                """.trimIndent(),
                refundId,
                actor.tenantId,
                propertyId,
                original.folioId,
                original.providerAccountId,
                actor.tenantUserId,
                idempotencyKeyId,
                transactionId,
                providerEvidence,
                refundReference,
                amount,
                original.currency,
                providerEvidence,
                reason,
                objectMapper.writeValueAsString(
                    mapOf(
                        "method" to if (isCash) "cash" else "mobile_money",
                        "cashSessionId" to cashSession?.id,
                    ),
                ),
            )
            val folioRefundId = billingPort.postConfirmedRefund(
                tenantId = actor.tenantId,
                propertyId = propertyId,
                request = ConfirmedPaymentRefundRequest(
                    originalPaymentTransactionId = transactionId,
                    refundPaymentTransactionId = refundId,
                    amount = amount,
                    processedBy = actor.tenantUserId,
                    reason = reason,
                    referenceNumber = providerEvidence ?: refundReference,
                    cashSessionId = cashSession?.id,
                ),
                idempotencyKeyId = idempotencyKeyId,
            )
            val cumulativeRefund = original.refundedAmount.add(amount).money()
            val nextStatus = if (cumulativeRefund == original.amount) {
                PaymentStatus.REFUNDED
            } else {
                PaymentStatus.PARTIALLY_REFUNDED
            }
            jdbcTemplate.update(
                """
                UPDATE payment_transactions
                SET refunded_amount = ?,
                    status = ?,
                    updated_at = now()
                WHERE tenant_id = ? AND id = ?
                """.trimIndent(),
                cumulativeRefund,
                nextStatus.databaseValue,
                actor.tenantId,
                transactionId,
            )
            jdbcTemplate.update(
                """
                UPDATE payment_transactions
                SET folio_payment_id = ?, updated_at = now()
                WHERE tenant_id = ? AND id = ?
                """.trimIndent(),
                folioRefundId,
                actor.tenantId,
                refundId,
            )
            if (cashSession != null) {
                jdbcTemplate.update(
                    """
                    UPDATE cash_sessions
                    SET expected_cash = expected_cash - ?, updated_at = now()
                    WHERE tenant_id = ? AND id = ? AND status = 'open'
                    """.trimIndent(),
                    amount,
                    actor.tenantId,
                    cashSession.id,
                )
            }
            meterRegistry.counter(
                "peak.payment.refund",
                "method",
                if (isCash) "cash" else "mobile_money",
                "result",
                "posted",
            ).increment()
            requireTransaction(
                actor.tenantId,
                propertyId,
                refundId,
                lock = false,
            ).also {
                recordSideEffects(
                    actor = actor,
                    propertyId = propertyId,
                    action = "payments.transaction.refunded",
                    aggregateType = PAYMENT_TRANSACTIONS,
                    aggregateId = refundId,
                    payload = mapOf(
                        "refundTransactionId" to refundId,
                        "refundOfTransactionId" to transactionId,
                        "amount" to amount,
                        "originalStatus" to nextStatus.databaseValue,
                        "reason" to reason,
                    ),
                    idempotencyKeyId = idempotencyKeyId,
                )
            }
        }
    }

    override fun getTransaction(
        propertyId: UUID,
        transactionId: UUID,
    ): PaymentTransactionResponse? {
        return read(propertyId) { actor ->
            findTransaction(actor.tenantId, propertyId, transactionId, lock = false)
        }
    }

    override fun listTransactions(
        propertyId: UUID,
        limit: Int,
    ): List<PaymentTransactionResponse> {
        require(limit in 1..200) {
            "limit must be between 1 and 200"
        }
        return read(propertyId) { actor ->
            jdbcTemplate.query(
                """
                $PAYMENT_TRANSACTION_SELECT
                WHERE tenant_id = ?
                  AND property_id = ?
                ORDER BY initiated_at DESC, id DESC
                LIMIT ?
                """.trimIndent(),
                ::mapTransaction,
                actor.tenantId,
                propertyId,
                limit,
            )
        }
    }

    override fun configureProvider(
        propertyId: UUID,
        request: ConfigurePaymentProviderRequest,
    ): PaymentProviderAccountResponse {
        return mutate(
            propertyId = propertyId,
            operationType = "payments.provider.configure",
            requestPayload = request,
            resourceType = PAYMENT_PROVIDER_ACCOUNTS,
            replayType = PaymentProviderAccountResponse::class.java,
        ) { actor, idempotencyKeyId ->
            val providerCode = request.providerCode.normalizedCode()
            require(providerCode in providerCodes) {
                "Payment provider adapter is unavailable for $providerCode"
            }
            if (environment.activeProfiles.contains("prod")) {
                require(providerCode != CONTRACT_MOCK_PROVIDER) {
                    "Mock payment providers are forbidden in production"
                }
            }
            val apiKeySecretRef = (
                request.apiKeySecretRef ?: request.secretRef
            )?.normalizedRequired("apiKeySecretRef")
                ?: throw PaymentRejectedException("apiKeySecretRef is required")
            val checksumKeySecretRef = (
                request.checksumKeySecretRef ?: request.webhookSecretRef
            )?.normalizedRequired("checksumKeySecretRef")
                ?: throw PaymentRejectedException(
                    "checksumKeySecretRef is required",
                )
            secretResolver.validate(apiKeySecretRef)
            secretResolver.validate(checksumKeySecretRef)
            val clientId = (
                request.clientId ?: request.merchantId
            )?.normalizedRequired("clientId")
                ?: throw PaymentRejectedException("clientId is required")
            val providerEnvironment = request.environment.trim().lowercase()
            require(providerEnvironment in setOf("sandbox", "production")) {
                "environment must be sandbox or production"
            }
            if (environment.activeProfiles.contains("prod")) {
                require(providerEnvironment == "production") {
                    "Sandbox payment accounts are forbidden in production"
                }
            }
            if (providerEnvironment == "production") {
                require(
                    providerCode in environment.approvedProviderCodes(
                        "peak.payment.production-approved-provider-codes",
                    ),
                ) {
                    "Payment provider is not approved for production"
                }
                require(
                    request.sandboxCertifiedAt != null &&
                            !request.sandboxEvidenceRef.isNullOrBlank(),
                ) {
                    "Production provider accounts require sandbox certification evidence"
                }
            }
            val endpointUrl = request.endpointUrl.trimmedOrNull()
                ?: if (providerCode == CLICKPESA_PROVIDER) {
                    CLICKPESA_ENDPOINT
                } else {
                    null
                }
            if (providerCode in setOf(HTTP_GATEWAY_PROVIDER, CLICKPESA_PROVIDER)) {
                outboundEndpointPolicy.requireAllowedProviderEndpoint(
                    URI.create(endpointUrl.orEmpty()),
                )
            }
            val providerId = jdbcTemplate.queryForObject(
                """
                INSERT INTO payment_providers (
                    tenant_id, provider_code, name, provider_type,
                    country_code, supported_currencies, supports_collections
                )
                VALUES (?, ?, ?, 'mobile_money', 'TZ', ARRAY['TZS'], true)
                ON CONFLICT (tenant_id, provider_code)
                DO UPDATE SET
                    name = EXCLUDED.name,
                    is_active = true,
                    updated_at = now()
                RETURNING id
                """.trimIndent(),
                UUID::class.java,
                actor.tenantId,
                providerCode,
                request.providerName.normalizedRequired("providerName"),
            ) ?: error("Payment provider id was not returned")
            if (request.isDefault) {
                jdbcTemplate.update(
                    """
                    UPDATE payment_provider_accounts
                    SET is_default = false, updated_at = now()
                    WHERE tenant_id = ? AND property_id = ? AND is_default = true
                    """.trimIndent(),
                    actor.tenantId,
                    propertyId,
                )
            }
            val accountId = UUID.randomUUID()
            try {
                jdbcTemplate.update(
                    """
                    INSERT INTO payment_provider_accounts (
                        id, tenant_id, property_id, provider_id, account_name,
                        endpoint_url, merchant_id, wallet_number, secret_ref,
                        webhook_secret_ref, client_id, api_key_secret_ref,
                        checksum_key_secret_ref, environment,
                        sandbox_certified_at, sandbox_evidence_ref,
                        is_default, is_active
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                            true)
                    """.trimIndent(),
                    accountId,
                    actor.tenantId,
                    propertyId,
                    providerId,
                    request.accountName.normalizedRequired("accountName"),
                    endpointUrl,
                    clientId,
                    request.walletNumber.trimmedOrNull(),
                    apiKeySecretRef,
                    checksumKeySecretRef,
                    clientId,
                    apiKeySecretRef,
                    checksumKeySecretRef,
                    providerEnvironment,
                    request.sandboxCertifiedAt?.let(Timestamp::from),
                    request.sandboxEvidenceRef.trimmedOrNull(),
                    request.isDefault,
                )
            } catch (ex: DuplicateKeyException) {
                throw PaymentConflictException("Payment provider account name is already in use")
            }
            requireProviderAccount(actor.tenantId, propertyId, accountId, lock = false)
                .also {
                    recordSideEffects(
                        actor = actor,
                        propertyId = propertyId,
                        action = "payments.provider.configured",
                        aggregateType = PAYMENT_PROVIDER_ACCOUNTS,
                        aggregateId = accountId,
                        payload = mapOf(
                            "providerAccountId" to accountId,
                            "providerCode" to providerCode,
                            "isDefault" to request.isDefault,
                        ),
                        idempotencyKeyId = idempotencyKeyId,
                    )
                }
        }
    }

    override fun listProviderAccounts(propertyId: UUID): List<PaymentProviderAccountResponse> {
        return read(propertyId) { actor ->
            jdbcTemplate.query(
                """
                $PAYMENT_PROVIDER_ACCOUNT_SELECT
                WHERE ppa.tenant_id = ?
                  AND ppa.property_id = ?
                ORDER BY ppa.is_default DESC, pp.name, ppa.account_name
                """.trimIndent(),
                ::mapProviderAccount,
                actor.tenantId,
                propertyId,
            )
        }
    }

    override fun createReconciliation(
        propertyId: UUID,
        request: CreatePaymentReconciliationRequest,
    ): PaymentReconciliationResponse {
        return mutate(
            propertyId = propertyId,
            operationType = "payments.reconciliation.create",
            requestPayload = request,
            resourceType = PAYMENT_RECONCILIATIONS,
            replayType = PaymentReconciliationResponse::class.java,
        ) { actor, idempotencyKeyId ->
            require(request.items.isNotEmpty()) {
                "Reconciliation requires at least one statement item"
            }
            require(request.items.size <= 1000) {
                "Reconciliation cannot exceed 1000 statement items"
            }
            requireProviderAccount(
                actor.tenantId,
                propertyId,
                request.providerAccountId,
                lock = false,
            )
            val id = UUID.randomUUID()
            val providerTotal = request.items.fold(BigDecimal.ZERO) { total, item ->
                total.add(item.providerAmount.nonNegativeMoney("providerAmount"))
            }.money()
            var systemTotal = BigDecimal.ZERO
            val matches = request.items.map { item ->
                val providerReference = item.providerReference.normalizedRequired("providerReference")
                val transaction = jdbcTemplate.query(
                    """
                    SELECT id, folio_payment_id, amount
                    FROM payment_transactions
                    WHERE tenant_id = ?
                      AND property_id = ?
                      AND provider_account_id = ?
                      AND provider_reference = ?
                      AND status IN ('posted', 'reconciled')
                    """.trimIndent(),
                    { rs, _ ->
                        ReconciliationMatch(
                            transactionId = rs.getObject("id", UUID::class.java),
                            folioPaymentId = rs.getObject("folio_payment_id", UUID::class.java),
                            amount = rs.getBigDecimal("amount").money(),
                        )
                    },
                    actor.tenantId,
                    propertyId,
                    request.providerAccountId,
                    providerReference,
                ).singleOrNull()
                if (transaction != null) {
                    systemTotal = systemTotal.add(transaction.amount)
                }
                Triple(item, providerReference, transaction)
            }
            systemTotal = systemTotal.money()
            val variance = providerTotal.subtract(systemTotal).money()
            val status = if (
                variance.compareTo(BigDecimal.ZERO) == 0 &&
                matches.all { it.third != null && it.first.providerAmount.money() == it.third?.amount }
            ) {
                "matched"
            } else {
                "variance"
            }
            jdbcTemplate.update(
                """
                INSERT INTO payment_reconciliations (
                    id, tenant_id, property_id, provider_account_id,
                    reconciliation_date, statement_reference, opening_balance,
                    provider_total, system_total, currency, status, notes
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'TZS', ?, ?)
                """.trimIndent(),
                id,
                actor.tenantId,
                propertyId,
                request.providerAccountId,
                request.reconciliationDate,
                request.statementReference.normalizedRequired("statementReference"),
                request.openingBalance.nonNegativeMoney("openingBalance"),
                providerTotal,
                systemTotal,
                status,
                request.notes.trimmedOrNull(),
            )
            matches.forEach { (item, providerReference, transaction) ->
                val providerAmount = item.providerAmount.money()
                val systemAmount = transaction?.amount ?: BigDecimal.ZERO.setScale(2)
                val matchStatus = when {
                    transaction == null -> "unmatched"
                    providerAmount.compareTo(systemAmount) == 0 -> "matched"
                    else -> "variance"
                }
                jdbcTemplate.update(
                    """
                    INSERT INTO payment_reconciliation_items (
                        id, tenant_id, reconciliation_id, payment_transaction_id,
                        folio_payment_id, provider_reference, item_date,
                        provider_amount, system_amount, match_status
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """.trimIndent(),
                    UUID.randomUUID(),
                    actor.tenantId,
                    id,
                    transaction?.transactionId,
                    transaction?.folioPaymentId,
                    providerReference,
                    Timestamp.from(item.itemDate),
                    providerAmount,
                    systemAmount,
                    matchStatus,
                )
            }
            requireReconciliation(actor.tenantId, propertyId, id, lock = false)
                .also {
                    recordSideEffects(
                        actor = actor,
                        propertyId = propertyId,
                        action = "payments.reconciliation.created",
                        aggregateType = PAYMENT_RECONCILIATIONS,
                        aggregateId = id,
                        payload = mapOf(
                            "reconciliationId" to id,
                            "providerTotal" to providerTotal,
                            "systemTotal" to systemTotal,
                            "variance" to variance,
                            "status" to status,
                        ),
                        idempotencyKeyId = idempotencyKeyId,
                    )
                }
        }
    }

    override fun approveReconciliation(
        propertyId: UUID,
        reconciliationId: UUID,
    ): PaymentReconciliationResponse {
        return mutate(
            propertyId = propertyId,
            operationType = "payments.reconciliation.approve",
            requestPayload = mapOf("reconciliationId" to reconciliationId),
            resourceType = PAYMENT_RECONCILIATIONS,
            replayType = PaymentReconciliationResponse::class.java,
        ) { actor, idempotencyKeyId ->
            val reconciliation = requireReconciliation(
                actor.tenantId,
                propertyId,
                reconciliationId,
                lock = true,
            )
            require(reconciliation.status == "matched") {
                "Only a matched zero-variance reconciliation can be approved"
            }
            require(reconciliation.variance.compareTo(BigDecimal.ZERO) == 0) {
                "Reconciliation variance must be zero before approval"
            }
            jdbcTemplate.update(
                """
                UPDATE payment_reconciliations
                SET status = 'approved',
                    reconciled_by = ?,
                    reconciled_at = now(),
                    updated_at = now()
                WHERE tenant_id = ?
                  AND property_id = ?
                  AND id = ?
                  AND status = 'matched'
                """.trimIndent(),
                actor.tenantUserId,
                actor.tenantId,
                propertyId,
                reconciliationId,
            )
            jdbcTemplate.update(
                """
                UPDATE payment_transactions pt
                SET status = 'reconciled',
                    reconciled_at = now(),
                    updated_at = now()
                FROM payment_reconciliation_items pri
                WHERE pri.tenant_id = pt.tenant_id
                  AND pri.payment_transaction_id = pt.id
                  AND pri.reconciliation_id = ?
                  AND pri.match_status = 'matched'
                  AND pt.tenant_id = ?
                  AND pt.property_id = ?
                  AND pt.status = 'posted'
                """.trimIndent(),
                reconciliationId,
                actor.tenantId,
                propertyId,
            )
            requireReconciliation(actor.tenantId, propertyId, reconciliationId, lock = false)
                .also {
                    recordSideEffects(
                        actor = actor,
                        propertyId = propertyId,
                        action = "payments.reconciliation.approved",
                        aggregateType = PAYMENT_RECONCILIATIONS,
                        aggregateId = reconciliationId,
                        payload = mapOf(
                            "reconciliationId" to reconciliationId,
                            "variance" to it.variance,
                        ),
                        idempotencyKeyId = idempotencyKeyId,
                    )
                }
        }
    }

    override fun listReconciliations(
        propertyId: UUID,
        limit: Int,
    ): List<PaymentReconciliationResponse> {
        require(limit in 1..200) {
            "limit must be between 1 and 200"
        }
        return read(propertyId) { actor ->
            jdbcTemplate.query(
                """
                SELECT id, property_id, provider_account_id,
                       reconciliation_date, statement_reference,
                       provider_total, system_total, variance, status
                FROM payment_reconciliations
                WHERE tenant_id = ? AND property_id = ?
                ORDER BY reconciliation_date DESC, created_at DESC
                LIMIT ?
                """.trimIndent(),
                ::mapReconciliation,
                actor.tenantId,
                propertyId,
                limit,
            )
        }
    }

    override fun getReconciliation(
        propertyId: UUID,
        reconciliationId: UUID,
    ): PaymentReconciliationResponse? {
        return read(propertyId) { actor ->
            jdbcTemplate.query(
                """
                SELECT id, property_id, provider_account_id,
                       reconciliation_date, statement_reference,
                       provider_total, system_total, variance, status
                FROM payment_reconciliations
                WHERE tenant_id = ?
                  AND property_id = ?
                  AND id = ?
                """.trimIndent(),
                ::mapReconciliation,
                actor.tenantId,
                propertyId,
                reconciliationId,
            ).singleOrNull()
        }
    }

    override fun importReconciliation(
        propertyId: UUID,
        request: ImportPaymentReconciliationRequest,
    ): PaymentReconciliationImportResponse {
        return mutate(
            propertyId = propertyId,
            operationType = "payments.reconciliation.import",
            requestPayload = request,
            resourceType = PAYMENT_RECONCILIATIONS,
            replayType = PaymentReconciliationImportResponse::class.java,
        ) { actor, idempotencyKeyId ->
            require(!request.endDate.isBefore(request.startDate)) {
                "endDate must not be before startDate"
            }
            require(request.endDate.toEpochDay() - request.startDate.toEpochDay() <= 31) {
                "Statement import range cannot exceed 31 days"
            }
            require(request.currency.uppercase() == "TZS") {
                "Only TZS statement imports are supported"
            }
            val account = requireProviderAccount(
                actor.tenantId,
                propertyId,
                request.providerAccountId,
                lock = false,
            )
            require(account.providerCode == CLICKPESA_PROVIDER) {
                "Statement imports are supported only for ClickPesa"
            }
            val importId = UUID.randomUUID()
            outboxPort.enqueue(
                OutboxEventCommand(
                    aggregateType = PAYMENT_RECONCILIATIONS,
                    aggregateId = importId,
                    tenantId = actor.tenantId,
                    propertyId = propertyId,
                    eventType = PAYMENT_RECONCILIATION_IMPORT_REQUESTED,
                    destination = OutboxDestination.PAYMENT,
                    payload = mapOf(
                        "importId" to importId,
                        "providerAccountId" to request.providerAccountId,
                        "startDate" to request.startDate,
                        "endDate" to request.endDate,
                        "currency" to "TZS",
                    ),
                    idempotencyKeyId = idempotencyKeyId,
                    priority = 3,
                ),
            )
            PaymentReconciliationImportResponse(
                importId = importId,
                providerAccountId = request.providerAccountId,
                status = "accepted",
            )
        }
    }

    private fun <T : Any> mutate(
        propertyId: UUID,
        operationType: String,
        requestPayload: Any,
        resourceType: String,
        replayType: Class<T>,
        block: (TenantActor, UUID) -> T,
    ): T {
        return requireNotNull(
            transactionTemplate.execute {
                val actor = bindActor(propertyId, lockProperty = true)
                when (
                    val reservation = idempotencyPort.reserve(
                        IdempotencyCommand(
                            operationType = operationType,
                            requestPayload = requestPayload,
                            resourceType = resourceType,
                        ),
                    )
                ) {
                    is IdempotencyReservation.Started -> {
                        val response = block(actor, reservation.recordId)
                        idempotencyPort.markSucceeded(
                            recordId = reservation.recordId,
                            responseCode = 200,
                            responseBody = response,
                            resourceId = resourceId(response),
                        )
                        recordCommandMetric(operationType, "succeeded")
                        response
                    }

                    is IdempotencyReservation.Replay -> {
                        if (reservation.responseBody.isNullOrBlank()) {
                            throw PaymentConflictException(
                                "Payment replay does not contain a stored response body",
                            )
                        }
                        objectMapper.readValue(reservation.responseBody, replayType)
                            .withReplayFlag()
                            .also { recordCommandMetric(operationType, "replayed") }
                    }

                    is IdempotencyReservation.InProgress -> {
                        recordCommandMetric(operationType, "in_progress")
                        throw PaymentConflictException(
                            "Payment command is already being processed",
                        )
                    }

                    is IdempotencyReservation.Conflict -> {
                        recordCommandMetric(operationType, "conflict")
                        throw PaymentConflictException(
                            "Idempotency key was used for a different payment command",
                        )
                    }
                }
            },
        )
    }

    private fun recordCommandMetric(operationType: String, result: String) {
        meterRegistry.counter(
            "peak.payment.command",
            "operation",
            operationType,
            "result",
            result,
        ).increment()
    }

    private fun <T> read(propertyId: UUID, block: (TenantActor) -> T): T {
        return requireNotNull(
            transactionTemplate.execute {
                block(bindActor(propertyId, lockProperty = false))
            },
        )
    }

    private fun bindActor(propertyId: UUID, lockProperty: Boolean): TenantActor {
        val actor = tenantRequestContext.bind()
        tenantRequestContext.requirePropertyUsable(actor.tenantId, propertyId, lockProperty)
        return actor
    }

    private fun requirePayableFolio(
        tenantId: UUID,
        propertyId: UUID,
        folioId: UUID,
        amount: BigDecimal,
    ): PayableFolio {
        val folio = jdbcTemplate.query(
            """
            SELECT currency_code, total_amount, total_paid, status
            FROM folios
            WHERE tenant_id = ?
              AND property_id = ?
              AND id = ?
              AND deleted_at IS NULL
            FOR UPDATE
            """.trimIndent(),
            { rs, _ ->
                PayableFolio(
                    currency = rs.getString("currency_code").trim(),
                    balanceDue = rs.getBigDecimal("total_amount")
                        .subtract(rs.getBigDecimal("total_paid"))
                        .money(),
                    status = rs.getString("status"),
                )
            },
            tenantId,
            propertyId,
            folioId,
        ).singleOrNull() ?: throw PaymentNotFoundException("Folio was not found")
        require(folio.status == "open") {
            "Payment can be collected only against an open folio"
        }
        require(folio.balanceDue > BigDecimal.ZERO) {
            "Folio has no outstanding balance"
        }
        require(amount <= folio.balanceDue) {
            "Payment amount exceeds the outstanding folio balance"
        }
        return folio
    }

    private fun requireCashSession(
        tenantId: UUID,
        propertyId: UUID,
        cashSessionId: UUID,
        lock: Boolean,
    ): CashSessionResponse {
        val lockClause = if (lock) "FOR UPDATE" else ""
        return jdbcTemplate.query(
            """
            SELECT *
            FROM cash_sessions
            WHERE tenant_id = ?
              AND property_id = ?
              AND id = ?
            $lockClause
            """.trimIndent(),
            ::mapCashSession,
            tenantId,
            propertyId,
            cashSessionId,
        ).singleOrNull() ?: throw PaymentNotFoundException("Cash session was not found")
    }

    private fun requireProviderAccount(
        tenantId: UUID,
        propertyId: UUID,
        providerAccountId: UUID,
        lock: Boolean,
    ): PaymentProviderAccountResponse {
        val lockClause = if (lock) "FOR UPDATE OF ppa" else ""
        return jdbcTemplate.query(
            """
            $PAYMENT_PROVIDER_ACCOUNT_SELECT
            WHERE ppa.tenant_id = ?
              AND ppa.property_id = ?
              AND ppa.id = ?
              AND ppa.is_active = true
              AND pp.is_active = true
            $lockClause
            """.trimIndent(),
            ::mapProviderAccount,
            tenantId,
            propertyId,
            providerAccountId,
        ).singleOrNull() ?: throw PaymentNotFoundException(
            "Active payment provider account was not found",
        )
    }

    private fun findTransaction(
        tenantId: UUID,
        propertyId: UUID,
        transactionId: UUID,
        lock: Boolean,
    ): PaymentTransactionResponse? {
        val lockClause = if (lock) "FOR UPDATE" else ""
        return jdbcTemplate.query(
            """
            $PAYMENT_TRANSACTION_SELECT
            WHERE tenant_id = ?
              AND property_id = ?
              AND id = ?
            $lockClause
            """.trimIndent(),
            ::mapTransaction,
            tenantId,
            propertyId,
            transactionId,
        ).singleOrNull()
    }

    private fun requireTransaction(
        tenantId: UUID,
        propertyId: UUID,
        transactionId: UUID,
        lock: Boolean,
    ): PaymentTransactionResponse {
        return findTransaction(tenantId, propertyId, transactionId, lock)
            ?: throw PaymentNotFoundException("Payment transaction was not found")
    }

    private fun requireReconciliation(
        tenantId: UUID,
        propertyId: UUID,
        reconciliationId: UUID,
        lock: Boolean,
    ): PaymentReconciliationResponse {
        val lockClause = if (lock) "FOR UPDATE" else ""
        return jdbcTemplate.query(
            """
            SELECT id, property_id, provider_account_id, reconciliation_date,
                   statement_reference, provider_total, system_total, variance, status
            FROM payment_reconciliations
            WHERE tenant_id = ?
              AND property_id = ?
              AND id = ?
            $lockClause
            """.trimIndent(),
            ::mapReconciliation,
            tenantId,
            propertyId,
            reconciliationId,
        ).singleOrNull() ?: throw PaymentNotFoundException(
            "Payment reconciliation was not found",
        )
    }

    private fun recordSideEffects(
        actor: TenantActor,
        propertyId: UUID,
        action: String,
        aggregateType: String,
        aggregateId: UUID,
        payload: Map<String, Any?>,
        idempotencyKeyId: UUID,
    ) {
        auditPort.recordTenantEvent(
            TenantAuditEvent(
                tenantId = actor.tenantId,
                action = action,
                resource = AuditResource(aggregateType, aggregateId),
                after = payload,
            ),
        )
        outboxPort.enqueue(
            OutboxEventCommand(
                aggregateType = aggregateType,
                aggregateId = aggregateId,
                tenantId = actor.tenantId,
                propertyId = propertyId,
                eventType = action,
                destination = OutboxDestination.PLATFORM,
                payload = payload,
                idempotencyKeyId = idempotencyKeyId,
                priority = 3,
            ),
        )
    }

    private fun mapCashSession(rs: ResultSet, @Suppress("UNUSED_PARAMETER") row: Int): CashSessionResponse {
        return CashSessionResponse(
            id = rs.getObject("id", UUID::class.java),
            propertyId = rs.getObject("property_id", UUID::class.java),
            cashierId = rs.getObject("cashier_id", UUID::class.java),
            status = rs.getString("status"),
            openingFloat = rs.getBigDecimal("opening_float").money(),
            expectedCash = rs.getBigDecimal("expected_cash").money(),
            actualCash = rs.getBigDecimal("actual_cash")?.money(),
            variance = rs.getBigDecimal("variance")?.money(),
            openedAt = rs.getTimestamp("opened_at").toInstant(),
            closedAt = rs.getTimestamp("closed_at")?.toInstant(),
        )
    }

    private fun mapTransaction(rs: ResultSet, @Suppress("UNUSED_PARAMETER") row: Int): PaymentTransactionResponse {
        return PaymentTransactionResponse(
            id = rs.getObject("id", UUID::class.java),
            propertyId = rs.getObject("property_id", UUID::class.java),
            folioId = rs.getObject("folio_id", UUID::class.java),
            posOrderId = rs.getObject("pos_order_id", UUID::class.java),
            providerAccountId = rs.getObject("provider_account_id", UUID::class.java),
            transactionType = rs.getString("transaction_type"),
            providerReference = rs.getString("provider_reference"),
            internalReference = rs.getString("internal_reference"),
            amount = rs.getBigDecimal("amount").money(),
            feeAmount = rs.getBigDecimal("fee_amount").money(),
            currency = rs.getString("currency").trim(),
            status = PaymentStatus.fromDatabase(rs.getString("status")),
            initiatedAt = rs.getTimestamp("initiated_at").toInstant(),
            postedAt = rs.getTimestamp("posted_at")?.toInstant(),
            confirmedAt = rs.getTimestamp("confirmed_at")?.toInstant(),
            failedAt = rs.getTimestamp("failed_at")?.toInstant(),
            expiresAt = rs.getTimestamp("expires_at")?.toInstant(),
            refundedAmount = rs.getBigDecimal("refunded_amount").money(),
            refundOfTransactionId = rs.getObject(
                "refund_of_transaction_id",
                UUID::class.java,
            ),
            reversalOfTransactionId = rs.getObject(
                "reversal_of_transaction_id",
                UUID::class.java,
            ),
        )
    }

    private fun mapProviderAccount(
        rs: ResultSet,
        @Suppress("UNUSED_PARAMETER") row: Int,
    ): PaymentProviderAccountResponse {
        return PaymentProviderAccountResponse(
            id = rs.getObject("id", UUID::class.java),
            propertyId = rs.getObject("property_id", UUID::class.java),
            providerCode = rs.getString("provider_code"),
            providerName = rs.getString("provider_name"),
            accountName = rs.getString("account_name"),
            merchantId = rs.getString("merchant_id"),
            clientId = rs.getString("client_id"),
            walletNumber = rs.getString("wallet_number"),
            isDefault = rs.getBoolean("is_default"),
            isActive = rs.getBoolean("is_active"),
            environment = rs.getString("environment"),
            sandboxCertifiedAt = rs.getTimestamp("sandbox_certified_at")
                ?.toInstant(),
        )
    }

    private fun mapReconciliation(
        rs: ResultSet,
        @Suppress("UNUSED_PARAMETER") row: Int,
    ): PaymentReconciliationResponse {
        return PaymentReconciliationResponse(
            id = rs.getObject("id", UUID::class.java),
            propertyId = rs.getObject("property_id", UUID::class.java),
            providerAccountId = rs.getObject("provider_account_id", UUID::class.java),
            reconciliationDate = rs.getDate("reconciliation_date").toLocalDate(),
            statementReference = rs.getString("statement_reference"),
            providerTotal = rs.getBigDecimal("provider_total").money(),
            systemTotal = rs.getBigDecimal("system_total").money(),
            variance = rs.getBigDecimal("variance").money(),
            status = rs.getString("status"),
        )
    }

    private fun resourceId(response: Any): UUID? {
        return when (response) {
            is CashSessionResponse -> response.id
            is PaymentTransactionResponse -> response.id
            is PaymentProviderAccountResponse -> response.id
            is PaymentReconciliationResponse -> response.id
            is PaymentReconciliationImportResponse -> response.importId
            else -> null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> T.withReplayFlag(): T {
        return when (this) {
            is CashSessionResponse -> copy(replayed = true) as T
            is PaymentTransactionResponse -> copy(replayed = true) as T
            is PaymentProviderAccountResponse -> copy(replayed = true) as T
            is PaymentReconciliationResponse -> copy(replayed = true) as T
            is PaymentReconciliationImportResponse -> copy(replayed = true) as T
            else -> this
        }
    }

    private fun BigDecimal.money(): BigDecimal = setScale(2, RoundingMode.HALF_UP)

    private fun BigDecimal.positiveMoney(field: String): BigDecimal {
        val value = money()
        if (value <= BigDecimal.ZERO) {
            throw PaymentRejectedException("$field must be greater than zero")
        }
        return value
    }

    private fun BigDecimal.nonNegativeMoney(field: String): BigDecimal {
        val value = money()
        if (value < BigDecimal.ZERO) {
            throw PaymentRejectedException("$field must not be negative")
        }
        return value
    }

    private fun String.normalizedRequired(field: String): String {
        return trim().takeIf { it.isNotEmpty() }
            ?: throw PaymentRejectedException("$field is required")
    }

    private fun String.normalizedCode(): String {
        return normalizedRequired("providerCode").lowercase().replace('-', '_')
            .also {
                require(PROVIDER_CODE.matches(it)) {
                    "providerCode must contain lowercase letters, numbers, and underscores"
                }
            }
    }

    private fun String?.trimmedOrNull(): String? {
        return this?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun Environment.approvedProviderCodes(property: String): Set<String> {
        return getProperty(property, "")
            .split(',')
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toSet()
    }

    private fun String.tanzanianE164(): String {
        val digits = filter(Char::isDigit)
        val normalized = when {
            digits.startsWith("255") -> "+$digits"
            digits.startsWith("0") && digits.length == 10 -> "+255${digits.drop(1)}"
            else -> this
        }
        require(TANZANIAN_PHONE.matches(normalized)) {
            "phoneNumber must be a valid Tanzanian mobile number"
        }
        return normalized
    }

    private fun String.normalizedProviderReference(): String {
        return normalizedRequired("referenceNumber").also {
            require(PROVIDER_REFERENCE.matches(it)) {
                "referenceNumber contains unsupported characters or length"
            }
        }
    }

    private fun paymentReference(id: UUID): String {
        return "PEAK-${id.toString().replace("-", "").take(20).uppercase()}"
    }

    private data class PayableFolio(
        val currency: String,
        val balanceDue: BigDecimal,
        val status: String,
    )

    private data class ReconciliationMatch(
        val transactionId: UUID,
        val folioPaymentId: UUID?,
        val amount: BigDecimal,
    )

    private companion object {
        const val CONTRACT_MOCK_PROVIDER = "contract_mock"
        const val HTTP_GATEWAY_PROVIDER = "http_gateway"
        const val CASH_SESSIONS = "cash_sessions"
        const val PAYMENT_TRANSACTIONS = "payment_transactions"
        const val PAYMENT_PROVIDER_ACCOUNTS = "payment_provider_accounts"
        const val PAYMENT_RECONCILIATIONS = "payment_reconciliations"
        const val PAYMENT_COLLECTION_REQUESTED = "payment.collection.requested"
        const val PAYMENT_RECONCILIATION_IMPORT_REQUESTED =
            "payment.reconciliation.import.requested"
        const val CLICKPESA_PROVIDER = "clickpesa"
        const val CLICKPESA_ENDPOINT = "https://api.clickpesa.com/third-parties"
        val PROVIDER_CODE = Regex("[a-z0-9_]{3,50}")
        val TANZANIAN_PHONE = Regex("^\\+255[67][0-9]{8}$")
        val PROVIDER_REFERENCE = Regex("[A-Za-z0-9._:/-]{3,200}")
        val PAYMENT_TRANSACTION_SELECT = """
            SELECT id, property_id, folio_id, pos_order_id, provider_account_id, transaction_type,
                   provider_reference, internal_reference, amount, fee_amount,
                   currency, status, initiated_at, posted_at, confirmed_at,
                   failed_at, expires_at, refunded_amount,
                   refund_of_transaction_id, reversal_of_transaction_id
            FROM payment_transactions
        """.trimIndent()
        val PAYMENT_PROVIDER_ACCOUNT_SELECT = """
            SELECT ppa.id, ppa.property_id, pp.provider_code,
                   pp.name AS provider_name, ppa.account_name, ppa.merchant_id,
                   ppa.client_id, ppa.wallet_number, ppa.is_default,
                   ppa.is_active, ppa.environment, ppa.sandbox_certified_at
            FROM payment_provider_accounts ppa
            JOIN payment_providers pp
              ON pp.tenant_id = ppa.tenant_id
             AND pp.id = ppa.provider_id
        """.trimIndent()
        const val MIN_REVERSAL_REASON_LENGTH = 10
        const val MAX_REVERSAL_REASON_LENGTH = 500
        const val MIN_REFUND_REASON_LENGTH = 10
        const val MAX_REFUND_REASON_LENGTH = 500
    }
}
