package com.mwombeki.peak.platformbilling.internal.web

import com.mwombeki.peak.platformbilling.api.PaymentAttemptResponse
import com.mwombeki.peak.platformbilling.api.PayPurchaseRequest
import com.mwombeki.peak.platformbilling.api.PlatformBillingNotFoundException
import com.mwombeki.peak.platformbilling.api.PlatformBillingPort
import com.mwombeki.peak.platformbilling.api.ProductSummary
import com.mwombeki.peak.platformbilling.api.PurchaseResponse
import com.mwombeki.peak.platformbilling.api.Quote
import com.mwombeki.peak.platformbilling.api.QuoteRequest
import java.util.UUID
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * Where a tenant buys Peak.
 *
 * Mapped under `tenants/{tenantId}` rather than a module path on purpose: these routes are
 * registered in `module_access_matrix` against `tenant_admin`, which the entitlement
 * reconciler never disables. A tenant whose subscription has lapsed must still be able to
 * reach the page that ends the lapse.
 */
@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/billing")
class TenantBillingController(
    private val platformBillingPort: PlatformBillingPort,
) {
    @GetMapping("/catalog")
    fun catalog(@PathVariable tenantId: UUID): List<ProductSummary> {
        return platformBillingPort.catalog()
    }

    @PostMapping("/quotes")
    fun quote(
        @PathVariable tenantId: UUID,
        @RequestBody request: QuoteRequest,
    ): Quote {
        return platformBillingPort.quote(tenantId, request)
    }

    @GetMapping("/purchases")
    fun purchases(
        @PathVariable tenantId: UUID,
        @RequestParam(defaultValue = "50") limit: Int,
    ): List<PurchaseResponse> {
        return platformBillingPort.purchases(tenantId, limit)
    }

    @GetMapping("/purchases/{purchaseId}")
    fun purchase(
        @PathVariable tenantId: UUID,
        @PathVariable purchaseId: UUID,
    ): PurchaseResponse {
        return platformBillingPort.purchase(tenantId, purchaseId)
            ?: throw PlatformBillingNotFoundException("Purchase was not found")
    }

    @PostMapping("/purchases")
    @ResponseStatus(HttpStatus.CREATED)
    fun createPurchase(
        @PathVariable tenantId: UUID,
        @RequestBody request: QuoteRequest,
    ): PurchaseResponse {
        return platformBillingPort.createPurchase(tenantId, request)
    }

    /**
     * Returns as soon as the prompt is sent. The purchase is still unpaid at this point —
     * only the provider callback settles it — so a caller must poll the purchase rather
     * than treat this response as a receipt.
     */
    @PostMapping("/purchases/{purchaseId}/payments")
    @ResponseStatus(HttpStatus.ACCEPTED)
    fun pay(
        @PathVariable tenantId: UUID,
        @PathVariable purchaseId: UUID,
        @RequestBody request: PayPurchaseRequest,
    ): PaymentAttemptResponse {
        return platformBillingPort.pay(tenantId, purchaseId, request)
    }
}
