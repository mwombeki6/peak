package com.mwombeki.peak.platformbilling.internal.web

import com.mwombeki.peak.platformbilling.api.IssuedReceipt
import com.mwombeki.peak.platformbilling.api.PlatformBillingAdminPort
import com.mwombeki.peak.platformbilling.api.StuckPayment
import com.mwombeki.peak.platformbilling.api.TenantCommercialStanding
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * What Peak's own operators can see about subscription revenue.
 *
 * Under `/api/v1/platform/`, so the route guard applies platform permissions rather than
 * tenant restriction — an operator must be able to look at a suspended tenant, since that is
 * usually the reason they are looking.
 *
 * Read-only. Confirming a payment by hand, writing off a debt or enabling a payment rail are
 * decisions with money attached; they belong in a reviewed path with an audit trail rather
 * than an endpoint added because someone was on a support call. `ConfirmationSource.OPERATOR`
 * exists for when that path is built.
 */
@RestController
@RequestMapping("/api/v1/platform/billing")
class PlatformBillingAdminController(
    private val adminPort: PlatformBillingAdminPort,
) {
    /**
     * The answer to "is this tenant's subscription broken?", which the raw subscription row
     * cannot give: during suspension it reads `past_due` while remaining deliberately
     * service-granting, and read alone that looks like something to fix.
     */
    @GetMapping("/standing")
    fun commercialStanding(
        @RequestParam(defaultValue = "200") limit: Int,
    ): List<TenantCommercialStanding> {
        return adminPort.commercialStanding(limit)
    }

    /**
     * Every customer who may have been debited for something they have not received. This
     * queue growing is the signal that a provider integration is misbehaving.
     */
    @GetMapping("/reconciliation")
    fun paymentsRequiringReconciliation(
        @RequestParam(defaultValue = "200") limit: Int,
    ): List<StuckPayment> {
        return adminPort.paymentsRequiringReconciliation(limit)
    }

    @GetMapping("/receipts")
    fun receipts(@RequestParam(defaultValue = "200") limit: Int): List<IssuedReceipt> {
        return adminPort.receipts(limit)
    }
}
