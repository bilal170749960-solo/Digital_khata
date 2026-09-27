package com.example.utils

import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToLong

/**
 * Centralized, safe currency formatting for Digital Khata.
 * Strictly adheres to Pakistani standard display: "Rs. 500", "Rs. 1,250", "Rs. 10,000", "Rs. 125,500"
 * Eliminates floating-point anomalies (NaN, Infinity, negative zero, dangling decimals).
 */
object CurrencyFormatter {
    private val numberFormat: NumberFormat = NumberFormat.getNumberInstance(Locale.US).apply {
        isGroupingUsed = true
        maximumFractionDigits = 0
    }

    fun format(amount: Double): String {
        if (amount.isNaN() || amount.isInfinite()) {
            return "Rs. 0"
        }
        val safeAmount = amount.coerceAtLeast(0.0)
        val roundedLong = safeAmount.roundToLong()
        return "Rs. ${numberFormat.format(roundedLong)}"
    }

    fun format(amount: Long): String {
        val safeAmount = amount.coerceAtLeast(0L)
        return "Rs. ${numberFormat.format(safeAmount)}"
    }
}
