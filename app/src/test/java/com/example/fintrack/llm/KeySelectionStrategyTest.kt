package com.example.fintrack.llm

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [KeySelectionStrategy] implementations.
 *
 * Covers [RoundRobinKeySelectionStrategy] cycle determinism and
 * [MostRemainingBudgetKeySelectionStrategy] capacity-aware selection.
 */
class KeySelectionStrategyTest {

    private val key1 = LlmKeyEntry(id = "k1", apiKey = "sk-key1", label = "Key 1", enabled = true)
    private val key2 = LlmKeyEntry(id = "k2", apiKey = "sk-key2", label = "Key 2", enabled = true)
    private val key3 = LlmKeyEntry(id = "k3", apiKey = "sk-key3", label = "Key 3", enabled = true)

    private val keys = listOf(key1, key2, key3)

    private fun stateFor(id: String, requestsToday: Long = 0L, isAuthFailed: Boolean = false) =
        KeyRuntimeState(
            keyId = id,
            requestsToday = requestsToday,
            dailyLimit = 1_000L,
            isAuthFailed = isAuthFailed,
        )

    @Test
    fun `roundRobin cycles through keys deterministically`() {
        val strategy = RoundRobinKeySelectionStrategy()
        val order = List(6) { strategy.selectKey(keys) { id -> stateFor(id) } }
        // 6 selections over 3 keys = 2 full cycles: k1, k2, k3, k1, k2, k3
        assertEquals("k1", order[0].id)
        assertEquals("k2", order[1].id)
        assertEquals("k3", order[2].id)
        assertEquals("k1", order[3].id)
        assertEquals("k2", order[4].id)
        assertEquals("k3", order[5].id)
    }

    @Test
    fun `roundRobin ignores runtime state`() {
        val strategy = RoundRobinKeySelectionStrategy()
        val selected = strategy.selectKey(keys) { stateFor(it, requestsToday = 1_000, isAuthFailed = true) }
        // Round-robin doesn't care about state — it just cycles.
        assertTrue(selected.id in keys.map { it.id })
    }

    @Test
    fun `mostRemainingBudget picks the least used key`() {
        val strategy = MostRemainingBudgetKeySelectionStrategy()
        val selected = strategy.selectKey(keys) { id ->
            when (id) {
                "k1" -> stateFor("k1", requestsToday = 100)
                "k2" -> stateFor("k2", requestsToday = 0)
                "k3" -> stateFor("k3", requestsToday = 500)
                else -> stateFor(id)
            }
        }
        assertEquals("k2", selected.id) // k2 has the most remaining budget
    }

    @Test
    fun `mostRemainingBudget prefers key with highest remaining budget`() {
        val strategy = MostRemainingBudgetKeySelectionStrategy()
        val selected = strategy.selectKey(keys) { id ->
            when (id) {
                "k1" -> stateFor("k1", requestsToday = 100)
                "k2" -> stateFor("k2", requestsToday = 500)
                "k3" -> stateFor("k3", requestsToday = 1)
                else -> stateFor(id)
            }
        }
        assertEquals("k3", selected.id) // k3 has only 1 used = 999 remaining
    }

    @Test
    fun `mostRemainingBudget handles all keys equal`() {
        val strategy = MostRemainingBudgetKeySelectionStrategy()
        val selected = strategy.selectKey(keys) { stateFor(it, requestsToday = 0L) }
        // All equal — first key returned
        assertTrue(selected.id in keys.map { it.id })
    }

    @Test
    fun `KeyRuntimeState isAvailable checks all conditions`() {
        val now = System.currentTimeMillis()
        val good = KeyRuntimeState("k1", requestsToday = 0, dailyLimit = 1000, cooldownUntilEpochMs = 0)
        val cooldown = KeyRuntimeState("k2", requestsToday = 0, dailyLimit = 1000, cooldownUntilEpochMs = now + 60_000)
        val exhausted = KeyRuntimeState("k3", requestsToday = 1000, dailyLimit = 1000)
        val authFailed = KeyRuntimeState("k4", requestsToday = 0, dailyLimit = 1000, isAuthFailed = true)

        assertTrue(good.isAvailable(now))
        assertFalse(cooldown.isAvailable(now))
        assertFalse(exhausted.isAvailable(now))
        assertFalse(authFailed.isAvailable(now))
    }

    @Test
    fun `remainingDailyBudget is never negative`() {
        assertEquals(0L, KeyRuntimeState("k1", requestsToday = 1500, dailyLimit = 1000).remainingDailyBudget)
    }
}