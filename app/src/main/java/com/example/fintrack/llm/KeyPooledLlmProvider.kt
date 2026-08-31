package com.example.fintrack.llm

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap

/**
 * Task 1: Multi-API-Key pooled LLM provider.
 *
 * Distributes requests across multiple configured API keys for the same provider/model,
 * multiplying effective throughput while strictly respecting each key's individual limits:
 * - 25 requests/minute per key (managed via independent TokenBucketRateLimiter instances)
 * - 1,000 requests/day per key (UTC calendar-day boundary matching Room usage counters)
 *
 * Features:
 * - Pluggable selection strategy (Round-Robin default, or Most-Remaining-Budget)
 * - Automatic 429 rotation to alternative keys with available capacity
 * - Per-key cooldown respect (from HTTP Retry-After headers)
 * - Hard failure exclusion on 401/403 invalid/revoked keys
 * - Atomic capacity claiming safe across concurrent coroutine workers
 * - Backward compatible with single-key configurations
 */
class KeyPooledLlmProvider(
    private val configProvider: () -> LlmConfig,
    private val chatCompletionsProvider: ChatCompletionsProvider,
    private val keySelectionStrategy: KeySelectionStrategy = RoundRobinKeySelectionStrategy(),
    private val dailyLimitPerKey: Long = DAILY_LIMIT_PER_KEY,
    private val requestsPerMinutePerKey: Double = 25.0,
) : LlmProvider {

    override val providerId: String get() = chatCompletionsProvider.providerId
    override val modelId: String get() = chatCompletionsProvider.modelId

    private val mutex = Mutex()
    private val rateLimiters = ConcurrentHashMap<String, TokenBucketRateLimiter>()
    private val cooldownUntilMap = ConcurrentHashMap<String, Long>()
    private val authFailedKeys = ConcurrentHashMap.newKeySet<String>()
    private val dailyUsageMap = ConcurrentHashMap<String, Long>()
    private var currentDayUtc: Long = getTodayUtc()

    companion object {
        const val DAILY_LIMIT_PER_KEY = 1_000L
        private const val MAX_ROTATION_ATTEMPTS = 5

        fun getTodayUtc(epochMs: Long = System.currentTimeMillis()): Long =
            Instant.ofEpochMilli(epochMs).atZone(ZoneOffset.UTC).toLocalDate().toEpochDay()
    }

    override suspend fun complete(prompt: String): String {
        var rotationAttempt = 0
        val triedKeysInThisPass = mutableSetOf<String>()

        while (true) {
            val (selectedKey, rateLimiter) = selectKeyAndAcquire(triedKeysInThisPass)

            try {
                val result = chatCompletionsProvider.completeWithKey(prompt, selectedKey.apiKey)
                recordSuccess(selectedKey.id)
                return result
            } catch (e: LlmProviderException) {
                when (e.errorClass) {
                    LlmErrorClass.RATE_LIMITED -> {
                        val cooldownMs = e.retryAfterMs ?: 2_500L
                        setCooldown(selectedKey.id, System.currentTimeMillis() + cooldownMs)
                        triedKeysInThisPass.add(selectedKey.id)
                        rotationAttempt++
                        if (rotationAttempt >= MAX_ROTATION_ATTEMPTS) {
                            throw e
                        }
                        // Continue loop to rotate to an alternate key
                    }
                    LlmErrorClass.SCHEMA_VALIDATION_FAILED -> {
                        val msg = e.message.orEmpty()
                        if (msg.contains("check your API key", ignoreCase = true) ||
                            msg.contains("HTTP 401", ignoreCase = true) ||
                            msg.contains("HTTP 403", ignoreCase = true)
                        ) {
                            markAuthFailed(selectedKey.id, msg)
                            triedKeysInThisPass.add(selectedKey.id)
                            rotationAttempt++
                            if (rotationAttempt >= MAX_ROTATION_ATTEMPTS) {
                                throw e
                            }
                            // Rotate to next available key
                        } else {
                            throw e
                        }
                    }
                    else -> throw e
                }
            }
        }
    }

    /**
     * Atomically selects an eligible key and acquires capacity on its rate limiter.
     */
    private suspend fun selectKeyAndAcquire(
        excludeKeyIds: Set<String>,
    ): Pair<LlmKeyEntry, TokenBucketRateLimiter> {
        while (true) {
            val selectionResult = mutex.withLock {
                checkAndResetDayBoundary()
                val cfg = configProvider()
                val activeKeys = cfg.activeKeys

                if (activeKeys.isEmpty()) {
                    throw LlmProviderException(
                        errorClass = LlmErrorClass.SCHEMA_VALIDATION_FAILED,
                        message = "No active API keys configured: add an API key in Settings",
                    )
                }

                val now = System.currentTimeMillis()

                // Check if all active keys exceeded daily cap
                val underDailyLimitKeys = activeKeys.filter { key ->
                    val usage = dailyUsageMap.getOrDefault(key.id, 0L)
                    usage < dailyLimitPerKey
                }
                if (underDailyLimitKeys.isEmpty()) {
                    throw LlmProviderException(
                        errorClass = LlmErrorClass.LOCAL_BUDGET_EXCEEDED,
                        message = "Daily request quota ($dailyLimitPerKey reqs/day) exhausted for all configured API keys",
                    )
                }

                // Filter keys that are not auth-failed and not currently excluded from this request pass
                val nonFailedKeys = underDailyLimitKeys.filter { key ->
                    !authFailedKeys.contains(key.id) && !excludeKeyIds.contains(key.id)
                }

                if (nonFailedKeys.isEmpty()) {
                    // All candidates failed auth or are excluded in this rotation pass
                    val allAuthFailed = activeKeys.all { authFailedKeys.contains(it.id) }
                    if (allAuthFailed) {
                        throw LlmProviderException(
                            errorClass = LlmErrorClass.SCHEMA_VALIDATION_FAILED,
                            message = "All configured API keys failed authentication: check your API keys in Settings",
                        )
                    }
                    // Every eligible key was excluded by this rotation pass (e.g. 429 cooldown).
                    // The excluded keys won't be reconsidered until the pass ends, so waiting here
                    // would spin forever. Fail fast with a rate-limit error instead.
                    throw LlmProviderException(
                        errorClass = LlmErrorClass.RATE_LIMITED,
                        message = "All configured API keys are currently rate-limited; retry later",
                    )
                }

                // Filter keys that are out of cooldown
                val readyKeys = nonFailedKeys.filter { key ->
                    val cooldown = cooldownUntilMap.getOrDefault(key.id, 0L)
                    cooldown <= now
                }

                if (readyKeys.isEmpty()) {
                    val minCooldown = nonFailedKeys.minOf { cooldownUntilMap.getOrDefault(it.id, now + 1000L) }
                    return@withLock SelectionResult.MustWait((minCooldown - now).coerceAtLeast(100L))
                }

                // Strategy state provider
                val stateProvider: (String) -> KeyRuntimeState = { keyId ->
                    KeyRuntimeState(
                        keyId = keyId,
                        requestsToday = dailyUsageMap.getOrDefault(keyId, 0L),
                        dailyLimit = dailyLimitPerKey,
                        cooldownUntilEpochMs = cooldownUntilMap.getOrDefault(keyId, 0L),
                        isAuthFailed = authFailedKeys.contains(keyId),
                    )
                }

                // Check for a key with immediate token bucket availability
                val immediateKey = readyKeys.firstOrNull { key ->
                    val limiter = getOrCreateRateLimiter(key.id)
                    limiter.availableTokens() > 0
                }

                val chosenKey = immediateKey ?: keySelectionStrategy.selectKey(readyKeys, stateProvider)
                val limiter = getOrCreateRateLimiter(chosenKey.id)

                SelectionResult.KeyChosen(chosenKey, limiter)
            }

            when (selectionResult) {
                is SelectionResult.MustWait -> {
                    delay(selectionResult.waitMs.coerceIn(50L, 5000L))
                }
                is SelectionResult.KeyChosen -> {
                    // Acquire rate limiter token outside global mutex so other keys are not blocked
                    selectionResult.rateLimiter.acquire()
                    return Pair(selectionResult.key, selectionResult.rateLimiter)
                }
            }
        }
    }

    private sealed interface SelectionResult {
        data class MustWait(val waitMs: Long) : SelectionResult
        data class KeyChosen(val key: LlmKeyEntry, val rateLimiter: TokenBucketRateLimiter) : SelectionResult
    }

    private fun getOrCreateRateLimiter(keyId: String): TokenBucketRateLimiter =
        rateLimiters.computeIfAbsent(keyId) {
            TokenBucketRateLimiter(
                tokensPerSecond = requestsPerMinutePerKey / 60.0,
                maxTokens = 1.0,
            )
        }

    private fun recordSuccess(keyId: String) {
        checkAndResetDayBoundary()
        dailyUsageMap.compute(keyId) { _, current -> (current ?: 0L) + 1L }
    }

    private fun setCooldown(keyId: String, untilEpochMs: Long) {
        cooldownUntilMap[keyId] = untilEpochMs
    }

    private fun markAuthFailed(keyId: String, error: String) {
        authFailedKeys.add(keyId)
    }

    private fun checkAndResetDayBoundary() {
        val today = getTodayUtc()
        if (today != currentDayUtc) {
            currentDayUtc = today
            dailyUsageMap.clear()
            authFailedKeys.clear()
            cooldownUntilMap.clear()
        }
    }

    // ---- Diagnostics & Status ----

    fun getKeyRuntimeStates(): List<KeyRuntimeState> {
        val cfg = configProvider()
        val now = System.currentTimeMillis()
        return cfg.keys.map { key ->
            KeyRuntimeState(
                keyId = key.id,
                requestsToday = dailyUsageMap.getOrDefault(key.id, 0L),
                dailyLimit = dailyLimitPerKey,
                cooldownUntilEpochMs = cooldownUntilMap.getOrDefault(key.id, 0L),
                isAuthFailed = authFailedKeys.contains(key.id),
                lastUsedEpochMs = 0L,
            )
        }
    }

    fun resetDailyUsageForTesting() {
        dailyUsageMap.clear()
        cooldownUntilMap.clear()
        authFailedKeys.clear()
        rateLimiters.values.forEach { it.reset() }
    }
}

