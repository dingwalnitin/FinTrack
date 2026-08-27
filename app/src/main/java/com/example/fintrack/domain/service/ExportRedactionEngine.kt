package com.example.fintrack.domain.service

/**
 * Stage 11 P23 #7 — export redaction engine (module 160).
 *
 * Extends the Stage 9 [RedactionEngine] with export-specific semantics:
 *  - full phone numbers and unrelated identifiers are removed;
 *  - REQUIRED financial identifiers survive: masked account suffixes
 *    (last4 / cardMask), UPI VPA handles (they are transaction-bearing),
 *    and reference ids (UTR/RRN) needed for reconciliation;
 *  - golden fixtures in [ExportRedactionGoldenFixtures] pin the exact
 *    expected output so redaction semantics cannot silently drift.
 *
 * Applied to every free-text field on the export path. Raw evidence bodies
 * are excluded from exports entirely unless the user explicitly opts in —
 * and even then they pass through this engine first.
 */
object ExportRedactionEngine {

    const val VERSION = "export-redact-v1"

    /** Full 10-digit Indian mobile numbers → masked, keeping last 2 digits. */
    private val PHONE_FULL = Regex("""(?<![0-9])(?:\+91[- ]?)?[6-9][0-9]{9}(?![0-9])""")

    /** Full (>4 digit) account/card numbers that are NOT already masked. */
    private val ACCOUNT_FULL = Regex("""\b(?:A\/c|a\/c|account|acct|card|XX|x+)\s*[xX*]*([0-9]{5,})\b""")

    /** OTPs never belong in an export. */
    private val OTP = Regex("""(?i)OTP[^0-9]{0,16}[0-9]{4,8}""")

    data class Result(val text: String, val redactions: Int)

    /**
     * Redact a free-text field for export.
     * Preserved: masked suffixes (••••1234 / XX1234), VPAs (name@bank),
     * reference ids, amounts and dates — these are required financial
     * identifiers per module 160.
     */
    fun redactForExport(text: String): Result {
        if (text.isBlank()) return Result(text, 0)
        var count = 0
        var out = text

        out = OTP.replace(out) { count++; "[OTP]" }
        // Masked forms like "XX1234" or "****1234" must survive; only
        // unmasked runs of >=5 digits after an account keyword are stripped.
        out = ACCOUNT_FULL.replace(out) { m ->
            count++
            val kw = m.value.takeWhile { !it.isDigit() && it != 'x' && it != 'X' }
            "${kw.trimEnd()}[REDACTED]"
        }
        out = PHONE_FULL.replace(out) { m ->
            count++
            val digits = m.value.filter { it.isDigit() }.takeLast(2)
            "[PHONE..$digits]"
        }
        return Result(out, count)
    }

    /**
     * True when a field is safe to export as-is: contains no unmasked
     * phone/account number and no OTP.
     */
    fun isExportSafe(text: String): Boolean {
        if (text.isBlank()) return true
        return PHONE_FULL.containsMatchIn(text).not() &&
            ACCOUNT_FULL.containsMatchIn(text).not() &&
            OTP.containsMatchIn(text).not()
    }
}

/**
 * Golden redaction fixtures (module 160 acceptance): input → exact expected
 * output. Tests iterate every fixture; any drift in regex behavior fails.
 */
object ExportRedactionGoldenFixtures {

    data class Fixture(
        val name: String,
        val input: String,
        val expectedOutput: String,
        val minRedactions: Int,
    )

    val ALL: List<Fixture> = listOf(
        Fixture(
            "full phone masked keeps last two",
            "call 9876543210 for support",
            "call [PHONE..10] for support",
            1,
        ),
        Fixture(
            "with country code",
            "registered +91 9876543210 ok",
            "registered [PHONE..10] ok",
            1,
        ),
        Fixture(
            "unmasked account number stripped",
            "transfer to A/c 1234567890 done",
            "transfer to A/c[REDACTED] done",
            1,
        ),
        Fixture(
            "masked card suffix preserved",
            "paid using XX1234",
            "paid using XX1234",
            0,
        ),
        Fixture(
            "vpa preserved (transaction-bearing)",
            "sent to rameshkumar95@ypl",
            "sent to rameshkumar95@ypl",
            0,
        ),
        Fixture(
            "otp removed",
            "OTP 483921 for Rs.500 purchase",
            "[OTP] for Rs.500 purchase",
            1,
        ),
        Fixture(
            "amounts and refs untouched",
            "Rs.2,550 debited ref UTR123456789",
            "Rs.2,550 debited ref UTR123456789",
            0,
        ),
    )
}
