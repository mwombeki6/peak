package com.mwombeki.peak.platformbilling.internal

import com.mwombeki.peak.platformbilling.api.PaymentMethod
import com.mwombeki.peak.platformbilling.api.PaymentMethodOption
import java.math.BigDecimal
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

/**
 * Which rails a given amount can actually travel down.
 *
 * This exists because the collection cap used to live in the quote: a selection totalling
 * more than the mobile money limit was refused outright, as if it were unsellable. It is
 * not. 5,000,000 TZS is a fact about USSD, not about the commercial agreement, and treating
 * the two as one created pressure toward genuinely bad answers — splitting a purchase across
 * several pushes, inventing a partially-paid state, or shortening an annual term to fit a
 * limit that has nothing to do with what was agreed.
 *
 * So the quote is always priced, and this answers the separate question of how it can be
 * paid. An amount no rail can carry yields a quote with nothing eligible and a reason the
 * customer can act on, which is a far better outcome than a refusal to quote at all.
 */
@Service
class PaymentMethodEligibilityService(
    private val jdbcTemplate: JdbcTemplate,
) {
    /**
     * Every enabled rail, each marked with whether it can carry this amount and why not.
     *
     * Ineligible methods are returned rather than filtered out: "bank transfer, for amounts
     * above 5,000,000" tells the customer something, whereas an empty list tells them only
     * that something is wrong.
     */
    fun methodsFor(amount: BigDecimal, currency: String): List<PaymentMethodOption> {
        return jdbcTemplate.query(
            """
            SELECT provider, payment_method, currency, min_amount, max_amount,
                   requires_msisdn
            FROM peak_payment_method_capabilities
            WHERE is_enabled = true
              AND currency = ?
            ORDER BY payment_method, provider
            """.trimIndent(),
            { rs, _ ->
                val method = PaymentMethod.fromDatabase(rs.getString("payment_method"))
                val minAmount = rs.getBigDecimal("min_amount")
                val maxAmount = rs.getBigDecimal("max_amount")
                val reason = ineligibleReason(amount, currency, minAmount, maxAmount)

                PaymentMethodOption(
                    provider = rs.getString("provider"),
                    method = method,
                    currency = rs.getString("currency").trim(),
                    requiresMsisdn = rs.getBoolean("requires_msisdn"),
                    eligible = reason == null,
                    ineligibleReason = reason,
                    maxAmount = maxAmount,
                )
            },
            currency,
        )
    }

    /**
     * Checked again at payment time, not only at quote time.
     *
     * A quote is valid for hours and the capability table can change inside that window, so
     * the answer shown to the customer is a suggestion and this is the enforcement.
     */
    fun requireEligible(
        provider: String,
        method: PaymentMethod,
        amount: BigDecimal,
        currency: String,
    ) {
        val capability = jdbcTemplate.query(
            """
            SELECT min_amount, max_amount, requires_msisdn, is_enabled
            FROM peak_payment_method_capabilities
            WHERE provider = ? AND payment_method = ? AND currency = ?
            """.trimIndent(),
            { rs, _ ->
                Capability(
                    minAmount = rs.getBigDecimal("min_amount"),
                    maxAmount = rs.getBigDecimal("max_amount"),
                    requiresMsisdn = rs.getBoolean("requires_msisdn"),
                    isEnabled = rs.getBoolean("is_enabled"),
                )
            },
            provider,
            method.databaseValue,
            currency,
        ).firstOrNull()

        require(capability != null && capability.isEnabled) {
            "${label(method)} is not available through $provider for $currency"
        }
        ineligibleReason(amount, currency, capability.minAmount, capability.maxAmount)?.let {
            throw IllegalArgumentException(it)
        }
    }

    fun requiresMsisdn(provider: String, method: PaymentMethod, currency: String): Boolean {
        return jdbcTemplate.query(
            """
            SELECT requires_msisdn FROM peak_payment_method_capabilities
            WHERE provider = ? AND payment_method = ? AND currency = ?
            """.trimIndent(),
            { rs, _ -> rs.getBoolean("requires_msisdn") },
            provider,
            method.databaseValue,
            currency,
        ).firstOrNull() ?: (method == PaymentMethod.MOBILE_MONEY)
    }

    private fun ineligibleReason(
        amount: BigDecimal,
        currency: String,
        minAmount: BigDecimal,
        maxAmount: BigDecimal?,
    ): String? {
        if (amount < minAmount) {
            return "Below the ${minAmount.toPlainString()} $currency minimum for this method"
        }
        if (maxAmount != null && amount > maxAmount) {
            return "Above the ${maxAmount.toPlainString()} $currency limit for a single " +
                "payment on this method"
        }
        return null
    }

    private fun label(method: PaymentMethod): String = when (method) {
        PaymentMethod.MOBILE_MONEY -> "Mobile money"
        PaymentMethod.BANK -> "Bank payment"
        PaymentMethod.CARD -> "Card payment"
    }

    private data class Capability(
        val minAmount: BigDecimal,
        val maxAmount: BigDecimal?,
        val requiresMsisdn: Boolean,
        val isEnabled: Boolean,
    )
}
