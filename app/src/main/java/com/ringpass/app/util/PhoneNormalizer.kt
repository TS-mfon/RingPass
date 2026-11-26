package com.ringpass.app.util

import java.util.Locale

object PhoneNormalizer {

    private val countryIsoToCallingCode = mapOf(
        "NG" to "234",
        "US" to "1",
        "CA" to "1",
        "GB" to "44",
        "GH" to "233",
        "KE" to "254",
        "ZA" to "27",
        "IN" to "91",
        "DE" to "49",
        "FR" to "33",
        "AE" to "971"
    )

    fun stripFormatting(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return ""

        val hasLeadingPlus = trimmed.startsWith("+")
        val digitsBuilder = StringBuilder(trimmed.length)
        for (ch in trimmed) {
            if (ch in '0'..'9') {
                digitsBuilder.append(ch)
            } else if (Character.isDigit(ch)) {
                val numeric = Character.digit(ch, 10)
                if (numeric in 0..9) digitsBuilder.append(('0'.code + numeric).toChar())
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
        return if (digitsOnly.length >= 10) {
            digitsOnly.takeLast(10)
        } else {
            digitsOnly
        }
    }

    fun toE164(
        raw: String,
        defaultCountryIso: String = Locale.getDefault().country.ifBlank { "NG" }
    ): String {
        val stripped = stripFormatting(raw)
        if (stripped.isEmpty()) return ""

        val callingCode = callingCodeForIso(defaultCountryIso)
        if (stripped.startsWith("+")) {
            val withoutPlus = stripped.drop(1)
            if (callingCode.isNotEmpty() && withoutPlus.startsWith("${callingCode}0") &&
                withoutPlus.length > callingCode.length + 7
            ) {
                return "+$callingCode${withoutPlus.drop(callingCode.length + 1)}"
            }
            return stripped
        }

        return when {
            stripped.startsWith("0") && stripped.length > 1 && callingCode.isNotEmpty() ->
                "+$callingCode${stripped.drop(1)}"
            stripped.length == 10 && callingCode.isNotEmpty() ->
                "+$callingCode$stripped"
            stripped.length > 10 ->
                "+$stripped"
            else -> stripped
        }
    }

    fun looksLikePhoneNumber(raw: String): Boolean {
        val digitsCount = raw.count { it in '0'..'9' }
        if (digitsCount < 7) return false
        val nonPhoneAlpha = raw.count { it.isLetter() }
        return nonPhoneAlpha == 0
    }

    fun callingCodeForIso(countryIso: String): String {
        return countryIsoToCallingCode[countryIso.uppercase(Locale.ROOT)] ?: "234"
    }
}
