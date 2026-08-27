package com.example.fintrack.parser

import com.example.fintrack.parser.classify.DeterministicSmsClassifier
import com.example.fintrack.parser.normalize.TextNormalizer
import com.example.fintrack.parser.rail.BankRailParser
import com.example.fintrack.parser.rail.MerchantRegistry
import com.example.fintrack.parser.rail.RailParser
import com.example.fintrack.parser.rail.UpiParsers
import java.time.ZoneId

/**
 * Parser facade — the single authoring framework (module 134).
 *
 * Pipeline: classify -> normalize -> rail-adapter extraction.
 * Classification is separate from extraction; adapters are deterministic and
 * return null rather than fabricate. The legacy [EvidenceParser] stub is
 * absorbed here: [parseLegacy] adapts the old domain-model signature to the
 * new framework so earlier stages keep compiling.
 *
 * Optional LLM path: only BORDERLINE messages may be escalated later; this
 * facade never calls network code itself.
 */
class FinTrackParser(
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val merchantRegistry: MerchantRegistry = MerchantRegistry.empty(),
    private val adapters: List<RailParser> =
        listOf(UpiParsers(merchantRegistry), BankRailParser()),
) {

    /**
     * Full pipeline over raw SMS text. Returns null for NON_FINANCIAL messages
     * and for FINANCIAL messages where no adapter matched deterministically.
     */
    fun parse(rawBody: String): ParseCandidate? {
        val classification = DeterministicSmsClassifier.classify(rawBody)
        if (classification.financialClass != FinancialClass.FINANCIAL) return null
        val normalized = TextNormalizer.normalize(rawBody)
        val rail = detectRail(normalized.lowercase())
        // Try the matching adapter first, then all others (some banks mix rails
        // in one message, e.g. "spent on card via UPI").
        val ordered = adapters.sortedByDescending { it.supports(rail) }
        for (adapter in ordered) {
            adapter.parse(normalized, zone)?.let { return it }
        }
        return null
    }

    /** Classification-only entry point for measurable P/R fixtures. */
    fun classify(rawBody: String) = DeterministicSmsClassifier.classify(rawBody)

    /** Normalized-text entry point for tests that pre-normalize. */
    fun parseNormalized(normalizedText: String): ParseCandidate? {
        val rail = detectRail(normalizedText.lowercase())
        val ordered = adapters.sortedByDescending { it.supports(rail) }
        for (adapter in ordered) {
            adapter.parse(normalizedText, zone)?.let { return it }
        }
        return null
    }

    private fun detectRail(lower: String): Rail = when {
        "upi" in lower || "vpa" in lower || "@ok" in lower || "@ybl" in lower ||
            "@paytm" in lower || "@ibl" in lower || "@axl" in lower -> Rail.UPI
        "imps" in lower -> Rail.IMPS
        "neft" in lower -> Rail.NEFT
        "rtgs" in lower -> Rail.RTGS
        "atm" in lower -> Rail.ATM
        "card" in lower -> if ("online" in lower || "ecom" in lower) Rail.CARD_ONLINE else Rail.CARD_POS
        else -> Rail.UNKNOWN
    }

    // ---- legacy stub absorption ----

    /**
     * Adapts the old EvidenceParser contract. Kept so prior increments keep
     * working; new code should use [parse].
     */
    @Deprecated("Use parse(rawBody); kept for legacy EvidenceParser callers")
    fun parseLegacy(evidence: com.example.fintrack.domain.model.Message): ParsedCandidate {
        val candidate = parse(evidence.body)
        return ParsedCandidate(
            amountMajor = candidate?.amountMinor?.let {
                com.example.fintrack.domain.policy.MoneyPolicy.toMajor(it, candidate.currencyCode ?: "INR")
                    .toDouble()
            },
            currencyCode = candidate?.currencyCode,
            counterparty = candidate?.counterpartyRaw,
        )
    }
}
