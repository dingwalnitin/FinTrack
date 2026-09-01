package com.example.fintrack.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * v5 LLM enrichment blueprint (Stage 4, P07/P08).
 *
 * Design invariants:
 *  - Raw evidence (raw_sms / messages) is never mutated by the LLM path.
 *  - Model output is advisory: it lands only in llm_interpretations with
 *    per-field confidence; nothing here is a second source of truth for
 *    transactions/balances. Promotion to a transaction goes through the
 *    existing FinanceRepositoryV2.postTransaction with provenance checks.
 *  - Jobs are durable: identity = sourceId + parser/prompt version, so process
 *    death cannot duplicate work (unique index on jobIdentity).
 *  - Response cache keyed by stable semantic input hash + prompt/schema/
 *    provider/model versions. Only validated responses are cached.
 */

/** Durable LLM job state machine. */
object LlmJobStates {
    const val PENDING = "PENDING"
    const val CLAIMED = "CLAIMED"
    const val RUNNING = "RUNNING"
    const val SUCCEEDED = "SUCCEEDED"
    const val RETRYABLE_FAILED = "RETRYABLE_FAILED"
    const val TERMINAL_FAILED = "TERMINAL_FAILED"
}

@Entity(
    tableName = "llm_jobs",
    indices = [
        Index(value = ["jobIdentity"], unique = true), // idempotency across restarts
        Index("status", "nextRetryAtEpochMs"),         // claim query
        Index("priority"),                             // fairness ordering
    ],
)
data class LlmJobEntity(
    @PrimaryKey val id: String,
    /** sha-256(sourceId | promptVersion | schemaVersion | providerId). Stable across processes. */
    val jobIdentity: String,
    val sourceMessageId: String,               // raw evidence link (never the body)
    val senderHash: String?,                   // hashed sender for context grouping — no raw PII
    val priority: Int,                         // lower = sooner (fairness across batches)
    val status: String,                        // LlmJobStates
    val attempts: Int,
    val maxAttempts: Int,
    val nextRetryAtEpochMs: Long,
    val claimedAtEpochMs: Long?,               // lease start; expired lease => reclaimable
    val claimedByWorker: String?,
    val promptVersion: String,
    val schemaVersion: String,
    val providerId: String,
    val lastErrorClass: String?,               // normalized error class only — no payloads
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

@Entity(
    tableName = "llm_interpretations",
    indices = [
        Index(value = ["responseHash"], unique = true), // idempotent store
        Index("sourceMessageId"),
        Index("promptVersion"),
    ],
)
data class LlmInterpretationEntity(
    @PrimaryKey val id: String,
    val sourceMessageId: String,
    val responseHash: String,                  // semantic input hash of validated response
    val promptVersion: String,
    val schemaVersion: String,
    val providerId: String,
    val modelId: String,
    // Advisory typed fields — null means unknown/absent, never guessed.
    val amountMinor: Long?,
    val currencyCode: String?,
    val direction: String?,                    // DEBIT | CREDIT
    val accountToken: String?,                 // masked suffix hint
    val rail: String?,
    val counterpartyRaw: String?,
    val counterpartyNormalized: String?,
    val categorySuggestion: String?,
    val transferTargetToken: String?,
    val recurring: Boolean?,
    val emiDetail: String?,
    val occurredAtEpochMs: Long?,
    // Per-field confidence in [0,1]; separate from user-confirmed facts.
    val confidenceAmount: Double?,
    val confidenceDirection: Double?,
    val confidenceAccount: Double?,
    val confidenceRail: Double?,
    val confidenceCounterparty: Double?,
    val confidenceCategory: Double?,
    val confidenceTransferTarget: Double?,
    val confidenceRecurring: Double?,
    val confidenceEmi: Double?,
    /** Human-readable evidence explanations (what in the message supports each field). */
    val evidenceExplanationsJson: String,
    val overallConfidence: Double?,
    val latencyMs: Long,
    val tokensPrompt: Int,
    val tokensCompletion: Int,
    val fromCache: Boolean,
    val createdAtEpochMs: Long,
    /** Stage 13 (D): raw LLM output JSON for audit/debug. Null for rows predating v12. Never overwritten by cache-hit. */
    val rawLlmJson: String? = null,
)

@Entity(
    tableName = "llm_response_cache",
    indices = [Index(value = ["cacheKey"], unique = true)],
)
data class LlmResponseCacheEntity(
    @PrimaryKey val id: String,
    /** sha-256(normalized input context | promptVersion | schemaVersion | providerId | modelId). */
    val cacheKey: String,
    val validatedResponseJson: String,         // only validated responses ever land here
    val promptVersion: String,
    val schemaVersion: String,
    val providerId: String,
    val modelId: String,
    val createdAtEpochMs: Long,
)

@Entity(
    tableName = "llm_usage_counters",
    indices = [Index(value = ["bucketDayUtc"], unique = true)],
)
data class LlmUsageCounterEntity(
    @PrimaryKey val id: String,
    val bucketDayUtc: Long,                    // epoch day UTC for daily budgeting
    val requests: Long,
    val cacheHits: Long,
    val tokensPrompt: Long,
    val tokensCompletion: Long,
    val validationFailures: Long,
    val retries: Long,
    val updatedAtEpochMs: Long,
)

/** Aggregate observability snapshot row (single row per metric name). */
@Entity(
    tableName = "llm_metrics",
    indices = [Index(value = ["metricName"], unique = true)],
)
data class LlmMetricEntity(
    @PrimaryKey val id: String,
    val metricName: String,                    // queue_depth, success_count, failure_count, ...
    val value: Long,
    val updatedAtEpochMs: Long,
)
