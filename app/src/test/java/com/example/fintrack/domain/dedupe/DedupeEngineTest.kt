package com.example.fintrack.domain.dedupe

import com.example.fintrack.domain.model.DedupeVerdict
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * P09 acceptance tests:
 *  - engine verdicts match the false-merge / false-split corpus thresholds
 *  - clustering is idempotent (same inputs -> same cluster identity, no dupes)
 *  - ambiguous pairs land in REVIEW, never auto-merged
 *  - user decisions are durable and append-only
 *  - evidence links are idempotent via stable linkIdentity
 */
class DedupeEngineTest {

    private fun candidate(
        eventId: String,
        amountMinor: Long? = 25_000L,
        refId: String? = null,
        rail: String? = "UPI",
        accountId: String? = "acc1",
        merchant: String? = "swiggy",
        cardMask: String? = null,
        atMs: Long = 1_700_000_000_000L,
        currency: String? = "INR",
        direction: String? = "DEBIT",
    ) = Candidate(
        eventId = eventId, amountMinor = amountMinor, currencyCode = currency,
        direction = direction, rail = rail, accountId = accountId, refId = refId,
        counterpartyNormalized = merchant, cardMask = cardMask, occurredAtEpochMs = atMs,
    )

    // ---- corpus regression ----

    @Test
    fun `corpus verdicts all match engine output`() {
        for (c in DedupeCorpus.ALL) {
            val result = DedupeEngine.scorePair(c.a, c.b)
            assertEquals("case ${c.id}: ${c.notes}", c.expected, result.verdict)
        }
    }

    @Test
    fun `corpus identities are order-independent`() {
        for (c in DedupeCorpus.ALL) {
            val ab = DedupeEngine.clusterIdentity(c.a, c.b)
            val ba = DedupeEngine.clusterIdentity(c.b, c.a)
            assertEquals("identity must be symmetric for ${c.id}", ab, ba)
        }
    }

    @Test
    fun `different pairs produce different cluster identities`() {
        val a = candidate("eA", refId = "ref-1")
        val b = candidate("eB", refId = "ref-1")
        val c = candidate("eC", refId = "ref-2")
        assertNotEquals(DedupeEngine.clusterIdentity(a, b), DedupeEngine.clusterIdentity(a, c))
    }

    // ---- threshold semantics ----

    @Test
    fun `same ref id with matching context auto-merges`() {
        val a = candidate("eA", refId = "418293746512")
        val b = candidate("eB", refId = "418293746512")
        assertEquals(DedupeVerdict.AUTO_MERGE, DedupeEngine.scorePair(a, b).verdict)
    }

    @Test
    fun `legitimate same-value purchases close together never auto-merge`() {
        // Two INR 99 coffees 4 minutes apart, no ref id — canonical false-merge trap.
        val a = candidate("eA", amountMinor = 9_900L, merchant = "blue tokai", cardMask = "1234")
        val b = candidate("eB", amountMinor = 9_900L, merchant = "blue tokai", cardMask = "1234", atMs = a.occurredAtEpochMs!! + 240_000L)
        val result = DedupeEngine.scorePair(a, b)
        assertTrue(
            "expected REVIEW or REJECT, got ${result.verdict}",
            result.verdict != DedupeVerdict.AUTO_MERGE,
        )
    }

    @Test
    fun `mismatched ref ids reject`() {
        val a = candidate("eA", refId = "111")
        val b = candidate("eB", refId = "222")
        assertEquals(DedupeVerdict.REJECT, DedupeEngine.scorePair(a, b).verdict)
    }

    @Test
    fun `unknown fields stay unknown and never fabricate matches`() {
        val a = candidate("eA", amountMinor = null, refId = null, rail = null, accountId = null, merchant = null, atMs = 0L)
        val b = candidate("eB", amountMinor = null, refId = null, rail = null, accountId = null, merchant = null, atMs = 0L)
        // Zero timestamps are 56 years apart from each other only if unequal;
        // here they are equal so ts fires — assert on verdict + no identity signals.
        val result = DedupeEngine.scorePair(a, b)
        assertEquals(DedupeVerdict.REJECT, result.verdict)
        assertTrue(
            "no identity signal may fire on unknown fields",
            listOf("ref", "amount", "rail", "account", "merchant", "card").all { result.signals[it] == 0.0 },
        )
    }

    // ---- service idempotency + durability ----

    private class FakeSink : com.example.fintrack.domain.dedupe.DedupeSink {
        val clustersByIdentity = ConcurrentHashMap<String, com.example.fintrack.domain.model.DedupeCluster>()
        val links = ConcurrentHashMap<String, com.example.fintrack.domain.model.EvidenceLink>()
        val decisions = mutableListOf<com.example.fintrack.domain.model.DedupeDecision>()

        override suspend fun findClusterByIdentity(clusterIdentity: String) =
            clustersByIdentity[clusterIdentity]

        override suspend fun upsertCluster(clusterIdentity: String, cluster: com.example.fintrack.domain.model.DedupeCluster) {
            clustersByIdentity[clusterIdentity] = cluster
        }

        override suspend fun upsertEvidenceLink(link: com.example.fintrack.domain.model.EvidenceLink, linkIdentity: String) {
            links.putIfAbsent(linkIdentity, link)
        }

        override suspend fun appendDecision(decision: com.example.fintrack.domain.model.DedupeDecision) {
            decisions += decision
        }
    }

    @Test
    fun `re-clustering the same pair is idempotent`() = runTest {
        val sink = FakeSink()
        val svc = com.example.fintrack.domain.dedupe.DedupeService(sink, clock = { Instant.EPOCH })
        val a = candidate("eA", refId = "ref-9")
        val b = candidate("eB", refId = "ref-9")

        val first = svc.clusterPair(a, b)
        val second = svc.clusterPair(a, b)

        assertEquals(1, sink.clustersByIdentity.size)
        assertEquals(first.id, second.id)
        assertEquals(first.createdAt, second.updatedAt) // original creation preserved
    }

    @Test
    fun `ambiguous pair routes to review status`() = runTest {
        val sink = FakeSink()
        val svc = com.example.fintrack.domain.dedupe.DedupeService(sink, clock = { Instant.EPOCH })
        val a = candidate("eA", amountMinor = 9_900L, merchant = "blue tokai", cardMask = "1234")
        val b = candidate("eB", amountMinor = 9_900L, merchant = "blue tokai", cardMask = "1234")
        val cluster = svc.clusterPair(a, b)
        assertEquals(com.example.fintrack.domain.model.DedupeClusterStatus.REVIEW, cluster.status)
        assertEquals(null, cluster.canonicalEventId)
    }

    @Test
    fun `evidence links are idempotent on repeat attach`() = runTest {
        val sink = FakeSink()
        val svc = com.example.fintrack.domain.dedupe.DedupeService(sink, clock = { Instant.EPOCH })
        svc.attachPrimaryEvidence("evt-1", "sms-1")
        svc.attachPrimaryEvidence("evt-1", "sms-1") // duplicate identity -> ignored by sink
        assertEquals(1, sink.links.size)
    }

    @Test
    fun `user merge decision is durable and append-only`() = runTest {
        val sink = FakeSink()
        val svc = com.example.fintrack.domain.dedupe.DedupeService(sink, clock = { Instant.EPOCH })
        svc.recordDecision("evt-1", com.example.fintrack.domain.model.DedupeDecisionKind.MERGE, actor = "USER", sourceKind = "USER_CORRECTION", sourceVersion = "user-v1", reason = "same purchase")
        svc.recordDecision("evt-1", com.example.fintrack.domain.model.DedupeDecisionKind.UNMERGE, actor = "USER", sourceKind = "USER_CORRECTION", sourceVersion = "user-v1", reason = "changed my mind")
        assertEquals(2, sink.decisions.size)
        // History preserved; most recent wins by appliedAt ordering.
        assertEquals(com.example.fintrack.domain.model.DedupeDecisionKind.UNMERGE, sink.decisions.last().kind)
    }

    @Test
    fun `keep-duplicate decision records explicit user acceptance`() = runTest {
        val sink = FakeSink()
        val svc = com.example.fintrack.domain.dedupe.DedupeService(sink, clock = { Instant.EPOCH })
        val d = svc.recordDecision(
            "evt-2", com.example.fintrack.domain.model.DedupeDecisionKind.KEEP_DUPLICATE,
            actor = "USER", sourceKind = "USER_CORRECTION", sourceVersion = "user-v1",
            reason = "two legit coffees",
        )
        assertNotNull(d)
        assertEquals(com.example.fintrack.domain.model.DedupeDecisionKind.KEEP_DUPLICATE, sink.decisions.single().kind)
    }
}
