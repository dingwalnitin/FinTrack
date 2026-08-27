package com.example.fintrack.parser.normalize

/**
 * Deterministic text normalization for financial SMS.
 *
 * Guarantees:
 *  - never changes meaning (amounts, dates, references preserved exactly)
 *  - raw text is immutable upstream; this produces a *derived* normalized copy
 *  - pure function: same input -> same output, no locale dependence
 *
 * Handles (module 133): whitespace collapse, Unicode variants (full-width
 * digits, NBSP, smart quotes), number separators (Indian 1,00,000 and decimal
 * comma variants), punctuation spacing.
 */
object TextNormalizer {

    /** Collapse all whitespace runs to single spaces, trim ends. */
    fun normalizeWhitespace(raw: String): String =
        raw.replace(WHITESPACE_REGEX, " ").trim()

    /**
     * Full normalization pipeline:
     *  1. Unicode fold (full-width -> ASCII, NBSP -> space, smart quotes)
     *  2. whitespace collapse
     *  3. zero-width character removal
     */
    fun normalize(raw: String): String {
        var s = unicodeFold(raw)
        s = removeZeroWidth(s)
        return normalizeWhitespace(s)
    }

    /**
     * Unicode folding that preserves meaning:
     *  - non-breaking / narrow spaces -> plain space
     *  - full-width digits & letters -> ASCII
     *  - typographic quotes/dashes -> ASCII equivalents
     */
    fun unicodeFold(raw: String): String = buildString(raw.length) {
        for (ch in raw) {
            when {
                ch == '\u00A0' || ch == '\u2007' || ch == '\u202F' -> append(' ')
                ch == '\u2018' || ch == '\u2019' -> append('\'')
                ch == '\u201C' || ch == '\u201D' -> append('"')
                ch == '\u2013' || ch == '\u2014' -> append('-')
                ch.code in 0xFF01..0xFF5E -> append((ch.code - 0xFEE0).toChar())
                else -> append(ch)
            }
        }
    }

    private fun removeZeroWidth(s: String): String =
        s.filter { c ->
            c.code !in ZERO_WIDTH_CODES
        }

    /**
     * Parse an amount token like "1,23,456.78", "123456.78", "Rs.1,234" into
     * minor units. Returns null when the token is not a clean number — we
     * never guess.
     *
     * Indian grouping (lakh/crore) uses commas every 2 after the first 3;
     * western grouping uses 3s. Both are accepted; separators are stripped,
     * not interpreted, so meaning is unchanged either way.
     */
    fun parseAmountToken(token: String): Long? {
        val cleaned = token.trim().removePrefix("Rs.").removePrefix("INR").trim()
        if (cleaned.isEmpty()) return null
        // Reject tokens with letters or stray symbols.
        if (!cleaned.all { it.isDigit() || it == ',' || it == '.' }) return null
        val noGrouping = cleaned.replace(",", "")
        val parts = noGrouping.split('.')
        if (parts.size > 2) return null
        val whole = parts[0]
        if (whole.isEmpty() || !whole.all { it.isDigit() }) return null
        val frac = when (parts.size) {
            2 -> parts[1]
            else -> ""
        }
        if (frac.length > 2) return null // more precision than paise: ambiguous, refuse
        val wholeMinor = whole.toLongOrNull() ?: return null
        val fracMinor = when (frac.length) {
            0 -> 0L
            1 -> frac.toLong() * 10L
            else -> frac.toLong()
        }
        return wholeMinor * 100L + fracMinor
    }

    /**
     * Normalize a UPI VPA (Virtual Payment Address): lowercase, single space
     * removal around '@', preserve handle. e.g. "John.Doe @ hdfcbank" ->
     * "john.doe@hdfcbank".
     */
    fun normalizeVpa(raw: String): String? {
        val collapsed = normalizeWhitespace(raw).replace(" ", "").lowercase()
        val at = collapsed.count { it == '@' }
        if (at != 1) return null // malformed VPA: keep unknown
        val (handle, bank) = collapsed.split('@')
        if (handle.isEmpty() || bank.isEmpty()) return null
        if (!bank.all { it.isLetterOrDigit() }) return null
        return "$handle@$bank"
    }

    /**
     * Normalize a masked card identifier like "XX1234", "**** 1234",
     * "XXXXXXXXXXXX1234". Extracts the trailing digit group as last4 only when
     * exactly 4 digits are present; otherwise returns null (unknown stays
     * unknown).
     */
    fun normalizeCardMask(raw: String): String? {
        val digits = raw.filter { it.isDigit() }
        return if (digits.length == 4) digits else null
    }

    private val WHITESPACE_REGEX = Regex("\\s+")

    private val ZERO_WIDTH_CODES = intArrayOf(0x200B, 0x200C, 0x200D, 0xFEFF)
}
