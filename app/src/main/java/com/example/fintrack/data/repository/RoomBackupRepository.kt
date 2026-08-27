package com.example.fintrack.data.repository

import com.example.fintrack.data.db.FinanceDaoV9
import com.example.fintrack.data.db.ImportBatchEntity
import com.example.fintrack.data.db.ImportStagingRowEntity
import com.example.fintrack.domain.model.BackupDataset
import com.example.fintrack.domain.service.BackupCodec
import com.example.fintrack.domain.service.BackupSink
import com.example.fintrack.domain.service.RedactionEngine
import java.util.UUID

/**
 * Stage 11 P23 — Room-backed [BackupSink].
 *
 * Export side serializes each dataset into canonical `key=value;` rows with
 * deterministic field ordering. Secrets-bearing stores (LLM provider config,
 * response cache) have no export path here at all.
 *
 * Import side stages rows into the v11 staging tables and commits through
 * the DAO's single @Transaction.
 */
class RoomBackupRepository(
    private val dao: FinanceDaoV9,
) : BackupSink {

    private val codec = BackupCodec()

    // ------------------------------------------------------------------
    // EXPORT
    // ------------------------------------------------------------------

    override suspend fun exportRows(dataset: BackupDataset): List<String> = when (dataset) {
        BackupDataset.ACCOUNTS -> dao.exportAccounts().map { accountRow(it) }
        BackupDataset.CATEGORIES -> dao.exportCategories().map { categoryRow(it) }
        BackupDataset.TRANSACTIONS -> dao.exportTransactions().map { txnRow(it) }
        BackupDataset.LEDGER_ENTRIES -> dao.exportLedgerEntries().map { ledgerRow(it) }
        BackupDataset.TRANSFERS -> dao.exportTransfers().map { transferRow(it) }
        BackupDataset.OPENING_BALANCES -> dao.exportOpeningBalances().map { obRow(it) }
        BackupDataset.BALANCE_SNAPSHOTS -> dao.exportSnapshots().map { snapRow(it) }
        BackupDataset.SENDER_MAPPINGS -> dao.exportSenderMappings().map { mappingRow(it) }
        BackupDataset.INSTITUTION_ALIASES -> dao.exportInstitutionAliases().map { aliasRow(it) }
        BackupDataset.REFUND_LINKS -> dao.exportRefundLinks().map { refundLinkRow(it) }
        BackupDataset.TRANSACTION_LINKS -> dao.exportTransactionLinks().map { txLinkRow(it) }
        BackupDataset.MERCHANTS -> dao.exportMerchants().map { merchantRow(it) }
        BackupDataset.MERCHANT_ALIASES -> dao.exportMerchantAliases().map { mAliasRow(it) }
        BackupDataset.CATEGORY_RULES -> dao.exportCategoryRules().map { ruleRow(it) }
        BackupDataset.VPA_BINDINGS -> dao.exportVpaBindings().map { vpaRow(it) }
        BackupDataset.REVIEW_ITEMS -> dao.exportReviewItems().map { reviewRow(it) }
        BackupDataset.SPLITS -> dao.exportSplits().map { splitRow(it) }
        BackupDataset.REIMBURSEMENT_LINKS -> dao.exportReimbursementLinks().map { reimbRow(it) }
        BackupDataset.TRAVEL_MODES -> dao.exportTravelModes().map { travelRow(it) }
        BackupDataset.TAGS -> dao.exportTags().map { tagRow(it) }
        BackupDataset.NOTES -> dao.exportNotes().map { noteRow(it) }
        BackupDataset.BUDGETS -> dao.exportBudgets().map { budgetRow(it) }
        BackupDataset.BUDGET_PERIODS -> dao.exportBudgetPeriods().map { bpRow(it) }
        BackupDataset.RECURRING_PATTERNS -> dao.exportRecurringPatterns().map { rpRow(it) }
        BackupDataset.RECURRING_OBSERVATIONS -> dao.exportRecurringObservations().map { roRow(it) }
        BackupDataset.CASH_RECONCILIATIONS -> dao.exportCashReconciliations().map { crRow(it) }
        BackupDataset.ATM_CASH_LINKS -> dao.exportAtmCashLinks().map { atmRow(it) }
    }

    // ------------------------------------------------------------------
    // STAGING
    // ------------------------------------------------------------------

    override suspend fun stageRows(dataset: BackupDataset, rows: List<String>) {
        val batch = dao.activeBatch() ?: throw IllegalStateException("no active import batch")
        val entities = rows.map { canonical ->
            ImportStagingRowEntity(
                id = UUID.randomUUID().toString(),
                batchId = batch.id,
                dataset = dataset.name,
                stableId = stableIdOf(canonical),
                canonicalRow = canonical,
                stagedAtEpochMs = System.currentTimeMillis(),
            )
        }
        dao.insertStagedRows(entities)
    }

    override suspend fun stagedDatasets(): List<BackupDataset> {
        val batch = dao.activeBatch() ?: return emptyList()
        return BackupDataset.entries.filter {
            dao.stagedRowCount(batch.id, it.name) > 0
        }
    }

    override suspend fun stagedRowCount(dataset: BackupDataset): Int {
        val batch = dao.activeBatch() ?: return 0
        return dao.stagedRowCount(batch.id, dataset.name)
    }

    override suspend fun stagedIds(dataset: BackupDataset): List<String> {
        val batch = dao.activeBatch() ?: return emptyList()
        return dao.stagedRows(batch.id, dataset.name).map { it.stableId }
    }

    override suspend fun stagedRowById(dataset: BackupDataset, stableId: String): String? {
        val batch = dao.activeBatch() ?: return null
        return dao.stagedRows(batch.id, dataset.name)
            .firstOrNull { it.stableId == stableId }?.canonicalRow
    }

    override suspend fun clearStaging() {
        val batch = dao.activeBatch()
        if (batch != null) {
            dao.clearStagingRows(batch.id)
            dao.deleteBatch(batch.id)
        }
    }

    // ------------------------------------------------------------------
    // CONFLICT DETECTION
    // ------------------------------------------------------------------

    override suspend fun liveRowById(dataset: BackupDataset, stableId: String): String? =
        when (dataset) {
            BackupDataset.ACCOUNTS -> dao.liveAccount(stableId)?.let { accountRow(it) }
            BackupDataset.CATEGORIES -> dao.liveCategory(stableId)?.let { categoryRow(it) }
            BackupDataset.TRANSACTIONS -> dao.liveTransaction(stableId)?.let { txnRow(it) }
            BackupDataset.LEDGER_ENTRIES -> dao.liveLedgerEntry(stableId)?.let { ledgerRow(it) }
            BackupDataset.BUDGETS -> dao.liveBudget(stableId)?.let { budgetRow(it) }
            BackupDataset.MERCHANTS -> dao.liveMerchant(stableId)?.let { merchantRow(it) }
            else -> null
        }

    override suspend fun liveIds(dataset: BackupDataset): List<String> = when (dataset) {
        BackupDataset.ACCOUNTS -> dao.liveAccountIds()
        BackupDataset.CATEGORIES -> dao.liveCategoryIds()
        BackupDataset.TRANSACTIONS -> dao.liveTransactionIds()
        BackupDataset.LEDGER_ENTRIES -> dao.liveLedgerEntryIds()
        BackupDataset.BUDGETS -> dao.liveBudgetIds()
        BackupDataset.MERCHANTS -> dao.liveMerchantIds()
        else -> emptyList()
    }

    // ------------------------------------------------------------------
    // COMMIT — single transaction inside the DAO
    // ------------------------------------------------------------------

    override suspend fun commitStaged(
        policy: com.example.fintrack.domain.model.MergePolicy,
        replaceIds: Map<BackupDataset, Set<String>>,
    ): Pair<Map<BackupDataset, Int>, Map<BackupDataset, Int>> {
        val batch = dao.activeBatch() ?: throw IllegalStateException("no active import batch")
        fun idsOf(ds: BackupDataset) = replaceIds[ds]?.toList() ?: emptyList()

        val counts = dao.commitStagedBatch(
            batchId = batch.id,
            replaceTransactionIds = idsOf(BackupDataset.TRANSACTIONS),
            replaceAccountIds = idsOf(BackupDataset.ACCOUNTS),
            replaceCategoryIds = idsOf(BackupDataset.CATEGORIES),
            replaceLedgerEntryIds = idsOf(BackupDataset.LEDGER_ENTRIES),
            replaceBudgetIds = idsOf(BackupDataset.BUDGETS),
            replaceMerchantIds = idsOf(BackupDataset.MERCHANTS),
        )
        dao.updateBatchStatus(batch.id, "COMMITTED")
        return Pair(
            BackupDataset.entries.associateWith { counts.inserted },
            BackupDataset.entries.associateWith { counts.replaced },
        )
    }

    /** Batch lifecycle used by the service wrapper before staging. */
    suspend fun beginBatchReturningId(
        formatVersion: Int,
        schemaVersion: Int,
        totalRows: Int,
    ): String {
        clearStaging()
        val id = UUID.randomUUID().toString()
        dao.insertBatch(
            ImportBatchEntity(
                id = id,
                createdAtEpochMs = System.currentTimeMillis(),
                status = "STAGED",
                formatVersion = formatVersion,
                schemaVersion = schemaVersion,
                totalStagedRows = totalRows,
            ),
        )
        return id
    }

    override suspend fun beginBatch(formatVersion: Int, schemaVersion: Int, totalRows: Int) {
        beginBatchReturningId(formatVersion, schemaVersion, totalRows)
    }

    // ------------------------------------------------------------------
    // Canonical row serialization (deterministic field order)
    // ------------------------------------------------------------------

    private fun kv(pairs: List<Pair<String, Any?>>): String =
        pairs.joinToString(";") { (k, v) ->
            "$k=" + when (v) {
                null -> NULL
                is Boolean -> v.toString()
                is Int -> v.toString()
                is Long -> v.toString()
                is Double -> v.toString()
                else -> esc(v.toString())
            }
        }

    private fun esc(s: String) = s.replace("\\", "\\\\").replace(";", "\\;").replace("=", "\\=")

    private fun stableIdOf(canonical: String): String =
        canonical.split(';').firstOrNull { it.startsWith("id=") }?.removePrefix("id=") ?: ""

    companion object {
        const val NULL = "\\N"
    }

    private fun accountRow(e: com.example.fintrack.data.db.AccountEntity) = kv(
        listOf(
            "id" to e.id, "name" to e.name, "normalizedName" to e.normalizedName,
            "currencyCode" to e.currencyCode, "accountType" to e.accountType,
            "createdAtEpochMs" to e.createdAtEpochMs, "lifecycle" to e.lifecycle,
            "nickname" to e.nickname, "last4" to e.last4, "institutionName" to e.institutionName,
        ),
    )

    private fun categoryRow(e: com.example.fintrack.data.db.CategoryEntity) = kv(
        listOf(
            "id" to e.id, "name" to e.name, "normalizedName" to e.normalizedName,
            "parentId" to e.parentId, "status" to e.status, "kind" to e.kind,
            "sortOrder" to e.sortOrder, "createdAtEpochMs" to e.createdAtEpochMs,
        ),
    )

    /**
     * Transaction export row. NOTE: free-text fields (merchant/description/
     * counterparty) pass through the export redaction engine so unmasked
     * phones/account numbers never leave the device. Raw SMS bodies are NOT
     * part of this dataset at all.
     */
    private fun txnRow(e: com.example.fintrack.data.db.TransactionEntity): String {
        val redactedMerchant = ExportRedactionSafe.text(e.merchant)
        val redactedDescription = ExportRedactionSafe.text(e.description)
        val redactedCounterparty = ExportRedactionSafe.text(e.counterparty)
        return kv(
            listOf(
                "id" to e.id, "messageId" to e.messageId, "accountId" to e.accountId,
                "categoryId" to e.categoryId, "amountMinor" to e.amountMinor,
                "currencyCode" to e.currencyCode, "occurredAtEpochMs" to e.occurredAtEpochMs,
                "localDateEpochDay" to e.localDateEpochDay,
                "counterparty" to redactedCounterparty,
                "counterpartyNormalized" to e.counterpartyNormalized,
                "referenceId" to e.referenceId, "state" to e.state,
                "sourceKind" to e.sourceKind, "sourceVersion" to e.sourceVersion,
                "sourceReason" to e.sourceReason,
                "correctionSourceKind" to e.correctionSourceKind,
                "correctionSourceVersion" to e.correctionSourceVersion,
                "correctionSourceReason" to e.correctionSourceReason,
                "correctionCapturedAtEpochMs" to e.correctionCapturedAtEpochMs,
                "dedupeKey" to e.dedupeKey, "kind" to e.kind, "subtype" to e.subtype,
                "status" to e.status, "merchant" to redactedMerchant,
                "description" to redactedDescription, "rail" to e.rail,
                "cardMask" to e.cardMask, "postingGroupId" to e.postingGroupId,
                "transferGroupId" to e.transferGroupId,
                "deletedAtEpochMs" to e.deletedAtEpochMs, "deletedReason" to e.deletedReason,
            ),
        )
    }

    private fun ledgerRow(e: com.example.fintrack.data.db.LedgerEntryEntity) = kv(
        listOf(
            "id" to e.id, "transactionId" to e.transactionId, "accountId" to e.accountId,
            "direction" to e.direction, "amountMinor" to e.amountMinor,
            "currencyCode" to e.currencyCode, "postingGroupId" to e.postingGroupId,
            "memo" to ExportRedactionSafe.text(e.memo),
        ),
    )

    private fun transferRow(e: com.example.fintrack.data.db.TransferEntity) = kv(
        listOf(
            "id" to e.id, "fromEntryId" to e.fromEntryId, "toEntryId" to e.toEntryId,
            "kind" to e.kind, "sourceKind" to e.sourceKind, "sourceVersion" to e.sourceVersion,
        ),
    )

    private fun obRow(e: com.example.fintrack.data.db.AccountOpeningBalanceEntity) = kv(
        listOf(
            "id" to e.id, "accountId" to e.accountId, "amountMinor" to e.amountMinor,
            "currencyCode" to e.currencyCode, "asOfEpochMs" to e.asOfEpochMs,
        ),
    )

    private fun snapRow(e: com.example.fintrack.data.db.BalanceSnapshotEntity) = kv(
        listOf(
            "id" to e.id, "accountId" to e.accountId, "amountMinor" to e.amountMinor,
            "currencyCode" to e.currencyCode, "kind" to e.kind, "messageId" to e.messageId,
            "capturedAtEpochMs" to e.capturedAtEpochMs, "sourceKind" to e.sourceKind,
            "sourceVersion" to e.sourceVersion, "snapshotIdentity" to e.snapshotIdentity,
        ),
    )

    private fun mappingRow(e: com.example.fintrack.data.db.SenderAccountMappingEntity) = kv(
        listOf(
            "id" to e.id, "senderId" to RedactionEngine.sha256(e.senderId).take(16),
            "accountId" to e.accountId, "confirmedByUser" to e.confirmedByUser,
            "sourceKind" to e.sourceKind, "sourceVersion" to e.sourceVersion,
            "createdAtEpochMs" to e.createdAtEpochMs,
        ),
    )

    private fun aliasRow(e: com.example.fintrack.data.db.InstitutionAliasEntity) = kv(
        listOf(
            "id" to e.id, "aliasRaw" to e.aliasRaw, "aliasNormalized" to e.aliasNormalized,
            "canonicalInstitution" to e.canonicalInstitution,
            "confirmedByUser" to e.confirmedByUser,
        ),
    )

    private fun refundLinkRow(e: com.example.fintrack.data.db.RefundLinkEntity) = kv(
        listOf(
            "id" to e.id, "refundedEventId" to e.refundedEventId,
            "refundEventId" to e.refundEventId, "kind" to e.kind,
            "amountMinor" to e.amountMinor, "currencyCode" to e.currencyCode,
            "sourceKind" to e.sourceKind, "sourceVersion" to e.sourceVersion,
            "sourceReason" to e.sourceReason, "refundIdentity" to e.refundIdentity,
            "createdAtEpochMs" to e.createdAtEpochMs,
        ),
    )

    private fun txLinkRow(e: com.example.fintrack.data.db.TransactionLinkEntity) = kv(
        listOf(
            "id" to e.id, "parentEventId" to e.parentEventId,
            "childEventId" to e.childEventId, "role" to e.role,
            "sourceKind" to e.sourceKind, "sourceVersion" to e.sourceVersion,
            "sourceReason" to e.sourceReason, "linkIdentity" to e.linkIdentity,
            "createdAtEpochMs" to e.createdAtEpochMs,
        ),
    )

    private fun merchantRow(e: com.example.fintrack.data.db.MerchantEntity) = kv(
        listOf(
            "id" to e.id, "displayName" to ExportRedactionSafe.text(e.displayName),
            "normalizedName" to e.normalizedName, "accountId" to e.accountId,
            "status" to e.status, "merchantIdentity" to e.merchantIdentity,
            "sourceKind" to e.sourceKind, "sourceVersion" to e.sourceVersion,
            "createdAtEpochMs" to e.createdAtEpochMs,
            "mergedIntoMerchantId" to e.mergedIntoMerchantId,
        ),
    )

    private fun mAliasRow(e: com.example.fintrack.data.db.MerchantAliasEntity) = kv(
        listOf(
            "id" to e.id, "merchantId" to e.merchantId, "aliasRaw" to e.aliasRaw,
            "aliasNormalized" to e.aliasNormalized, "aliasIdentity" to e.aliasIdentity,
            "sourceKind" to e.sourceKind, "sourceVersion" to e.sourceVersion,
            "createdAtEpochMs" to e.createdAtEpochMs,
        ),
    )

    private fun ruleRow(e: com.example.fintrack.data.db.CategoryRuleEntity) = kv(
        listOf(
            "id" to e.id, "name" to e.name, "priority" to e.priority, "status" to e.status,
            "matchKind" to e.matchKind, "matchValue" to e.matchValue,
            "merchantId" to e.merchantId, "categoryId" to e.categoryId,
            "sourceKind" to e.sourceKind, "sourceVersion" to e.sourceVersion,
            "createdAtEpochMs" to e.createdAtEpochMs, "createdBy" to e.createdBy,
        ),
    )

    private fun vpaRow(e: com.example.fintrack.data.db.MerchantVpaBindingEntity) = kv(
        listOf(
            "id" to e.id, "merchantId" to e.merchantId, "vpa" to e.vpa,
            "vpaIdentity" to e.vpaIdentity, "confirmedByUser" to e.confirmedByUser,
            "sourceKind" to e.sourceKind, "sourceVersion" to e.sourceVersion,
            "createdAtEpochMs" to e.createdAtEpochMs,
        ),
    )

    private fun reviewRow(e: com.example.fintrack.data.db.ReviewItemEntity) = kv(
        listOf(
            "id" to e.id, "transactionId" to e.transactionId, "reason" to e.reason,
            "priority" to e.priority, "status" to e.status,
            "createdAtEpochMs" to e.createdAtEpochMs,
            "resolvedAtEpochMs" to e.resolvedAtEpochMs, "explanation" to e.explanation,
            "sourceKind" to e.sourceKind, "sourceVersion" to e.sourceVersion,
        ),
    )

    private fun splitRow(e: com.example.fintrack.data.db.TransactionSplitEntity) = kv(
        listOf(
            "id" to e.id, "parentTransactionId" to e.parentTransactionId,
            "childTransactionId" to e.childTransactionId,
            "splitIdentity" to e.splitIdentity, "sourceKind" to e.sourceKind,
            "sourceVersion" to e.sourceVersion, "createdAtEpochMs" to e.createdAtEpochMs,
        ),
    )

    private fun reimbRow(e: com.example.fintrack.data.db.ReimbursementLinkEntity) = kv(
        listOf(
            "id" to e.id, "expenseTransactionId" to e.expenseTransactionId,
            "reimbursingTransactionId" to e.reimbursingTransactionId,
            "linkIdentity" to e.linkIdentity, "sourceKind" to e.sourceKind,
            "sourceVersion" to e.sourceVersion, "createdAtEpochMs" to e.createdAtEpochMs,
        ),
    )

    private fun travelRow(e: com.example.fintrack.data.db.TravelModeEntity) = kv(
        listOf(
            "id" to e.id, "accountId" to e.accountId, "label" to e.label,
            "currencyCode" to e.currencyCode, "startEpochDay" to e.startEpochDay,
            "endEpochDay" to e.endEpochDay, "status" to e.status,
            "sourceKind" to e.sourceKind, "sourceVersion" to e.sourceVersion,
            "createdAtEpochMs" to e.createdAtEpochMs,
        ),
    )

    private fun tagRow(e: com.example.fintrack.data.db.TransactionTagEntity) = kv(
        listOf(
            "id" to e.id, "transactionId" to e.transactionId, "tag" to e.tag,
            "sourceKind" to e.sourceKind, "sourceVersion" to e.sourceVersion,
            "createdAtEpochMs" to e.createdAtEpochMs,
        ),
    )

    private fun noteRow(e: com.example.fintrack.data.db.TransactionNoteEntity) = kv(
        listOf(
            "id" to e.id, "transactionId" to e.transactionId,
            "note" to ExportRedactionSafe.text(e.note), "sourceKind" to e.sourceKind,
            "sourceVersion" to e.sourceVersion, "createdAtEpochMs" to e.createdAtEpochMs,
            "updatedAtEpochMs" to e.updatedAtEpochMs,
        ),
    )

    private fun budgetRow(e: com.example.fintrack.data.db.BudgetEntity) = kv(
        listOf(
            "id" to e.id, "name" to e.name, "scopeKind" to e.scopeKind,
            "categoryId" to e.categoryId, "accountId" to e.accountId,
            "periodType" to e.periodType, "startDayOfMonth" to e.startDayOfMonth,
            "targetAmountMinor" to e.targetAmountMinor, "currencyCode" to e.currencyCode,
            "rolloverEnabled" to e.rolloverEnabled, "rolloverCapMinor" to e.rolloverCapMinor,
            "exclusionsJson" to e.exclusionsJson, "scopeIdentity" to e.scopeIdentity,
            "status" to e.status, "sourceKind" to e.sourceKind,
            "sourceVersion" to e.sourceVersion, "createdAtEpochMs" to e.createdAtEpochMs,
        ),
    )

    private fun bpRow(e: com.example.fintrack.data.db.BudgetPeriodEntity) = kv(
        listOf(
            "id" to e.id, "budgetId" to e.budgetId,
            "periodStartEpochDay" to e.periodStartEpochDay,
            "periodEndEpochDay" to e.periodEndEpochDay,
            "rolloverInMinor" to e.rolloverInMinor, "boundaryAction" to e.boundaryAction,
            "computedAtEpochMs" to e.computedAtEpochMs,
        ),
    )

    private fun rpRow(e: com.example.fintrack.data.db.RecurringPatternEntity) = kv(
        listOf(
            "id" to e.id, "patternIdentity" to e.patternIdentity,
            "accountId" to e.accountId, "counterpartyNormalized" to e.counterpartyNormalized,
            "merchant" to ExportRedactionSafe.text(e.merchant), "categoryId" to e.categoryId,
            "periodicity" to e.periodicity, "intervalDays" to e.intervalDays,
            "canonicalAmountMinor" to e.canonicalAmountMinor,
            "minObservedAmountMinor" to e.minObservedAmountMinor,
            "maxObservedAmountMinor" to e.maxObservedAmountMinor,
            "currencyCode" to e.currencyCode, "confidence" to e.confidence,
            "firstSeenEpochMs" to e.firstSeenEpochMs, "lastSeenEpochMs" to e.lastSeenEpochMs,
            "nextExpectedEpochMs" to e.nextExpectedEpochMs, "status" to e.status,
            "isSubscription" to e.isSubscription, "decidedBy" to e.decidedBy,
            "sourceKind" to e.sourceKind, "sourceVersion" to e.sourceVersion,
            "createdAtEpochMs" to e.createdAtEpochMs, "updatedAtEpochMs" to e.updatedAtEpochMs,
        ),
    )

    private fun roRow(e: com.example.fintrack.data.db.RecurringObservationEntity) = kv(
        listOf(
            "id" to e.id, "patternId" to e.patternId, "transactionId" to e.transactionId,
            "amountMinor" to e.amountMinor, "occurredAtEpochMs" to e.occurredAtEpochMs,
            "observationIdentity" to e.observationIdentity,
            "createdAtEpochMs" to e.createdAtEpochMs,
        ),
    )

    private fun crRow(e: com.example.fintrack.data.db.CashReconciliationEntity) = kv(
        listOf(
            "id" to e.id, "accountId" to e.accountId, "countedMinor" to e.countedMinor,
            "ledgerDerivedMinor" to e.ledgerDerivedMinor,
            "differenceMinor" to e.differenceMinor, "outcome" to e.outcome,
            "adjustmentTransactionId" to e.adjustmentTransactionId, "reason" to e.reason,
            "reconciliationIdentity" to e.reconciliationIdentity,
            "sourceKind" to e.sourceKind, "sourceVersion" to e.sourceVersion,
            "atEpochMs" to e.atEpochMs,
        ),
    )

    private fun atmRow(e: com.example.fintrack.data.db.AtmCashLinkEntity) = kv(
        listOf(
            "id" to e.id, "withdrawalTransactionId" to e.withdrawalTransactionId,
            "cashAccountId" to e.cashAccountId, "amountMinor" to e.amountMinor,
            "currencyCode" to e.currencyCode,
            "withdrawalOccurredAtEpochMs" to e.withdrawalOccurredAtEpochMs,
            "matchedBy" to e.matchedBy, "candidateCount" to e.candidateCount,
            "ambiguous" to e.ambiguous, "confirmedByUser" to e.confirmedByUser,
            "linkIdentity" to e.linkIdentity, "sourceKind" to e.sourceKind,
            "sourceVersion" to e.sourceVersion, "createdAtEpochMs" to e.createdAtEpochMs,
        ),
    )
}

/**
 * Free-text fields go through the export redactor exactly once on the way
 * out. Centralized here so every serializer calls the same path.
 */
private object ExportRedactionSafe {
    fun text(raw: String?): String? =
        raw?.let { com.example.fintrack.domain.service.ExportRedactionEngine.redactForExport(it).text }
}
