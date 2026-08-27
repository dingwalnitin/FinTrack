package com.example.fintrack.diagnostics

import com.example.fintrack.BuildConfig
import com.example.fintrack.data.db.FinanceDaoV8
import com.example.fintrack.data.db.SmsDao
import com.example.fintrack.data.db.LlmDao
import com.example.fintrack.data.db.FinTrackDatabaseV2
import com.example.fintrack.data.db.migration.Migrations
import com.example.fintrack.parser.FinTrackParser
import com.example.fintrack.parser.FinancialClass
import com.example.fintrack.parser.fixture.FixtureCorpus
import com.example.fintrack.domain.service.RedactionEngine
import com.example.fintrack.domain.service.ReconciliationService
import com.example.fintrack.domain.service.UnresolvedDataReportService
import java.time.LocalDate
import java.util.Locale

/**
 * Stage 12 P25 — diagnostics report builder.
 *
 * Composes a [DiagnosticsReport] from durable read-only sources. It does not
 * mutate any state; running the diagnostics surface multiple times is
 * idempotent. Sensitive fields (raw SMS bodies, OTPs, account numbers) are
 * never read into the report — only aggregate counts and stable identifiers
 * pass through.
 */
class DiagnosticsService(
    private val database: FinTrackDatabaseV2,
    private val dao: FinanceDaoV8,
    private val smsDao: SmsDao,
    private val llmDao: LlmDao,
    private val parser: FinTrackParser,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val locale: Locale = Locale.getDefault(),
    private val lowConfidenceThreshold: Double = 0.6,
) {

    suspend fun buildReport(): DiagnosticsReport {
        val env = DiagnosticsReport.Environment(
            applicationId = BuildConfig.APPLICATION_ID,
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE.toLong(),
            schemaVersion = FinTrackDatabaseV2.SCHEMA_VERSION,
            debugBuild = BuildConfig.DEBUG,
            locale = locale.toLanguageTag(),
            networkEgressAllowed = false, // Stage 12 contract: LLM path remains OFF by default
        )
        val dbReport = readDatabase()
        val queues = readQueues()
        val parserStats = computeParserStats()
        val llmStats = readLlmStats()
        val migration = readMigration()
        val unresolved = readUnresolved()
        val duplicates = readDuplicates()
        val reconciliation = readReconciliation()
        val failures = readRecentFailures()
        return DiagnosticsReport(
            environment = env,
            database = dbReport,
            queues = queues,
            parserStats = parserStats,
            llmStats = llmStats,
            migration = migration,
            unresolved = unresolved,
            duplicates = duplicates,
            reconciliation = reconciliation,
            recentFailures = failures,
            exportable = true,
        )
    }

    private suspend fun readDatabase(): DiagnosticsReport.Database {
        return DiagnosticsReport.Database(
            dbName = "fintrack.db",
            schemaVersion = FinTrackDatabaseV2.SCHEMA_VERSION,
            targetSchemaVersion = FinTrackDatabaseV2.SCHEMA_VERSION,
            transactionCount = dao.activeTransactionCount().toLong(),
            accountCount = dao.allAccounts().size.toLong(),
            categoryCount = dao.activeCategories().size.toLong(),
            budgetCount = dao.exportBudgets().size.toLong(),
            rawSmsCount = smsDao.rawCount(),
            llmJobCount = llmDao.totalJobs(),
            processingJobCount = dao.processingJobCount().toLong(),
            reviewItemCount = dao.openReviewItemCount().toLong(),
            auditLogCount = dao.auditLogCount().toLong(),
        )
    }

    private suspend fun readQueues(): DiagnosticsReport.Queues {
        val cursor = smsDao.getCursor()
        return DiagnosticsReport.Queues(
            smsBackfill = DiagnosticsReport.SmsQueueStatus(
                totalSeen = cursor?.totalSeen ?: 0L,
                totalPersisted = cursor?.totalPersisted ?: 0L,
                totalDuplicate = cursor?.totalDuplicate ?: 0L,
                cursorProviderId = cursor?.lastProviderId,
                status = cursor?.status ?: "IDLE",
                lastUpdatedAtEpochMs = cursor?.lastUpdatedAtEpochMs,
            ),
            llm = DiagnosticsReport.LlmQueueStatus(
                pending = llmDao.countInStatus("PENDING"),
                claimedOrRunning = llmDao.countInStatus("CLAIMED") + llmDao.countInStatus("RUNNING"),
                retryableFailed = llmDao.countInStatus("RETRYABLE_FAILED"),
                terminalFailed = llmDao.countInStatus("TERMINAL_FAILED"),
                succeeded = llmDao.countInStatus("SUCCEEDED"),
                expiredLeases = llmDao.expiredLeases(clock()),
            ),
            processing = DiagnosticsReport.ProcessingQueueStatus(
                pending = dao.processingPendingCount().toLong(),
                running = dao.processingRunningCount().toLong(),
                stale = dao.staleProcessingJobCount(clock()).toLong(),
            ),
        )
    }

    private fun computeParserStats(): DiagnosticsReport.ParserStats {
        val total = FixtureCorpus.ALL.size
        var financial = 0; var nonFin = 0; var borderline = 0; var malformed = 0
        var tp = 0; var fp = 0; var fn = 0
        var extracted = 0
        val railCounts = mutableMapOf<String, Int>()
        val creditKindCounts = mutableMapOf<String, Int>()
        for (f in FixtureCorpus.ALL) {
            val cls = parser.classify(f.raw).financialClass
            when (cls) {
                FinancialClass.FINANCIAL -> financial++
                FinancialClass.NON_FINANCIAL -> nonFin++
                FinancialClass.BORDERLINE -> borderline++
            }
            if (f.expectedClass == FinancialClass.BORDERLINE &&
                (f.raw.contains("Rs.,,,") || f.raw.contains("foo@@bar"))) malformed++
            val predictedPos = cls == FinancialClass.FINANCIAL
            val actualPos = f.expectedClass == FinancialClass.FINANCIAL
            when {
                predictedPos && actualPos -> tp++
                predictedPos && !actualPos -> fp++
                !predictedPos && actualPos -> fn++
            }
            val candidate = parser.parse(f.raw)
            if (candidate != null) {
                extracted++
                railCounts.merge(candidate.rail.name, 1) { a, b -> a + b }
                candidate.creditKind?.name?.let {
                    creditKindCounts.merge(it, 1) { a, b -> a + b }
                }
            }
        }
        val precision = if (tp + fp == 0) 1.0 else tp.toDouble() / (tp + fp)
        val recall = if (tp + fn == 0) 1.0 else tp.toDouble() / (tp + fn)
        val extractionRate = if (total == 0) 1.0 else extracted.toDouble() / total
        return DiagnosticsReport.ParserStats(
            fixtureVersion = FixtureCorpus.VERSION,
            totalFixtures = total,
            financial = financial,
            nonFinancial = nonFin,
            borderline = borderline,
            malformed = malformed,
            precision = precision,
            recall = recall,
            extractionRate = extractionRate,
            railBreakdown = railCounts,
            creditKindBreakdown = creditKindCounts,
        )
    }

    private suspend fun readLlmStats(): DiagnosticsReport.LlmStats {
        return DiagnosticsReport.LlmStats(
            totalJobs = llmDao.totalJobs(),
            jobsSucceeded = llmDao.countInStatus("SUCCEEDED"),
            jobsRetryableFailed = llmDao.countInStatus("RETRYABLE_FAILED"),
            jobsTerminalFailed = llmDao.countInStatus("TERMINAL_FAILED"),
            lowConfidenceInterpretations = dao.lowConfidenceInterpretationCount(lowConfidenceThreshold).toLong(),
            cacheHits = llmDao.cacheEntryCount(),
            cacheMisses = 0L,
        )
    }

    private fun readMigration(): DiagnosticsReport.MigrationStatus {
        val registered = Migrations.ALL.map { it.startVersion }.sorted()
        val target = FinTrackDatabaseV2.SCHEMA_VERSION
        val current = target // Migrations are forward-only; if Room reached SCHEMA_VERSION, current == target
        val pending = if (current < target) (current + 1..target).toList() else emptyList()
        return DiagnosticsReport.MigrationStatus(
            currentSchemaVersion = current,
            registeredMigrations = registered,
            pendingMigrations = pending,
            destructiveFallbackEnabled = false, // explicitly forbidden beyond v1
        )
    }

    private suspend fun readUnresolved(): DiagnosticsReport.UnresolvedSummary {
        return DiagnosticsReport.UnresolvedSummary(
            unknownKindCount = dao.unknownKindCount().toLong(),
            uncategorizedSpendCount = dao.uncategorizedSpendCount().toLong(),
            openReviewItemCount = dao.openReviewItemCount().toLong(),
            unmappedSenderCount = dao.unmappedSenderCount().toLong(),
            lowConfidenceInterpretationCount = dao.lowConfidenceInterpretationCount(lowConfidenceThreshold).toLong(),
        )
    }

    private suspend fun readDuplicates(): DiagnosticsReport.DuplicateSummary {
        return DiagnosticsReport.DuplicateSummary(
            totalClusters = dao.totalClusterCount().toLong(),
            autoMerged = dao.clusterCountInStatus("MERGED").toLong(),
            reviewPending = dao.clusterCountInStatus("REVIEW").toLong(),
            rejected = dao.clusterCountInStatus("REJECTED").toLong(),
        )
    }

    private suspend fun readReconciliation(): DiagnosticsReport.ReconciliationSummary {
        val recon = ReconciliationService()
        val accounts = dao.allAccounts()
        val openings = dao.allOpeningBalances().associate { it.accountId to it.amountMinor }
        val allTxns = dao.allActiveTransactions()
        val snapshots = dao.allSnapshots()
        val byAccount = snapshots.groupBy { it.accountId }
            .mapValues { (_, list) -> list.maxByOrNull { it.capturedAtEpochMs }?.let { it.capturedAtEpochMs to it.amountMinor } }
        var matched = 0
        var explained = 0
        var unexplained = 0
        var noObs = 0
        for (acct in accounts) {
            val r = recon.reconcile(
                accountId = acct.id,
                accountLabel = acct.nickname ?: acct.name,
                currencyCode = acct.currencyCode,
                openingBalanceMinor = openings[acct.id],
                postings = allTxns.filter { it.accountId == acct.id }.map { tx ->
                    com.example.fintrack.domain.service.LedgerTxnView(
                        id = tx.id,
                        accountId = tx.accountId,
                        categoryId = tx.categoryId,
                        kind = tx.kind,
                        directionDebit = when (tx.kind) {
                            "INCOME", "REFUND" -> false
                            else -> true
                        },
                        amountMinor = tx.amountMinor,
                        localDateEpochDay = tx.localDateEpochDay,
                        counterpartyNormalized = tx.counterpartyNormalized,
                        merchant = tx.merchant,
                        currencyCode = tx.currencyCode,
                        occurredAtEpochMs = tx.occurredAtEpochMs,
                        subtype = tx.subtype,
                        statusDeleted = tx.status == "DELETED",
                        rail = tx.rail,
                        cardMask = tx.cardMask,
                    )
                },
                latestSnapshot = byAccount[acct.id],
            )
            when (recon.verdict(r)) {
                ReconciliationService.Verdict.Matched -> matched++
                is ReconciliationService.Verdict.ExplainedByLaterPostings -> explained++
                is ReconciliationService.Verdict.Unexplained -> unexplained++
                ReconciliationService.Verdict.NoObservation -> noObs++
            }
        }
        return DiagnosticsReport.ReconciliationSummary(
            totalAccounts = accounts.size,
            matched = matched,
            explainedByLaterPostings = explained,
            unexplained = unexplained,
            noObservation = noObs,
        )
    }

    private suspend fun readRecentFailures(limit: Int = 25): List<DiagnosticsReport.FailureSample> {
        return llmDao.recentFailureSamples(limit).map { f ->
            DiagnosticsReport.FailureSample(
                jobType = "LLM",
                jobIdentity = f.jobIdentity,
                status = f.status,
                errorClass = f.lastErrorClass,
                attempts = f.attempts,
                lastUpdatedAtEpochMs = f.updatedAtEpochMs,
            )
        }
    }

    /**
     * Render a deterministic, redacted diagnostic summary that is safe to
     * share with an agent / paste into a bug report. The output intentionally
     * contains no raw SMS bodies, no account numbers, no OTPs and no
     * provider secrets.
     */
    fun exportAsText(report: DiagnosticsReport): String = buildString {
        appendLine("FinTrack diagnostics v${report.environment.versionName} (${report.environment.versionCode})")
        appendLine("applicationId: ${report.environment.applicationId}")
        appendLine("locale: ${report.environment.locale}")
        appendLine("schemaVersion: ${report.database.schemaVersion}")
        appendLine("networkEgressAllowed: ${report.environment.networkEgressAllowed}")
        appendLine()
        appendLine("-- counts --")
        appendLine("transactions: ${report.database.transactionCount}")
        appendLine("accounts: ${report.database.accountCount}")
        appendLine("categories: ${report.database.categoryCount}")
        appendLine("budgets: ${report.database.budgetCount}")
        appendLine("rawSms: ${report.database.rawSmsCount}")
        appendLine("llmJobs: ${report.database.llmJobCount}")
        appendLine("processingJobs: ${report.database.processingJobCount}")
        appendLine("reviewItems: ${report.database.reviewItemCount}")
        appendLine("auditLog: ${report.database.auditLogCount}")
        appendLine()
        appendLine("-- queues --")
        appendLine("smsBackfill: status=${report.queues.smsBackfill.status} " +
            "seen=${report.queues.smsBackfill.totalSeen} " +
            "persisted=${report.queues.smsBackfill.totalPersisted} " +
            "duplicate=${report.queues.smsBackfill.totalDuplicate}")
        appendLine("llm: pending=${report.queues.llm.pending} " +
            "running=${report.queues.llm.claimedOrRunning} " +
            "retryableFailed=${report.queues.llm.retryableFailed} " +
            "terminalFailed=${report.queues.llm.terminalFailed} " +
            "expiredLeases=${report.queues.llm.expiredLeases}")
        appendLine("processing: pending=${report.queues.processing.pending} " +
            "running=${report.queues.processing.running} " +
            "stale=${report.queues.processing.stale}")
        appendLine()
        appendLine("-- parser (${report.parserStats.fixtureVersion}) --")
        appendLine("fixtures=${report.parserStats.totalFixtures} " +
            "precision=${"%.3f".format(report.parserStats.precision)} " +
            "recall=${"%.3f".format(report.parserStats.recall)} " +
            "extractionRate=${"%.3f".format(report.parserStats.extractionRate)}")
        appendLine("railBreakdown=${report.parserStats.railBreakdown}")
        appendLine("creditKindBreakdown=${report.parserStats.creditKindBreakdown}")
        appendLine()
        appendLine("-- unresolved --")
        appendLine("unknownKind=${report.unresolved.unknownKindCount} " +
            "uncategorized=${report.unresolved.uncategorizedSpendCount} " +
            "openReview=${report.unresolved.openReviewItemCount} " +
            "unmappedSenders=${report.unresolved.unmappedSenderCount} " +
            "lowConf=${report.unresolved.lowConfidenceInterpretationCount}")
        appendLine()
        appendLine("-- duplicates --")
        appendLine("clusters=${report.duplicates.totalClusters} " +
            "autoMerged=${report.duplicates.autoMerged} " +
            "reviewPending=${report.duplicates.reviewPending} " +
            "rejected=${report.duplicates.rejected}")
        appendLine()
        appendLine("-- reconciliation --")
        appendLine("accounts=${report.reconciliation.totalAccounts} " +
            "matched=${report.reconciliation.matched} " +
            "explained=${report.reconciliation.explainedByLaterPostings} " +
            "unexplained=${report.reconciliation.unexplained} " +
            "noObservation=${report.reconciliation.noObservation}")
        appendLine()
        if (report.recentFailures.isNotEmpty()) {
            appendLine("-- recent failures (top ${report.recentFailures.size}) --")
            for (f in report.recentFailures) {
                appendLine("${f.jobType} ${f.jobIdentity} status=${f.status} " +
                    "attempts=${f.attempts} errorClass=${f.errorClass ?: "-"}")
            }
        }
    }.let { RedactionEngine.redact(it).redactedText }
}
