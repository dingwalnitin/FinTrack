package com.example.fintrack.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * v6 P09 dedup blueprint (Stage 5).
 *
 * Design invariants (App Bible):
 *  - Raw evidence (raw_sms / messages) is immutable. Multiple raw messages may
 *    support one FinancialEvent through [EvidenceLinkEntity]; raw rows are
 *    never copied, mutated or deleted to "merge" events.
 *  - [DedupeClusterEntity] is the durable artifact of a clustering run.
 *    Idempotent via stable [clusterIdentity] (sha-256 of the candidate pair
 *    signals) so re-running the dedupe engine — backfill, parser re-runs,
 *    prompt-version changes — never produces a second cluster.
 *  - User decisions about merge/split are first-class data and must survive
 *    automated reprocessing. [DedupeDecisionEntity] is append-only; the
 *    authoritative [decisionSourceKind] is the stored source-rank owner.
 *  - High-confidence merges auto-apply; ambiguous candidate pairs route to
 *    Review — never auto-merge legitimate same-value purchases close in time.
 *  - Financial events are addressed by stable UUID, never by raw content.
 *
 * Indices:
 *  - clusterIdentity unique: idempotent clustering runs.
 *  - (eventId, rawSmsId) unique on EvidenceLink: a given raw row supports a
 *    given event at most once; adding a different raw row to the same event
 *    just inserts a new link.
 *  - (clusterId, eventId) unique on DedupeClusterMember: idempotent cluster
 *    membership.
 *  - decisionEventId + decisionKind unique: append-only history of decisions
 *    per (event, kind).
 */
@Entity(
    tableName = "evidence_links",
    indices = [
        Index(value = ["eventId", "rawSmsId"], unique = true),
        Index("eventId"),
        Index("rawSmsId"),
        Index("linkIdentity", unique = true),
    ],
)
data class EvidenceLinkEntity(
    @PrimaryKey val id: String,
    /** Stable UUID of the FinancialEvent (TransactionEntity.id). */
    val eventId: String,
    /** Stable UUID of the raw_sms row (raw_sms.id). */
    val rawSmsId: String,
    /**
     * sha-256(eventId | rawSmsId | linkKind) — durable identity of the link.
     * Lets a parser re-run safely re-assert the same link without a second
     * row; protects against partial overwrites across process restarts.
     */
    val linkIdentity: String,
    /**
     * Why this link was created. RAW_PRIMARY = the canonical raw message the
     * event was created from. RAW_SECONDARY = an additional supporting raw
     * message (e.g. confirmation SMS, follow-up, partial-info SMS).
     */
    val linkKind: String,
    /** Origin of the link decision. PROMOTED | USER_MERGED | IMPORT | AUTO. */
    val sourceKind: String,
    val sourceVersion: String,
    /** Optional human-readable reason ("split from cluster c123", "manual link"). */
    val sourceReason: String?,
    val createdAtEpochMs: Long,
)

@Entity(
    tableName = "dedupe_clusters",
    indices = [
        Index(value = ["clusterIdentity"], unique = true),
        Index("status"),
        Index("createdAtEpochMs"),
    ],
)
data class DedupeClusterEntity(
    @PrimaryKey val id: String,
    /**
     * Stable identity for the cluster content: sha-256 of the ordered candidate
     * pair signatures (amount | direction | account | rail | tsWindow | ref |
     * merchant | cardMask). Re-running the engine with the same inputs is a
     * no-op (idempotent on this index).
     */
    val clusterIdentity: String,
    /** Lifecycle: PROPOSED | MERGED | REVIEW | SPLIT | REJECTED. */
    val status: String,
    /** Highest member score in [0,1] for diagnostics. */
    val topScore: Double,
    /** Lowest threshold band that fired (AUTO_MERGE, REVIEW, REJECT). */
    val verdict: String,
    /** Free-form reasons that contributed to the verdict. */
    val reasonsJson: String,
    /** Best-effort canonical event id when status == MERGED; null otherwise. */
    val canonicalEventId: String?,
    val createdAtEpochMs: Long,
    val updatedAtEpochMs: Long,
)

@Entity(
    tableName = "dedupe_cluster_members",
    indices = [
        Index(value = ["clusterId", "eventId"], unique = true),
        Index("eventId"),
    ],
)
data class DedupeClusterMemberEntity(
    @PrimaryKey val id: String,
    val clusterId: String,
    /** TransactionEntity.id of the member event. */
    val eventId: String,
    /** Member score in [0,1]; 0.0 means the member was added by user override. */
    val score: Double,
    /** Which signals contributed (e.g. "amount=1.0, ref=0.4, ts=0.3"). */
    val signalBreakdownJson: String,
    val createdAtEpochMs: Long,
)

/**
 * Append-only user/automation decision history. Survives parser re-runs and
 * prompt-version changes; the most recent row for a (eventId, decisionKind)
 * is authoritative.
 */
@Entity(
    tableName = "dedupe_decisions",
    indices = [
        Index(value = ["decisionEventId", "decisionKind", "appliedAtEpochMs"], unique = true),
        Index("clusterId"),
        Index("decisionEventId"),
    ],
)
data class DedupeDecisionEntity(
    @PrimaryKey val id: String,
    /** TransactionEntity.id the decision applies to. */
    val decisionEventId: String,
    /** DedupeClusterEntity.id this decision was about, if any. */
    val clusterId: String?,
    /**
     * MERGE | UNMERGE | SPLIT | LINK | UNLINK | FORCE_REVIEW | KEEP_DUPLICATE.
     * KEEP_DUPLICATE means the user explicitly accepted two events as
     * legitimately separate (e.g. two INR 99 coffee purchases same hour).
     */
    val decisionKind: String,
    /** USER | SYSTEM | LLM_VALIDATED. */
    val actor: String,
    /** Outranks every automated rank — see ProvenancePolicy. */
    val sourceKind: String,
    val sourceVersion: String,
    /** Free-form reasons explaining the decision (what the user/system saw). */
    val reason: String?,
    val appliedAtEpochMs: Long,
)
