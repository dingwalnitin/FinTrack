package com.example.fintrack.llm

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Token-bucket rate limiter for LLM API calls.
 *
 * The bucket fills at [tokensPerSecond] and holds at most [maxTokens] tokens.
 * Each call to [acquire] consumes one token, blocking until one is available.
 * Bursts are bounded by [maxTokens].
 *
 * Supports fractional tokens per second (e.g. 25 requests / 60 seconds = 0.416667 tokens/sec).
 * Thread-safe via mutex.
 */
class TokenBucketRateLimiter(
    /** Steady-state tokens per second (e.g. 25.0 / 60.0 for 25 RPM). */
    private val tokensPerSecond: Double = 25.0 / 60.0,
    /** Maximum burst capacity (e.g. 1.0 for strictly steady, or 2.0 conservative burst). */
    private val maxTokens: Double = 1.0,
) {
    /** Secondary constructor for integer arguments (backward compatibility). */
    constructor(tokensPerSecond: Int, maxTokens: Int) : this(
        tokensPerSecond = tokensPerSecond.toDouble(),
        maxTokens = maxTokens.toDouble(),
    )

    /** Current token count (may be fractional). */
    private var tokens: Double = maxTokens
    private var lastRefillEpochMs: Long = System.currentTimeMillis()
    private val mutex = Mutex()

    /**
     * Acquire one token, suspending until one is available (blocking call
     * pattern — use from a background coroutine, never from the UI thread).
     */
    suspend fun acquire() {
        while (true) {
            val waitMs = mutex.withLock {
                refill()
                if (tokens >= 1.0) {
                    tokens -= 1.0
                    return
                }
                val needed = 1.0 - tokens
                ((needed / tokensPerSecond) * 1000.0).toLong().coerceAtLeast(50L)
            }
            delay(waitMs)
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

    /** Returns the milliseconds until at least 1.0 token is available (0 if already available). */
    suspend fun timeUntilNextTokenMs(): Long = mutex.withLock {
        refill()
        if (tokens >= 1.0) {
            0L
        } else {
            val needed = 1.0 - tokens
            ((needed / tokensPerSecond) * 1000.0).toLong().coerceAtLeast(0L)
        }
    }

    /** Current token count (approximate, for UI display). */
    suspend fun availableTokens(): Int = mutex.withLock {
        refill()
        tokens.toInt().coerceAtLeast(0)
    }

    /** Reset — for testing. */
    fun reset() {
        tokens = maxTokens
        lastRefillEpochMs = System.currentTimeMillis()
    }

    private fun refill() {
        val now = System.currentTimeMillis()
        val elapsed = now - lastRefillEpochMs
        if (elapsed > 0) {
            val added = (elapsed.toDouble() / 1000.0) * tokensPerSecond
            tokens = (tokens + added).coerceAtMost(maxTokens)
            lastRefillEpochMs = now
        }
    }
}