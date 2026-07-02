package com.mwombeki.peak.integrations.internal

import com.mwombeki.peak.fiscal.api.FiscalProvider
import com.mwombeki.peak.fiscal.api.FiscalSubmissionCommand
import com.mwombeki.peak.fiscal.api.FiscalSubmissionResult
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.HexFormat
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import tools.jackson.databind.ObjectMapper

@Component
@ConditionalOnProperty(
    prefix = "peak.fiscal.providers.signed-simulator",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class SignedSimulatorFiscalProvider(
    private val objectMapper: ObjectMapper,
) : FiscalProvider {
    override val providerCode = "signed_simulator"
    private val attempts = ConcurrentHashMap<String, Int>()

    override fun submit(command: FiscalSubmissionCommand): FiscalSubmissionResult {
        val scenario = scenario(command)
        if (scenario == "timeout") {
            error("Signed fiscal simulator timeout")
        }
        if (
            scenario == "retry" &&
            attempts.merge(command.receiptId.toString(), 1, Int::plus) == 1
        ) {
            error("Signed fiscal simulator transient retry")
        }
        val suffix = command.receiptId.toString()
            .replace("-", "")
            .take(16)
            .uppercase()
        val payload = sortedMapOf<String, Any?>(
            "accepted" to (scenario != "reject"),
            "currency" to command.currency,
            "invoiceId" to command.invoiceId.toString(),
            "invoiceNumber" to command.invoiceNumber,
            "receiptId" to command.receiptId.toString(),
            "scenario" to scenario,
            "total" to command.total,
        )
        val canonical = objectMapper.writeValueAsString(payload)
        val signature = sign(command.credential, canonical)
        if (scenario == "reject") {
            return FiscalSubmissionResult(
                accepted = false,
                providerDocumentId = null,
                receiptNumber = null,
                fiscalCode = null,
                verificationCode = null,
                qrCodeUrl = null,
                responseMetadata = mapOf(
                    "scenario" to scenario,
                    "signature" to signature,
                    "signatureMethod" to "HMAC-SHA256",
                    "payloadHash" to sha256(canonical),
                ),
                errorCode = "SIMULATED_REJECTION",
                errorMessage = "Signed simulator rejected the fiscal document",
            )
        }
        val documentPrefix = if (scenario == "credit_note") "SIM-CN" else "SIM-INV"
        return FiscalSubmissionResult(
            accepted = true,
            providerDocumentId = "$documentPrefix-$suffix",
            receiptNumber = "SIM-$suffix",
            fiscalCode = "FISC-$suffix",
            verificationCode = signature.take(24).uppercase(),
            qrCodeUrl = "https://fiscal.invalid/verify/$suffix",
            responseMetadata = mapOf(
                "scenario" to scenario,
                "signature" to signature,
                "signatureMethod" to "HMAC-SHA256",
                "payloadHash" to sha256(canonical),
            ),
        )
    }

    private fun scenario(command: FiscalSubmissionCommand): String {
        if (command.correctionOfReceiptId != null) {
            return "credit_note"
        }
        val value = "${command.invoiceNumber} ${command.endpointUrl}".lowercase()
        return when {
            "reject" in value -> "reject"
            "timeout" in value -> "timeout"
            "retry" in value -> "retry"
            else -> "accept"
        }
    }

    private fun sign(secret: String, payload: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(
            SecretKeySpec(
                secret.toByteArray(StandardCharsets.UTF_8),
                "HmacSHA256",
            ),
        )
        return HexFormat.of().formatHex(
            mac.doFinal(payload.toByteArray(StandardCharsets.UTF_8)),
        )
    }

    private fun sha256(payload: String): String {
        return HexFormat.of().formatHex(
            MessageDigest.getInstance("SHA-256")
                .digest(payload.toByteArray(StandardCharsets.UTF_8)),
        )
    }
}
