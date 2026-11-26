package com.ringpass.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PhoneNormalizerTest {

    @Test
    fun `local and international Nigerian formats produce identical match keys`() {
        val local = PhoneNormalizer.toMatchKey("08031234567")
        val international = PhoneNormalizer.toMatchKey("+2348031234567")
        val spaced = PhoneNormalizer.toMatchKey("+234 803 123 4567")
        val parenthesized = PhoneNormalizer.toMatchKey("(0803) 123-4567")
        val doubleZero = PhoneNormalizer.toMatchKey("00234-803-123-4567")
        val malformedTrunk = PhoneNormalizer.toMatchKey("+234 (0) 803 123 4567")

        assertEquals("8031234567", local)
        assertEquals(local, international)
        assertEquals(local, spaced)
        assertEquals(local, parenthesized)
        assertEquals(local, doubleZero)
        assertEquals(local, malformedTrunk)
    }

    @Test
    fun `US formatted numbers normalize to identical trailing 10 digits`() {
        val plain = PhoneNormalizer.toMatchKey("6505550199")
        val formatted = PhoneNormalizer.toMatchKey("(650) 555-0199")
        val withCountry = PhoneNormalizer.toMatchKey("+1 650-555-0199")

        assertEquals("6505550199", plain)
        assertEquals(plain, formatted)
        assertEquals(plain, withCountry)
    }

    @Test
    fun `toE164 normalizes local and international inputs consistently`() {
        assertEquals("+2348031234567", PhoneNormalizer.toE164("08031234567", "NG"))
        assertEquals("+2348031234567", PhoneNormalizer.toE164("+234 803 123 4567", "NG"))
        assertEquals("+2348031234567", PhoneNormalizer.toE164("002348031234567", "NG"))
        assertEquals("+2348031234567", PhoneNormalizer.toE164("+234 (0) 803 123 4567", "NG"))
        assertEquals("+16505550199", PhoneNormalizer.toE164("650-555-0199", "US"))
    }

    @Test
    fun `looksLikePhoneNumber distinguishes digits from contact display names`() {
        assertTrue(PhoneNormalizer.looksLikePhoneNumber("+234 803 123 4567"))
        assertTrue(PhoneNormalizer.looksLikePhoneNumber("08031234567"))
        assertFalse(PhoneNormalizer.looksLikePhoneNumber("Dispatch Rider"))
        assertFalse(PhoneNormalizer.looksLikePhoneNumber("12345"))
    }
}
