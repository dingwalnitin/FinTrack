package com.example.fintrack.application.enrichment

import com.example.fintrack.data.db.LlmInterpretationEntity
import com.example.fintrack.data.db.LlmJobStates
import com.example.fintrack.data.db.RawSmsEntity
import com.example.fintrack.data.db.SmsDao
import com.example.fintrack.data.db.LlmDao
import com.example.fintrack.llm.LlmErrorClass
import com.example.fintrack.llm.LlmProvider
import com.example.fintrack.llm.LlmProviderException
import com.example.fintrack.llm.LlmResponseDecoder
import com.example.fintrack.llm.ParseRequest
import com.example.fintrack.llm.PromptBuilder
import com.example.fintrack.llm.RetryPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener

/**
 * On-demand LLM scan of captured SMS.
 *
 * Pipelined two-phase design:
 *  Phase 1 — Triage: batches of [TRIAGE_BATCH_SIZE] SMS are classified in one
 *    call; messages explicitly named by a caller (a just-received SMS) are
 *    instead triaged one-shot so they are not stuck behind a backlog.
 *  Phase 2 — Extraction: messages above [TRIAGE_THRESHOLD] stream straight
 *    into a bounded channel drained by [EXTRACTION_CONCURRENCY] workers, so
 *    extraction overlaps triage instead of waiting for it.
 *
 * Every message ends the pass with a durable outcome in `llm_jobs`, so a
 * later pass never re-pays for triage or extraction it already did.
 *
 * Progress is exposed as a [StateFlow] for the Settings progress bars.
 */
class LlmProcessingService(
    private val smsDao: SmsDao,
    private val llmDao: LlmDao,
    private val provider: LlmProvider,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val discoveryService: LlmDiscoveryService? = null,
) {

    data class Progress(
        val running: Boolean = false,
        val total: Long = 0,
        val processed: Long = 0,
        val succeeded: Long = 0,
        val failed: Long = 0,
        val status: String = "IDLE",
        val lastError: String? = null,
        val triageTotal: Long = 0,
        val triageProcessed: Long = 0,
        val triageBatchesTotal: Int = 0,
        val triageBatchesCompleted: Int = 0,
        val extractionTotal: Long = 0,
        val extractionProcessed: Long = 0,
        val batchTriaged: Long = 0,
        val directTriaged: Long = 0,
    )

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    private val jobStore = LlmJobStore(llmDao)

    // The scope is NEVER cancelled. We track the active scan in [scanJob] and
    // cancel just that job on stop(), so a later startScan() can still launch
    // on a live scope. (Cancelling the scope itself would permanently poison
    // this singleton: every subsequent scope.launch would be a no-op and the
    // UI would freeze at 0/0 forever.)
    private var scanJob: Job? = null

    /**
     * Set when a scan is requested while one is already in flight. The running
     * scan snapshotted `raw_sms` before that request, so it would otherwise
     * finish without ever seeing the newly captured rows and the caller's
     * join() would return having silently dropped them.
     */
    private var rescanRequested = false

    /** Message ids a caller asked to be triaged one-shot rather than batched. */
    private val requestedDirectIds = linkedSetOf<String>()

    /** Minimum triage confidence to proceed to interpretation. */
    private val TRIAGE_THRESHOLD = 0.7
    /** Batch size for the triage phase. */
    private val TRIAGE_BATCH_SIZE = 20
    private val EXTRACTION_CONCURRENCY = 2
    /** Attempts (including the first) for a single triage provider call before failing open. */
    private val TRIAGE_MAX_ATTEMPTS = 4

    /**
     * Start scanning all SMS. Idempotent — no-op if already running.
     * Non-blocking: returns immediately; progress is observed via [progress].
     */
    fun startScan() {
        ensureScanStarted(emptySet())
    }

    /**
     * Scan and suspend until every message captured *before this call* has a
     * durable outcome. [directMessageIds] are triaged one at a time so a
     * just-arrived SMS is not queued behind a historical backlog.
     */
    suspend fun startScanAndWait(directMessageIds: Set<String> = emptySet()) {
        ensureScanStarted(directMessageIds).join()
    }

    @Synchronized
    private fun ensureScanStarted(directMessageIds: Set<String>): Job {
        requestedDirectIds += directMessageIds
        scanJob?.takeIf { it.isActive }?.let { active ->
            rescanRequested = true
            return active
        }
        rescanRequested = false
        _progress.value = Progress(running = true, status = "SCANNING")
        return scope.launch { runScan() }.also { scanJob = it }
    }

    @Synchronized
    fun stopScan() {
        rescanRequested = false
        requestedDirectIds.clear()
        val job = scanJob
        scanJob = null
        _progress.update { it.copy(running = false, status = "IDLE") }
        job?.cancel()
    }

    @Synchronized
    private fun takeRequestedDirectIds(): Set<String> {
        val ids = requestedDirectIds.toSet()
        requestedDirectIds.clear()
        return ids
    }

    /**
     * Publishes "no scan is active" and decides whether another pass is owed,
     * atomically. Doing both under one lock closes the window where a caller
     * would otherwise attach to a job that is already unwinding.
     */
    @Synchronized
    private fun consumeRescanRequest(self: Job?): Boolean {
        if (rescanRequested) {
            rescanRequested = false
            return true
        }
        if (scanJob === self) scanJob = null
        return false
    }

    /**
     * Only the job that still owns [scanJob] (or a slot nobody has claimed) may
     * clear state. Without the identity check a cancelled scan's unwind would
     * mark a freshly started scan as not-running, stalling its pipeline.
     */
    @Synchronized
    private fun finishScan(self: Job?) {
        val owns = scanJob == null || scanJob === self
        if (scanJob === self) scanJob = null
        if (owns) _progress.update { if (it.running) it.copy(running = false) else it }
    }

    private suspend fun runScan() {
        val self = currentCoroutineContext()[Job]
        try {
            while (true) {
                runOnePass()
                if (!consumeRescanRequest(self)) break
            }
        } finally {
            finishScan(self)
        }
    }

    private suspend fun runOnePass() {
        _progress.value = Progress(running = true, status = "SCANNING")

        val rows = try {
            smsDao.allRawRows()
        } catch (t: Throwable) {
            currentCoroutineContext().ensureActive()
            _progress.update {
                it.copy(
                    running = false, status = "FAILED",
                    lastError = t.message ?: "Failed to load SMS",
                )
            }
            return
        }

        if (rows.isEmpty()) {
            _progress.update { it.copy(running = false, status = "COMPLETE", total = 0) }
            return
        }

        val total = rows.size.toLong()

        // Two bulk queries instead of one per row: anything already interpreted
        // or carrying a terminal outcome is done and must not be re-sent.
        val settled = jobStore.settledMessageIds()
        val pendingRows = rows.filterNot { it.id in settled }
        val directIds = takeRequestedDirectIds()
        val directRows = pendingRows.filter { it.id in directIds }
        val batchRows = pendingRows.filterNot { it.id in directIds }
        val triageBatchCount = (batchRows.size + TRIAGE_BATCH_SIZE - 1) / TRIAGE_BATCH_SIZE
        _progress.update {
            it.copy(
                total = total,
                processed = total - pendingRows.size,
                triageTotal = pendingRows.size.toLong(),
                triageBatchesTotal = triageBatchCount,
            )
        }

        if (pendingRows.isEmpty()) {
            _progress.update { it.copy(running = false, status = "COMPLETE", processed = total) }
            return
        }

        _progress.update { it.copy(status = "PROCESSING") }
        processPipeline(directRows, batchRows)
        persistRoutingCounters()

        val processed = _progress.value.processed
        _progress.update {
            it.copy(
                running = false,
                status = if (processed >= total && total > 0) "COMPLETE" else "IDLE",
            )
        }
    }

    /** Lifetime batch/direct routing totals, so the split survives process death. */
    private suspend fun persistRoutingCounters() {
        val snapshot = _progress.value
        val now = System.currentTimeMillis()
        runCatching {
            jobStore.setMetric(
                METRIC_BATCH_TRIAGED,
                jobStore.metric(METRIC_BATCH_TRIAGED) + snapshot.batchTriaged,
                now,
            )
            jobStore.setMetric(
                METRIC_DIRECT_TRIAGED,
                jobStore.metric(METRIC_DIRECT_TRIAGED) + snapshot.directTriaged,
                now,
            )
        }
    }

    private suspend fun processPipeline(
        directRows: List<RawSmsEntity>,
        batchRows: List<RawSmsEntity>,
    ) = coroutineScope {
        val extractionQueue = Channel<RawSmsEntity>(capacity = TRIAGE_BATCH_SIZE)
        val workers = List(EXTRACTION_CONCURRENCY) {
            launch {
                for (row in extractionQueue) {
                    val succeeded = processOne(row)
                    _progress.update { progress ->
                        progress.copy(
                            processed = progress.processed + 1,
                            extractionProcessed = progress.extractionProcessed + 1,
                            succeeded = progress.succeeded + if (succeeded) 1 else 0,
                            failed = progress.failed + if (succeeded) 0 else 1,
                        )
                    }
                }
            }
        }

        suspend fun classifyAndEnqueue(rows: List<RawSmsEntity>, direct: Boolean) {
            if (rows.isEmpty()) return
            val passedIds = triageOnce(rows)
            val passedRows = rows.filter { it.id in passedIds }
            val rejectedRows = rows.filterNot { it.id in passedIds }
            rejectedRows.forEach { recordOutcome(it, LlmJobStates.SUCCEEDED, "TRIAGE_REJECTED") }
            _progress.update { progress ->
                progress.copy(
                    processed = progress.processed + rejectedRows.size,
                    triageProcessed = progress.triageProcessed + rows.size,
                    triageBatchesCompleted = progress.triageBatchesCompleted + if (direct) 0 else 1,
                    extractionTotal = progress.extractionTotal + passedRows.size,
                    batchTriaged = progress.batchTriaged + if (direct) 0 else rows.size,
                    directTriaged = progress.directTriaged + if (direct) rows.size else 0,
                )
            }
            passedRows.forEach { extractionQueue.send(it) }
        }

        try {
            directRows.forEach { classifyAndEnqueue(listOf(it), direct = true) }
            batchRows.chunked(TRIAGE_BATCH_SIZE).forEach {
                classifyAndEnqueue(it, direct = false)
            }
        } finally {
            extractionQueue.close()
        }
        workers.joinAll()
    }

    private suspend fun recordOutcome(row: RawSmsEntity, status: String, errorClass: String?) {
        runCatching {
            jobStore.recordScanOutcome(
                sourceMessageId = row.id,
                senderHash = row.sender?.let { sha256(it) },
                providerId = provider.providerId,
                status = status,
                errorClass = errorClass,
                nowEpochMs = System.currentTimeMillis(),
            )
        }
    }

    /**
     * Phase 1: classify one already-sized batch. Returns the ids that met
     * [TRIAGE_THRESHOLD]. Any transport or decode failure fails OPEN so a
     * flaky classifier never silently discards a real transaction.
     */
    private suspend fun triageOnce(batch: List<RawSmsEntity>): Set<String> {
        val prompt = buildTriagePrompt(batch)
        val raw = completeTriageWithRetry(prompt) ?: return batch.map { it.id }.toSet()

        val scores = try {
            decodeTriageResponse(raw, batch.size)
        } catch (_: Throwable) {
            val retryRaw = completeTriageWithRetry(buildTriageRetryPrompt(prompt))
                ?: return batch.map { it.id }.toSet()
            try {
                decodeTriageResponse(retryRaw, batch.size)
            } catch (t: Throwable) {
                currentCoroutineContext().ensureActive()
                _progress.update {
                    it.copy(lastError = "Triage decode failed for batch: ${t.message?.take(100)}")
                }
                return batch.map { it.id }.toSet()
            }
        }

        return batch.filterIndexed { i, _ -> scores.getOrElse(i) { 1.0 } >= TRIAGE_THRESHOLD }
            .map { it.id }
            .toSet()
    }

    /**
     * Calls the provider with exponential backoff on transient errors (rate
     * limits, timeouts, provider outages/[TRIAGE_MAX_ATTEMPTS]). Returns null
     * once retries are exhausted or the error is permanent, so the caller can
     * fail open instead of dropping the batch.
     */
    private suspend fun completeTriageWithRetry(prompt: String): String? {
        var attempt = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            try {
                return provider.complete(prompt)
            } catch (e: LlmProviderException) {
                val delayMs = RetryPolicy.nextDelayMs(e.errorClass, attempt, e.retryAfterMs)
                if (delayMs == null || attempt >= TRIAGE_MAX_ATTEMPTS - 1) {
                    _progress.update { it.copy(lastError = "Triage failed for batch: ${e.message?.take(100)}") }
                    return null
                }
                attempt++
                delay(delayMs)
            } catch (t: Throwable) {
                currentCoroutineContext().ensureActive()
                val delayMs = RetryPolicy.nextDelayMs(LlmErrorClass.PROVIDER_UNAVAILABLE, attempt)
                if (delayMs == null || attempt >= TRIAGE_MAX_ATTEMPTS - 1) {
                    _progress.update { it.copy(lastError = "Triage failed for batch: ${t.message?.take(100)}") }
                    return null
                }
                attempt++
                delay(delayMs)
            }
        }
    }

    /**
     * Build a triage prompt for a batch of SMS. Asks the LLM to classify
     * each message as personal-finance-related or not, returning confidence.
     * Optimized for token savings and robust structured output.
     */
    private fun buildTriagePrompt(batch: List<RawSmsEntity>): String = buildString {
        appendLine("You are a financial SMS classifier. Classify each message by index:")
        appendLine("- High confidence (>=0.7): Personal finance transactions (bank debits/credits, UPI, card spends, ATM, transfers, bill payments, refunds, salary).")
        appendLine("- Low confidence (<0.7): Non-transactions (OTP/2FA codes, promotional/marketing offers, spam, delivery/service alerts, social updates).")
        appendLine()
        appendLine("Formatting & Token Rules:")
        appendLine("1. Return ONLY a valid JSON array of objects, with no other text, no markdown fences (```), and no preamble/outro.")
        appendLine("2. Even for a single message, ALWAYS return a JSON array starting with [ and ending with ].")
        appendLine("3. Output ONLY \"i\" (index) and \"c\" (confidence) keys. NEVER echo or repeat the SMS body, sender, or any explanation.")
        appendLine("4. Schema: [{\"i\":0,\"c\":0.95},{\"i\":1,\"c\":0.1}]")
        appendLine()
        appendLine("SMS messages:")
        batch.forEachIndexed { index, sms ->
            val normalizedBody = sms.body.replace(Regex("\\s+"), " ").trim()
            appendLine("--- Message $index ---")
            appendLine(normalizedBody)
        }
    }

    private fun buildTriageRetryPrompt(originalPrompt: String): String = buildString {
        append(originalPrompt)
        appendLine()
        appendLine("Your previous response could not be parsed as valid JSON.")
        appendLine("RETRY THE SAME TRIAGE TASK FROM THE ORIGINAL INPUT.")
        appendLine("Output ONLY the JSON array.")
        appendLine("Do not include markdown, code fences, commentary, explanations, or text before/after the array.")
        appendLine("The response MUST:")
        appendLine("1. Start with [")
        appendLine("2. End with ]")
        appendLine("3. Contain valid JSON syntax")
        appendLine("4. Use double quotes for all keys and string values")
        appendLine("5. Contain no trailing commas")
        appendLine("6. Match the required schema exactly")
    }

    /**
     * Decode a triage response: extract the JSON array and return the
     * confidence scores in order. Returns a list of size [expectedCount];
     * any index the model omitted defaults to 1.0 (fail open), consistent
     * with how outright triage errors are handled above.
     */
    private fun decodeTriageResponse(raw: String, expectedCount: Int): List<Double> {
        // Strip any markdown fences that the model might wrap around the JSON.
        val cleaned = raw
            .replace(Regex("```(?:json)?\\s*"), "")
            .replace(Regex("\\s*```"), "")
            .trim()
        val value = JSONTokener(cleaned).nextValue()
        val arr = when (value) {
            is JSONArray -> value
            is JSONObject -> JSONArray().put(value)
            else -> throw IllegalArgumentException("Expected a JSON array or object")
        }
        val scores = MutableList(expectedCount) { 1.0 }
        for (i in 0 until arr.length()) {
            val obj = arr.getJSONObject(i)
            val idx = if (obj.has("index")) obj.optInt("index", -1) else obj.optInt("i", -1)
            val conf = when {
                obj.has("confidence") -> obj.optDouble("confidence", 0.0)
                obj.has("c") -> obj.optDouble("c", 0.0)
                obj.has("score") -> obj.optDouble("score", 0.0)
                else -> 0.0
            }
            if (idx in 0 until expectedCount) {
                scores[idx] = conf.coerceIn(0.0, 1.0)
            }
        }
        return scores
    }

    /**
     * Process one raw SMS: build prompt -> call provider (rate-limited,
     * backoff) -> validate -> promote + persist. Returns true on success.
     * Always leaves a durable outcome so the next pass can skip this row
     * (except for retryable transport failures, which stay eligible).
     */
    private suspend fun processOne(row: RawSmsEntity): Boolean {
        val request = ParseRequest(
            sourceMessageId = row.id,
            senderHash = row.sender?.let { sha256(it) } ?: "unknown",
            bodyText = row.body,
            receivedAtEpochMs = row.receivedAtEpochMs,
        )
        val prompt = PromptBuilder.build(request)

        var attempt = 0
        var activePrompt = prompt
        var formatRetryUsed = false
        var lastErrorClass: String? = null
        val maxAttempts = 4
        while (attempt < maxAttempts) {
            currentCoroutineContext().ensureActive()
            try {
                val startedAt = System.nanoTime()
                val raw = provider.complete(activePrompt)
                val latencyMs = ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(1L)
                val bounds = boundsFrom(request)
                when (val result = LlmResponseDecoder.decode(raw, bounds)) {
                    is LlmResponseDecoder.ValidationResult.Invalid -> {
                        if (!formatRetryUsed && result.errorClass in FORMAT_RETRY_ERRORS) {
                            formatRetryUsed = true
                            activePrompt = buildExtractionRetryPrompt(prompt, result.reason)
                            continue
                        }
                        _progress.update {
                            it.copy(lastError = "Validation failed: ${result.reason}")
                        }
                        recordOutcome(row, LlmJobStates.TERMINAL_FAILED, result.errorClass.name)
                        return false
                    }
                    is LlmResponseDecoder.ValidationResult.Valid -> {
                        return try {
                            // Promote first: it is idempotent (stable ids +
                            // IGNORE on the unique dedupeKey), so a crash
                            // between the two writes replays harmlessly. The
                            // reverse order would strand the interpretation
                            // and permanently skip the row on later passes.
                            discoveryService?.promote(row, result.response, request.promptVersion)
                            persistInterpretation(
                                row = row,
                                request = request,
                                response = result.response,
                                latencyMs = latencyMs,
                                tokensPrompt = estimateTokens(activePrompt),
                                tokensCompletion = estimateTokens(raw),
                            )
                            recordOutcome(row, LlmJobStates.SUCCEEDED, null)
                            true
                        } catch (t: Throwable) {
                            currentCoroutineContext().ensureActive()
                            _progress.update {
                                it.copy(lastError = "Discovery sync failed: ${t.message?.take(100)}")
                            }
                            recordOutcome(row, LlmJobStates.RETRYABLE_FAILED, "DISCOVERY_SYNC")
                            false
                        }
                    }
                    is LlmResponseDecoder.ValidationResult.NonFinancial -> {
                        // Not a transaction (OTP, promo, etc.) — settle as succeeded
                        // without writing an interpretation or promoting a ledger row.
                        recordOutcome(row, LlmJobStates.SUCCEEDED, NON_FINANCIAL_ERROR_CLASS)
                        return true
                    }
                }
            } catch (e: LlmProviderException) {
                lastErrorClass = e.errorClass.name
                val delay = RetryPolicy.nextDelayMs(e.errorClass, attempt, e.retryAfterMs)
                if (delay == null) {
                    _progress.update { it.copy(lastError = e.message) }
                    recordOutcome(row, LlmJobStates.RETRYABLE_FAILED, lastErrorClass)
                    return false
                }
                attempt++
                delay(delay)
            } catch (t: Throwable) {
                currentCoroutineContext().ensureActive()
                lastErrorClass = LlmErrorClass.PROVIDER_UNAVAILABLE.name
                val delay = RetryPolicy.nextDelayMs(LlmErrorClass.PROVIDER_UNAVAILABLE, attempt)
                if (delay == null) {
                    _progress.update { it.copy(lastError = t.message) }
                    recordOutcome(row, LlmJobStates.RETRYABLE_FAILED, lastErrorClass)
                    return false
                }
                attempt++
                delay(delay)
            }
        }
        recordOutcome(row, LlmJobStates.RETRYABLE_FAILED, lastErrorClass)
        return false
    }

    private fun buildExtractionRetryPrompt(originalPrompt: String, reason: String): String = buildString {
        append(originalPrompt)
        appendLine()
        appendLine("Your previous response failed validation: $reason")
        appendLine("RETRY THE SAME EXTRACTION TASK FROM THE ORIGINAL INPUT.")
        appendLine("Output ONLY one valid JSON object matching the schema exactly.")
        appendLine("Do not include markdown, code fences, commentary, explanations, or text before/after the object.")
        appendLine("Use double quotes for keys and strings, omit unsupported fields, and include no trailing commas.")
    }

    private suspend fun persistInterpretation(
        row: RawSmsEntity,
        request: ParseRequest,
        response: LlmResponseDecoder.RawParsed,
        latencyMs: Long,
        tokensPrompt: Int,
        tokensCompletion: Int,
    ) {
        val i = response.interpretation
        val now = System.currentTimeMillis()
        val cacheKey = PromptBuilder.cacheKey(request, provider.providerId, provider.modelId)
        val responseHash = sha256(row.id + "\u0000" + cacheKey)
        jobStore.storeInterpretation(
            LlmInterpretationEntity(
                id = UUID.randomUUID().toString(),
                sourceMessageId = row.id,
                responseHash = responseHash,
                promptVersion = request.promptVersion,
                schemaVersion = request.schemaVersion,
                providerId = provider.providerId,
                modelId = provider.modelId,
                amountMinor = i.amountMinor,
                currencyCode = i.currencyCode,
                direction = i.direction?.name,
                accountToken = i.accountToken,
                rail = i.rail?.name,
                counterpartyRaw = i.counterpartyRaw,
                counterpartyNormalized = i.counterpartyNormalized,
                categorySuggestion = i.categorySuggestion,
                transferTargetToken = i.transferTargetToken,
                recurring = i.recurring,
                emiDetail = i.emiDetail,
                occurredAtEpochMs = i.occurredAtEpochMs,
                confidenceAmount = i.confidenceAmount?.value,
                confidenceDirection = i.confidenceDirection?.value,
                confidenceAccount = i.confidenceAccount?.value,
                confidenceRail = i.confidenceRail?.value,
                confidenceCounterparty = i.confidenceCounterparty?.value,
                confidenceCategory = i.confidenceCategory?.value,
                confidenceTransferTarget = i.confidenceTransferTarget?.value,
                confidenceRecurring = i.confidenceRecurring?.value,
                confidenceEmi = i.confidenceEmi?.value,
                evidenceExplanationsJson = explanationsJson(i),
                overallConfidence = response.overallConfidence,
                latencyMs = latencyMs,
                tokensPrompt = tokensPrompt,
                tokensCompletion = tokensCompletion,
                fromCache = false,
                createdAtEpochMs = now,
            )
        )
    }

    private fun boundsFrom(request: ParseRequest): LlmResponseDecoder.EvidenceBounds {
        val text = request.bodyText
        val amounts = Regex("(?:rs\\.?|inr|₹)\\s*([0-9][0-9,]*(?:\\.[0-9]{1,2})?)", RegexOption.IGNORE_CASE)
            .findAll(text)
            .map { it.groupValues[1].replace(",", "") }
            .mapNotNull { s ->
                s.toLongOrNull()?.let { whole ->
                    if (s.contains('.')) Math.round(s.toDouble() * 100) else whole * 100
                }
            }
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

    private fun explanationsJson(i: com.example.fintrack.llm.Interpretation): String {
        val o = JSONObject()
        fun put(key: String, c: com.example.fintrack.llm.FieldConfidence?) {
            if (c != null) {
                o.put(key, JSONObject().put("value", c.value).put("explanation", c.explanation))
            }
        }
        put("amount", i.confidenceAmount); put("direction", i.confidenceDirection)
        put("account", i.confidenceAccount); put("rail", i.confidenceRail)
        put("counterparty", i.confidenceCounterparty); put("category", i.confidenceCategory)
        put("transferTarget", i.confidenceTransferTarget); put("recurring", i.confidenceRecurring)
        put("emi", i.confidenceEmi)
        return o.toString()
    }

    private fun sha256(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun estimateTokens(text: String): Int = (text.length / 4).coerceAtLeast(1)

    private companion object {
        val FORMAT_RETRY_ERRORS = setOf(LlmErrorClass.BAD_JSON, LlmErrorClass.SCHEMA_VALIDATION_FAILED)
        const val NON_FINANCIAL_ERROR_CLASS = "NON_FINANCIAL_SMS"
        const val METRIC_BATCH_TRIAGED = "llm.triage.batched.total"
        const val METRIC_DIRECT_TRIAGED = "llm.triage.direct.total"
    }
}