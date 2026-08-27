package com.example.fintrack.parser.rail

import com.example.fintrack.parser.CreditKind
import com.example.fintrack.parser.Direction
import com.example.fintrack.parser.FieldProvenance
import com.example.fintrack.parser.ParseCandidate
import com.example.fintrack.parser.Rail
import com.example.fintrack.parser.classify.DeterministicSmsClassifier
import com.example.fintrack.parser.extract.Extraction
import com.example.fintrack.parser.normalize.TextNormalizer
import java.time.ZoneId

/**
 * Rail adapter contract (module 134). One adapter per rail/bank family.
 * Adapters are deterministic: same normalized text -> same candidate.
 */
interface RailParser {
    /** Which rails this adapter can produce candidates for. */
    fun supports(rail: Rail): Boolean

    /**
     * Extract a [ParseCandidate] from already-normalized text. Returns null
     * when the adapter cannot match deterministically — never fabricates.
     */
    fun parse(normalizedText: String, zone: ZoneId): ParseCandidate?
}

/**
 * UPI adapter. Handles "paid/received via UPI", VPA normalization,
 * UPI reference numbers, P2P vs merchant classification from the VPA handle.
 */
class UpiParsers(private val merchantRegistry: MerchantRegistry? = null) : RailParser {

    override fun supports(rail: Rail): Boolean = rail == Rail.UPI

    override fun parse(text: String, zone: ZoneId): ParseCandidate? {
        val lower = text.lowercase()
        if (!isUpiText(lower)) return null

        val amount = Extraction.amount(text)
        val direction = Extraction.direction(text)
        val ref = Extraction.bankReference(text)
        val at = Extraction.occurredAt(text, zone)
        val cp = Extraction.counterparty(text)

        if (amount == null || direction == null) return null

        // A UPI candidate must carry at least one strong identity signal: a
        // valid VPA, a UPI reference number, or a recognizable counterparty
        // ("to/from NAME"). Without any of these, the message is too thin to
        // safely persist — keep unknown.
        val hasVpa = cp?.value?.second != null
        val hasUpiRef = ref?.value != null && (lower.contains("upi") || lower.contains("vpa"))
        val hasCounterparty = cp?.value?.first != null
        if (!hasVpa && !hasUpiRef && !hasCounterparty) return null

        val vpa = cp?.value?.second
        val prov = mutableMapOf(
            ParseCandidate.P_AMOUNT to amount.provenance,
            ParseCandidate.P_DIRECTION to direction.provenance,
            ParseCandidate.P_RAIL to FieldProvenance("rail.upi", Extraction.FIXTURE_VERSION, 0.95),
        )
        ref?.let { prov[ParseCandidate.P_BANK_REFERENCE] = it.provenance }
        at?.let { prov[ParseCandidate.P_OCCURRED_AT] = it.provenance }
        cp?.let { prov[ParseCandidate.P_COUNTERPARTY] = it.provenance }
        vpa?.let {
            prov[ParseCandidate.P_UPI_VPA] = FieldProvenance(
                "upi.vpa-normalized", Extraction.FIXTURE_VERSION, 0.9,
            )
        }

        val creditKind = if (direction.value == Direction.CREDIT) {
            classifyCredit(lower, vpa, merchantRegistry).also {
                prov[ParseCandidate.P_CREDIT_KIND] = FieldProvenance(
                    "credit.kind", Extraction.FIXTURE_VERSION, 0.7,
                )
            }
        } else null

        return ParseCandidate(
            amountMinor = amount.value,
            currencyCode = "INR",
            direction = direction.value,
            accountToken = Extraction.accountToken(text)?.value,
            cardMask = Extraction.accountToken(text)?.value?.let { TextNormalizer.normalizeCardMask(it) },
            upiVpa = vpa,
            bankReference = ref?.value,
            occurredAtEpochMs = at?.value?.first,
            localDateEpochDay = at?.value?.second,
            rail = Rail.UPI,
            counterpartyRaw = cp?.value?.first,
            counterpartyNormalized = cp?.value?.first?.let { Extraction.normalizeCounterparty(it) },
            creditKind = creditKind,
            classificationConfidence = DeterministicSmsClassifier.classify(text).confidence,
            fieldProvenance = prov,
        )
    }

    private fun isUpiText(lower: String): Boolean =
        "upi" in lower || "vpa" in lower || "@ok" in lower || "@ybl" in lower ||
            "@paytm" in lower || "@ibl" in lower || "@axl" in lower

    private fun classifyCredit(
        lowerText: String,
        vpa: String?,
        registry: MerchantRegistry?,
    ): CreditKind = when {
        "salary" in lowerText -> CreditKind.SALARY
        "interest" in lowerText -> CreditKind.INTEREST_CREDIT
        "cashback" in lowerText -> CreditKind.CASHBACK
        "refund" in lowerText || "reversal" in lowerText -> CreditKind.REFUND
        vpa != null && registry?.isKnownMerchant(vpa) == true -> CreditKind.MERCHANT_CREDIT
        vpa != null -> CreditKind.P2P_RECEIVE
        else -> CreditKind.UNKNOWN
    }
}

/**
 * Bank / card / ATM adapter covering IMPS, NEFT, RTGS, card POS/online, ATM
 * and rail-unknown credit messages (salary, interest, cashback, refund).
 *
 * For messages with a clear rail keyword the rail is recorded; for messages
 * without one (e.g. "Interest credited Rs.X to your savings A/c") the adapter
 * produces a candidate with rail=UNKNOWN and lower confidence, as long as at
 * least one identity hint (account token or bank ref) is present.
 */
class BankRailParser : RailParser {

    override fun supports(rail: Rail): Boolean = rail != Rail.UPI

    override fun parse(text: String, zone: ZoneId): ParseCandidate? {
        val lower = text.lowercase()
        val amount = Extraction.amount(text)
        val direction = Extraction.direction(text)
        if (amount == null || direction == null) return null

        val ref = Extraction.bankReference(text)
        val at = Extraction.occurredAt(text, zone)
        val token = Extraction.accountToken(text)

        val detectedRail = detectRail(lower)
        if (detectedRail == null && ref == null && token == null) return null
        val rail = detectedRail ?: Rail.UNKNOWN

        val prov = mutableMapOf(
            ParseCandidate.P_AMOUNT to amount.provenance,
            ParseCandidate.P_DIRECTION to direction.provenance,
            ParseCandidate.P_RAIL to FieldProvenance(
                "rail.${rail.name.lowercase()}",
                Extraction.FIXTURE_VERSION,
                if (rail == Rail.UNKNOWN) 0.5 else 0.9,
            ),
        )
        ref?.let { prov[ParseCandidate.P_BANK_REFERENCE] = it.provenance }
        at?.let { prov[ParseCandidate.P_OCCURRED_AT] = it.provenance }
        token?.let { prov[ParseCandidate.P_ACCOUNT_TOKEN] = it.provenance }

        val creditKind = if (direction.value == Direction.CREDIT) {
            classifyCredit(lower).also {
                prov[ParseCandidate.P_CREDIT_KIND] = FieldProvenance(
                    "credit.kind", Extraction.FIXTURE_VERSION, 0.75,
                )
            }
        } else null

        // Stage 12 P25 #4 / P11 #6 follow-up: detect an SMS-embedded charge
        // (e.g. "IMPS charge Rs.5") and surface it as a separate fee amount.
        // The persistence + linking path (TransactionLinkEntity role=FEE)
        // creates the distinct FEE event; the main candidate is unchanged.
        val feeAmount = extractEmbeddedFee(lower)

        return ParseCandidate(
            amountMinor = amount.value,
            currencyCode = "INR",
            direction = direction.value,
            accountToken = token?.value,
            cardMask = token?.value?.let { TextNormalizer.normalizeCardMask(it) },
            upiVpa = null,
            bankReference = ref?.value,
            occurredAtEpochMs = at?.value?.first,
            localDateEpochDay = at?.value?.second,
            rail = rail,
            counterpartyRaw = null,
            counterpartyNormalized = null,
            creditKind = creditKind,
            feeAmountMinor = feeAmount?.let { (minor) ->
                prov[ParseCandidate.P_FEE] = FieldProvenance(
                    "fee.embedded-charge", Extraction.FIXTURE_VERSION, 0.85,
                )
                minor
            },
            classificationConfidence = if (rail == Rail.UNKNOWN) 0.7 else 0.85,
            fieldProvenance = prov,
        )
    }

    /**
     * Detect an SMS-embedded bank charge/fee, e.g. "IMPS charge Rs.5.00" or
     * "Rs.5.00 charged for IMPS". Returns (amountMinor) or null when the
     * message carries no fee phrase. Never guesses: only exact charge words
     * next to an amount match.
     */
    private fun extractEmbeddedFee(lower: String): Pair<Long, String>? {
        val feeWords = listOf("charge", "charges", "fee", "fees")
        val chargeIndex = feeWords.firstOrNull { lower.contains(it) } ?: return null
        // Match "charge Rs.X", "Rs.X charge", "charges of Rs.X".
        val patterns = listOf(
            Regex("""\b(?:$chargeIndex)\s+(?:of\s+)?(?:rs\.?|inr)\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)""", RegexOption.IGNORE_CASE),
            Regex("""\b(?:rs\.?|inr)\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)\s+(?:$chargeIndex)""", RegexOption.IGNORE_CASE),
            Regex("""\b(?:$chargeIndex)s?\s+of\s+(?:rs\.?|inr)\s*([0-9][0-9,]*(?:\.[0-9]{1,2})?)""", RegexOption.IGNORE_CASE),
        )
        for (p in patterns) {
            val m = p.find(lower) ?: continue
            val minor = TextNormalizer.parseAmountToken(m.groupValues[1]) ?: continue
            return minor to m.value
        }
        return null
    }

    private fun detectRail(lower: String): Rail? = when {
        "imps" in lower -> Rail.IMPS
        "neft" in lower -> Rail.NEFT
        "rtgs" in lower -> Rail.RTGS
        "atm" in lower -> Rail.ATM
        "pos" in lower -> Rail.CARD_POS
        "card" in lower && "online" in lower -> Rail.CARD_ONLINE
        "card" in lower -> Rail.CARD_POS
        else -> null
    }

    private fun classifyCredit(lower: String): CreditKind = when {
        "salary" in lower -> CreditKind.SALARY
        "interest" in lower -> CreditKind.INTEREST_CREDIT
        "cashback" in lower -> CreditKind.CASHBACK
        "refund" in lower || "reversal" in lower -> CreditKind.REFUND
        else -> CreditKind.TRANSFER_IN
    }
}

/**
 * Registry of merchants learned from user-confirmed UPI VPAs (module 140).
 * Only confirmed mappings are consulted for automatic classification;
 * everything else stays unknown.
 */
class MerchantRegistry(private val confirmedVpaToMerchant: Map<String, String> = emptyMap()) {

    fun isKnownMerchant(normalizedVpa: String): Boolean =
        confirmedVpaToMerchant.containsKey(normalizedVpa)

    fun merchantFor(normalizedVpa: String): String? = confirmedVpaToMerchant[normalizedVpa]

    companion object {
        fun empty() = MerchantRegistry()
    }
}
