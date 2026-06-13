package com.mwombeki.peak.shared.util

import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.util.Locale

/**
 * Financial Currency Formatter.
 * Handles exact high-precision financial operations using BigDecimal to prevent float rounding bugs.
 */

object CurrencyUtils {
    // Tanzanian Shilling typically does not show minor fractional coins in standard modern receipts,
    // but the database must track decimals strictly for tax/VAT math accuracy.
    private const val MONEY_SCALE = 2
    private val TZ_LOCALE = Locale("sw", "TZ")

    /**
     * Standardizes any raw money value to a clean, scale-adjusted production BigDecimal value.
     */
    fun toMoneyAmount(amount: Double): BigDecimal {
        return BigDecimal.valueOf(amount).setScale(MONEY_SCALE, RoundingMode.HALF_UP)
    }

    /**
     * Formats a financial amount into a clean, human-readable string with currency symbols (kwa mfano, TSh 150,000.00).
     */
    fun formatToTsh(amount: BigDecimal): String {
        val formatter = NumberFormat.getCurrencyInstance(TZ_LOCALE)
        formatter.minimumFractionDigits = 0
        formatter.maximumFractionDigits = 0
        return formatter.format(amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP))
    }

    /**
     * Computes explicit VAT/Tax fractions cleanly (e.g., extracting 18% standard VAT from a total price).
     */
    fun calculateTaxAmount(totalInclusive: BigDecimal, taxRatePercent: Double): BigDecimal {
        val rateFactor = BigDecimal.valueOf(taxRatePercent).divide(BigDecimal.valueOf(100.0), 4, RoundingMode.HALF_UP)
        val divisor = BigDecimal.ONE.add(rateFactor)
        val exclusiveAmount = totalInclusive.divide(divisor, MONEY_SCALE, RoundingMode.HALF_UP)
        return totalInclusive.subtract(exclusiveAmount)
    }


}