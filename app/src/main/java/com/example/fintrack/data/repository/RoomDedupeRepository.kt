package com.example.fintrack.data.repository

import com.example.fintrack.data.db.DedupeClusterEntity
import com.example.fintrack.data.db.DedupeClusterMemberEntity
import com.example.fintrack.data.db.DedupeDecisionEntity
import com.example.fintrack.data.db.EvidenceLinkEntity
import com.example.fintrack.data.db.FinanceDaoV3
import com.example.fintrack.domain.dedupe.DedupeSink
import com.example.fintrack.domain.model.DedupeCluster
import com.example.fintrack.domain.model.DedupeClusterStatus
import com.example.fintrack.domain.model.DedupeDecision
import com.example.fintrack.domain.model.DedupeVerdict
import com.example.fintrack.domain.model.EvidenceLink

/**
 * Room-backed [DedupeSink]. All writes are idempotent (IGNORE on
 * linkIdentity / cluster-member / decision uniqueness). The
 * replace-by-identity path for clusters uses the unique
 * clusterIdentity index.
 */
class RoomDedupeRepository(private val dao: FinanceDaoV3) : DedupeSink {

    override suspend fun findClusterByIdentity(clusterIdentity: String): DedupeCluster? =
        dao.findClusterByIdentity(clusterIdentity)?.let { entity ->
            entity.toDomain(dao.clusterMembers(entity.id))
        }

    override suspend fun upsertCluster(clusterIdentity: String, cluster: DedupeCluster) {
        val entity = cluster.toEntity(clusterIdentity)
        dao.upsertCluster(entity)
        // Re-write members every time the cluster is updated. The unique
        // (clusterId, eventId) index protects against duplicates; we delete
        // the prior member set first to keep the score snapshot consistent.
        val existing = dao.clusterMembers(cluster.id)
        existing.forEach { dao.deleteClusterMemberById(it.id) }
        cluster.members.forEach { m ->
            dao.insertClusterMember(
                DedupeClusterMemberEntity(
                    id = java.util.UUID.randomUUID().toString(),
                    clusterId = cluster.id,
                    eventId = m.eventId,
                    score = m.score,
                    signalBreakdownJson = MiniJson.encodeMap(m.signals),
                    createdAtEpochMs = cluster.createdAt.toEpochMilli(),
                )
            )
        }
    }

    override suspend fun upsertEvidenceLink(link: EvidenceLink, linkIdentity: String) {
        dao.insertEvidenceLink(
            EvidenceLinkEntity(
                id = link.id,
                eventId = link.eventId,
                rawSmsId = link.rawSmsId,
                linkIdentity = linkIdentity,
                linkKind = link.linkKind.name,
                sourceKind = link.origin.name,
                sourceVersion = "dedup-v1",
                sourceReason = link.reason,
                createdAtEpochMs = link.createdAt.toEpochMilli(),
            )
        )
    }

    override suspend fun appendDecision(decision: DedupeDecision) {
        dao.insertDecision(
            DedupeDecisionEntity(
                id = decision.id,
                decisionEventId = decision.eventId,
                clusterId = decision.clusterId,
                decisionKind = decision.kind.name,
                actor = decision.actor,
                sourceKind = decision.sourceKind,
                sourceVersion = decision.sourceVersion,
                reason = decision.reason,
                appliedAtEpochMs = decision.appliedAt.toEpochMilli(),
            )
        )
    }

    // ---- helpers ----

    private fun DedupeClusterEntity.toDomain(memberRows: List<DedupeClusterMemberEntity>): DedupeCluster =
        DedupeCluster(
            id = id,
            status = DedupeClusterStatus.valueOf(status),
            verdict = DedupeVerdict.valueOf(verdict),
            topScore = topScore,
            reasons = MiniJson.decodeList(reasonsJson),
            canonicalEventId = canonicalEventId,
            members = memberRows.map { it.toDomain() },
            createdAt = java.time.Instant.ofEpochMilli(createdAtEpochMs),
            updatedAt = java.time.Instant.ofEpochMilli(updatedAtEpochMs),
        )

    private fun DedupeClusterMemberEntity.toDomain() =
        com.example.fintrack.domain.model.DedupeClusterMember(
            eventId = eventId,
            score = score,
            signals = MiniJson.decodeMap(signalBreakdownJson),
        )

    private fun DedupeCluster.toEntity(identity: String): DedupeClusterEntity = DedupeClusterEntity(
        id = id,
        clusterIdentity = identity,
        status = status.name,
        topScore = topScore,
        verdict = verdict.name,
        reasonsJson = MiniJson.encodeList(reasons),
        canonicalEventId = canonicalEventId,
        createdAtEpochMs = createdAt.toEpochMilli(),
        updatedAtEpochMs = updatedAt.toEpochMilli(),
    )
}
