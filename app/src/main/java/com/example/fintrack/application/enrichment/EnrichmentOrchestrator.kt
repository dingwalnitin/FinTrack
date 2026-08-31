package com.example.fintrack.application.enrichment

import com.example.fintrack.llm.FieldConfidence
import com.example.fintrack.llm.Interpretation
import com.example.fintrack.llm.LlmErrorClass
import com.example.fintrack.llm.LlmProvider
import com.example.fintrack.llm.LlmProviderException
import com.example.fintrack.llm.LlmResponseDecoder
import com.example.fintrack.llm.ParseRequest
import com.example.fintrack.llm.PROMPT_VERSION
import com.example.fintrack.llm.SCHEMA_VERSION
import com.example.fintrack.llm.PromptBuilder
import com.example.fintrack.llm.RetryPolicy

import com.example.fintrack.data.db.LlmInterpretationEntity
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/**
 * P08: bounded four-worker LLM enrichment pipeline.
 *
 * Guarantees:
 *  - at most [MAX_WORKERS] = 4 concurrent provider calls (semaphore + worker pool)
 *  - jobs are claimed transactionally; process death cannot duplicate work
 *    (unique jobIdentity + CAS status transitions + lease expiry reclaim)
 *  - only validated responses are persisted or cached
 *  - provider outages / 429s back off and never block local finance operation
 *    (this orchestrator runs purely in the background)
 *  - sensitive prompt contents never enter logs — only ids, classes, counts
 */
class EnrichmentOrchestrator(
    private val jobStore: LlmJobStore,
    private val provider: LlmProvider,
    private val evidenceLoader: suspend (sourceMessageId: String) -> ParseRequest?,
    /** Daily token budget across prompt+completion tokens. */
    private val dailyTokenBudget: Long = DEFAULT_DAILY_TOKEN_BUDGET,
    private val callTimeoutMs: Long = 30_000,
    private val leaseMs: Long = 5 * 60_000L,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

    companion object {
        const val MAX_WORKERS = 4
        const val DEFAULT_DAILY_TOKEN_BUDGET = 200_000L

        /** Simple observability counters (in-memory; durable usage lives in Room). */
        data class Metrics(
            val queued: Long,
            val succeeded: Long,
            val failed: Long,
            val retries: Long,
            val cacheHits: Long,
            val validationFailures: Long,
            val activeWorkers: Int,
        )
    }

    private val semaphore = Semaphore(MAX_WORKERS)
    private val workChannel = Channel<Unit>(Channel.UNLIMITED)
    private val metricsMutex = Mutex()
    private var succeededCount = 0L
    private var failedCount = 0L
    private var retryCount = 0L
    private var cacheHitCount = 0L
    private var validationFailureCount = 0L
    private var activeWorkers = 0

    @Volatile private var running = false

    /** Start the fixed four-worker pool. Idempotent. */
    fun start() {
        if (running) return
        running = true
        repeat(MAX_WORKERS) { workerIndex ->
            scope.launch { workerLoop("worker-$workerIndex") }
        }
    }

    fun stop() {
        running = false
        scope.cancel()
    }

    /** Wake workers (e.g., after enqueue). Non-blocking. */
    fun poke() {
        if (!running) return
        workChannel.trySend(Unit)
    }

    private suspend fun workerLoop(workerId: String) {
        while (running) {
            workChannel.receive() // wait for a wake signal
            while (true) {
                val now = System.currentTimeMillis()
                jobStore.promoteRetryableToPending(now)
                val job = jobStore.claim(workerId, now, leaseMs) ?: break
                semaphore.acquire()
                try {
                    metricsMutex.withLock { activeWorkers++ }
                    processJob(job, workerId)
                } finally {
                    metricsMutex.withLock { activeWorkers-- }
                    semaphore.release()
                }
            }
        }
    }

    /**
     * One job: load evidence -> cache check -> provider call -> validate ->
     * persist interpretation -> complete/fail job. Never throws.
     */
    internal suspend fun processJob(job: com.example.fintrack.data.db.LlmJobEntity, workerId: String) {
        val now = System.currentTimeMillis()
        val request = try {
            evidenceLoader(job.sourceMessageId)
        } catch (t: Throwable) {
            null
        }
        if (request == null) {
            jobStore.markFailed(job, LlmErrorClass.INVALID_CONTENT, null, now)
            bumpMetrics(failed = 1)
            return
        }

        // ---- budget guard ----
        if (!budgetAllows()) {
            jobStore.markFailed(job, LlmErrorClass.LOCAL_BUDGET_EXCEEDED, null, now)
            bumpMetrics(failed = 1)
            return
        }

        // ---- cache ----
        val cacheKey = PromptBuilder.cacheKey(request, provider.providerId, provider.modelId)
        val cached = jobStore.cachedResponse(cacheKey)
        if (cached != null) {
            persistFromCache(job, cached.validatedResponseJson, cacheKey, now)
            jobStore.bumpUsage(dayUtc(now), now, cacheHits = 1)
            bumpMetrics(cacheHits = 1, succeeded = 1)
            return
        }

        // ---- provider call with timeout ----
        val prompt = PromptBuilder.build(request)
        val startedAt = System.currentTimeMillis()
        val raw = try {
            withTimeout(callTimeoutMs) { provider.complete(prompt) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            fail(job, LlmErrorClass.TIMEOUT, null, startedAt)
            return
        } catch (e: LlmProviderException) {
            fail(job, e.errorClass, e.retryAfterMs, startedAt)
            return
        } catch (t: Throwable) {
            fail(job, LlmErrorClass.PROVIDER_UNAVAILABLE, null, startedAt)
            return
        }
        val latencyMs = System.currentTimeMillis() - startedAt

        // ---- validate BEFORE any persistence ----
        val bounds = boundsFrom(request)
        when (val result = LlmResponseDecoder.decode(raw, bounds)) {
            is LlmResponseDecoder.ValidationResult.Invalid -> {
                jobStore.bumpUsage(dayUtc(now), now, validationFailures = 1)
                bumpMetrics(validationFailures = 1)
                fail(job, result.errorClass, null, startedAt)
            }
            is LlmResponseDecoder.ValidationResult.Valid -> {
                // Response identity: semantic input hash + message id so identical
                // inputs from different messages never collide on the unique index.
                val responseHash = request.sourceMessageId.sha256(cacheKey)
                val entity = LlmInterpretationEntity(
                    id = UUID.randomUUID().toString(),
                    sourceMessageId = request.sourceMessageId,
                    responseHash = responseHash,
                    promptVersion = request.promptVersion,
                    schemaVersion = request.schemaVersion,
                    providerId = provider.providerId,
                    modelId = provider.modelId,
                    amountMinor = result.response.interpretation.amountMinor,
                    currencyCode = result.response.interpretation.currencyCode,
                    direction = result.response.interpretation.direction?.name,
                    accountToken = result.response.interpretation.accountToken,
                    rail = result.response.interpretation.rail?.name,
                    counterpartyRaw = result.response.interpretation.counterpartyRaw,
                    counterpartyNormalized = result.response.interpretation.counterpartyNormalized,
                    categorySuggestion = result.response.interpretation.categorySuggestion,
                    transferTargetToken = result.response.interpretation.transferTargetToken,
                    recurring = result.response.interpretation.recurring,
                    emiDetail = result.response.interpretation.emiDetail,
                    occurredAtEpochMs = result.response.interpretation.occurredAtEpochMs,
                    confidenceAmount = result.response.interpretation.confidenceAmount?.value,
                    confidenceDirection = result.response.interpretation.confidenceDirection?.value,
                    confidenceAccount = result.response.interpretation.confidenceAccount?.value,
                    confidenceRail = result.response.interpretation.confidenceRail?.value,
                    confidenceCounterparty = result.response.interpretation.confidenceCounterparty?.value,
                    confidenceCategory = result.response.interpretation.confidenceCategory?.value,
                    confidenceTransferTarget = result.response.interpretation.confidenceTransferTarget?.value,
                    confidenceRecurring = result.response.interpretation.confidenceRecurring?.value,
                    confidenceEmi = result.response.interpretation.confidenceEmi?.value,
                    evidenceExplanationsJson = explanationsJson(result.response.interpretation),
                    overallConfidence = result.response.overallConfidence,
                    latencyMs = latencyMs,
                    tokensPrompt = estimateTokens(prompt),
                    tokensCompletion = estimateTokens(raw),
                    fromCache = false,
                    createdAtEpochMs = System.currentTimeMillis(),
                )
                jobStore.storeInterpretation(entity)
                jobStore.putCacheEntry(
                    com.example.fintrack.data.db.LlmResponseCacheEntity(
                        id = UUID.randomUUID().toString(),
                        cacheKey = cacheKey,
                        validatedResponseJson = raw,
                        promptVersion = request.promptVersion,
                        schemaVersion = request.schemaVersion,
                        providerId = provider.providerId,
                        modelId = provider.modelId,
                        createdAtEpochMs = System.currentTimeMillis(),
                    )
                )
                jobStore.markSucceeded(job, System.currentTimeMillis())
                jobStore.bumpUsage(
                    dayUtc(now), System.currentTimeMillis(), requests = 1,
                    tokensPrompt = entity.tokensPrompt.toLong(),
                    tokensCompletion = entity.tokensCompletion.toLong(),
                )
                bumpMetrics(succeeded = 1)
            }
            is LlmResponseDecoder.ValidationResult.NonFinancial -> {
                // Not a transaction (OTP, promo, etc.) — settle the job without
                // persisting an interpretation or caching the raw response.
                jobStore.markSucceeded(job, System.currentTimeMillis())
                bumpMetrics(succeeded = 1)
            }
        }
    }

    private suspend fun fail(job: com.example.fintrack.data.db.LlmJobEntity,
                             errorClass: LlmErrorClass, retryAfterMs: Long?, startedAt: Long) {
        val newState = jobStore.markFailed(job, errorClass, retryAfterMs, System.currentTimeMillis())
        if (newState == com.example.fintrack.data.db.LlmJobStates.RETRYABLE_FAILED) {
            jobStore.bumpUsage(dayUtc(System.currentTimeMillis()), System.currentTimeMillis(), retries = 1)
            bumpMetrics(retries = 1)
        } else {
            bumpMetrics(failed = 1)
        }
    }

    private suspend fun persistFromCache(job: com.example.fintrack.data.db.LlmJobEntity,
                                         validatedJson: String, cacheKey: String, now: Long) {
        val request = evidenceLoader(job.sourceMessageId) ?: return
        val parsed = LlmResponseDecoder.decode(validatedJson, boundsFrom(request))
        if (parsed is LlmResponseDecoder.ValidationResult.Valid) {
            val responseHash = request.sourceMessageId.sha256(cacheKey)
            jobStore.storeInterpretation(
                LlmInterpretationEntity(
                    id = UUID.randomUUID().toString(),
                    sourceMessageId = request.sourceMessageId,
                    responseHash = responseHash,
                    promptVersion = job.promptVersion,
                    schemaVersion = job.schemaVersion,
                    providerId = provider.providerId,
                    modelId = provider.modelId,
                    amountMinor = parsed.response.interpretation.amountMinor,
                    currencyCode = parsed.response.interpretation.currencyCode,
                    direction = parsed.response.interpretation.direction?.name,
                    accountToken = parsed.response.interpretation.accountToken,
                    rail = parsed.response.interpretation.rail?.name,
                    counterpartyRaw = parsed.response.interpretation.counterpartyRaw,
                    counterpartyNormalized = parsed.response.interpretation.counterpartyNormalized,
                    categorySuggestion = parsed.response.interpretation.categorySuggestion,
                    transferTargetToken = parsed.response.interpretation.transferTargetToken,
                    recurring = parsed.response.interpretation.recurring,
                    emiDetail = parsed.response.interpretation.emiDetail,
                    occurredAtEpochMs = parsed.response.interpretation.occurredAtEpochMs,
                    confidenceAmount = parsed.response.interpretation.confidenceAmount?.value,
                    confidenceDirection = parsed.response.interpretation.confidenceDirection?.value,
                    confidenceAccount = parsed.response.interpretation.confidenceAccount?.value,
                    confidenceRail = parsed.response.interpretation.confidenceRail?.value,
                    confidenceCounterparty = parsed.response.interpretation.confidenceCounterparty?.value,
                    confidenceCategory = parsed.response.interpretation.confidenceCategory?.value,
                    confidenceTransferTarget = parsed.response.interpretation.confidenceTransferTarget?.value,
                    confidenceRecurring = parsed.response.interpretation.confidenceRecurring?.value,
                    confidenceEmi = parsed.response.interpretation.confidenceEmi?.value,
                    evidenceExplanationsJson = explanationsJson(parsed.response.interpretation),
                    overallConfidence = parsed.response.overallConfidence,
                    latencyMs = 0,
                    tokensPrompt = 0,
                    tokensCompletion = 0,
                    fromCache = true,
                    createdAtEpochMs = now,
                )
            )
        }
        jobStore.markSucceeded(job, now)
    }

    private suspend fun budgetAllows(): Boolean {
        val usage = jobStore.usageForDay(dayUtc(System.currentTimeMillis())) ?: return true
        return (usage.tokensPrompt + usage.tokensCompletion) < dailyTokenBudget
    }

    private fun dayUtc(epochMs: Long): Long = Instant.ofEpochMilli(epochMs).atZone(java.time.ZoneOffset.UTC).toLocalDate().toEpochDay()

    private fun boundsFrom(request: ParseRequest): LlmResponseDecoder.EvidenceBounds {
        val text = request.bodyText
        val amounts = Regex("(?:rs\\.?|inr|₹)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)", RegexOption.IGNORE_CASE)
            .findAll(text)
            .map { it.groupValues[1].replace(",", "") }
            .mapNotNull { s -> s.toLongOrNull()?.let { whole ->
                // "250" in an SMS means rupees -> minor units; "250.50" handled below
                if (s.contains('.')) Math.round(s.toDouble() * 100) else whole * 100
            } }
            .toSet()
        val accountTokens = Regex("(?:XX|xx|x)(\\d{2,6})").findAll(text)
            .map { "XX${it.groupValues[1]}" }.toSet() +
            Regex("(?:A/c|a/c|card)\\s*(?:XX)?(\\d{4})").findAll(text).map { "XX${it.groupValues[1]}" }.toSet()
        val rails = setOf("UPI", "IMPS", "NEFT", "RTGS", "ATM")
            .filter { it.lowercase() in text.lowercase() }.toSet() +
            if ("card" in text.lowercase()) setOf("CARD_POS", "CARD_ONLINE") else emptySet()
        val counterparties = Regex("(?:to|from)\\s+([A-Z][A-Za-z&. ]{2,40})").findAll(text)
            .map { it.groupValues[1].trim() }.toSet()
        return LlmResponseDecoder.EvidenceBounds(
            knownAmountsMinor = amounts,
            knownAccountTokens = accountTokens,
            knownRails = rails,
            knownCounterparties = counterparties,
            receivedAtEpochMs = request.receivedAtEpochMs,
        )
    }

    private fun explanationsJson(i: Interpretation): String {
        val o = org.json.JSONObject()
        fun put(key: String, c: FieldConfidence?) {
            if (c != null) {
                o.put(key, org.json.JSONObject().put("value", c.value).put("explanation", c.explanation))
            }
        }
        put("amount", i.confidenceAmount); put("direction", i.confidenceDirection)
        put("account", i.confidenceAccount); put("rail", i.confidenceRail)
        put("counterparty", i.confidenceCounterparty); put("category", i.confidenceCategory)
        put("transferTarget", i.confidenceTransferTarget); put("recurring", i.confidenceRecurring)
        put("emi", i.confidenceEmi)
        return o.toString()
    }

    private fun estimateTokens(text: String): Int = (text.length / 4).coerceAtLeast(1)

    private fun String.sha256(salt: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest((this + "\u0000" + salt).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private suspend fun bumpMetrics(succeeded: Long = 0, failed: Long = 0,
                                    retries: Long = 0, cacheHits: Long = 0,
                                    validationFailures: Long = 0) {
        metricsMutex.withLock {
            succeededCount += succeeded; failedCount += failed
            retryCount += retries; cacheHitCount += cacheHits
            validationFailureCount += validationFailures
        }
    }

    suspend fun snapshotMetrics(): Metrics = metricsMutex.withLock {
        Metrics(
            queued = jobStore.queueDepth(),
            succeeded = succeededCount, failed = failedCount, retries = retryCount,
            cacheHits = cacheHitCount, validationFailures = validationFailureCount,
            activeWorkers = activeWorkers,
        )
    }
}

