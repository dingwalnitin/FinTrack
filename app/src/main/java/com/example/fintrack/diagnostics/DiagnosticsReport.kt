package com.example.fintrack.diagnostics

import com.example.fintrack.data.db.FinTrackDatabaseV2

/**
 * Stage 12 P25 — Developer diagnostics surface.
 *
 * Pure data carrier for everything a developer (or a regression test) needs
 * to inspect at a glance:
 *  - build / environment identity
 *  - database / schema version + migration status
 *  - queue status (SMS, LLM, processing jobs)
 *  - parser / LLM statistics from the fixture corpus
 *  - unresolved / duplicate / reconciliation mismatches
 *  - recent failure / stuck-job summaries
 *
 * Diagnostics never include raw SMS bodies, account numbers, OTPs or
 * provider secrets. Diagnostic exports are themselves redacted via the
 * [RedactionEngine]; the export path enforces that contract.
 */
data class DiagnosticsReport(
    val environment: Environment,
    val database: Database,
    val queues: Queues,
    val parserStats: ParserStats,
    val llmStats: LlmStats,
    val migration: MigrationStatus,
    val unresolved: UnresolvedSummary,
    val duplicates: DuplicateSummary,
    val reconciliation: ReconciliationSummary,
    val recentFailures: List<FailureSample>,
    val exportable: Boolean,
) {
    /** Build environment + identity (never includes build secrets). */
    data class Environment(
        val applicationId: String,
        val versionName: String,
        val versionCode: Long,
        val schemaVersion: Int,
        val debugBuild: Boolean,
        val locale: String,
        val networkEgressAllowed: Boolean,
    )

    data class Database(
        val dbName: String,
        val schemaVersion: Int,
        val targetSchemaVersion: Int,
        val transactionCount: Long,
        val accountCount: Long,
        val categoryCount: Long,
        val budgetCount: Long,
        val rawSmsCount: Long,
        val llmJobCount: Long,
        val processingJobCount: Long,
        val reviewItemCount: Long,
        val auditLogCount: Long,
    )

    data class Queues(
        val smsBackfill: SmsQueueStatus,
        val llm: LlmQueueStatus,
        val processing: ProcessingQueueStatus,
    )

    data class SmsQueueStatus(
        val totalSeen: Long,
        val totalPersisted: Long,
        val totalDuplicate: Long,
        val cursorProviderId: Long?,
        val status: String,
        val lastUpdatedAtEpochMs: Long?,
    )

    data class LlmQueueStatus(
        val pending: Long,
        val claimedOrRunning: Long,
        val retryableFailed: Long,
        val terminalFailed: Long,
        val succeeded: Long,
        val expiredLeases: Long,
    )

    data class ProcessingQueueStatus(
        val pending: Long,
        val running: Long,
        val stale: Long,
    )

    /** Precision/recall over the current [FixtureCorpus]. */
    data class ParserStats(
        val fixtureVersion: String,
        val totalFixtures: Int,
        val financial: Int,
        val nonFinancial: Int,
        val borderline: Int,
        val malformed: Int,
        val precision: Double,
        val recall: Double,
        val extractionRate: Double,
        val railBreakdown: Map<String, Int>,
        val creditKindBreakdown: Map<String, Int>,
    )

    /** LLM aggregate counters from the durable job table. */
    data class LlmStats(
        val totalJobs: Long,
        val jobsSucceeded: Long,
        val jobsRetryableFailed: Long,
        val jobsTerminalFailed: Long,
        val lowConfidenceInterpretations: Long,
        val cacheHits: Long,
        val cacheMisses: Long,
    )

    /** Migration status: which version is live and the chain of registered migrations. */
    data class MigrationStatus(
        val currentSchemaVersion: Int,
        val registeredMigrations: List<Int>,
        val pendingMigrations: List<Int>,
        val destructiveFallbackEnabled: Boolean,
    )

    /** Aggregated unresolved-data report (Stage 9 surface, retained). */
    data class UnresolvedSummary(
        val unknownKindCount: Long,
        val uncategorizedSpendCount: Long,
        val openReviewItemCount: Long,
        val unmappedSenderCount: Long,
        val lowConfidenceInterpretationCount: Long,
    ) {
        val total: Long
            get() = unknownKindCount + uncategorizedSpendCount + openReviewItemCount +
                unmappedSenderCount + lowConfidenceInterpretationCount
    }

    /** Duplicate-cluster summary. */
    data class DuplicateSummary(
        val totalClusters: Long,
        val autoMerged: Long,
        val reviewPending: Long,
        val rejected: Long,
    )

    /** Reconciliation mismatches. */
    data class ReconciliationSummary(
        val totalAccounts: Int,
        val matched: Int,
        val explainedByLaterPostings: Int,
        val unexplained: Int,
        val noObservation: Int,
    ) {
        val hasMismatches: Boolean
            get() = unexplained > 0
    }

    /** One recent failure / stuck-job sample. No raw bodies, no PII. */
    data class FailureSample(
        val jobType: String,            // SMS_BACKFILL | LLM | PROCESSING | PARSER
        val jobIdentity: String,
        val status: String,
        val errorClass: String?,
        val attempts: Int,
        val lastUpdatedAtEpochMs: Long,
    )
}
