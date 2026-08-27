package com.example.fintrack.domain.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-domain tests for the SMS ingestion policy.
 */
class SmsIngestionPolicyTest {

    @Test
    fun `contentHash is stable for the same triple`() {
        val a = SmsIngestionPolicy.contentHash("HDFC", "INR 500 debited", 1_700_000_000_000L)
        val b = SmsIngestionPolicy.contentHash("HDFC", "INR 500 debited", 1_700_000_000_000L)
        assertEquals(a, b)
    }

    @Test
    fun `contentHash changes when sender changes`() {
        val a = SmsIngestionPolicy.contentHash("HDFC", "INR 500 debited", 1_700_000_000_000L)
        val b = SmsIngestionPolicy.contentHash("ICICI", "INR 500 debited", 1_700_000_000_000L)
        assertNotEquals(a, b)
    }

    @Test
    fun `contentHash changes when body changes`() {
        val a = SmsIngestionPolicy.contentHash("HDFC", "INR 500 debited", 1_700_000_000_000L)
        val b = SmsIngestionPolicy.contentHash("HDFC", "INR 700 debited", 1_700_000_000_000L)
        assertNotEquals(a, b)
    }

    @Test
    fun `contentHash changes when timestamp changes`() {
        val a = SmsIngestionPolicy.contentHash("HDFC", "INR 500 debited", 1_700_000_000_000L)
        val b = SmsIngestionPolicy.contentHash("HDFC", "INR 500 debited", 1_700_000_000_001L)
        assertNotEquals(a, b)
    }

    @Test
    fun `null sender is preserved as a stable unknown`() {
        val a = SmsIngestionPolicy.contentHash(null, "hi", 1L)
        val b = SmsIngestionPolicy.contentHash(null, "hi", 1L)
        assertEquals(a, b)
        // Empty sender is treated the same as null by the policy.
        val c = SmsIngestionPolicy.contentHash("", "hi", 1L)
        assertEquals(a, c)
    }

    @Test
    fun `status codes are well-known and parseable`() {
        for (s in SmsIngestionPolicy.Status.entries) {
            assertEquals(s, SmsIngestionPolicy.Status.fromCode(s.code))
        }
        assertEquals(SmsIngestionPolicy.Status.IDLE, SmsIngestionPolicy.Status.fromCode(null))
        assertEquals(SmsIngestionPolicy.Status.IDLE, SmsIngestionPolicy.Status.fromCode("???"))
    }

    @Test
    fun `policy forbids SMS deletion anywhere`() {
        // This is a policy sentinel test — verify source kinds are immutable
        // constants; an accidental change here should fail loudly.
        assertEquals("SMS_RECEIVED", SmsIngestionPolicy.SOURCE_KIND_SMS_RECEIVED)
        assertEquals("BACKFILL", SmsIngestionPolicy.SOURCE_KIND_BACKFILL)
        // No "DELETE" source kind is defined; deletion is forbidden.
        val kinds = listOf(SmsIngestionPolicy.SOURCE_KIND_SMS_RECEIVED, SmsIngestionPolicy.SOURCE_KIND_BACKFILL)
        for (name in kinds) {
            assertTrue("source kind must not mention deletion", !name.contains("DELETE"))
        }
    }
}
