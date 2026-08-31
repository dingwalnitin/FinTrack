package com.example.fintrack.llm

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenBucketRateLimiterTest {

    // Regression test for the fixed crash: acquire() used to manually
    // unlock()/lock() the mutex around delay(), so cancelling a coroutine
    // suspended in that delay left the mutex in a corrupted lock state and
    // the next withLock threw "Mutex is not locked".
    @Test
    fun `cancelling acquire mid-delay does not corrupt the mutex`() = runBlocking {
        val limiter = TokenBucketRateLimiter(tokensPerSecond = 1, maxTokens = 1)
        limiter.tryAcquire() // drain the single token so the next acquire() must wait.

        val waiter = CoroutineScope(Dispatchers.Default).launch {
            limiter.acquire()
        }
        waiter.cancelAndJoin() // cancel while presumably suspended in delay()

        // The mutex must still be usable afterward — no leaked lock state.
        limiter.reset()
        assertTrue(limiter.tryAcquire())
    }

    @Test
    fun `burst up to maxTokens all succeed immediately`() = runTest {
        val limiter = TokenBucketRateLimiter(tokensPerSecond = 3, maxTokens = 10)
        repeat(10) {
            assertTrue("burst token $it should be available", limiter.tryAcquire())
        }
        assertFalse(limiter.tryAcquire())
    }

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

    @Test
    fun `timeUntilNextTokenMs is zero when token available`() = runBlocking {
        val limiter = TokenBucketRateLimiter(tokensPerSecond = 100, maxTokens = 1)
        assertEquals(0L, limiter.timeUntilNextTokenMs())
    }

    @Test
    fun `timeUntilNextTokenMs is positive when bucket empty`() = runBlocking {
        val limiter = TokenBucketRateLimiter(tokensPerSecond = 2, maxTokens = 1)
        limiter.tryAcquire() // drain the single token
        val wait = limiter.timeUntilNextTokenMs()
        assertTrue("expected positive wait, got $wait", wait > 0L)
    }

    @Test
    fun `timeUntilNextTokenMs returns zero after reset`() = runBlocking {
        val limiter = TokenBucketRateLimiter(tokensPerSecond = 2, maxTokens = 1)
        limiter.tryAcquire()
        limiter.reset()
        assertEquals(0L, limiter.timeUntilNextTokenMs())
    }

    @Test
    fun `fractional refill accumulates tokens over time`() = runBlocking {
        // Uses runBlocking (real time) because runTest virtual time can't
        // advance System.currentTimeMillis() used inside the rate limiter.
        val limiter = TokenBucketRateLimiter(tokensPerSecond = 60.0, maxTokens = 1.0)
        assertTrue(limiter.tryAcquire()) // consume the one token

        val wait = limiter.timeUntilNextTokenMs()
        Thread.sleep(wait + 20)
        assertTrue("token should have refilled after delay", limiter.tryAcquire())
    }

    @Test
    fun `availableTokens reflects fractional refill after consumption`() = runBlocking {
        val limiter = TokenBucketRateLimiter(tokensPerSecond = 60.0, maxTokens = 1.0)
        limiter.tryAcquire()
        // Right after consuming, no full token available.
        assertEquals(0, limiter.availableTokens())
    }
}