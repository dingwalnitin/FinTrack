package com.example.fintrack

import android.app.Application
import androidx.room.Room
import com.example.fintrack.data.db.FinTrackDatabaseV2
import com.example.fintrack.data.db.migration.Migrations
import com.example.fintrack.data.repository.RoomCardRepository
import com.example.fintrack.data.repository.RoomCategorizationRepository
import com.example.fintrack.data.repository.RoomDedupeRepository
import com.example.fintrack.data.repository.RoomEmiRepository
import com.example.fintrack.data.repository.RoomFinanceRepository
import com.example.fintrack.data.repository.RoomFinanceRepositoryV2
import com.example.fintrack.data.repository.RoomManualEntryRepository
import com.example.fintrack.data.repository.RoomRefundRepository
import com.example.fintrack.data.repository.RoomReviewRepository
import com.example.fintrack.data.repository.RoomInsightsRepository
import com.example.fintrack.data.repository.RoomSmsRepository
import com.example.fintrack.data.repository.RoomStage8Repository
import com.example.fintrack.data.repository.RoomTransactionWriteRepository
import com.example.fintrack.data.repository.RoomTransferRepository
import com.example.fintrack.domain.FinanceRepository
import com.example.fintrack.domain.dedupe.DedupeService
import com.example.fintrack.domain.merchant.MerchantRegistry as DomainMerchantRegistry
import com.example.fintrack.domain.repository.FinanceRepositoryV2
import com.example.fintrack.domain.repository.SmsRepository
import com.example.fintrack.domain.service.BulkCorrectionService
import com.example.fintrack.domain.service.BudgetService
import com.example.fintrack.domain.service.CardTransactionService
import com.example.fintrack.domain.service.CashService
import com.example.fintrack.domain.service.CategorizationService
import com.example.fintrack.domain.service.InsightsEngine
import com.example.fintrack.domain.service.EmiPlanService
import com.example.fintrack.domain.service.ManualEntryService
import com.example.fintrack.domain.service.RecurringService
import com.example.fintrack.domain.service.ReconciliationService
import com.example.fintrack.domain.service.RefundService
import com.example.fintrack.domain.service.ReviewQueueService
import com.example.fintrack.domain.service.SplitService
import com.example.fintrack.domain.service.TagsNotesService
import com.example.fintrack.domain.service.TransactionWriteService
import com.example.fintrack.domain.service.TransferService
import com.example.fintrack.application.enrichment.LlmJobStore
import com.example.fintrack.data.repository.RoomAiQueryRepository
import com.example.fintrack.data.repository.RoomAuditLogRepository
import com.example.fintrack.data.repository.RoomAppLockRepository
import com.example.fintrack.data.repository.RoomBackupRepository
import com.example.fintrack.data.repository.RoomSettingsProfileRepository
import com.example.fintrack.security.KeystoreSecretVault
import com.example.fintrack.domain.ai.AiQueryParser
import com.example.fintrack.sms.ContentResolverSmsSource
import com.example.fintrack.sms.SmsSource

class FinTrackApplication : Application() {

    val database: FinTrackDatabaseV2 by lazy {
        Room.databaseBuilder(this, FinTrackDatabaseV2::class.java, "fintrack.db")
            .addMigrations(*Migrations.ALL)
            .build()
    }

    val financeRepository: FinanceRepositoryV2 by lazy {
        RoomFinanceRepositoryV2(database.financeDaoV2())
    }

    val legacyRepository: FinanceRepository by lazy {
        RoomFinanceRepository(database.financeDaoV2())
    }

    /** Stage 5 P09: dedup sink + service. */
    val dedupeSink: RoomDedupeRepository by lazy { RoomDedupeRepository(database.financeDaoV3()) }
    val dedupeService: DedupeService by lazy { DedupeService(dedupeSink) }

    /** Stage 5 P10: transactional transaction + posting write service. */
    val transactionWriteService: TransactionWriteService by lazy {
        TransactionWriteService(RoomTransactionWriteRepository(database.financeDaoV3()))
    }

    /** Stage 5 P11: transfer / refund / manual-entry services. */
    val transferService: TransferService by lazy {
        TransferService(RoomTransferRepository(database.financeDaoV4(), database.financeDaoV3()))
    }
    val manualEntrySink: RoomManualEntryRepository by lazy {
        RoomManualEntryRepository(database.financeDaoV3(), database.financeDaoV4())
    }
    val manualEntryService: ManualEntryService by lazy {
        ManualEntryService(transactionWriteService, manualEntrySink)
    }
    val refundService: RefundService by lazy {
        RefundService(
            writeService = transactionWriteService,
            sink = RoomRefundRepository(database.financeDaoV4()),
        )
    }

    /** Stage 6 P12: credit-card register, statement, payment, rewards, adjustments. */
    val cardRepository: RoomCardRepository by lazy {
        RoomCardRepository(database.financeDaoV5())
    }
    val cardTransactionService: CardTransactionService by lazy {
        CardTransactionService(
            cardSink = cardRepository,
            statementSink = cardRepository,
            lineSink = cardRepository,
            paymentSink = cardRepository,
            rewardSink = cardRepository,
            adjustmentSink = cardRepository,
        )
    }

    /** Stage 6 P13: EMI plan + installment + preclosure + refinance. */
    val emiRepository: RoomEmiRepository by lazy {
        RoomEmiRepository(database.financeDaoV5())
    }
    val emiPlanService: EmiPlanService by lazy {
        EmiPlanService(
            planSink = emiRepository,
            installmentSink = emiRepository,
            preclosureSink = emiRepository,
        )
    }

    /** SMS evidence source. The same instance is shared with the receiver and worker. */
    val smsSource: SmsSource by lazy { ContentResolverSmsSource(this) }

    val smsRepository: SmsRepository by lazy {
        RoomSmsRepository(database.smsDao())
    }

    /** Stage 4: durable LLM job store over the v5 tables. */
    val llmJobStore: LlmJobStore by lazy { LlmJobStore(database.llmDao()) }

    /** LLM config store (base URL / API key / model id) for Chat Completions. */
    val llmConfigStore: com.example.fintrack.llm.LlmConfigStore by lazy {
        com.example.fintrack.llm.LlmConfigStore(this)
    }

    /** OpenAI-compatible Chat Completions provider built from saved config. */
    val chatCompletionsProvider: com.example.fintrack.llm.ChatCompletionsProvider by lazy {
        // Pass a live config provider rather than a snapshot so edits to the
        // base URL / API key / model in Settings take effect on the very next
        // request (the same shared instance is reused for every LLM call).
        com.example.fintrack.llm.ChatCompletionsProvider(
            configProvider = { llmConfigStore.load() },
        )
    }

    /**
     * Multi-API-Key pooled rate-limited provider: distributes requests across
     * all configured and enabled API keys (25 req/min, 1,000 req/day per key).
     */
    val rateLimitedLlmProvider: com.example.fintrack.llm.LlmProvider by lazy {
        com.example.fintrack.llm.KeyPooledLlmProvider(
            configProvider = { llmConfigStore.load() },
            chatCompletionsProvider = chatCompletionsProvider,
        )
    }

    val llmDiscoveryService: com.example.fintrack.application.enrichment.LlmDiscoveryService by lazy {
        com.example.fintrack.application.enrichment.LlmDiscoveryService(
            com.example.fintrack.application.enrichment.FinanceRepositoryLlmDiscoverySink(financeRepository),
        )
    }

    /**
     * On-demand "process ALL SMS through the LLM" service with exponential
     * backoff + progress StateFlow. Triggered from Settings.
     */
    val llmProcessingService: com.example.fintrack.application.enrichment.LlmProcessingService by lazy {
        com.example.fintrack.application.enrichment.LlmProcessingService(
            smsDao = database.smsDao(),
            llmDao = database.llmDao(),
            provider = rateLimitedLlmProvider,
            discoveryService = llmDiscoveryService,
        )
    }

    // ---- Stage 7 (P14 + P15) ----

    /** P14: categorization engine + merchant normalization. */
    val categorizationRepository: RoomCategorizationRepository by lazy {
        RoomCategorizationRepository(database.financeDaoV6())
    }
    val categorizationService: CategorizationService by lazy {
        CategorizationService(DomainMerchantRegistry.empty())
    }

    /** P15: review queue, splits, reimbursement, travel modes, tags, notes. */
    val reviewRepository: RoomReviewRepository by lazy {
        RoomReviewRepository(database.financeDaoV6())
    }
    val reviewQueueService: ReviewQueueService by lazy {
        ReviewQueueService(reviewRepository)
    }
    val splitService: SplitService by lazy {
        SplitService(transactionWriteService, reviewRepository)
    }
    val bulkCorrectionService: BulkCorrectionService by lazy {
        BulkCorrectionService(categorizationRepository)
    }
    val tagsNotesService: TagsNotesService by lazy {
        TagsNotesService(reviewRepository)
    }

    // ---- Stage 8 (P16 + P17 + P18) ----

    /** P16/P17/P18: budgets, recurring patterns, cash reconciliation. */
    val stage8Repository: RoomStage8Repository by lazy {
        RoomStage8Repository(database.financeDaoV7())
    }
    val budgetService: BudgetService by lazy { BudgetService() }
    val recurringService: RecurringService by lazy { RecurringService() }
    val cashService: CashService by lazy { CashService() }

    // ---- Stage 9 (P19 + P20) ----

    /** Read-only dashboard / insights / search / diagnostics repository. */
    val insightsRepository: RoomInsightsRepository by lazy {
        RoomInsightsRepository(database.financeDaoV8())
    }
    val insightsEngine: InsightsEngine by lazy { InsightsEngine() }
    val reconciliationService: ReconciliationService by lazy { ReconciliationService() }

    // ---- Stage 10 (P21 + P22) ----

    /** AI query execution + audit over the existing read-only DAOs. */
    val aiQueryRepository: RoomAiQueryRepository by lazy {
        RoomAiQueryRepository(database.financeDaoV8(), database.financeDaoV4())
    }

    /** Deterministic NL → validated-plan parser with category/account aliasing. */
    val aiQueryParser: AiQueryParser by lazy {
        AiQueryParser(
            aliasResolver = { surface, kind ->
                when (kind) {
                    AiQueryParser.AliasKind.CATEGORY ->
                        aiCategoryResolver.resolve(surface, emptyList()).let { r ->
                            (r as? com.example.fintrack.domain.ai.CategoryAliasResolver.Resolution.Resolved)?.categoryId
                        }
                    else -> null
                }
            },
        )
    }

    private val aiCategoryResolver: com.example.fintrack.domain.ai.CategoryAliasResolver by lazy {
        com.example.fintrack.domain.ai.CategoryAliasResolver()
    }

    // ---- Stage 11 (P23 + P24) ----

    /** P23: backup/restore over the v11 staging tables. */
    val backupRepository: RoomBackupRepository by lazy {
        RoomBackupRepository(database.financeDaoV9())
    }
    val backupService: com.example.fintrack.domain.service.BackupService by lazy {
        com.example.fintrack.domain.service.BackupService(
            sink = backupRepository,
            codec = com.example.fintrack.domain.service.BackupCodec(),
            clock = { System.currentTimeMillis() },
        )
    }

    /** P24: settings profiles (module 175). */
    val settingsProfileService: com.example.fintrack.domain.service.SettingsProfileService by lazy {
        com.example.fintrack.domain.service.SettingsProfileService(
            sink = RoomSettingsProfileRepository(database.financeDaoV9()),
            clock = { System.currentTimeMillis() },
        )
    }

    /** P24: retention-bounded audit log for money-changing/sensitive actions. */
    val auditService: com.example.fintrack.domain.service.AuditService by lazy {
        com.example.fintrack.domain.service.AuditService(
            sink = RoomAuditLogRepository(database.financeDaoV9()),
            clock = { System.currentTimeMillis() },
        )
    }

    /** P24 #5: app lock. Secret lives in Keystore-backed vault, never in Room. */
    val appLockService: com.example.fintrack.domain.service.AppLockService by lazy {
        com.example.fintrack.domain.service.AppLockService(
            vault = KeystoreSecretVault(this),
            sink = RoomAppLockRepository(database.financeDaoV9()),
            clock = { System.currentTimeMillis() },
        )
    }

    // ---- Stage 12 (P25) ----

    /** Developer diagnostics: environment, queues, parser stats, migration, unresolved. */
    val diagnosticsService: com.example.fintrack.diagnostics.DiagnosticsService by lazy {
        com.example.fintrack.diagnostics.DiagnosticsService(
            database = database,
            dao = database.financeDaoV8(),
            smsDao = database.smsDao(),
            llmDao = database.llmDao(),
            parser = com.example.fintrack.parser.FinTrackParser(),
        )
    }

    /** Parser playground: run synthetic SMS through the pipeline without touching the ledger. */
    val parserPlayground: com.example.fintrack.diagnostics.ParserPlayground by lazy {
        com.example.fintrack.diagnostics.ParserPlayground()
    }

    /** Fixture diff tooling: compare parser output against the golden baseline. */
    val fixtureDiff: com.example.fintrack.diagnostics.FixtureDiff by lazy {
        com.example.fintrack.diagnostics.FixtureDiff()
    }

    /** Stage 12 P27 — release readiness check. */
    val releaseReadinessCheck: com.example.fintrack.diagnostics.ReleaseReadinessCheck by lazy {
        com.example.fintrack.diagnostics.ReleaseReadinessCheck()
    }

    /** Stage 12 P25 #5 — transfer candidate source + matcher for auto-link. */
    val transferCandidateSource: com.example.fintrack.domain.service.TransferCandidateSource by lazy {
        com.example.fintrack.data.repository.RoomTransferCandidateSource(database.financeDaoV8())
    }
    val transferCandidateMatcher: com.example.fintrack.domain.service.TransferCandidateMatcher by lazy {
        com.example.fintrack.domain.service.TransferCandidateMatcher(transferCandidateSource)
    }

    // ---- Stage 13 (A + D) ----

    /** Payee category rules + transaction evidence repository (v12). */
    val payeeEvidenceRepository: com.example.fintrack.data.repository.RoomPayeeEvidenceRepository by lazy {
        com.example.fintrack.data.repository.RoomPayeeEvidenceRepository(database)
    }
    val payeeRuleResolver: com.example.fintrack.domain.service.PayeeRuleResolver by lazy {
        com.example.fintrack.domain.service.PayeeRuleResolver(emptyMap())
    }

    /** Stage 13 (F): SMS review — manual overrides via the write path. */
    val smsReviewService: com.example.fintrack.domain.service.SmsReviewService by lazy {
        com.example.fintrack.domain.service.SmsReviewService(
            writeService = transactionWriteService,
        )
    }
}
