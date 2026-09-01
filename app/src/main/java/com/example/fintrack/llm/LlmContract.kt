package com.example.fintrack.llm

/**
 * P07: provider-neutral, strictly-typed LLM enrichment contract.
 *
 * Invariants:
 *  - Model output is ADVISORY. Nothing here writes to Room; persistence goes
 *    through the application layer after full validation.
 *  - Every field is nullable = unknown stays unknown. The model may not invent
 *    identifiers; anything it emits must be traceable to supplied evidence.
 *  - Prompt/schema IDs are versioned so results remain interpretable when the
 *    prompt evolves (P08 migration path).
 */

const val PROMPT_VERSION = "enrich-prompt-v3"
const val SCHEMA_VERSION = "enrich-schema-v1"

/** Minimal normalized evidence handed to the model. Never raw truth. */
data class ParseRequest(
    /** Stable id of the raw evidence row (raw_sms.id / messages.id). */
    val sourceMessageId: String,
    /** Hashed sender id (sha-256) — raw sender never leaves the device. */
    val senderHash: String,
    /** Normalized message body text. */
    val bodyText: String,
    /** Epoch ms the evidence was received (from evidence metadata, not model). */
    val receivedAtEpochMs: Long,
    /**
     * Nearby same-sender messages within a time window, for duplicate /
     * missing-detail resolution. Also evidence, labeled as such in the prompt.
     */
    val nearbyEvidence: List<NearbyEvidence> = emptyList(),
    val promptVersion: String = PROMPT_VERSION,
    val schemaVersion: String = SCHEMA_VERSION,
) {
    data class NearbyEvidence(
        val sourceMessageId: String,
        val bodyText: String,
        val receivedAtEpochMs: Long,
    )
}

/** Per-field confidence + human-readable evidence explanation. */
data class FieldConfidence(
    val value: Double,
    /** What in the evidence supports this field, e.g. "amount token Rs.250.00". */
    val explanation: String,
) {
    init {
        require(value in 0.0..1.0) { "confidence must be in [0,1]" }
    }
}

/** All advisory fields with per-field confidence. Null = unknown/absent. */
data class Interpretation(
    val amountMinor: Long?,
    val currencyCode: String?,
    val direction: Direction?,
    val accountToken: String?,
    val rail: Rail?,
    val counterpartyRaw: String?,
    val counterpartyNormalized: String?,
    val categorySuggestion: String?,
    val transferTargetToken: String?,
    val recurring: Boolean?,
    val emiDetail: String?,
    val occurredAtEpochMs: Long?,
    val confidenceAmount: FieldConfidence?,
    val confidenceDirection: FieldConfidence?,
    val confidenceAccount: FieldConfidence?,
    val confidenceRail: FieldConfidence?,
    val confidenceCounterparty: FieldConfidence?,
    val confidenceCategory: FieldConfidence?,
    val confidenceTransferTarget: FieldConfidence?,
    val confidenceRecurring: FieldConfidence?,
    val confidenceEmi: FieldConfidence?,
    /** Stage 13 (C): account type hint from LLM (SAVINGS, CURRENT, CREDIT_CARD, etc.). Only accepted when the SMS explicitly references a card/savings/current account. */
    val accountType: AccountType? = null,
) {
    enum class Direction { DEBIT, CREDIT }

    enum class Rail { UPI, IMPS, NEFT, RTGS, CARD_POS, CARD_ONLINE, ATM, ACH, UNKNOWN }

    /** Stage 13 (C): account types the model may suggest. */
    enum class AccountType { SAVINGS, CURRENT, CREDIT_CARD, LOAN, OVERDRAFT, PREPAID, UNKNOWN }
}

/** Successful, fully-validated model output plus usage metadata. */
data class ParseResponse(
    val interpretation: Interpretation,
    val overallConfidence: Double?,
    val promptVersion: String,
    val schemaVersion: String,
    val providerId: String,
    val modelId: String,
    val latencyMs: Long,
    val tokensPrompt: Int,
    val tokensCompletion: Int,
)

/** Normalized error classes — providers map their native errors onto these. */
enum class LlmErrorClass {
    /** Transient provider throttling — retry with backoff. */
    RATE_LIMITED,
    /** Provider 5xx / outage — retry with backoff. */
    PROVIDER_UNAVAILABLE,
    /** Network timeout — retry with backoff. */
    TIMEOUT,
    /** Malformed JSON from the model — bounded retry then terminal. */
    BAD_JSON,
    /** JSON valid but failed schema/enum/sanity validation — never retried. */
    SCHEMA_VALIDATION_FAILED,
    /** Missing critical values or impossible dates/amounts — never retried. */
    INVALID_CONTENT,
    /** Hallucinated identifier / unsupported field — never retried. */
    HALLUCINATION_REJECTED,
    /** Local precondition failed (e.g., budget exhausted) — no point retrying now. */
    LOCAL_BUDGET_EXCEEDED;

    val isRetryable: Boolean
        get() = this == RATE_LIMITED || this == PROVIDER_UNAVAILABLE ||
            this == TIMEOUT || this == BAD_JSON

    companion object {
        fun fromCode(code: String?): LlmErrorClass? =
            entries.firstOrNull { it.name == code }
    }
}

/** Normalized provider failure carrying a classified error. */
class LlmProviderException(
    val errorClass: LlmErrorClass,
    message: String,
    /** Retry-after hint from provider headers where available, epoch ms delta. */
    val retryAfterMs: Long? = null,
    cause: Throwable? = null,
) : Exception(message, cause)
