package com.mwombeki.peak.platformbilling.internal.web

import com.mwombeki.peak.platformbilling.api.PlatformBillingWebhookPort
import com.mwombeki.peak.platformbilling.api.PlatformBillingWebhookReceipt
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Where a provider confirms that Peak has been paid.
 *
 * Deliberately not under `/api/v1/payments/webhooks/`, which settles a *property's* guest
 * payments against a folio using that property's provider account. This settles *Peak's*
 * revenue into Peak's own merchant account. Sharing a route would mean sharing a credential
 * source, and a misrouted callback would credit the wrong ledger entirely.
 *
 * The body is taken as a raw String because the signature covers the exact bytes; letting
 * Jackson round-trip it first would verify a re-serialisation rather than what was sent.
 */
@RestController
@RequestMapping("/api/v1/platform-billing/webhooks")
class PlatformBillingWebhookController(
    private val platformBillingWebhookPort: PlatformBillingWebhookPort,
) {
    @PostMapping(
        "/{providerCode}",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun receive(
        @PathVariable providerCode: String,
        @RequestHeader headers: Map<String, String>,
        @RequestBody payload: String,
    ): PlatformBillingWebhookReceipt {
        return platformBillingWebhookPort.receive(
            providerCode = providerCode,
            payload = payload,
            headers = headers,
        )
    }
}
