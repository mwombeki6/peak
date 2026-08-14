package com.mwombeki.peak.integrations.internal

import com.mwombeki.peak.payment.api.PaymentProvider
import com.mwombeki.peak.payment.api.ProviderCollectionCommand
import com.mwombeki.peak.payment.api.ProviderCollectionResult
import com.mwombeki.peak.payment.api.ProviderStatementItem
import com.mwombeki.peak.payment.api.ProviderStatementQuery
import com.mwombeki.peak.payment.api.ProviderStatementResult
import com.mwombeki.peak.payment.api.ProviderPaymentStatus
import com.mwombeki.peak.payment.api.ProviderStatusQuery
import com.mwombeki.peak.payment.api.ProviderStatusResult
import com.mwombeki.peak.payment.api.ProviderWebhookNotification
import com.mwombeki.peak.shared.outbound.OutboundEndpointPolicy
import java.math.BigDecimal
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap
import io.micrometer.core.instrument.MeterRegistry
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import tools.jackson.databind.JsonNode
import tools.jackson.databind.ObjectMapper

@ConfigurationProperties(prefix = "peak.payment.providers.clickpesa")
data class ClickPesaProperties(
    val connectTimeout: Duration = Duration.ofSeconds(3),
    val requestTimeout: Duration = Duration.ofSeconds(15),
    val tokenTtl: Duration = Duration.ofHours(1),
    val tokenRefreshSkew: Duration = Duration.ofMinutes(5),
)

@Component
class ClickPesaPaymentProvider(
    private val objectMapper: ObjectMapper,
    private val checksum: ClickPesaChecksum,
    private val endpointPolicy: OutboundEndpointPolicy,
    private val properties: ClickPesaProperties,
    private val clock: Clock,
    private val meterRegistry: MeterRegistry,
) : PaymentProvider {
    override val providerCode = "clickpesa"
    private val httpClient = HttpClient.newBuilder()
        .connectTimeout(properties.connectTimeout)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()
    private val tokens = ConcurrentHashMap<String, CachedToken>()
    private val tokenLock = Any()

    override fun initiate(
        command: ProviderCollectionCommand,
    ): ProviderCollectionResult {
        val unsignedPayload = objectMapper.writeValueAsString(
            linkedMapOf(
                "amount" to command.amount.stripTrailingZeros().toPlainString(),
                "currency" to command.currency,
                "orderReference" to command.internalReference,
                "phoneNumber" to command.payerIdentifier.filter(Char::isDigit),
            ),
        )
        val payload = objectMapper.writeValueAsString(
            linkedMapOf(
                "amount" to command.amount.stripTrailingZeros().toPlainString(),
                "currency" to command.currency,
                "orderReference" to command.internalReference,
                "phoneNumber" to command.payerIdentifier.filter(Char::isDigit),
                "checksum" to checksum.create(
                    unsignedPayload,
                    command.checksumKey,
                ),
            ),
        )
        val token = token(
            baseUri(command.endpointUrl),
            command.clientId,
            command.apiKey,
        )
        val response = send(
            HttpRequest.newBuilder(
                endpoint(
                    command.endpointUrl,
                    "payments/initiate-ussd-push-request",
                ),
            )
                .timeout(properties.requestTimeout)
                .header("Authorization", token)
                .header("Content-Type", "application/json")
                .header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(payload))
                .build(),
        )
        val node = objectMapper.readTree(response)
        val providerStatus = node.requiredText("status").uppercase()
        require(providerStatus != "FAILED") {
            "ClickPesa rejected the USSD collection"
        }
        require(
            providerStatus in setOf(
                "PROCESSING",
                "PENDING",
                "SUCCESS",
                "SETTLED",
            ),
        ) {
            "Unsupported ClickPesa initiation status: $providerStatus"
        }
        return ProviderCollectionResult(
            providerReference = node.requiredText("id"),
            // Initiation responses are acceptance evidence, not settlement
            // evidence. A webhook or status query must verify posting.
            status = ProviderPaymentStatus.PENDING,
            providerStatus = providerStatus,
            providerTimestamp = node.optionalInstant("createdAt"),
        )
    }

    override fun queryStatus(
        command: ProviderStatusQuery,
    ): ProviderStatusResult {
        val token = token(
            baseUri(command.endpointUrl),
            command.clientId,
            command.apiKey,
        )
        val encodedReference = URLEncoder.encode(
            command.internalReference,
            StandardCharsets.UTF_8,
        )
        val response = send(
            HttpRequest.newBuilder(
                endpoint(command.endpointUrl, "payments/$encodedReference"),
            )
                .timeout(properties.requestTimeout)
                .header("Authorization", token)
                .header("Accept", "application/json")
                .GET()
                .build(),
        )
        val root = objectMapper.readTree(response)
        val node = if (root.isArray) root.firstOrNull()
            ?: error("ClickPesa status response is empty") else root
        val providerStatus = node.requiredText("status").uppercase()
        return ProviderStatusResult(
            internalReference = node.requiredText("orderReference"),
            providerReference = node.path("id").asString(null)
                ?: node.path("paymentReference").asString(null),
            status = providerStatus.toCanonicalStatus(),
            providerStatus = providerStatus,
            amount = node.path("collectedAmount").asString(null)?.toBigDecimal(),
            currency = node.path("collectedCurrency").asString(null),
            clientId = node.path("clientId").asString(null),
            providerTimestamp = node.optionalInstant("updatedAt"),
        )
    }

    override fun statement(
        command: ProviderStatementQuery,
    ): ProviderStatementResult {
        val token = token(
            baseUri(command.endpointUrl),
            command.clientId,
            command.apiKey,
        )
        val query = "startDate=${command.startDate}" +
                "&endDate=${command.endDate}" +
                "&currency=${command.currency}"
        val response = send(
            HttpRequest.newBuilder(
                URI.create(
                    endpoint(command.endpointUrl, "account/statement")
                        .toString() + "?$query",
                ),
            )
                .timeout(properties.requestTimeout)
                .header("Authorization", token)
                .header("Accept", "application/json")
                .GET()
                .build(),
        )
        val node = objectMapper.readTree(response)
        val details = node.path("accountDetails")
        val items = node.path("transactions").toList().map { item ->
            ProviderStatementItem(
                providerReference = item.requiredText("id"),
                orderReference = item.path("orderReference").asString(null),
                occurredAt = Instant.parse(item.requiredText("date")),
                amount = item.requiredText("amount").toBigDecimal(),
            )
        }
        return ProviderStatementResult(
            openingBalance = details.requiredText("openingBalance").toBigDecimal(),
            closingBalance = details.requiredText("closingBalance").toBigDecimal(),
            items = items,
        )
    }

    override fun parseWebhook(payload: String): ProviderWebhookNotification {
        val root = objectMapper.readTree(payload)
        val event = root.requiredText("event").uppercase()
        require(event in setOf("PAYMENT RECEIVED", "PAYMENT FAILED")) {
            "Unsupported ClickPesa webhook event"
        }
        val data = root.path("data")
        require(!data.isMissingNode && data.isObject) {
            "ClickPesa webhook data is required"
        }
        val providerStatus = data.requiredText("status").uppercase()
        val id = data.requiredText("id")
        return ProviderWebhookNotification(
            eventKey = "$event:$id",
            eventType = event,
            internalReference = data.requiredText("orderReference"),
            providerReference = id,
            status = providerStatus.toCanonicalStatus(),
            providerStatus = providerStatus,
            amount = data.path("collectedAmount").asString("0").toBigDecimal(),
            currency = data.path("collectedCurrency").asString("TZS").uppercase(),
            merchantIdentity = data.path("clientId").asString(null)
                ?: root.path("clientId").asString(null),
            payerIdentity = data.path("customer").path("phoneNumber").asString(null),
            providerTimestamp = data.optionalInstant("updatedAt"),
            checksumMethod = root.path("checksumMethod").asString(null),
            metadata = mapOf(
                "channel" to data.path("channel").asString(null),
                "message" to data.path("message").asString(null),
                "paymentReference" to data.path("paymentReference").asString(null),
            ),
        )
    }

    override fun verifyAndParseWebhook(
        payload: String,
        checksumKey: String,
        checksumRequired: Boolean,
    ): ProviderWebhookNotification {
        val node = objectMapper.readTree(payload)
        val supplied = node.path("checksum").asString(null)
        val method = node.path("checksumMethod").asString(null)
        if (checksumRequired && supplied.isNullOrBlank()) {
            throw IllegalArgumentException(
                "ClickPesa webhook checksum is required",
            )
        }
        if (!supplied.isNullOrBlank()) {
            require(method.equals("HMAC-SHA256", ignoreCase = true)) {
                "ClickPesa webhook checksum method is unsupported"
            }
        }
        if (
            !supplied.isNullOrBlank() &&
            !checksum.verify(payload, checksumKey, supplied)
        ) {
            throw IllegalArgumentException(
                "ClickPesa webhook checksum is invalid",
            )
        }
        return parseWebhook(payload)
    }

    private fun token(baseUri: URI, clientId: String, apiKey: String): String {
        val key = sha256("$baseUri|$clientId|$apiKey")
        val now = clock.instant()
        tokens[key]?.takeIf {
            now.isBefore(it.expiresAt.minus(properties.tokenRefreshSkew))
        }?.let {
            tokenMetric("cache_hit")
            return it.value
        }
        synchronized(tokenLock) {
            val refreshNow = clock.instant()
            tokens[key]?.takeIf {
                refreshNow.isBefore(
                    it.expiresAt.minus(properties.tokenRefreshSkew),
                )
            }?.let {
                tokenMetric("cache_hit")
                return it.value
            }
            return try {
                val response = send(
                    HttpRequest.newBuilder(endpoint(baseUri.toString(), "generate-token"))
                        .timeout(properties.requestTimeout)
                        .header("client-id", clientId)
                        .header("api-key", apiKey)
                        .header("Accept", "application/json")
                        .POST(HttpRequest.BodyPublishers.noBody())
                        .build(),
                )
                val node = objectMapper.readTree(response)
                require(node.path("success").asBoolean(false)) {
                    "ClickPesa token generation was rejected"
                }
                val value = node.requiredText("token")
                tokens[key] = CachedToken(
                    value = value,
                    expiresAt = refreshNow.plus(properties.tokenTtl),
                )
                tokenMetric("refreshed")
                value
            } catch (ex: Exception) {
                tokenMetric("failed")
                throw ex
            }
        }
    }

    private fun tokenMetric(result: String) {
        meterRegistry.counter(
            "peak.clickpesa.token",
            "result",
            result,
        ).increment()
    }

    private fun send(request: HttpRequest): String {
        endpointPolicy.requireAllowedProviderEndpoint(request.uri())
        val response = httpClient.send(
            request,
            HttpResponse.BodyHandlers.ofInputStream(),
        )
        response.body().use { body ->
            check(response.statusCode() in 200..299) {
                "ClickPesa returned HTTP ${response.statusCode()}"
            }
            val bytes = body.readNBytes(MAX_RESPONSE_BYTES + 1)
            check(bytes.size <= MAX_RESPONSE_BYTES) {
                "ClickPesa response exceeds 64 KiB"
            }
            return String(bytes, StandardCharsets.UTF_8)
        }
    }

    private fun endpoint(base: String?, path: String): URI {
        return endpoint(baseUri(base), path)
    }

    private fun endpoint(base: URI, path: String): URI {
        return URI.create("${base.toString().trimEnd('/')}/${path.trimStart('/')}")
    }

    private fun baseUri(value: String?): URI {
        val uri = URI.create(value?.trim().orEmpty())
        require(uri.scheme == "https" && !uri.host.isNullOrBlank()) {
            "ClickPesa endpoint must be an absolute HTTPS URL"
        }
        return uri
    }

    /**
     * ClickPesa's vocabulary, and nothing else's. This used to return "posted" — a state in
     * Peak's `payment_transactions`, not an outcome a provider can report — which is how the
     * boundary came to be defined by whatever the first adapter happened to emit.
     */
    private fun String.toCanonicalStatus(): ProviderPaymentStatus {
        return when (uppercase()) {
            "SUCCESS", "SETTLED" -> ProviderPaymentStatus.SUCCEEDED
            "PROCESSING", "PENDING" -> ProviderPaymentStatus.PENDING
            "FAILED" -> ProviderPaymentStatus.FAILED
            // Deliberately not FAILED. An unrecognised word means Peak does not know what
            // happened, and the domain must be told that rather than told it failed.
            else -> ProviderPaymentStatus.UNKNOWN
        }
    }

    private fun JsonNode.requiredText(field: String): String {
        return path(field).asString().trim().takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException(
                "ClickPesa response field $field is required",
            )
    }

    private fun JsonNode.optionalInstant(field: String): Instant? {
        return path(field).asString(null)?.let(Instant::parse)
    }

    private fun sha256(value: String): String {
        return HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256")
                .digest(value.toByteArray(StandardCharsets.UTF_8)),
        )
    }

    private data class CachedToken(
        val value: String,
        val expiresAt: Instant,
    )

    private companion object {
        const val MAX_RESPONSE_BYTES = 64 * 1024
    }
}
