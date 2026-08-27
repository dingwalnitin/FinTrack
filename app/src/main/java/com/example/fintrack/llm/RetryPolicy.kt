package com.example.fintrack.llm

import kotlin.random.Random

/**
 * P07: exponential backoff with bounded jitter. Retryable classes back off;
 * permanent classes never retry.
 *
 * Jitter bounds are chosen so delays stay strictly increasing across attempts
 * for the same error class (deterministic ordering, testable): the max of
 * attempt N is below the min of attempt N+1 given a 2x base and jitter in
 * [MIN_JITTER, MAX_JITTER].
 */
object RetryPolicy {

    const val BASE_DELAY_MS = 2_000L
    const val MAX_DELAY_MS = 5 * 60_000L

    /** Jitter window: delay * [MIN_JITTER, MAX_JITTER]. */
    const val MIN_JITTER = 0.8
    const val MAX_JITTER = 1.5

    /**
     * Delay before the next attempt, or null when the error is permanent.
     * [attempt] is 0-based (0 = first retry after the initial failure).
     *
     * - RATE_LIMITED with an explicit [retryAfterMs] returns that value
     *   verbatim (provider contract — no jitter).
     * - All other retryable errors get an exponential backoff with bounded
     *   jitter, capped at [MAX_DELAY_MS].
     */
    fun nextDelayMs(errorClass: LlmErrorClass, attempt: Int, retryAfterMs: Long? = null): Long? {
        if (!errorClass.isRetryable) return null
        if (retryAfterMs != null && errorClass == LlmErrorClass.RATE_LIMITED) {
            return retryAfterMs.coerceAtMost(MAX_DELAY_MS)
        }
        val exp = BASE_DELAY_MS shl attempt.coerceIn(0, 7)
        val jittered = (exp * jitterFactor()).toLong().coerceAtMost(MAX_DELAY_MS)
        return jittered.coerceAtLeast(1)
    }

    /** Deterministic-with-random jitter factor in [MIN_JITTER, MAX_JITTER]. */
    private fun jitterFactor(): Double =
        MIN_JITTER + Random.nextDouble() * (MAX_JITTER - MIN_JITTER)
}
