package com.example.fintrack.parser.classify

import com.example.fintrack.parser.BorderlineReason
import com.example.fintrack.parser.ClassificationResult
import com.example.fintrack.parser.FinancialClass
import com.example.fintrack.parser.normalize.TextNormalizer

/**
 * Cheap deterministic financial-SMS classifier (module 132).
 *
 * Design:
 *  - pure keyword/structure signals over normalized text; no network, no LLM
 *  - classification is separate from extraction: this never extracts fields
 *  - measurable: emits matched signal ids so fixture corpora can compute
 *    precision/recall per signal
 *
 * Borderline messages (e.g. OTPs that mention amounts, marketing with "offer")
 * are surfaced as BORDERLINE so a later optional LLM pass can decide; we never
 * guess here.
 */
object DeterministicSmsClassifier {

    private const val VERSION = "classifier-v1"

    // ---- positive signals (financial) ----
    private val DEBIT_SIGNALS = listOf(
        "debited", "spent", "withdrawn", "paid", "purchase", "deducted",
        "charged", "used your card", "atm withdrawal",
        // Rail-prefixed transfer verbs: "IMPS transfer of/from", "UPI payment of".
        "imps transfer", "neft transfer", "rtgs transfer", "upi payment",
        // Generic source-side transfer verbs (Stage 12 repair: the extractor
        // already treats "transfer of/from" as debit; the classifier must agree
        // so "Transfer of Rs.X from A/c" is FINANCIAL, not BORDERLINE).
        "transfer of", "transfer from", "transferred from",
        // Hinglish debit marker: "A/c XX se Rs.1500 ka payment hua hai" —
        // common in Indian bank SMS. Mirrors the extractor's debit verbs.
        "payment hua", "payment hui", "ka payment",
    )
    private val CREDIT_SIGNALS = listOf(
        "credited", "received", "deposited", "salary", "refund", "cashback",
        "interest credited", "reversal", "imps credit", "neft credit", "rtgs credit",
        "credit of", "credited to your", "credited to a/c",
        // Destination-side transfer verb (mirrors the extractor's CREDIT_VERB).
        "transferred to",
        // Hinglish credit marker.
        "payment mila", "payment mili",
    )
    private val RAIL_SIGNALS = mapOf(
        "upi" to listOf("upi", "vpa", "@ok", "@ybl", "@paytm", "@ibl", "@axl"),
        "imps" to listOf("imps"),
        "neft" to listOf("neft"),
        "rtgs" to listOf("rtgs"),
        "card" to listOf("card", "xx", "ending"),
        "atm" to listOf("atm"),
    )
    private val AMOUNT_HINT = Regex(
        "(rs\\.?|inr|₹)\\s*[0-9][0-9,]*(\\.[0-9]{1,2})?",
        RegexOption.IGNORE_CASE,
    )

    // ---- negative signals (non-financial) ----
    private val NON_FINANCIAL_SIGNALS = listOf(
        "otp", "one time password", "verification code", "your login",
        "password reset", "welcome", "activate", "kyc", "download the app",
        "offer", "discount", "congratulations", "lottery", "click here",
    )

    /** Future-dated markers: the event has NOT occurred yet — never a transaction. */
    private val FUTURE_SIGNALS = listOf(
        "will be debited", "will be credited", "will be deducted", "scheduled",
        "will be charged",
    )

    fun classify(rawBody: String): ClassificationResult {
        val text = TextNormalizer.normalize(rawBody).lowercase()
        val matched = mutableListOf<String>()

        val debitHits = DEBIT_SIGNALS.filter { it in text }
        val creditHits = CREDIT_SIGNALS.filter { it in text }
        val nonFinHits = NON_FINANCIAL_SIGNALS.filter { it in text }
        val amountHit = AMOUNT_HINT.containsMatchIn(text)
        val railHits = RAIL_SIGNALS.entries
            .filter { (_, words) -> words.any { it in text } }
            .map { it.key }

        if (amountHit) matched.add("amount-token")
        matched.addAll(railHits.map { "rail:$it" })
        matched.addAll(debitHits.map { "debit:${it.take(12)}" })
        matched.addAll(creditHits.map { "credit:${it.take(12)}" })
        matched.addAll(nonFinHits.map { "nonfin:${it.take(12)}" })

        val economicSignals = debitHits.size + creditHits.size
        val futureHits = FUTURE_SIGNALS.filter { it in text }

        return when {
            // Future-dated markers mean the event has not happened — BORDERLINE
            // so a later LLM pass can decide, never a transaction yet.
            futureHits.isNotEmpty() && economicSignals > 0 ->
                result(FinancialClass.BORDERLINE, 0.5, matched.also { it.addAll(futureHits.map { "future:$it" }) })

            // Marketing / OTP noise wins even if an amount appears.
            nonFinHits.isNotEmpty() && economicSignals == 0 ->
                result(FinancialClass.NON_FINANCIAL, 0.9, matched)

            nonFinHits.isNotEmpty() && economicSignals > 0 && !amountHit ->
                result(FinancialClass.NON_FINANCIAL, 0.7, matched)

            // Economic verb + amount + (rail or bank context): confident financial.
            economicSignals > 0 && amountHit ->
                result(FinancialClass.FINANCIAL, confidenceFor(economicSignals, railHits), matched)

            // Amount present but no economic verb — borderline (could be balance info,
            // could be an OTP quoting an amount).
            amountHit && economicSignals == 0 ->
                result(FinancialClass.BORDERLINE, 0.4, matched)

            // Economic verb but no amount — borderline (e.g. "your card was used"
            // without an amount in the visible part).
            economicSignals > 0 && !amountHit ->
                result(FinancialClass.BORDERLINE, 0.5, matched)

            else -> result(FinancialClass.NON_FINANCIAL, 0.6, matched)
        }
    }

    /** Signals that pushed a message into BORDERLINE, for LLM triage later. */
    fun borderlineReason(result: ClassificationResult): BorderlineReason? =
        when (result.financialClass) {
            FinancialClass.BORDERLINE ->
                if (result.matchedSignals.any { it.startsWith("amount") }) {
                    BorderlineReason.AMOUNT_WITHOUT_VERB
                } else {
                    BorderlineReason.VERB_WITHOUT_AMOUNT
                }
            else -> null
        }

    fun version(): String = VERSION

    private fun confidenceFor(economicSignals: Int, rails: List<String>): Double {
        var c = 0.7 + 0.1 * minOf(economicSignals - 1, 2)
        if (rails.isNotEmpty()) c += 0.05
        return c.coerceAtMost(0.98)
    }

    private fun result(cls: FinancialClass, conf: Double, signals: List<String>) =
        ClassificationResult(cls, conf, signals)
}
