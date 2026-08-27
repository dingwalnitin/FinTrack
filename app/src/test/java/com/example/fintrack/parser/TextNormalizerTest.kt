package com.example.fintrack.parser

import com.example.fintrack.parser.normalize.TextNormalizer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Normalizer tests (module 133): whitespace, Unicode variants, number
 * separators, VPA and card-mask normalization — all meaning-preserving.
 */
class TextNormalizerTest {

    @Test
    fun `whitespace collapses`() {
        assertEquals("a b c", TextNormalizer.normalizeWhitespace("  a\t\tb \n c  "))
    }

    @Test
    fun `non-breaking space becomes plain space`() {
        assertEquals("Rs. 100 debited", TextNormalizer.normalize("Rs.\u00A0100 debited"))
    }

    @Test
    fun `full-width digits fold to ascii`() {
        // "１２３" full-width -> "123"
        assertEquals("otp 123", TextNormalizer.normalize("\uFF1F otp \uFF11\uFF12\uFF33".let {
            it.replace('\uFF1F', ' ').replace('\uFF33', '3')
        }))
        assertEquals("amount 1234", TextNormalizer.normalize("amount\u00A0\uFF11\uFF12\uFF13\uFF14"))
    }

    @Test
    fun `zero-width characters are removed`() {
        assertEquals("abc", TextNormalizer.normalize("a\u200Bb\u200Cc\uFEFF"))
    }

    @Test
    fun `indian grouped amounts parse exactly`() {
        assertEquals(12345678L, TextNormalizer.parseAmountToken("1,23,456.78"))
        assertEquals(50000L, TextNormalizer.parseAmountToken("500.00"))
        assertEquals(50000L, TextNormalizer.parseAmountToken("500"))
        assertEquals(50500L, TextNormalizer.parseAmountToken("505"))
        // 5,00,000 = 5 lakh -> 50,000,000 minor (5 lakh × 100 paise)
        assertEquals(50_000_000L, TextNormalizer.parseAmountToken("5,00,000"))
        assertEquals(50_000_000L, TextNormalizer.parseAmountToken("5,00,000.00"))
    }

    @Test
    fun `malformed amount tokens are rejected not guessed`() {
        assertNull(TextNormalizer.parseAmountToken(",,,"))
        assertNull(TextNormalizer.parseAmountToken("12.34.56"))
        assertNull(TextNormalizer.parseAmountToken("12.345")) // >2 fraction digits: ambiguous
        assertNull(TextNormalizer.parseAmountToken(""))
        assertNull(TextNormalizer.parseAmountToken("abc"))
    }

    @Test
    fun `vpa normalization lowercases and strips spaces`() {
        assertEquals("john.doe@okhdfcbank", TextNormalizer.normalizeVpa("John.Doe @ okhdfcbank"))
        assertEquals("swiggy@ybl", TextNormalizer.normalizeVpa("SWIGGY@YBL"))
    }

    @Test
    fun `malformed vpas stay unknown`() {
        assertNull(TextNormalizer.normalizeVpa("foo@@bar"))
        assertNull(TextNormalizer.normalizeVpa("no-at-sign"))
        assertNull(TextNormalizer.normalizeVpa("@handle-only"))
        assertNull(TextNormalizer.normalizeVpa("user@"))
        assertNull(TextNormalizer.normalizeVpa("user@bad handle!"))
    }

    @Test
    fun `card masks normalize to last4 only when exactly four digits`() {
        assertEquals("4411", TextNormalizer.normalizeCardMask("XX4411"))
        assertEquals("8823", TextNormalizer.normalizeCardMask("**** 8823"))
        assertEquals("1234", TextNormalizer.normalizeCardMask("XXXXXXXXXXXX1234"))
        assertNull(TextNormalizer.normalizeCardMask("XX12345"))   // 5 digits: unknown
        assertNull(TextNormalizer.normalizeCardMask("XX12"))      // 2 digits: unknown
        assertNull(TextNormalizer.normalizeCardMask("no digits"))
    }
}
