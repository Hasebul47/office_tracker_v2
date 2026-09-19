package com.officetracker.core.util

import java.util.Locale
import kotlin.math.roundToInt

object Format {
    fun distance(meters: Double): String = when {
        meters < 1000 -> "${meters.roundToInt()} m"
        else -> String.format(Locale.ENGLISH, "%.1f km", meters / 1000)
    }

    fun km(meters: Double): String = String.format(Locale.ENGLISH, "%.2f", meters / 1000)

    fun allowance(meters: Double, ratePerKm: Double): Double = (meters / 1000.0) * ratePerKm

    fun taka(amount: Double): String = String.format(Locale.ENGLISH, "৳%,.0f", amount)

    fun takaPlain(amount: Double): String = String.format(Locale.ENGLISH, "%.2f", amount)
}
