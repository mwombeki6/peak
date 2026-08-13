package com.mwombeki.peak.platformbilling.internal

import com.mwombeki.peak.payment.api.PaymentProvider
import com.mwombeki.peak.payment.api.ProviderCollectionCommand
import com.mwombeki.peak.platformbilling.api.PaymentAttemptResponse
import com.mwombeki.peak.platformbilling.api.PaymentAttemptStatus
import com.mwombeki.peak.platformbilling.api.PayPurchaseRequest
import com.mwombeki.peak.platformbilling.api.PlatformBillingConflictException
import com.mwombeki.peak.platformbilling.api.PlatformBillingNotFoundException
import com.mwombeki.peak.shared.context.TenantRequestContext
import com.mwombeki.peak.shared.secrets.SecretReferenceResolver
import java.math.BigDecimal
import java.sql.Timestamp
import java.util.UUID
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate

/**
 * Pushes a PIN prompt to the payer for an open purchase.
 *
 * This never completes a purchase. Mobile money has no mandate: the only thing that proves
 * a payment happened is a signed provider callback, which settles in the worker. An
 * attempt that returns "pending" here has done its whole job.
 */
@Service
class PlatformCollectionService(
    private val jdbcTemplate: JdbcTemplate,
    private val transactionTemplate: TransactionTemplate,
    private val tenantRequestContext: TenantRequestContext,
    private val secretReferenceResolver: SecretReferenceResolver,
    private val properties: PlatformBillingProperties,
    adapters: List<PaymentProvider>,
) {
    private val adaptersByCode = adapters.associateBy { it.providerCode }

    init {
        // Fail at boot, not at a customer's first payment. A configured provider with no
        // adapter is a deployment mistake, and the worst possible time to learn about it is
        // when someone has already decided to buy.
        if (properties.enabled) {
            require(properties.primaryProvider.trim() in adaptersByCode) {
                "peak.platformbilling.primary-provider is '${properties.primaryProvider}' " +
                    "but no such payment adapter is registered. Available: " +
                    adaptersByCode.keys.sorted().joinToString(", ")
            }
            val fallback = properties.fallbackProvider.trim()
            require(fallback.isEmpty() || fallback in adaptersByCode) {
                "peak.platformbilling.fallback-provider is '$fallback' but no such payment " +
                    "adapter is registered. Available: " +
                    adaptersByCode.keys.sorted().joinToString(", ")
            }
        }
    }

    /**
     * Deliberately three transactions, not one.
     *
     * The attempt row is committed *before* the provider is called, so an initiation that
     * times out after the prompt was already pushed still leaves evidence that money may be
     * in flight. Doing it in one transaction would roll the row back on failure and make a
     * timeout indistinguishable from never having tried — the one case where the customer
     * may have paid and we have no record.
     *
     * It also keeps a network call to a third party out of an open database transaction,
     * so a slow provider costs us latency rather than the connection pool.
     */
    fun pay(purchaseId: UUID, request: PayPurchaseRequest): PaymentAttemptResponse {
        val prepared = requireNotNull(
            transactionTemplate.execute {
                val actor = tenantRequestContext.bind()
                val purchase = openPurchase(actor.tenantId, purchaseId)
                val msisdn = normalizeTanzanianMsisdn(request.payerMsisdn)

                val attemptNo = nextAttemptNo(purchaseId)
                require(attemptNo <= properties.maxPaymentAttempts) {
                    "This purchase has already been attempted ${properties.maxPaymentAttempts} " +
                        "times. Start a new purchase, or contact us to pay by bank transfer."
                }

                val provider = resolveProvider()
                PreparedAttempt(
                    attemptId = insertAttempt(
                        purchaseId = purchaseId,
                        tenantId = actor.tenantId,
                        attemptNo = attemptNo,
                        provider = provider.providerCode,
                        channel = request.channel,
                        msisdn = msisdn,
                        amount = purchase.totalAmount,
                        currency = purchase.currency,
                        internalReference = internalReference(purchaseId, attemptNo),
                        initiatedBy = actor.tenantUserId,
                    ),
                    attemptNo = attemptNo,
                    provider = provider,
                    msisdn = msisdn,
                    amount = purchase.totalAmount,
                    currency = purchase.currency,
                    internalReference = internalReference(purchaseId, attemptNo),
                )
            },
        )

        val result = runCatching {
            prepared.provider.initiate(
                ProviderCollectionCommand(
                    transactionId = prepared.attemptId,
                    internalReference = prepared.internalReference,
                    endpointUrl = properties.endpointUrl,
                    clientId = secretReferenceResolver.resolve(properties.clientIdSecretRef),
                    payerIdentifier = prepared.msisdn,
                    amount = prepared.amount,
                    currency = prepared.currency,
                    apiKey = secretReferenceResolver.resolve(properties.apiKeySecretRef),
                    checksumKey = secretReferenceResolver.resolve(properties.checksumKeySecretRef),
                    providerChannel = request.channel,
                ),
            )
        }.getOrElse { failure ->
            transactionTemplate.execute {
                tenantRequestContext.bind()
                markAttemptFailed(prepared.attemptId, failure)
            }
            throw failure
        }

        transactionTemplate.execute {
            tenantRequestContext.bind()
            markAttemptInitiated(prepared.attemptId, result.providerReference, result.redirectUrl)
            jdbcTemplate.update(
                "UPDATE peak_purchases SET status = 'awaiting_payment', updated_at = now() WHERE id = ?",
                purchaseId,
            )
        }

        return PaymentAttemptResponse(
            id = prepared.attemptId,
            purchaseId = purchaseId,
            attemptNo = prepared.attemptNo,
            provider = prepared.provider.providerCode,
            status = PaymentAttemptStatus.PENDING,
            internalReference = prepared.internalReference,
            redirectUrl = result.redirectUrl,
        )
    }

    private fun internalReference(purchaseId: UUID, attemptNo: Int): String =
        "PEAK-${purchaseId.toString().take(8)}-$attemptNo".uppercase()

    private data class PreparedAttempt(
        val attemptId: UUID,
        val attemptNo: Int,
        val provider: PaymentProvider,
        val msisdn: String,
        val amount: BigDecimal,
        val currency: String,
        val internalReference: String,
    )

    private fun resolveProvider(): PaymentProvider {
        val primary = properties.primaryProvider.trim()
        adaptersByCode[primary]?.let { return it }

        val fallback = properties.fallbackProvider.trim()
        adaptersByCode[fallback.takeIf { it.isNotEmpty() }]?.let { return it }

        throw IllegalStateException(
            "No payment adapter is available for platform billing; " +
                "peak.platformbilling.primary-provider is '$primary' and known adapters are " +
                adaptersByCode.keys.sorted().joinToString(", "),
        )
    }

    private fun openPurchase(tenantId: UUID, purchaseId: UUID): OpenPurchase {
        val purchase = jdbcTemplate.query(
            """
            SELECT status, total_amount, currency, quote_expires_at
            FROM peak_purchases
            WHERE id = ? AND tenant_id = ?
            FOR UPDATE
            """.trimIndent(),
            { rs, _ ->
                OpenPurchase(
                    status = rs.getString("status"),
                    totalAmount = rs.getBigDecimal("total_amount"),
                    currency = rs.getString("currency").trim(),
                    quoteExpired = rs.getTimestamp("quote_expires_at").toInstant()
                        .isBefore(java.time.Instant.now()),
                )
            },
            purchaseId,
            tenantId,
        ).firstOrNull() ?: throw PlatformBillingNotFoundException("Purchase was not found")

        if (purchase.status == "paid") {
            throw PlatformBillingConflictException("This purchase has already been paid")
        }
        if (purchase.status !in OPEN_STATUSES) {
            throw PlatformBillingConflictException(
                "This purchase is ${purchase.status} and can no longer be paid",
            )
        }
        if (purchase.quoteExpired) {
            throw PlatformBillingConflictException(
                "This quote has expired. Price it again before paying.",
            )
        }
        return purchase
    }

    private fun nextAttemptNo(purchaseId: UUID): Int {
        return (
            jdbcTemplate.queryForObject(
                "SELECT coalesce(max(attempt_no), 0) FROM peak_payment_attempts WHERE purchase_id = ?",
                Int::class.java,
                purchaseId,
            ) ?: 0
            ) + 1
    }

    @Suppress("LongParameterList")
    private fun insertAttempt(
        purchaseId: UUID,
        tenantId: UUID,
        attemptNo: Int,
        provider: String,
        channel: String?,
        msisdn: String,
        amount: BigDecimal,
        currency: String,
        internalReference: String,
        initiatedBy: UUID,
    ): UUID {
        val attemptId = UUID.randomUUID()
        try {
            jdbcTemplate.update(
                """
                INSERT INTO peak_payment_attempts (
                    id, purchase_id, tenant_id, attempt_no, provider, provider_channel,
                    payer_msisdn, amount, currency, internal_reference, status,
                    initiated_by_user_id, expires_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'created', ?, now() + interval '15 minutes')
                """.trimIndent(),
                attemptId,
                purchaseId,
                tenantId,
                attemptNo,
                provider,
                channel,
                msisdn,
                amount,
                currency,
                internalReference,
                initiatedBy,
            )
        } catch (ex: DuplicateKeyException) {
            // uq_peak_payment_attempts_open. Two prompts for one order is a support ticket
            // and, worse, a customer who pays twice.
            throw PlatformBillingConflictException(
                "A payment for this purchase is already in progress. " +
                    "Wait for it to finish or expire before trying again.",
            )
        }
        return attemptId
    }

    private fun markAttemptInitiated(
        attemptId: UUID,
        providerReference: String,
        redirectUrl: String?,
    ) {
        jdbcTemplate.update(
            """
            UPDATE peak_payment_attempts
            SET status = 'pending', provider_reference = ?, redirect_url = ?, updated_at = now()
            WHERE id = ?
            """.trimIndent(),
            providerReference,
            redirectUrl,
            attemptId,
        )
    }

    private fun markAttemptFailed(attemptId: UUID, failure: Throwable) {
        jdbcTemplate.update(
            """
            UPDATE peak_payment_attempts
            SET status = 'failed', failure_code = 'initiate_failed', failure_detail = ?,
                updated_at = now()
            WHERE id = ?
            """.trimIndent(),
            failure.message?.take(500) ?: failure.javaClass.simpleName,
            attemptId,
        )
    }

    /**
     * Accepts what a Tanzanian owner would actually type — 0755…, 255755…, +255755… — and
     * normalises to the 12-digit form providers expect.
     *
     * Validated because this pushes a prompt to whatever number it is given: a typo bothers
     * a stranger, and an unvalidated field invites using Peak as a nuisance-SMS relay.
     */
    internal fun normalizeTanzanianMsisdn(raw: String): String {
        val digits = raw.filter { it.isDigit() || it == '+' }.removePrefix("+")
        val normalized = when {
            digits.length == 12 && digits.startsWith("255") -> digits
            digits.length == 10 && digits.startsWith("0") -> "255" + digits.drop(1)
            digits.length == 9 && digits.startsWith("7") -> "255$digits"
            else -> throw IllegalArgumentException(
                "That does not look like a Tanzanian mobile number. " +
                    "Use 07XXXXXXXX or 2557XXXXXXXX.",
            )
        }
        require(normalized[3] in TZ_MOBILE_PREFIXES) {
            "That does not look like a Tanzanian mobile number. Use 07XXXXXXXX or 2556XXXXXXXX."
        }
        return normalized
    }

    private data class OpenPurchase(
        val status: String,
        val totalAmount: BigDecimal,
        val currency: String,
        val quoteExpired: Boolean,
    )

    private companion object {
        val OPEN_STATUSES = setOf("quoted", "awaiting_payment")

        /** Tanzanian mobile numbers begin 06 or 07 nationally, so 2556… or 2557…. */
        val TZ_MOBILE_PREFIXES = setOf('6', '7')
    }
}
