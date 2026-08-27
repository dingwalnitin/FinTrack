package com.example.fintrack.llm

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenBucketRateLimiterTest {

    @Test
    fun `tryAcquire succeeds while tokens available`() = runTest {
        val limiter = TokenBucketRateLimiter(tokensPerSecond = 100, maxTokens = 5)
        repeat(5) {
            assertTrue("token $it should be available", limiter.tryAcquire())
        }
        // Bucket now empty — no token available immediately.
        assertFalse(limiter.tryAcquire())
    }

    @Test
    fun `availableTokens starts at max`() = runBlocking {
        val limiter = TokenBucketRateLimiter(tokensPerSecond = 100, maxTokens = 7)
        assertEquals(7, limiter.availableTokens())
    }

    @Test
    fun `reset restores full bucket`() = runTest {
        val limiter = TokenBucketRateLimiter(tokensPerSecond = 100, maxTokens = 4)
        limiter.tryAcquire()
        assertTrue(limiter.availableTokens() < 4)
        limiter.reset()
        assertEquals(4, limiter.availableTokens())
    }
}