package com.example.fintrack.domain.model

import java.time.Instant

/**
 * P09 dedup domain vocabulary.
 *
 * Design:
 *  - [EvidenceLink] connects one FinancialEvent to one or more raw SMS rows
 *    without copying any transaction fact or deleting the SMS. Multiple
 *    messages can support one event.
 *  - [DedupeCluster] is a durable artifact of a clustering run. Stable
 *    [clusterIdentity] makes clustering idempotent across backfill, parser
 *    re-runs, and prompt-version changes.
 *  - [DedupeDecision] is append-only user/automation history. The most
 *    recent row for (eventId, decisionKind) is authoritative; older rows
 *    remain auditable.
 */

/** Stronger of the two raw-link kinds. */
enum class EvidenceLinkKind { RAW_PRIMARY, RAW_SECONDARY }

/** Why a link or decision was created. */
enum class EvidenceLinkOrigin { PROMOTED, USER_MERGED, USER_LINKED, IMPORT, AUTO }

/** Dedupe cluster lifecycle. */
enum class DedupeClusterStatus { PROPOSED, MERGED, REVIEW, SPLIT, REJECTED }

/** Engine verdict on a candidate pair. */
enum class DedupeVerdict {
    /** Strong signal, confidence above the merge threshold — engine merged. */
    AUTO_MERGE,
    /** Mixed signals, ambiguity above threshold — routed to Review. */
    REVIEW,
    /** Weak or contradictory signals — kept as separate events. */
    REJECT
}

/** User / automation decision kinds. */
enum class DedupeDecisionKind {
    MERGE, UNMERGE, SPLIT, LINK, UNLINK, FORCE_REVIEW, KEEP_DUPLICATE
}

data class EvidenceLink(
    val id: String,
    val eventId: String,
    val rawSmsId: String,
    val linkKind: EvidenceLinkKind,
    val origin: EvidenceLinkOrigin,
    val reason: String?,
    val createdAt: Instant,
)

/** Member score + signal breakdown used by the engine and surfaced in Review UI. */
data class DedupeClusterMember(
    val eventId: String,
    val score: Double,            // 0.0..1.0
    val signals: Map<String, Double>,
)

data class DedupeCluster(
    val id: String,
    val status: DedupeClusterStatus,
    val verdict: DedupeVerdict,
    val topScore: Double,
    val reasons: List<String>,
    val canonicalEventId: String?,
    val members: List<DedupeClusterMember>,
    val createdAt: Instant,
    val updatedAt: Instant,
)

data class DedupeDecision(
    val id: String,
    val eventId: String,
    val clusterId: String?,
    val kind: DedupeDecisionKind,
    val actor: String,            // USER | SYSTEM | LLM_VALIDATED
    val sourceKind: String,       // USER_CORRECTION | SMS | IMPORT_FILE | HEURISTIC | LLM_INTERPRETATION
    val sourceVersion: String,
    val reason: String?,
    val appliedAt: Instant,
)
