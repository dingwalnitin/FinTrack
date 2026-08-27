package com.example.fintrack.parser

/**
 * Common parse-candidate schema (module 135).
 *
 * Every field is nullable = "unknown stays unknown". No field is ever guessed:
 * a parser adapter writes null when its rule does not match deterministically.
 *
 * Each extracted field carries [FieldProvenance] identifying the deterministic
 * rule, fixture/version and confidence so any downstream value can be traced
 * back to evidence.
 */

/** How a single extracted field was derived. */
data class FieldProvenance(
    /** Deterministic rule id, e.g. "upi.amount.rs-prefix". */
    val ruleId: String,
    /** Parser fixture corpus version, e.g. "fixtures-v1". */
    val fixtureVersion: String,
    /** Confidence in [0,1]; 1.0 for exact structural matches. */
    val confidence: Double,
) {
    init {
        require(confidence in 0.0..1.0)
        require(ruleId.isNotBlank())
    }
}

/** Economic direction of the candidate event. */
enum class Direction { DEBIT, CREDIT }

/** Payment rail detected from structure of the message. */
enum class Rail {
    UPI, IMPS, NEFT, RTGS, CARD_POS, CARD_ONLINE, ATM, ACH, UNKNOWN
}

/**
 * Explicit economic-kind classification for credit-side messages where
 * meaning matters but must not be fabricated.
 */
enum class CreditKind {
    SALARY, INTEREST_CREDIT, CASHBACK, REFUND, P2P_RECEIVE, TRANSFER_IN, MERCHANT_CREDIT, UNKNOWN
}

/** The common candidate schema all rail adapters emit. */
data class ParseCandidate(
    val amountMinor: Long?,
    val currencyCode: String?,          // null = unknown; never defaulted silently
    val direction: Direction?,
    val accountToken: String?,          // masked account suffix / card last4 hint
    val cardMask: String?,              // normalized 4-digit mask or null
    val upiVpa: String?,                // normalized VPA or null
    val bankReference: String?,         // UTR / RRN / ref number
    val occurredAtEpochMs: Long?,       // parsed date/time; null when absent
    val localDateEpochDay: Long?,       // derived only when occurredAt present
    val rail: Rail,
    val counterpartyRaw: String?,       // merchant/payee name as it appears
    val counterpartyNormalized: String?,
    val creditKind: CreditKind?,        // only for CREDIT; null otherwise
    /**
     * Stage 12 P25 #4 (P11 #6 follow-up): an SMS-embedded charge/fee on top
     * of the main transaction (e.g. "IMPS charge Rs.5"). Null when absent.
     * The persistence + linking path (TransactionLinkEntity role=FEE) is
     * used by the caller to create the separate FEE event.
     */
    val feeAmountMinor: Long? = null,
    val classificationConfidence: Double,
    val fieldProvenance: Map<String, FieldProvenance>,
) {
    companion object {
        /** Provenance map keys — stable contract for tests and audit. */
        const val P_AMOUNT = "amount"
        const val P_DIRECTION = "direction"
        const val P_ACCOUNT_TOKEN = "accountToken"
        const val P_CARD_MASK = "cardMask"
        const val P_UPI_VPA = "upiVpa"
        const val P_BANK_REFERENCE = "bankReference"
        const val P_OCCURRED_AT = "occurredAt"
        const val P_COUNTERPARTY = "counterparty"
        const val P_RAIL = "rail"
        const val P_CREDIT_KIND = "creditKind"
        const val P_FEE = "feeAmount"
    }
}

/**
 * Classification result: is this message financial at all? Kept strictly
 * separate from extraction (module 132): a classifier says FINANCIAL /
 * NON_FINANCIAL / BORDERLINE; extraction runs only on FINANCIAL (+ optionally
 * BORDERLINE via LLM later).
 */
enum class FinancialClass { FINANCIAL, NON_FINANCIAL, BORDERLINE }

/** Why a message landed in BORDERLINE (drives optional later LLM triage). */
enum class BorderlineReason { AMOUNT_WITHOUT_VERB, VERB_WITHOUT_AMOUNT }

data class ClassificationResult(
    val financialClass: FinancialClass,
    val confidence: Double,
    /** Signals that fired, for measurable precision/recall fixtures. */
    val matchedSignals: List<String>,
)
