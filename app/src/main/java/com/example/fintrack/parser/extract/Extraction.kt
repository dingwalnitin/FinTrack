package com.example.fintrack.parser.extract

import com.example.fintrack.parser.FieldProvenance
import com.example.fintrack.parser.normalize.TextNormalizer

/**
 * Shared deterministic extraction helpers used by all rail adapters.
 * Every helper returns null when its rule does not match — no guessing.
 */
object Extraction {

    data class Hit<T>(val value: T, val provenance: FieldProvenance)

    const val FIXTURE_VERSION = "fixtures-v1"

    // ---- amount ----

    private val AMOUNT_RS = Regex(
        "(?:rs\\.?|inr|₹)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)",
        RegexOption.IGNORE_CASE,
    )
    /** "INR 500/-" or "Rs.500/-" trailing dash form. */
    private val AMOUNT_TRAILING = Regex(
        "(?:rs\\.?|inr)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)\\s*/-",
        RegexOption.IGNORE_CASE,
    )

    fun amount(text: String): Hit<Long>? {
        // Prefer the /- terminated form when present (banks use it for the
        // transaction amount specifically).
        val m = AMOUNT_TRAILING.find(text) ?: AMOUNT_RS.find(text) ?: return null
        val minor = TextNormalizer.parseAmountToken(m.groupValues[1]) ?: return null
        return Hit(minor, FieldProvenance("amount.rs-prefix", FIXTURE_VERSION, 0.95))
    }

    // ---- direction ----
    // "transfer" alone is ambiguous ("IMPS transfer to" is debit from sender,
    // "transfer to your account" is credit for receiver). We resolve the
    // ambiguity per match: debit when source-side ("transfer from", "IMPS
    // transfer of Rs.. to"), credit when destination-side ("transfer to your
    // A/c", "credit of Rs.. via IMPS"). When both sides are present the
    // candidate stays unresolved.
    private val DEBIT_VERB = Regex(
        "\\b(debited|spent|withdrawn|paid|purchase|deducted|charged|transfer (?:of|from)|" +
            "imps? transfer (?:of|from)|payment hua|payment hui|ka payment)\\b",
        RegexOption.IGNORE_CASE,
    )
    private val CREDIT_VERB = Regex(
        "\\b(credited|deposited|received|interest credited|salary\\b|cashback|refund|reversal|" +
            "transfer to (?:your|account)|credited to (?:your|account)|" +
            "credit of (?:inr|rs)|imps? credit of|neft credit|rtgs credit|payment mila|payment mili)\\b",
        RegexOption.IGNORE_CASE,
    )

    fun direction(text: String): Hit<com.example.fintrack.parser.Direction>? {
        val debit = DEBIT_VERB.containsMatchIn(text)
        val credit = CREDIT_VERB.containsMatchIn(text)
        return when {
            debit && !credit -> Hit(
                com.example.fintrack.parser.Direction.DEBIT,
                FieldProvenance("direction.debit-verb", FIXTURE_VERSION, 0.95),
            )
            credit && !debit -> Hit(
                com.example.fintrack.parser.Direction.CREDIT,
                FieldProvenance("direction.credit-verb", FIXTURE_VERSION, 0.95),
            )
            else -> null // both or neither: ambiguous, leave unknown
        }
    }

    // ---- account token (masked suffix) ----

    /**
     * Matches "XX1234", "a/c XX1234", "A/c *1234", "XXXXXX1234". Extracts
     * exactly-4-digit groups only.
     */
    private val ACCOUNT_TOKEN = Regex(
        "(?:(?:a/?c|account|card|x{2,}|\\*)[\\s.:\\-]*)([0-9]{4})\\b",
        RegexOption.IGNORE_CASE,
    )
    /** "card ending 1234" / "card no 1234" / "card number ...xx1234". */
    private val CARD_ENDING_PLAIN = Regex(
        "card\\s+(?:ending|no\\.?|number)?\\s*([0-9]{4})\\b",
        RegexOption.IGNORE_CASE,
    )
    private val CARD_ENDING_MASKED = Regex(
        "card\\s+(?:ending|no\\.?|number)?\\s*(?:in\\s+)?(?:xx+|\\*+)\\s*([0-9]{4})\\b",
        RegexOption.IGNORE_CASE,
    )

    fun accountToken(text: String): Hit<String>? {
        (CARD_ENDING_MASKED.find(text)
            ?: CARD_ENDING_PLAIN.find(text)
            ?: ACCOUNT_TOKEN.find(text))?.let { m ->
            val digits = m.groupValues[1]
            if (digits.length == 4) {
                return Hit(digits, FieldProvenance("account.masked-suffix", FIXTURE_VERSION, 0.85))
            }
        }
        return null
    }

    // ---- bank reference (UTR/RRN/ref no) ----

    private val REF_PATTERNS = listOf(
        "ref" to Regex("(?:ref(?:erence)?\\s*(?:no\\.?|number)?)[\\s:.#]*([A-Za-z0-9]{6,20})", RegexOption.IGNORE_CASE),
        "utr" to Regex("\\butr[\\s:]*([A-Za-z0-9]{8,22})", RegexOption.IGNORE_CASE),
        "rrn" to Regex("\\brrn[\\s:]*([0-9]{6,22})", RegexOption.IGNORE_CASE),
        "upi-ref" to Regex("\\b(?:upi[\\s:]*)?(?:ref|txn id)[\\s:.#]*([0-9]{9,18})", RegexOption.IGNORE_CASE),
    )

    fun bankReference(text: String): Hit<String>? {
        for ((rule, regex) in REF_PATTERNS) {
            regex.find(text)?.let { m ->
                return Hit(m.groupValues[1].uppercase(), FieldProvenance("bankref.$rule", FIXTURE_VERSION, 0.9))
            }
        }
        return null
    }

    // ---- date/time ----

    private val DATE_DD_MM_YY = Regex(
        "\\bon\\s+([0-9]{1,2})[-/]([0-9]{1,2})[-/]([0-9]{2,4})(?:\\s+(?:at\\s+)?([0-9]{1,2}):([0-9]{2}))?",
        RegexOption.IGNORE_CASE,
    )
    private val DATE_YYYY_MM_DD = Regex(
        "\\b([0-9]{4})-([0-9]{2})-([0-9]{2})(?:[ T]([0-9]{2}):([0-9]{2}))?",
    )

    /**
     * Parses explicit dates. Returns epoch millis + derived local day using
     * [zone]. Ambiguous formats (dd/mm vs mm/dd cannot be distinguished) are
     * treated as dd/mm/yyyy — the Indian SMS convention; anything else stays
     * unknown.
     */
    fun occurredAt(text: String, zone: java.time.ZoneId): Hit<Pair<Long, Long>>? {
        DATE_YYYY_MM_DD.find(text)?.let { m ->
            val y = m.groupValues[1].toIntOrNull() ?: return@let
            val mo = m.groupValues[2].toIntOrNull() ?: return@let
            val d = m.groupValues[3].toIntOrNull() ?: return@let
            val hh = m.groupValues[4].ifEmpty { "12" }.toIntOrNull() ?: 12
            val mm = m.groupValues[5].ifEmpty { "00" }.toIntOrNull() ?: 0
            if (mo in 1..12 && d in 1..31 && hh in 0..23 && mm in 0..59) {
                val ldt = java.time.LocalDateTime.of(y, mo, d, hh, mm)
                val instant = ldt.atZone(zone).toInstant()
                return Hit(
                    instant.toEpochMilli() to com.example.fintrack.domain.policy.DateTimePolicy
                        .localDateEpochDay(instant, zone),
                    FieldProvenance("date.iso", FIXTURE_VERSION, 0.98),
                )
            }
        }
        DATE_DD_MM_YY.find(text)?.let { m ->
            val d = m.groupValues[1].toIntOrNull() ?: return@let
            val mo = m.groupValues[2].toIntOrNull() ?: return@let
            var y = m.groupValues[3].toIntOrNull() ?: return@let
            if (y < 100) y += 2000
            val hh = m.groupValues[4].ifEmpty { "12" }.toIntOrNull() ?: 12
            val mm = m.groupValues[5].ifEmpty { "00" }.toIntOrNull() ?: 0
            if (mo in 1..12 && d in 1..31 && hh in 0..23 && mm in 0..59) {
                val ldt = java.time.LocalDateTime.of(y, mo, d, hh, mm)
                val instant = ldt.atZone(zone).toInstant()
                return Hit(
                    instant.toEpochMilli() to com.example.fintrack.domain.policy.DateTimePolicy
                        .localDateEpochDay(instant, zone),
                    FieldProvenance("date.ddmmyyyy", FIXTURE_VERSION, 0.9),
                )
            }
        }
        return null
    }

    // ---- counterparty ----

    /** UPI: "to NAME (VPA)" / "from NAME (VPA)" / "to VPA". */
    private val UPI_TO_FROM = Regex(
        "(?:to|from)\\s+([A-Za-z0-9 .&'-]{2,40}?)\\s*(?:\\(([^)]+)\\))?\\s*(?:on|using|via|\\.|$)",
        RegexOption.IGNORE_CASE,
    )

    fun counterparty(text: String): Hit<Pair<String?, String?>>? {
        UPI_TO_FROM.find(text)?.let { m ->
            val name = m.groupValues[1].trim().takeIf { it.isNotEmpty() }
            val vpaRaw = m.groupValues[2].trim().takeIf { it.isNotEmpty() }
            val vpa = vpaRaw?.let { TextNormalizer.normalizeVpa(it) }
            if (name != null || vpa != null) {
                return Hit(
                    name to vpa,
                    FieldProvenance("counterparty.to-from", FIXTURE_VERSION, 0.75),
                )
            }
        }
        return null
    }

    /** Normalizes a counterparty name for matching (not for display). */
    fun normalizeCounterparty(raw: String): String =
        raw.trim().lowercase().replace(Regex("[^a-z0-9 ]"), "").replace(Regex("\\s+"), " ").trim()
}
