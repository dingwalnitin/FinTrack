package com.example.fintrack.domain

import com.example.fintrack.domain.service.PayeeIdentity
import com.example.fintrack.domain.service.PayeeRuleResolver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 13 (A) — pure JVM tests for payee identity + rule resolution.
 */
class PayeeCategoryRuleServiceTest {

    // ---- identity normalization ----

    @Test
    fun `normalize lowercases trims and collapses whitespace`() {
        assertEquals("swiggy", PayeeIdentity.normalize("  Swiggy  "))
        assertEquals("dominos pizza", PayeeIdentity.normalize("  Dominos   Pizza  "))
    }

    @Test
    fun `identityHash prefers VPA over name`() {
        val hash = PayeeIdentity.identityHash("swiggy@ybl", "Swiggy India Pvt Ltd")
        val hash2 = PayeeIdentity.identityHash("SWIGGY@YBL", "Swiggy India Pvt Ltd")
        // VPA is case-insensitively normalized, so hashes match.
        assertEquals(hash, hash2)
    }

    @Test
    fun `identityHash differentiates distinct VPAs`() {
        val a = PayeeIdentity.identityHash("a@ybl", "Swiggy")
        val b = PayeeIdentity.identityHash("b@ybl", "Swiggy")
        assertNotEquals(a, b)
    }

    @Test
    fun `identityHash falls back to name when no vpa`() {
        val a = PayeeIdentity.identityHash(null, "Zomato")
        val b = PayeeIdentity.identityHash(null, "  zomato ")
        assertEquals(a, b)
    }

    @Test
    fun `sha256 is 64 hex chars`() {
        assertEquals(64, PayeeIdentity.sha256("anything").length)
        assertTrue(PayeeIdentity.sha256("x").matches(Regex("[0-9a-f]{64}")))
    }

    // ---- resolver ----

    @Test
    fun `resolver returns null when no rule`() {
        val r = PayeeRuleResolver(emptyMap())
        assertNull(r.resolve("a@ybl", "Swiggy"))
        assertFalse(r.hasRule("a@ybl", "Swiggy"))
    }

    @Test
    fun `resolver applies rule by vpa`() {
        val hash = PayeeIdentity.identityHash("swiggy@ybl", "Swiggy")
        val r = PayeeRuleResolver(mapOf(hash to "cat-food"))
        assertEquals("cat-food", r.resolve("swiggy@ybl", "Swiggy"))
        assertTrue(r.hasRule("swiggy@ybl", "Swiggy"))
    }

    @Test
    fun `resolver applies rule by name fallback`() {
        val hash = PayeeIdentity.identityHash(null, "Zomato")
        val r = PayeeRuleResolver(mapOf(hash to "cat-food"))
        assertEquals("cat-food", r.resolve(null, "Zomato"))
    }

    @Test
    fun `rule survives case differences`() {
        val hash = PayeeIdentity.identityHash("swiggy@ybl", "Swiggy")
        val r = PayeeRuleResolver(mapOf(hash to "cat-food"))
        assertEquals("cat-food", r.resolve("SWIGGY@YBL", "Swiggy India"))
    }
}
