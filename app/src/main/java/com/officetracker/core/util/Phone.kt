package com.officetracker.core.util

object Phone {
    /**
     * Normalises Bangladeshi mobile numbers to the 11-digit local form (01XXXXXXXXX).
     * "+8801712-345678", "8801712345678" and "01712 345678" all become "01712345678".
     */
    fun normalize(input: String): String {
        val digits = input.filter { it.isDigit() }
        return when {
            digits.startsWith("880") && digits.length == 13 -> "0" + digits.substring(3)
            digits.length == 10 && digits.startsWith("1") -> "0$digits"
            else -> digits
        }
    }

    fun isValid(input: String): Boolean = Regex("^01[3-9]\\d{8}$").matches(normalize(input))

    /** Firebase Auth identity for a phone number (email/password provider, no SMS cost). */
    fun toAuthEmail(phone: String, domain: String): String = "${normalize(phone)}@$domain"

    fun pretty(phone: String): String {
        val p = normalize(phone)
        return if (p.length == 11) "${p.substring(0, 5)}-${p.substring(5)}" else p
    }
}
