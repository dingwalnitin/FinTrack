package com.example.fintrack.domain.dedupe

import com.example.fintrack.domain.model.DedupeVerdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Stage 12 P26 #4 — dedup evaluation harness.
 *
 * False-merge / false-split metrics over the adversarial corpus. The corpus
 * deliberately includes same-value-close-time purchases that must NOT
 * auto-merge (they route to REVIEW), and strong-ref pairs that must merge.
 */
class DedupeEvaluationHarnessTest {

    @Test
    fun `false merge rate is zero on adversarial same-value-close-time fixtures`() {
        val falseMerges = DedupeCorpus.ALL.count { c ->
            c.expected == DedupeVerdict.REVIEW &&
                DedupeEngine.scorePair(c.a, c.b).verdict == DedupeVerdict.AUTO_MERGE
        }
        assertEquals("no false merges allowed", 0, falseMerges)
    }

    @Test
    fun `false split rate is zero on strong-ref fixtures`() {
        val falseSplits = DedupeCorpus.ALL.count { c ->
            c.expected == DedupeVerdict.AUTO_MERGE &&
                DedupeEngine.scorePair(c.a, c.b).verdict != DedupeVerdict.AUTO_MERGE
        }
        assertEquals("no false splits allowed", 0, falseSplits)
    }

    @Test
    fun `review band catches ambiguous pairs`() {
        val reviewCount = DedupeCorpus.ALL.count { c ->
            c.expected == DedupeVerdict.REVIEW &&
                DedupeEngine.scorePair(c.a, c.b).verdict == DedupeVerdict.REVIEW
        }
        val totalReview = DedupeCorpus.ALL.count { it.expected == DedupeVerdict.REVIEW }
        assertTrue("review band must catch all ambiguous pairs", reviewCount == totalReview)
    }

    @Test
    fun `adversarial same-value-close-time fixtures are present`() {
        val adversarial = DedupeCorpus.ALL.filter { c ->
            c.a.amountMinor == c.b.amountMinor &&
                c.a.occurredAtEpochMs != null && c.b.occurredAtEpochMs != null &&
                kotlin.math.abs(c.a.occurredAtEpochMs - c.b.occurredAtEpochMs) < 300_000 &&
                c.expected == DedupeVerdict.REVIEW
        }
        // rv-same-amount-coffee (4 min apart, same amount, same card) qualifies.
        assertTrue("need adversarial same-value-close-time fixtures", adversarial.isNotEmpty())
        // All such fixtures must route to REVIEW, never AUTO_MERGE.
        adversarial.forEach { c ->
            assertEquals("${c.id} must route to REVIEW", DedupeVerdict.REVIEW,
                DedupeEngine.scorePair(c.a, c.b).verdict)
        }
    }

    @Test
    fun `cluster identity is stable and symmetric`() {
        for (c in DedupeCorpus.ALL) {
            val ab = DedupeEngine.clusterIdentity(c.a, c.b)
            val ba = DedupeEngine.clusterIdentity(c.b, c.a)
            assertEquals("identity must be symmetric for ${c.id}", ab, ba)
        }
    }

    @Test
    fun `score is bounded to 0-1`() {
        for (c in DedupeCorpus.ALL) {
            val result = DedupeEngine.scorePair(c.a, c.b)
            assertTrue("score ${result.score} out of bounds for ${c.id}",
                result.score in 0.0..1.0)
        }
    }
}