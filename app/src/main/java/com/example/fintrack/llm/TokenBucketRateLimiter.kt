package com.example.fintrack.llm

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Token-bucket rate limiter for LLM API calls.
 *
 * The bucket fills at [tokensPerSecond] and holds at most [maxTokens] tokens.
 * Each call to [acquire] consumes one token, blocking until one is available.
 * Bursts are bounded by [maxTokens] (e.g. 60 tokens with 1/sec fill = 60
 * burst, then 1/sec steady).
 *
 * Thread-safe via mutex.
 */
class TokenBucketRateLimiter(
    /** Steady-state tokens per second (e.g. 3 for 3 RPM with 60s refill). */
    private val tokensPerSecond: Int = 3,
    /** Maximum burst capacity (e.g. 10 for a burst of 10 then steady). */
    private val maxTokens: Int = 10,
) {
    /** Current token count (may be fractional). */
    private var tokens: Double = maxTokens.toDouble()
    private var lastRefillEpochMs: Long = System.currentTimeMillis()
    private val mutex = Mutex()

    /**
     * Acquire one token, suspending until one is available (blocking call
     * pattern — use from a background coroutine, never from the UI thread).
     */
    suspend fun acquire() {
        mutex.withLock {
            refill()
            while (tokens < 1.0) {
                // Unlock while waiting so other coroutines can refill
                val waitMs = ((1.0 - tokens) / tokensPerSecond * 1000).toLong().coerceAtLeast(50)
                mutex.unlock()
                delay(waitMs)
                mutex.lock()
                refill()
            }
            tokens -= 1.0
        }
    }

    /** Try to acquire without blocking. Returns true if a token was available. */
    suspend fun tryAcquire(): Boolean {
        mutex.withLock {
            refill()
            if (tokens >= 1.0) {
                tokens -= 1.0
                return true
            }
            return false
        }
    }

    /** Current token count (approximate, for UI display). */
    suspend fun availableTokens(): Int = mutex.withLock { tokens.toInt().coerceAtLeast(0) }

    /** Reset — for testing. */
    fun reset() {
        tokens = maxTokens.toDouble()
        lastRefillEpochMs = System.currentTimeMillis()
    }

    private fun refill() {
        val now = System.currentTimeMillis()
        val elapsed = now - lastRefillEpochMs
        if (elapsed > 0) {
            val added = (elapsed.toDouble() / 1000.0) * tokensPerSecond
            tokens = (tokens + added).coerceAtMost(maxTokens.toDouble())
            lastRefillEpochMs = now
        }
    }
}