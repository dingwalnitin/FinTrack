package com.example.fintrack.application.enrichment

import com.example.fintrack.data.db.LlmInterpretationEntity
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
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.security.MessageDigest
import java.util.UUID

/**
 * On-demand LLM scan of ALL captured SMS.
 *
 * This is the "process every SMS through the LLM" path triggered from
 * Settings. It reads every raw_sms row, builds a strictly-evidence-labeled
 * prompt ([PromptBuilder]), calls the provider through a token-bucket
 * rate limiter + exponential backoff ([RetryPolicy] / [LlmJobStore]), and
 * persists validated interpretations only.
 *
 * Progress (processed / total, succeeded / failed) is exposed as a
 * [StateFlow] so the Settings progress bar can observe it.
 */
class LlmProcessingService(
    private val smsDao: SmsDao,
    private val llmDao: LlmDao,
    private val provider: LlmProvider,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

    data class Progress(
        val running: Boolean = false,
        val total: Long = 0,
        val processed: Long = 0,
        val succeeded: Long = 0,
        val failed: Long = 0,
        val status: String = "IDLE",
        val lastError: String? = null,
    )

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    private val jobStore = com.example.fintrack.application.enrichment.LlmJobStore(llmDao)

    /** Start scanning all SMS. Idempotent — no-op if already running. */
    fun startScan() {
        if (_progress.value.running) return
        _progress.value = Progress(running = true, status = "SCANNING")
        scope.launch { runScan() }
    }

    fun stopScan() {
        // Mark not-running; the loop checks the flag between jobs.
        _progress.value = _progress.value.copy(running = false, status = "IDLE")
        scope.cancel()
    }

    private suspend fun runScan() {
        val rows = try {
            smsDao.allRawRows()
        } catch (t: Throwable) {
            _progress.value = _progress.value.copy(
                running = false, status = "FAILED",
                lastError = t.message ?: "Failed to load SMS",
            )
            return
        }
        val total = rows.size.toLong()
        var processed = 0L
        var succeeded = 0L
        var failed = 0L

        _progress.value = _progress.value.copy(total = total)

        for (row in rows) {
            if (!_progress.value.running) break
            // Already interpreted? Skip (idempotent).
            if (jobStore.interpretationsForMessage(row.id).isNotEmpty()) {
                processed++
                continue
            }
            val ok = processOne(row)
            if (ok) succeeded++ else failed++
            processed++
            _progress.value = _progress.value.copy(
                processed = processed,
                succeeded = succeeded,
                failed = failed,
                status = if (_progress.value.running) "SCANNING" else "IDLE",
            )
        }

        _progress.value = _progress.value.copy(
            running = false,
            status = if (processed >= total && total > 0) "COMPLETE" else "IDLE",
        )
    }

    /**
     * Process one raw SMS: build prompt -> call provider (rate-limited,
     * backoff) -> validate -> persist interpretation. Returns true on success.
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
        val maxAttempts = 4
        while (attempt < maxAttempts) {
            if (!_progress.value.running) return false
            try {
                val raw = provider.complete(prompt) // rate limiter inside provider
                val bounds = boundsFrom(request)
                when (val result = LlmResponseDecoder.decode(raw, bounds)) {
                    is LlmResponseDecoder.ValidationResult.Invalid -> {
                        // Never retry permanent validation failures.
                        _progress.value = _progress.value.copy(
                            lastError = "Validation failed: ${result.reason}",
                        )
                        return false
                    }
                    is LlmResponseDecoder.ValidationResult.Valid -> {
                        persistInterpretation(row, request, result.response)
                        return true
                    }
                }
            } catch (e: LlmProviderException) {
                val delay = RetryPolicy.nextDelayMs(e.errorClass, attempt, e.retryAfterMs)
                if (delay == null) {
                    _progress.value = _progress.value.copy(lastError = e.message)
                    return false
                }
                attempt++
                delay(delay)
            } catch (t: Throwable) {
                val delay = RetryPolicy.nextDelayMs(LlmErrorClass.PROVIDER_UNAVAILABLE, attempt)
                if (delay == null) return false
                attempt++
                delay(delay)
            }
        }
        return false
    }

    private suspend fun persistInterpretation(
        row: RawSmsEntity,
        request: ParseRequest,
        response: LlmResponseDecoder.RawParsed,
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
                latencyMs = 0,
                tokensPrompt = 0,
                tokensCompletion = 0,
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
        val o = org.json.JSONObject()
        fun put(key: String, c: com.example.fintrack.llm.FieldConfidence?) {
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

    private fun sha256(input: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}