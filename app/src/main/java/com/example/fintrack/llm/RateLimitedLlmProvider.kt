package com.example.fintrack.llm

/**
 * Decorator that applies a [TokenBucketRateLimiter] to any [LlmProvider].
 *
 * Every [complete] call first acquires a token, so ALL LLM traffic through
 * this wrapper is throttled to the configured rate (e.g. 3 req/sec steady
 * with burst of 10). Exponential backoff for retries is handled separately by
 * the job store ([RetryPolicy]); this class only shapes inbound request rate.
 */
class RateLimitedLlmProvider(
    private val delegate: LlmProvider,
    private val rateLimiter: TokenBucketRateLimiter = TokenBucketRateLimiter(),
) : LlmProvider {

    override val providerId: String get() = delegate.providerId
    override val modelId: String get() = delegate.modelId

    override suspend fun complete(prompt: String): String {
        rateLimiter.acquire()
        return delegate.complete(prompt)
    }
}