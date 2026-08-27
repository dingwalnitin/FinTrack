package com.example.fintrack.domain.dedupe

import com.example.fintrack.domain.model.DedupeCluster
import com.example.fintrack.domain.model.DedupeClusterMember
import com.example.fintrack.domain.model.DedupeClusterStatus
import com.example.fintrack.domain.model.DedupeDecision
import com.example.fintrack.domain.model.DedupeDecisionKind
import com.example.fintrack.domain.model.DedupeVerdict
import com.example.fintrack.domain.model.EvidenceLink
import com.example.fintrack.domain.model.EvidenceLinkKind
import com.example.fintrack.domain.model.EvidenceLinkOrigin
import java.time.Instant
import java.util.UUID

/**
 * P09 dedup service. Pure logic, no Room dependency — the data layer
 * supplies a [DedupeSink] implementation.
 *
 * Guarantees:
 *  - Idempotent: re-running with the same inputs returns the same clusters.
 *  - High-confidence merges auto-apply; ambiguous pairs go to REVIEW
 *    (P09 #4: never auto-merge legitimate same-value purchases close in
 *    time — the engine deliberately gives timestamp proximity a low weight
 *    so the verdict lands in REVIEW band).
 *  - User decisions about merge/split are first-class and durable: every
 *    call to [recordDecision] appends a decision; the most-recent row for
 *    (eventId, kind) is authoritative, but history is preserved.
 */
class DedupeService(
    private val sink: DedupeSink,
    private val clock: () -> Instant = Instant::now,
) {

    /**
     * Score a pair, decide a verdict, and persist the resulting cluster
     * (or update an existing one with the same identity). Returns the
     * cluster after persistence so the caller can show Review UI for
     * [DedupeVerdict.REVIEW] verdicts.
     */
    suspend fun clusterPair(a: Candidate, b: Candidate): DedupeCluster {
        val score = DedupeEngine.scorePair(a, b)
        val identity = DedupeEngine.clusterIdentity(a, b)
        val existing = sink.findClusterByIdentity(identity)
        val now = clock()
        val reasons = score.signals
            .filterValues { it > 0.0 }
            .map { (k, v) -> "$k=${"%.2f".format(v)}" }
        val status = when (score.verdict) {
            DedupeVerdict.AUTO_MERGE -> DedupeClusterStatus.MERGED
            DedupeVerdict.REVIEW -> DedupeClusterStatus.REVIEW
            DedupeVerdict.REJECT -> DedupeClusterStatus.REJECTED
        }
        val members = listOf(
            DedupeEngine.toMember(a.eventId, score),
            DedupeEngine.toMember(b.eventId, score),
        )
        val cluster = if (existing == null) {
            DedupeCluster(
                id = UUID.randomUUID().toString(),
                status = status,
                verdict = score.verdict,
                topScore = score.score,
                reasons = reasons,
                canonicalEventId = if (status == DedupeClusterStatus.MERGED) a.eventId else null,
                members = members,
                createdAt = now,
                updatedAt = now,
            )
        } else {
            existing.copy(
                status = status,
                verdict = score.verdict,
                topScore = score.score,
                reasons = reasons,
                canonicalEventId = if (status == DedupeClusterStatus.MERGED) a.eventId else existing.canonicalEventId,
                members = members,
                updatedAt = now,
            )
        }
        sink.upsertCluster(identity, cluster)
        // For MERGED, link the secondary event's raw SMS to the primary so
        // both messages are visible in the transaction detail (P09 #3).
        if (status == DedupeClusterStatus.MERGED) {
            // The caller passes the raw ids it already knows about via
            // [attachSecondaryEvidence] — we don't load raw_sms here.
        }
        return cluster
    }

    /**
     * Promote a raw SMS to a primary evidence link for an event. Idempotent:
     * repeat calls are no-ops because the linkIdentity unique index in Room
     * rejects duplicates.
     */
    suspend fun attachPrimaryEvidence(eventId: String, rawSmsId: String): EvidenceLink {
        val link = EvidenceLink(
            id = UUID.randomUUID().toString(),
            eventId = eventId,
            rawSmsId = rawSmsId,
            linkKind = EvidenceLinkKind.RAW_PRIMARY,
            origin = EvidenceLinkOrigin.PROMOTED,
            reason = null,
            createdAt = clock(),
        )
        sink.upsertEvidenceLink(link, linkIdentity = linkIdentityFor(link))
        return link
    }

    /**
     * Attach additional raw SMS rows to an existing event. Used when a
     * confirmation / follow-up SMS arrives, or when user-merged events
     * pull their evidence together.
     */
    suspend fun attachSecondaryEvidence(
        eventId: String,
        rawSmsId: String,
        origin: EvidenceLinkOrigin = EvidenceLinkOrigin.AUTO,
        reason: String? = null,
    ): EvidenceLink {
        val link = EvidenceLink(
            id = UUID.randomUUID().toString(),
            eventId = eventId,
            rawSmsId = rawSmsId,
            linkKind = EvidenceLinkKind.RAW_SECONDARY,
            origin = origin,
            reason = reason,
            createdAt = clock(),
        )
        sink.upsertEvidenceLink(link, linkIdentity = linkIdentityFor(link))
        return link
    }

    /**
     * Record a user/system decision. Append-only: every call writes a new
     * row. The most-recent row per (eventId, kind) wins; older rows stay
     * auditable.
     */
    suspend fun recordDecision(
        eventId: String,
        kind: DedupeDecisionKind,
        actor: String,
        sourceKind: String,
        sourceVersion: String,
        clusterId: String? = null,
        reason: String? = null,
    ): DedupeDecision {
        val decision = DedupeDecision(
            id = UUID.randomUUID().toString(),
            eventId = eventId,
            clusterId = clusterId,
            kind = kind,
            actor = actor,
            sourceKind = sourceKind,
            sourceVersion = sourceVersion,
            reason = reason,
            appliedAt = clock(),
        )
        sink.appendDecision(decision)
        return decision
    }

    /** Stable identity for an EvidenceLink. Used by the Room IGNORE-on-conflict writes. */
    fun linkIdentityFor(link: EvidenceLink): String {
        val raw = "${link.eventId}|${link.rawSmsId}|${link.linkKind.name}"
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}

/**
 * Persistence interface implemented by the data layer. DedupeService has
 * no knowledge of Room; the data layer maps domain writes to entity
 * writes inside Room @Transaction methods.
 */
interface DedupeSink {
    suspend fun findClusterByIdentity(clusterIdentity: String): DedupeCluster?
    suspend fun upsertCluster(clusterIdentity: String, cluster: DedupeCluster)
    suspend fun upsertEvidenceLink(link: EvidenceLink, linkIdentity: String)
    suspend fun appendDecision(decision: DedupeDecision)
}
