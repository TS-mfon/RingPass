package com.ringpass.app.util

import java.util.Locale

object PhoneNormalizer {

    private val invisibleCharsRegex = Regex("""[\p{Cf}\u00A0]+""")
    private val multiSpaceRegex = Regex("""\s+""")

    private val countryIsoToCallingCode = mapOf(
        "NG" to "234", "US" to "1", "CA" to "1", "GB" to "44",
        "GH" to "233", "KE" to "254", "ZA" to "27", "IN" to "91",
        "DE" to "49", "FR" to "33", "AE" to "971"
    )

    fun cleanText(raw: String): String =
        raw.replace(invisibleCharsRegex, " ").replace(multiSpaceRegex, " ").trim()

    fun stripFormatting(raw: String): String {
        val trimmed = cleanText(raw)
        if (trimmed.isEmpty()) return ""
        val hasLeadingPlus = trimmed.startsWith("+")
        val digitsBuilder = StringBuilder(trimmed.length)
        for (ch in trimmed) {
            if (ch in '0'..'9') digitsBuilder.append(ch)
            else if (Character.isDigit(ch)) {
                val n = Character.digit(ch, 10)
                if (n in 0..9) digitsBuilder.append(('0'.code + n).toChar())
            }
        }
        val digits = digitsBuilder.toString()
        if (digits.isEmpty()) return ""
        return when {
            hasLeadingPlus -> "+$digits"
            digits.startsWith("00") && digits.length > 4 -> "+${digits.drop(2)}"
            else -> digits
        }
    }

    fun toMatchKey(raw: String): String {
        val stripped = stripFormatting(raw)
        val digitsOnly = if (stripped.startsWith("+")) stripped.drop(1) else stripped
        return if (digitsOnly.length >= 10) digitsOnly.takeLast(10) else digitsOnly
    }

    fun toE164(
        raw: String,
        defaultCountryIso: String = Locale.getDefault().country.ifBlank { "NG" }
    ): String {
        val stripped = stripFormatting(raw)
        if (stripped.isEmpty()) return ""
        val code = callingCodeForIso(defaultCountryIso)
        if (stripped.startsWith("+")) {
            val rest = stripped.drop(1)
            if (code.isNotEmpty() && rest.startsWith("${code}0") && rest.length > code.length + 7) {
                return "+$code${rest.drop(code.length + 1)}"
            }
            return stripped
        }
        return when {
            stripped.startsWith("0") && stripped.length > 1 && code.isNotEmpty() -> "+$code${stripped.drop(1)}"
            stripped.length == 10 && code.isNotEmpty() -> "+$code$stripped"
            stripped.length > 10 -> "+$stripped"
            else -> stripped
        }
    }

    fun looksLikePhoneNumber(raw: String): Boolean {
        val cleaned = cleanText(raw)
        return cleaned.count { it in '0'..'9' } >= 7 && cleaned.none { it.isLetter() }
    }

    fun callingCodeForIso(countryIso: String): String =
        countryIsoToCallingCode[countryIso.uppercase(Locale.ROOT)] ?: "234"
}
