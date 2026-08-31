package com.example.fintrack.llm

import java.util.concurrent.atomic.AtomicInteger

/**
 * Runtime state for a single API key in the pool.
 * Tracks requests-today, cooldown, and auth status for capacity-aware selection.
 */
data class KeyRuntimeState(
    val keyId: String,
    val requestsToday: Long = 0L,
    val dailyLimit: Long = 1_000L,
    val cooldownUntilEpochMs: Long = 0L,
    val isAuthFailed: Boolean = false,
    val lastUsedEpochMs: Long = 0L,
) {
    val remainingDailyBudget: Long get() = (dailyLimit - requestsToday).coerceAtLeast(0L)

    fun isAvailable(nowMs: Long): Boolean =
        !isAuthFailed && remainingDailyBudget > 0 && nowMs >= cooldownUntilEpochMs
}

/**
 * Pluggable strategy that selects which key to use for the next request.
 */
interface KeySelectionStrategy {
    /**
     * Select the best key from [activeKeys] given [stateFor] runtime state lookup.
     * Implementations must be deterministic for the same input.
     */
    fun selectKey(
        activeKeys: List<LlmKeyEntry>,
        stateFor: (keyId: String) -> KeyRuntimeState,
    ): LlmKeyEntry
}

/**
 * Classic round-robin: cycles through keys sequentially, distributing load evenly.
 */
class RoundRobinKeySelectionStrategy : KeySelectionStrategy {
    private val counter = AtomicInteger(0)

    override fun selectKey(
        activeKeys: List<LlmKeyEntry>,
        stateFor: (keyId: String) -> KeyRuntimeState,
    ): LlmKeyEntry {
        require(activeKeys.isNotEmpty()) { "No active keys available" }
        val index = Math.floorMod(counter.getAndIncrement(), activeKeys.size)
        return activeKeys[index]
    }
}

/**
 * Selects the key with the most remaining daily budget (least used today).
 * Best for uneven workloads where one key may be near its daily cap.
 */
class MostRemainingBudgetKeySelectionStrategy : KeySelectionStrategy {
    override fun selectKey(
        activeKeys: List<LlmKeyEntry>,
        stateFor: (keyId: String) -> KeyRuntimeState,
    ): LlmKeyEntry {
        require(activeKeys.isNotEmpty()) { "No active keys available" }
        return activeKeys.maxByOrNull { stateFor(it.id).remainingDailyBudget } ?: activeKeys.first()
    }
}

