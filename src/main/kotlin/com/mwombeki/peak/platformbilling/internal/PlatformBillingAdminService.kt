package com.mwombeki.peak.platformbilling.internal

import com.mwombeki.peak.platformbilling.api.IssuedReceipt
import com.mwombeki.peak.platformbilling.api.PlatformBillingAdminPort
import com.mwombeki.peak.platformbilling.api.StuckPayment
import com.mwombeki.peak.platformbilling.api.TenantCommercialStanding
import java.util.UUID
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

/**
 * Reads the operator views.
 *
 * Runs on the platform session established by the route guard, which is why these can read
 * across tenants where the tenant-facing services cannot. The views themselves do the
 * masking and the phrasing, so what an operator sees in the API and what they would see
 * querying the database directly are the same thing.
 */
@Service
class PlatformBillingAdminService(
    private val jdbcTemplate: JdbcTemplate,
) : PlatformBillingAdminPort {

    override fun commercialStanding(limit: Int): List<TenantCommercialStanding> {
        return jdbcTemplate.query(
            """
            SELECT tenant_id, tenant_name, commercial_standing, subscription_row_status,
                   service_relationship, operational_policy, paid_through, payment_status,
                   renewal_offer_status, outstanding_amount, outstanding_currency
            FROM peak_tenant_commercial_standing
            ORDER BY
                -- Worst standing first: the tenants an operator needs to see are the ones
                -- who are stuck, not the ones paying happily.
                CASE commercial_standing
                    WHEN 'suspended' THEN 0
                    WHEN 'restricted' THEN 1
                    ELSE 2
                END,
                paid_through NULLS LAST
            LIMIT ?
            """.trimIndent(),
            { rs, _ ->
                TenantCommercialStanding(
                    tenantId = rs.getObject("tenant_id", UUID::class.java),
                    tenantName = rs.getString("tenant_name"),
                    commercialStanding = rs.getString("commercial_standing"),
                    subscriptionRowStatus = rs.getString("subscription_row_status"),
                    serviceRelationship = rs.getString("service_relationship"),
                    operationalPolicy = rs.getString("operational_policy"),
                    paidThrough = rs.getTimestamp("paid_through")?.toInstant(),
                    paymentStatus = rs.getString("payment_status"),
                    renewalOfferStatus = rs.getString("renewal_offer_status"),
                    outstandingAmount = rs.getBigDecimal("outstanding_amount"),
                    outstandingCurrency = rs.getString("outstanding_currency")?.trim(),
                )
            },
            limit.coerceIn(1, 1000),
        )
    }

    override fun paymentsRequiringReconciliation(limit: Int): List<StuckPayment> {
        return jdbcTemplate.query(
            """
            SELECT tenant_id, tenant_name, purchase_id, attempt_id, amount, currency,
                   provider, payer_msisdn_masked, internal_reference, provider_reference,
                   started_at, last_status_checked_at, status_check_count,
                   last_provider_status, last_status_error
            FROM peak_payments_requiring_reconciliation
            ORDER BY started_at
            LIMIT ?
            """.trimIndent(),
            { rs, _ ->
                StuckPayment(
                    tenantId = rs.getObject("tenant_id", UUID::class.java),
                    tenantName = rs.getString("tenant_name"),
                    purchaseId = rs.getObject("purchase_id", UUID::class.java),
                    attemptId = rs.getObject("attempt_id", UUID::class.java),
                    amount = rs.getBigDecimal("amount"),
                    currency = rs.getString("currency").trim(),
                    provider = rs.getString("provider"),
                    payerMsisdnMasked = rs.getString("payer_msisdn_masked"),
                    internalReference = rs.getString("internal_reference"),
                    providerReference = rs.getString("provider_reference"),
                    startedAt = rs.getTimestamp("started_at").toInstant(),
                    lastStatusCheckedAt = rs.getTimestamp("last_status_checked_at")?.toInstant(),
                    statusCheckCount = rs.getInt("status_check_count"),
                    lastProviderStatus = rs.getString("last_provider_status"),
                    lastStatusError = rs.getString("last_status_error"),
                )
            },
            limit.coerceIn(1, 1000),
        )
    }

    override fun receipts(limit: Int): List<IssuedReceipt> {
        return jdbcTemplate.query(
            """
            SELECT receipt_number, tenant_id, purchase_id, issued_at, total_amount, currency
            FROM peak_receipts
            ORDER BY issued_at DESC
            LIMIT ?
            """.trimIndent(),
            { rs, _ ->
                IssuedReceipt(
                    receiptNumber = rs.getString("receipt_number"),
                    tenantId = rs.getObject("tenant_id", UUID::class.java),
                    purchaseId = rs.getObject("purchase_id", UUID::class.java),
                    issuedAt = rs.getTimestamp("issued_at").toInstant(),
                    totalAmount = rs.getBigDecimal("total_amount"),
                    currency = rs.getString("currency").trim(),
                )
            },
            limit.coerceIn(1, 1000),
        )
    }
}
