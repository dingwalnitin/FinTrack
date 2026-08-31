package com.example.fintrack.llm

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [KeyPooledLlmProvider]: multi-API-key rotation, rate-limit
 * handling, daily budget enforcement, auth-failure exclusion, and cooldown
 * respect.
 */
class KeyPooledLlmProviderTest {

    private val key1 = LlmKeyEntry(id = "k1", apiKey = "sk-key1", label = "Key 1", enabled = true)
    private val key2 = LlmKeyEntry(id = "k2", apiKey = "sk-key2", label = "Key 2", enabled = true)
    private val key3 = LlmKeyEntry(id = "k3", apiKey = "sk-key3", label = "Key 3", enabled = true)

    private val activeKeys = listOf(key1, key2, key3)

    /**
     * A [ChatCompletionsProvider] substitute that never makes real HTTP calls.
     * Records the API key used for each call so the test can assert rotation.
     */
    private open class RecordingChatCompletionsProvider(
        keys: List<LlmKeyEntry>,
        private val failOnKey: String? = null,
        private val rateLimitAfterCalls: Int? = null,
        private val retryAfterOn429: Long? = null,
        private val schemaFailMessage: String? = null,
    ) : ChatCompletionsProvider(LlmConfig(keys = keys)) {

        val usedKeys = mutableListOf<String>()
        private val callCounts = mutableMapOf<String, Int>()

        override suspend fun completeWithKey(prompt: String, apiKey: String?): String {
            val key = apiKey ?: ""
            usedKeys.add(key)
            val count = callCounts.getOrDefault(key, 0) + 1
            callCounts[key] = count

            // A schema failure whose message does NOT carry the auth markers.
            if (schemaFailMessage != null) {
                throw LlmProviderException(
                    errorClass = LlmErrorClass.SCHEMA_VALIDATION_FAILED,
                    message = schemaFailMessage,
                )
            }

            if (key == failOnKey) {
                throw LlmProviderException(
                    errorClass = LlmErrorClass.SCHEMA_VALIDATION_FAILED,
                    message = "HTTP 401 Unauthorized: check your API key",
                )
            }

            if (rateLimitAfterCalls != null && count > rateLimitAfterCalls) {
                throw LlmProviderException(
                    errorClass = LlmErrorClass.RATE_LIMITED,
                    message = "Simulated 429 rate limit",
                    retryAfterMs = retryAfterOn429,
                )
            }

            return """{"choices":[{"message":{"content":"{\"isFinancial\":false,\"reason\":\"test\"}"}}]}"""
        }
    }

    private lateinit var provider: KeyPooledLlmProvider
    private lateinit var recordingProvider: RecordingChatCompletionsProvider

    @Before
    fun setUp() {
        recordingProvider = RecordingChatCompletionsProvider(keys = activeKeys)
        provider = KeyPooledLlmProvider(
            configProvider = { LlmConfig(keys = activeKeys) },
            chatCompletionsProvider = recordingProvider,
            dailyLimitPerKey = 1_000L,
            requestsPerMinutePerKey = 25.0,
        )
    }

    private fun pooledWith(provider: ChatCompletionsProvider, keys: List<LlmKeyEntry> = activeKeys) =
        KeyPooledLlmProvider(
            configProvider = { LlmConfig(keys = keys) },
            chatCompletionsProvider = provider,
            dailyLimitPerKey = 1_000L,
            requestsPerMinutePerKey = 25.0,
        )

    @Test
    fun `rotates through all keys in round-robin`() = runTest {
        repeat(3) { provider.complete("test prompt $it") }

        assertEquals(3, recordingProvider.usedKeys.size)
        assertEquals("sk-key1", recordingProvider.usedKeys[0])
        assertEquals("sk-key2", recordingProvider.usedKeys[1])
        assertEquals("sk-key3", recordingProvider.usedKeys[2])
    }

    @Test
    fun `skips auth-failed keys and rotates to valid ones`() = runTest {
        val failingProvider = RecordingChatCompletionsProvider(keys = activeKeys, failOnKey = "sk-key1")
        val p = pooledWith(failingProvider)

        val result = p.complete("test")
        assertNotNull(result)
        // key1 was tried and failed; the others must have been used
        assertTrue(failingProvider.usedKeys.any { it == "sk-key2" || it == "sk-key3" })
    }

    @Test
    fun `fails when all keys are auth-failed`() = runTest {
        val allFailProvider = object : ChatCompletionsProvider(LlmConfig(keys = activeKeys)) {
            override suspend fun completeWithKey(prompt: String, apiKey: String?): String {
                throw LlmProviderException(
                    errorClass = LlmErrorClass.SCHEMA_VALIDATION_FAILED,
                    message = "HTTP 401 Unauthorized: check your API key",
                )
            }
        }

        val p = pooledWith(allFailProvider)
        var caught: LlmProviderException? = null
        try {
            p.complete("test")
        } catch (e: LlmProviderException) {
            caught = e
        }
        assertNotNull("Expected LlmProviderException", caught)
        assertEquals(LlmErrorClass.SCHEMA_VALIDATION_FAILED, caught?.errorClass)
    }

    @Test
    fun `fails when no keys configured`() = runTest {
        val p = pooledWith(recordingProvider, keys = emptyList())
        var caught: LlmProviderException? = null
        try {
            p.complete("test")
        } catch (e: LlmProviderException) {
            caught = e
        }
        assertNotNull("Expected LlmProviderException", caught)
        assertEquals(LlmErrorClass.SCHEMA_VALIDATION_FAILED, caught?.errorClass)
    }

    @Test
    fun `ignores disabled keys`() = runTest {
        val disabledKeys = listOf(key1, key2.copy(enabled = false), key3)
        val recording = RecordingChatCompletionsProvider(keys = disabledKeys)
        val p = pooledWith(recording, keys = disabledKeys)

        p.complete("call 1")
        p.complete("call 2")

        assertFalse(recording.usedKeys.contains("sk-key2"))
        assertTrue(recording.usedKeys.contains("sk-key1"))
        assertTrue(recording.usedKeys.contains("sk-key3"))
    }

    @Test
    fun `daily budget exhaustion fails`() = runTest {
        val singleKeyProvider = RecordingChatCompletionsProvider(keys = listOf(key1))
        val p = KeyPooledLlmProvider(
            configProvider = { LlmConfig(keys = listOf(key1)) },
            chatCompletionsProvider = singleKeyProvider,
            dailyLimitPerKey = 2L,
            requestsPerMinutePerKey = 25.0,
        )

        p.complete("call 1")
        p.complete("call 2")

        var caught: LlmProviderException? = null
        try {
            p.complete("call 3")
        } catch (e: LlmProviderException) {
            caught = e
        }
        assertNotNull("Expected LlmProviderException", caught)
        assertEquals(LlmErrorClass.LOCAL_BUDGET_EXCEEDED, caught?.errorClass)
    }

    @Test
    fun `rotates on rate limit to an alternate key`() = runTest {
        // key1 rate-limits after 1 call; key2/key3 succeed.
        val rateLimitedProvider = RecordingChatCompletionsProvider(
            keys = activeKeys,
            rateLimitAfterCalls = 1,
        )
        val p = pooledWith(rateLimitedProvider)

        repeat(3) { p.complete("prompt $it") }

        // key1 got a 429 after its first call and was put in cooldown; the
        // remaining calls must have gone to key2/key3.
        val uses = rateLimitedProvider.usedKeys
        assertTrue(uses.contains("sk-key2"))
        assertTrue(uses.contains("sk-key3"))
    }

    @Test
    fun `resetDailyUsageForTesting clears state`() {
        provider.resetDailyUsageForTesting()
        val states = provider.getKeyRuntimeStates()
        assertEquals(3, states.size)
        states.forEach { state ->
            assertEquals(0L, state.requestsToday)
            assertFalse(state.isAuthFailed)
        }
    }

    @Test
    fun `getKeyRuntimeStates returns per-key status`() {
        val states = provider.getKeyRuntimeStates()
        assertEquals(3, states.size)
        assertEquals("k1", states[0].keyId)
        assertEquals("k2", states[1].keyId)
        assertEquals("k3", states[2].keyId)
        states.forEach { state ->
            assertEquals(1_000L, state.dailyLimit)
            assertFalse(state.isAuthFailed)
        }
    }

    @Test
    fun `retryAfter value is respected in cooldown`() = runBlocking {
        val rateLimitedProvider = RecordingChatCompletionsProvider(
            keys = activeKeys,
            rateLimitAfterCalls = 0,
            retryAfterOn429 = 5_000L,
        )
        val p = pooledWith(rateLimitedProvider)

        // Every key 429s on its first call with retryAfter=5000, so the pool
        // exhausts all rotation attempts and fails with RATE_LIMITED rather than hang.
        var caught: LlmProviderException? = null
        try {
            p.complete("prompt")
        } catch (e: LlmProviderException) {
            caught = e
        }
        assertNotNull("Expected RATE_LIMITED after all keys exhausted", caught)
        assertEquals(LlmErrorClass.RATE_LIMITED, caught?.errorClass)

        // The retryAfter (5000ms) must have been applied to key1's cooldown,
        // not the shorter 2500ms default.
        val k1State = p.getKeyRuntimeStates().first { it.keyId == "k1" }
        val cooldownRemaining = k1State.cooldownUntilEpochMs - System.currentTimeMillis()
        assertTrue("key1 should be in cooldown", k1State.cooldownUntilEpochMs > System.currentTimeMillis())
        assertTrue("key1 cooldown should reflect the 5000ms retryAfter", cooldownRemaining > 3_000L)
    }

    @Test
    fun `non-auth schema failure is rethrown not treated as auth failure`() = runTest {
        val schemaFailProvider = RecordingChatCompletionsProvider(
            keys = activeKeys,
            schemaFailMessage = "invalid field: weather",
        )
        val p = pooledWith(schemaFailProvider)

        var caught: LlmProviderException? = null
        try {
            p.complete("test")
        } catch (e: LlmProviderException) {
            caught = e
        }
        assertNotNull("Expected LlmProviderException", caught)
        assertEquals(LlmErrorClass.SCHEMA_VALIDATION_FAILED, caught?.errorClass)
        // A schema/content failure is NOT an auth failure: no key is marked auth-failed.
        assertTrue(p.getKeyRuntimeStates().none { it.isAuthFailed })
    }

    @Test
    fun `rotation stops after max attempts`() = runTest {
        val manyKeys = (1..6).map { i ->
            LlmKeyEntry(id = "k$i", apiKey = "sk-key$i", label = "Key $i", enabled = true)
        }
        // Every key rate-limits on its first call.
        val rateLimitedProvider = RecordingChatCompletionsProvider(
            keys = manyKeys,
            rateLimitAfterCalls = 0,
        )
        val p = pooledWith(rateLimitedProvider, keys = manyKeys)

        var caught: LlmProviderException? = null
        try {
            p.complete("test")
        } catch (e: LlmProviderException) {
            caught = e
        }
        assertNotNull("Expected LlmProviderException", caught)
        assertEquals(LlmErrorClass.RATE_LIMITED, caught?.errorClass)
        // MAX_ROTATION_ATTEMPTS=5: only 5 keys are tried before giving up, not all 6.
        assertEquals(5, rateLimitedProvider.usedKeys.size)
    }

    @Test
    fun `getTodayUtc maps epoch zero to UTC epoch day zero`() {
        assertEquals(0L, KeyPooledLlmProvider.getTodayUtc(0L))
    }

    @Test
    fun `getTodayUtc is stable across calls within the same day`() {
        val a = KeyPooledLlmProvider.getTodayUtc(1_700_000_000_000L)
        val b = KeyPooledLlmProvider.getTodayUtc(1_700_000_000_001L)
        assertEquals(a, b)
    }
}