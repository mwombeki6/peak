package com.mwombeki.peak.fiscal.internal

import com.mwombeki.peak.audit.api.AuditPort
import com.mwombeki.peak.audit.api.AuditResource
import com.mwombeki.peak.audit.api.TenantAuditEvent
import com.mwombeki.peak.fiscal.api.ConfigureFiscalProviderRequest
import com.mwombeki.peak.fiscal.api.FiscalConflictException
import com.mwombeki.peak.fiscal.api.FiscalNotFoundException
import com.mwombeki.peak.fiscal.api.FiscalPort
import com.mwombeki.peak.fiscal.api.FiscalProviderConfigResponse
import com.mwombeki.peak.fiscal.api.FiscalReceiptResponse
import com.mwombeki.peak.fiscal.api.FiscalRejectedException
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
import java.net.URI
import java.sql.ResultSet
import java.util.UUID
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper

@Service
class FiscalService(
    private val jdbcTemplate: JdbcTemplate,
    private val tenantRequestContext: TenantRequestContext,
    private val idempotencyPort: IdempotencyPort,
    private val auditPort: AuditPort,
    private val outboxPort: OutboxPort,
    private val transactionTemplate: TransactionTemplate,
    private val objectMapper: ObjectMapper,
    private val secretResolver: SecretReferenceResolver,
    private val outboundEndpointPolicy: OutboundEndpointPolicy,
    private val meterRegistry: MeterRegistry,
    adapters: List<FiscalProviderAdapter>,
) : FiscalPort {
    private val providerCodes = adapters.mapTo(mutableSetOf()) { it.providerCode }

    override fun configureProvider(
        propertyId: UUID,
        request: ConfigureFiscalProviderRequest,
    ): FiscalProviderConfigResponse {
        return mutate(
            propertyId = propertyId,
            operationType = "fiscal.provider.configure",
            requestPayload = request,
            resourceType = FISCAL_PROVIDER_CONFIGS,
            replayType = FiscalProviderConfigResponse::class.java,
        ) { actor, idempotencyKeyId ->
            secretResolver.validate(request.secretRef)
            val providerCode = request.providerCode.normalizedCode()
            require(providerCode in providerCodes) {
                "Fiscal provider adapter is unavailable for $providerCode"
            }
            val environment = request.environment.trim().lowercase()
            require(environment in setOf("sandbox", "production")) {
                "environment must be sandbox or production"
            }
            val endpointUrl = request.endpointUrl.normalizedRequired("endpointUrl")
            if (providerCode == HTTP_GATEWAY_PROVIDER) {
                outboundEndpointPolicy.requireAllowedProviderEndpoint(URI.create(endpointUrl))
            }
            val providerId = jdbcTemplate.queryForObject(
                """
                INSERT INTO fiscal_providers (
                    provider_code, country_code, name, authority_name,
                    fiscal_mode, supports_realtime, is_active
                )
                VALUES (?, 'TZ', ?, ?, 'EFD_VFD', true, true)
                ON CONFLICT (provider_code)
                DO UPDATE SET
                    name = EXCLUDED.name,
                    authority_name = EXCLUDED.authority_name,
                    is_active = true,
                    updated_at = now()
                RETURNING id
                """.trimIndent(),
                UUID::class.java,
                providerCode,
                request.providerName.normalizedRequired("providerName"),
                request.authorityName.normalizedRequired("authorityName"),
            ) ?: error("Fiscal provider id was not returned")
            if (request.isDefault) {
                jdbcTemplate.update(
                    """
                    UPDATE fiscal_provider_configs
                    SET is_default = false, updated_at = now()
                    WHERE tenant_id = ? AND property_id = ? AND is_default = true
                    """.trimIndent(),
                    actor.tenantId,
                    propertyId,
                )
            }
            val id = UUID.randomUUID()
            try {
                jdbcTemplate.update(
                    """
                    INSERT INTO fiscal_provider_configs (
                        id, tenant_id, property_id, provider_id, environment,
                        device_serial, branch_code, taxpayer_identifier,
                        endpoint_url, secret_ref, is_default, is_active
                    )
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, true)
                    """.trimIndent(),
                    id,
                    actor.tenantId,
                    propertyId,
                    providerId,
                    environment,
                    request.deviceSerial.trimmedOrNull(),
                    request.branchCode.trimmedOrNull(),
                    request.taxpayerIdentifier.normalizedRequired("taxpayerIdentifier"),
                    endpointUrl,
                    request.secretRef.trim(),
                    request.isDefault,
                )
            } catch (ex: DuplicateKeyException) {
                throw FiscalConflictException(
                    "Fiscal provider configuration already exists for this environment",
                )
            }
            requireProviderConfig(actor.tenantId, propertyId, id)
                .also {
                    recordSideEffects(
                        actor = actor,
                        propertyId = propertyId,
                        action = "fiscal.provider.configured",
                        aggregateType = FISCAL_PROVIDER_CONFIGS,
                        aggregateId = id,
                        payload = mapOf(
                            "providerConfigId" to id,
                            "providerCode" to providerCode,
                            "environment" to environment,
                            "isDefault" to request.isDefault,
                        ),
                        idempotencyKeyId = idempotencyKeyId,
                    )
                }
        }
    }

    override fun listProviderConfigs(propertyId: UUID): List<FiscalProviderConfigResponse> {
        return read(propertyId) { actor ->
            jdbcTemplate.query(
                """
                $PROVIDER_CONFIG_SELECT
                WHERE fpc.tenant_id = ? AND fpc.property_id = ?
                ORDER BY fpc.is_default DESC, fp.name, fpc.environment
                """.trimIndent(),
                ::mapProviderConfig,
                actor.tenantId,
                propertyId,
            )
        }
    }

    override fun listReceipts(
        propertyId: UUID,
        limit: Int,
    ): List<FiscalReceiptResponse> {
        require(limit in 1..200) {
            "limit must be between 1 and 200"
        }
        return read(propertyId) { actor ->
            jdbcTemplate.query(
                """
                $RECEIPT_SELECT
                WHERE tenant_id = ? AND property_id = ?
                ORDER BY submitted_at DESC, id DESC
                LIMIT ?
                """.trimIndent(),
                ::mapReceipt,
                actor.tenantId,
                propertyId,
                limit,
            )
        }
    }

    override fun getReceipt(
        propertyId: UUID,
        receiptId: UUID,
    ): FiscalReceiptResponse? {
        return read(propertyId) { actor ->
            findReceipt(actor.tenantId, propertyId, receiptId, lock = false)
        }
    }

    override fun retryReceipt(
        propertyId: UUID,
        receiptId: UUID,
    ): FiscalReceiptResponse {
        return mutate(
            propertyId = propertyId,
            operationType = "fiscal.receipt.retry",
            requestPayload = mapOf("receiptId" to receiptId),
            resourceType = FISCAL_RECEIPTS,
            replayType = FiscalReceiptResponse::class.java,
        ) { actor, idempotencyKeyId ->
            val receipt = findReceipt(actor.tenantId, propertyId, receiptId, lock = true)
                ?: throw FiscalNotFoundException("Fiscal receipt was not found")
            require(receipt.status in setOf("rejected", "pending")) {
                "Only rejected or pending fiscal receipts can be retried"
            }
            jdbcTemplate.update(
                """
                UPDATE fiscal_receipts
                SET status = 'pending',
                    updated_at = now()
                WHERE tenant_id = ? AND property_id = ? AND id = ?
                """.trimIndent(),
                actor.tenantId,
                propertyId,
                receiptId,
            )
            outboxPort.enqueue(
                OutboxEventCommand(
                    aggregateType = "invoices",
                    aggregateId = receipt.invoiceId,
                    tenantId = actor.tenantId,
                    propertyId = propertyId,
                    eventType = FISCAL_RETRY_REQUESTED,
                    destination = OutboxDestination.FISCAL,
                    payload = mapOf(
                        "receiptId" to receiptId,
                        "invoiceId" to receipt.invoiceId,
                    ),
                    idempotencyKeyId = idempotencyKeyId,
                    priority = 1,
                ),
            )
            findReceipt(actor.tenantId, propertyId, receiptId, lock = false)
                ?.also {
                    auditPort.recordTenantEvent(
                        TenantAuditEvent(
                            tenantId = actor.tenantId,
                            action = "fiscal.receipt.retry.requested",
                            resource = AuditResource(FISCAL_RECEIPTS, receiptId),
                            after = mapOf(
                                "receiptId" to receiptId,
                                "invoiceId" to receipt.invoiceId,
                            ),
                        ),
                    )
                }
                ?: error("Fiscal receipt disappeared after retry")
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
                        val result = block(actor, reservation.recordId)
                        idempotencyPort.markSucceeded(
                            recordId = reservation.recordId,
                            responseCode = 200,
                            responseBody = result,
                            resourceId = resourceId(result),
                        )
                        recordCommandMetric(operationType, "succeeded")
                        result
                    }

                    is IdempotencyReservation.Replay -> {
                        if (reservation.responseBody.isNullOrBlank()) {
                            throw FiscalConflictException(
                                "Fiscal replay does not contain a stored response body",
                            )
                        }
                        objectMapper.readValue(reservation.responseBody, replayType)
                            .withReplayFlag()
                            .also { recordCommandMetric(operationType, "replayed") }
                    }

                    is IdempotencyReservation.InProgress -> {
                        recordCommandMetric(operationType, "in_progress")
                        throw FiscalConflictException(
                            "Fiscal command is already being processed",
                        )
                    }

                    is IdempotencyReservation.Conflict -> {
                        recordCommandMetric(operationType, "conflict")
                        throw FiscalConflictException(
                            "Idempotency key was used for a different fiscal command",
                        )
                    }
                }
            },
        )
    }

    private fun recordCommandMetric(operationType: String, result: String) {
        meterRegistry.counter(
            "peak.fiscal.command",
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

    private fun requireProviderConfig(
        tenantId: UUID,
        propertyId: UUID,
        configId: UUID,
    ): FiscalProviderConfigResponse {
        return jdbcTemplate.query(
            """
            $PROVIDER_CONFIG_SELECT
            WHERE fpc.tenant_id = ? AND fpc.property_id = ? AND fpc.id = ?
            """.trimIndent(),
            ::mapProviderConfig,
            tenantId,
            propertyId,
            configId,
        ).singleOrNull() ?: throw FiscalNotFoundException(
            "Fiscal provider configuration was not found",
        )
    }

    private fun findReceipt(
        tenantId: UUID,
        propertyId: UUID,
        receiptId: UUID,
        lock: Boolean,
    ): FiscalReceiptResponse? {
        val lockClause = if (lock) "FOR UPDATE" else ""
        return jdbcTemplate.query(
            """
            $RECEIPT_SELECT
            WHERE tenant_id = ? AND property_id = ? AND id = ?
            $lockClause
            """.trimIndent(),
            ::mapReceipt,
            tenantId,
            propertyId,
            receiptId,
        ).singleOrNull()
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

    private fun mapProviderConfig(
        rs: ResultSet,
        @Suppress("UNUSED_PARAMETER") row: Int,
    ): FiscalProviderConfigResponse {
        return FiscalProviderConfigResponse(
            id = rs.getObject("id", UUID::class.java),
            propertyId = rs.getObject("property_id", UUID::class.java),
            providerCode = rs.getString("provider_code"),
            providerName = rs.getString("provider_name"),
            environment = rs.getString("environment"),
            endpointUrl = rs.getString("endpoint_url"),
            deviceSerial = rs.getString("device_serial"),
            branchCode = rs.getString("branch_code"),
            taxpayerIdentifier = rs.getString("taxpayer_identifier"),
            isDefault = rs.getBoolean("is_default"),
            isActive = rs.getBoolean("is_active"),
        )
    }

    private fun mapReceipt(
        rs: ResultSet,
        @Suppress("UNUSED_PARAMETER") row: Int,
    ): FiscalReceiptResponse {
        return FiscalReceiptResponse(
            id = rs.getObject("id", UUID::class.java),
            propertyId = rs.getObject("property_id", UUID::class.java),
            invoiceId = rs.getObject("invoice_id", UUID::class.java),
            fiscalMode = rs.getString("fiscal_mode"),
            receiptNumber = rs.getString("receipt_number"),
            fiscalCode = rs.getString("fiscal_code"),
            verificationCode = rs.getString("verification_code"),
            qrCodeUrl = rs.getString("qr_code_url"),
            status = rs.getString("status"),
            submittedAt = rs.getTimestamp("submitted_at").toInstant(),
        )
    }

    private fun resourceId(result: Any): UUID? {
        return when (result) {
            is FiscalProviderConfigResponse -> result.id
            is FiscalReceiptResponse -> result.id
            else -> null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun <T : Any> T.withReplayFlag(): T {
        return when (this) {
            is FiscalProviderConfigResponse -> copy(replayed = true) as T
            is FiscalReceiptResponse -> copy(replayed = true) as T
            else -> this
        }
    }

    private fun String.normalizedRequired(field: String): String {
        return trim().takeIf { it.isNotEmpty() }
            ?: throw FiscalRejectedException("$field is required")
    }

    private fun String.normalizedCode(): String {
        return normalizedRequired("providerCode").lowercase().replace('-', '_')
            .also {
                require(PROVIDER_CODE.matches(it)) {
                    "providerCode must contain lowercase letters, numbers, and underscores"
                }
            }
    }

    private fun String?.trimmedOrNull(): String? = this?.trim()?.takeIf(String::isNotEmpty)

    private companion object {
        const val HTTP_GATEWAY_PROVIDER = "http_gateway"
        const val FISCAL_PROVIDER_CONFIGS = "fiscal_provider_configs"
        const val FISCAL_RECEIPTS = "fiscal_receipts"
        const val FISCAL_RETRY_REQUESTED = "fiscal.receipt.retry.requested"
        val PROVIDER_CODE = Regex("[a-z0-9_]{3,50}")
        val PROVIDER_CONFIG_SELECT = """
            SELECT fpc.id, fpc.property_id, fp.provider_code,
                   fp.name AS provider_name, fpc.environment, fpc.endpoint_url,
                   fpc.device_serial, fpc.branch_code, fpc.taxpayer_identifier,
                   fpc.is_default, fpc.is_active
            FROM fiscal_provider_configs fpc
            JOIN fiscal_providers fp ON fp.id = fpc.provider_id
        """.trimIndent()
        val RECEIPT_SELECT = """
            SELECT id, property_id, invoice_id, fiscal_mode, receipt_number,
                   fiscal_code, verification_code, qr_code_url, status, submitted_at
            FROM fiscal_receipts
        """.trimIndent()
    }
}
