package com.example.fintrack.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction

/**
 * Stage 11 (P23 + P24) data layer.
 *
 * P23: export reads (canonical row serialization per dataset), import
 * staging (temporary-by-contract tables) and the single atomic commit that
 * moves staged rows into live tables. The commit is the ONLY path from
 * staging to live data and runs inside one Room @Transaction so a failed
 * restore can never leave the database half-written.
 *
 * P24: settings profiles, retention-bounded audit log and app-lock state.
 */
@Dao
interface FinanceDaoV9 {

    // =====================================================================
    // P23 — export reads
    // =====================================================================

    @Query("SELECT * FROM accounts ORDER BY id")
    suspend fun exportAccounts(): List<AccountEntity>

    @Query("SELECT * FROM categories ORDER BY id")
    suspend fun exportCategories(): List<CategoryEntity>

    @Query("SELECT * FROM transactions ORDER BY id")
    suspend fun exportTransactions(): List<TransactionEntity>

    @Query("SELECT * FROM ledger_entries ORDER BY id")
    suspend fun exportLedgerEntries(): List<LedgerEntryEntity>

    @Query("SELECT * FROM transfers ORDER BY id")
    suspend fun exportTransfers(): List<TransferEntity>

    @Query("SELECT * FROM account_opening_balances ORDER BY accountId")
    suspend fun exportOpeningBalances(): List<AccountOpeningBalanceEntity>

    @Query("SELECT * FROM balance_snapshots ORDER BY capturedAtEpochMs, id")
    suspend fun exportSnapshots(): List<BalanceSnapshotEntity>

    @Query("SELECT * FROM sender_account_mappings ORDER BY id")
    suspend fun exportSenderMappings(): List<SenderAccountMappingEntity>

    @Query("SELECT * FROM institution_aliases ORDER BY aliasNormalized")
    suspend fun exportInstitutionAliases(): List<InstitutionAliasEntity>

    @Query("SELECT * FROM refund_links ORDER BY id")
    suspend fun exportRefundLinks(): List<RefundLinkEntity>

    @Query("SELECT * FROM transaction_links ORDER BY id")
    suspend fun exportTransactionLinks(): List<TransactionLinkEntity>

    @Query("SELECT * FROM merchants ORDER BY id")
    suspend fun exportMerchants(): List<MerchantEntity>

    @Query("SELECT * FROM merchant_aliases ORDER BY id")
    suspend fun exportMerchantAliases(): List<MerchantAliasEntity>

    @Query("SELECT * FROM category_rules ORDER BY priority, id")
    suspend fun exportCategoryRules(): List<CategoryRuleEntity>

    @Query("SELECT * FROM merchant_vpa_bindings ORDER BY vpa")
    suspend fun exportVpaBindings(): List<MerchantVpaBindingEntity>

    @Query("SELECT * FROM review_items ORDER BY id")
    suspend fun exportReviewItems(): List<ReviewItemEntity>

    @Query("SELECT * FROM transaction_splits ORDER BY id")
    suspend fun exportSplits(): List<TransactionSplitEntity>

    @Query("SELECT * FROM reimbursement_links ORDER BY id")
    suspend fun exportReimbursementLinks(): List<ReimbursementLinkEntity>

    @Query("SELECT * FROM travel_modes ORDER BY startEpochDay")
    suspend fun exportTravelModes(): List<TravelModeEntity>

    @Query("SELECT * FROM transaction_tags ORDER BY transactionId, tag")
    suspend fun exportTags(): List<TransactionTagEntity>

    @Query("SELECT * FROM transaction_notes ORDER BY transactionId")
    suspend fun exportNotes(): List<TransactionNoteEntity>

    @Query("SELECT * FROM budgets ORDER BY id")
    suspend fun exportBudgets(): List<BudgetEntity>

    @Query("SELECT * FROM budget_periods ORDER BY budgetId, periodStartEpochDay")
    suspend fun exportBudgetPeriods(): List<BudgetPeriodEntity>

    @Query("SELECT * FROM recurring_patterns ORDER BY id")
    suspend fun exportRecurringPatterns(): List<RecurringPatternEntity>

    @Query("SELECT * FROM recurring_observations ORDER BY patternId, transactionId")
    suspend fun exportRecurringObservations(): List<RecurringObservationEntity>

    @Query("SELECT * FROM cash_reconciliations ORDER BY atEpochMs")
    suspend fun exportCashReconciliations(): List<CashReconciliationEntity>

    @Query("SELECT * FROM atm_cash_links ORDER BY withdrawalTransactionId")
    suspend fun exportAtmCashLinks(): List<AtmCashLinkEntity>

    // =====================================================================
    // P23 — live row lookups (conflict detection)
    // =====================================================================

    @Query("SELECT * FROM transactions WHERE id = :id LIMIT 1")
    suspend fun liveTransaction(id: String): TransactionEntity?

    @Query("SELECT id FROM transactions")
    suspend fun liveTransactionIds(): List<String>

    @Query("SELECT * FROM accounts WHERE id = :id LIMIT 1")
    suspend fun liveAccount(id: String): AccountEntity?

    @Query("SELECT id FROM accounts")
    suspend fun liveAccountIds(): List<String>

    @Query("SELECT * FROM categories WHERE id = :id LIMIT 1")
    suspend fun liveCategory(id: String): CategoryEntity?

    @Query("SELECT id FROM categories")
    suspend fun liveCategoryIds(): List<String>

    @Query("SELECT * FROM ledger_entries WHERE id = :id LIMIT 1")
    suspend fun liveLedgerEntry(id: String): LedgerEntryEntity?

    @Query("SELECT id FROM ledger_entries")
    suspend fun liveLedgerEntryIds(): List<String>

    @Query("SELECT * FROM budgets WHERE id = :id LIMIT 1")
    suspend fun liveBudget(id: String): BudgetEntity?

    @Query("SELECT id FROM budgets")
    suspend fun liveBudgetIds(): List<String>

    @Query("SELECT * FROM merchants WHERE id = :id LIMIT 1")
    suspend fun liveMerchant(id: String): MerchantEntity?

    @Query("SELECT id FROM merchants")
    suspend fun liveMerchantIds(): List<String>

    // =====================================================================
    // P23 — staging
    // =====================================================================

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertStagedRows(rows: List<ImportStagingRowEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBatch(batch: ImportBatchEntity)

    @Query("UPDATE import_batches SET status = :status WHERE id = :batchId")
    suspend fun updateBatchStatus(batchId: String, status: String)

    @Query("SELECT * FROM import_batches WHERE status IN ('STAGED','PREVIEWED') ORDER BY createdAtEpochMs DESC LIMIT 1")
    suspend fun activeBatch(): ImportBatchEntity?

    @Query("SELECT * FROM import_staging_rows WHERE batchId = :batchId AND dataset = :dataset ORDER BY stableId")
    suspend fun stagedRows(batchId: String, dataset: String): List<ImportStagingRowEntity>

    @Query("SELECT COUNT(*) FROM import_staging_rows WHERE batchId = :batchId AND dataset = :dataset")
    suspend fun stagedRowCount(batchId: String, dataset: String): Int

    @Query("DELETE FROM import_staging_rows WHERE batchId = :batchId")
    suspend fun clearStagingRows(batchId: String)

    @Query("DELETE FROM import_batches WHERE id = :batchId")
    suspend fun deleteBatch(batchId: String)

    /**
     * THE single write path from staging into live tables.
     * Runs in one Room @Transaction:
     *  - inserts staged rows whose stable ids don't exist live;
     *  - replaces rows listed in the replace* lists only when the user
     *    explicitly chose REPLACE;
     *  - everything else is skipped (KEEP_LIVE semantics).
     */
    @Transaction
    suspend fun commitStagedBatch(
        batchId: String,
        replaceTransactionIds: List<String>,
        replaceAccountIds: List<String>,
        replaceCategoryIds: List<String>,
        replaceLedgerEntryIds: List<String>,
        replaceBudgetIds: List<String>,
        replaceMerchantIds: List<String>,
    ): CommitCounts {
        var inserted = 0
        var replaced = 0

        for (row in stagedAccounts(batchId)) {
            val entity = decodeAccount(row.canonicalRow) ?: continue
            val res = if (row.stableId in replaceAccountIds) {
                upsertAccountReplace(entity)
            } else {
                upsertAccountIgnore(entity)
            }
            // OnConflictStrategy.IGNORE returns -1L when the row was SKIPPED
            // (a live row already exists); REPLACE always writes and returns
            // a real rowId. So -1L means "kept live", never "replaced".
            if (res == -1L) { /* kept live — skipped */ } else { inserted++ }
        }
        for (row in stagedCategories(batchId)) {
            val entity = decodeCategory(row.canonicalRow) ?: continue
            val res = if (row.stableId in replaceCategoryIds) {
                upsertCategoryReplace(entity)
            } else {
                upsertCategoryIgnore(entity)
            }
            if (res == -1L) { /* kept live */ } else { inserted++ }
        }
        for (row in stagedTransactions(batchId)) {
            val entity = decodeTransaction(row.canonicalRow) ?: continue
            val res = if (row.stableId in replaceTransactionIds) {
                upsertTxnReplace(entity)
            } else {
                upsertTxnIgnore(entity)
            }
            if (res == -1L) { /* kept live */ } else { inserted++ }
        }
        for (row in stagedLedgerEntries(batchId)) {
            val entity = decodeLedgerEntry(row.canonicalRow) ?: continue
            val res = if (row.stableId in replaceLedgerEntryIds) {
                upsertLedgerEntryReplace(entity)
            } else {
                upsertLedgerEntryIgnore(entity)
            }
            if (res == -1L) { /* kept live */ } else { inserted++ }
        }
        for (row in stagedBudgets(batchId)) {
            val entity = decodeBudget(row.canonicalRow) ?: continue
            val res = if (row.stableId in replaceBudgetIds) {
                upsertBudgetReplace(entity)
            } else {
                upsertBudgetIgnore(entity)
            }
            if (res == -1L) { /* kept live */ } else { inserted++ }
        }
        for (row in stagedMerchants(batchId)) {
            val entity = decodeMerchant(row.canonicalRow) ?: continue
            val res = if (row.stableId in replaceMerchantIds) {
                upsertMerchantReplace(entity)
            } else {
                upsertMerchantIgnore(entity)
            }
            if (res == -1L) { /* kept live */ } else { inserted++ }
        }
        return CommitCounts(inserted, replaced)
    }

    data class CommitCounts(val inserted: Int, val replaced: Int)

    // Canonical-row decoders. Kept next to the commit transaction so the
    // staging format and its consumer can never drift apart silently.

    private fun fields(row: String): Map<String, String> = row.split(';').mapNotNull {
        val i = it.indexOf('=')
        if (i <= 0) null else it.substring(0, i) to unesc(it.substring(i + 1))
    }.toMap()

    private fun unesc(s: String): String = s.replace("\\=", "=").replace("\\;", ";").replace("\\\\", "\\")
    private fun nul(fields: Map<String, String>, key: String): String? =
        fields[key]?.takeIf { it != "\\N" }
    private fun lng(fields: Map<String, String>, key: String, dflt: Long = 0L): Long =
        fields[key]?.toLongOrNull() ?: dflt
    private fun bool(fields: Map<String, String>, key: String): Boolean = fields[key] == "true"

    private fun decodeAccount(row: String): AccountEntity? {
        val f = fields(row)
        val id = f["id"] ?: return null
        return AccountEntity(
            id = id,
            name = f["name"] ?: return null,
            normalizedName = f["normalizedName"] ?: return null,
            currencyCode = f["currencyCode"] ?: "INR",
            accountType = f["accountType"] ?: "BANK",
            createdAtEpochMs = lng(f, "createdAtEpochMs"),
            lifecycle = f["lifecycle"] ?: "ACTIVE",
            nickname = nul(f, "nickname"),
            last4 = nul(f, "last4"),
            institutionName = nul(f, "institutionName"),
        )
    }

    private fun decodeCategory(row: String): CategoryEntity? {
        val f = fields(row)
        val id = f["id"] ?: return null
        return CategoryEntity(
            id = id,
            name = f["name"] ?: return null,
            normalizedName = f["normalizedName"] ?: return null,
            parentId = nul(f, "parentId"),
            status = f["status"] ?: "ACTIVE",
            kind = f["kind"] ?: "TAXONOMY",
            sortOrder = lng(f, "sortOrder").toInt(),
            createdAtEpochMs = lng(f, "createdAtEpochMs"),
        )
    }

    private fun decodeTransaction(row: String): TransactionEntity? {
        val f = fields(row)
        val id = f["id"] ?: return null
        return TransactionEntity(
            id = id,
            messageId = nul(f, "messageId"),
            accountId = f["accountId"] ?: return null,
            categoryId = nul(f, "categoryId"),
            amountMinor = lng(f, "amountMinor"),
            currencyCode = f["currencyCode"] ?: "INR",
            occurredAtEpochMs = lng(f, "occurredAtEpochMs"),
            localDateEpochDay = lng(f, "localDateEpochDay"),
            counterparty = nul(f, "counterparty"),
            counterpartyNormalized = nul(f, "counterpartyNormalized"),
            referenceId = nul(f, "referenceId"),
            state = f["state"] ?: "POSTED",
            sourceKind = f["sourceKind"] ?: "IMPORT_FILE",
            sourceVersion = f["sourceVersion"] ?: "import-v1",
            sourceReason = nul(f, "sourceReason"),
            correctionSourceKind = nul(f, "correctionSourceKind"),
            correctionSourceVersion = nul(f, "correctionSourceVersion"),
            correctionSourceReason = nul(f, "correctionSourceReason"),
            correctionCapturedAtEpochMs = lng(f, "correctionCapturedAtEpochMs", -1L).takeIf { it >= 0 },
            dedupeKey = f["dedupeKey"] ?: id, // fallback keeps unique index satisfied
            kind = f["kind"] ?: "UNKNOWN",
            subtype = nul(f, "subtype"),
            status = f["status"] ?: "POSTED",
            merchant = nul(f, "merchant"),
            description = nul(f, "description"),
            rail = nul(f, "rail"),
            cardMask = nul(f, "cardMask"),
            postingGroupId = nul(f, "postingGroupId"),
            transferGroupId = nul(f, "transferGroupId"),
            deletedAtEpochMs = lng(f, "deletedAtEpochMs", -1L).takeIf { it >= 0 },
            deletedReason = nul(f, "deletedReason"),
        )
    }

    private fun decodeLedgerEntry(row: String): LedgerEntryEntity? {
        val f = fields(row)
        val id = f["id"] ?: return null
        return LedgerEntryEntity(
            id = id,
            transactionId = f["transactionId"] ?: return null,
            accountId = f["accountId"] ?: return null,
            direction = f["direction"] ?: "DEBIT",
            amountMinor = lng(f, "amountMinor"),
            currencyCode = f["currencyCode"] ?: "INR",
            postingGroupId = nul(f, "postingGroupId"),
            memo = nul(f, "memo"),
        )
    }

    private fun decodeBudget(row: String): BudgetEntity? {
        val f = fields(row)
        val id = f["id"] ?: return null
        return BudgetEntity(
            id = id,
            name = f["name"] ?: return null,
            scopeKind = f["scopeKind"] ?: "OVERALL",
            categoryId = nul(f, "categoryId"),
            accountId = nul(f, "accountId"),
            periodType = f["periodType"] ?: "MONTHLY",
            startDayOfMonth = lng(f, "startDayOfMonth", 1).toInt(),
            targetAmountMinor = lng(f, "targetAmountMinor"),
            currencyCode = f["currencyCode"] ?: "INR",
            rolloverEnabled = bool(f, "rolloverEnabled"),
            rolloverCapMinor = lng(f, "rolloverCapMinor", -1L).takeIf { it >= 0 },
            exclusionsJson = f["exclusionsJson"] ?: "",
            scopeIdentity = f["scopeIdentity"] ?: id,
            status = f["status"] ?: "ACTIVE",
            sourceKind = f["sourceKind"] ?: "IMPORT_FILE",
            sourceVersion = f["sourceVersion"] ?: "import-v1",
            createdAtEpochMs = lng(f, "createdAtEpochMs"),
        )
    }

    private fun decodeMerchant(row: String): MerchantEntity? {
        val f = fields(row)
        val id = f["id"] ?: return null
        return MerchantEntity(
            id = id,
            displayName = f["displayName"] ?: return null,
            normalizedName = f["normalizedName"] ?: return null,
            accountId = nul(f, "accountId"),
            status = f["status"] ?: "ACTIVE",
            merchantIdentity = f["merchantIdentity"] ?: id,
            sourceKind = f["sourceKind"] ?: "IMPORT_FILE",
            sourceVersion = f["sourceVersion"] ?: "import-v1",
            createdAtEpochMs = lng(f, "createdAtEpochMs"),
            mergedIntoMerchantId = nul(f, "mergedIntoMerchantId"),
        )
    }

    // ---- Stage 12 P25 #7: additional decoders for full restore fidelity ----

    private fun decodeTransfer(row: String): TransferEntity? {
        val f = fields(row)
        val id = f["id"] ?: return null
        return TransferEntity(
            id = id,
            fromEntryId = f["fromEntryId"] ?: return null,
            toEntryId = f["toEntryId"] ?: return null,
            kind = f["kind"] ?: "TRANSFER",
            sourceKind = f["sourceKind"] ?: "IMPORT_FILE",
            sourceVersion = f["sourceVersion"] ?: "import-v1",
        )
    }

    private fun decodeOpeningBalance(row: String): AccountOpeningBalanceEntity? {
        val f = fields(row)
        val id = f["id"] ?: return null
        return AccountOpeningBalanceEntity(
            id = id,
            accountId = f["accountId"] ?: return null,
            amountMinor = lng(f, "amountMinor"),
            currencyCode = f["currencyCode"] ?: "INR",
            asOfEpochMs = lng(f, "asOfEpochMs"),
        )
    }

    private fun decodeBalanceSnapshot(row: String): BalanceSnapshotEntity? {
        val f = fields(row)
        val id = f["id"] ?: return null
        return BalanceSnapshotEntity(
            id = id,
            accountId = f["accountId"] ?: return null,
            amountMinor = lng(f, "amountMinor"),
            currencyCode = f["currencyCode"] ?: "INR",
            kind = f["kind"] ?: "MANUAL_ACTUAL",
            messageId = nul(f, "messageId"),
            capturedAtEpochMs = lng(f, "capturedAtEpochMs"),
            sourceKind = f["sourceKind"] ?: "IMPORT_FILE",
            sourceVersion = f["sourceVersion"] ?: "import-v1",
            snapshotIdentity = f["snapshotIdentity"] ?: id,
        )
    }

    private fun decodeSenderMapping(row: String): SenderAccountMappingEntity? {
        val f = fields(row)
        val id = f["id"] ?: return null
        return SenderAccountMappingEntity(
            id = id,
            senderId = f["senderId"] ?: return null,
            accountId = f["accountId"] ?: return null,
            confirmedByUser = bool(f, "confirmedByUser"),
            sourceKind = f["sourceKind"] ?: "IMPORT_FILE",
            sourceVersion = f["sourceVersion"] ?: "import-v1",
            createdAtEpochMs = lng(f, "createdAtEpochMs"),
        )
    }

    private fun decodeRefundLink(row: String): RefundLinkEntity? {
        val f = fields(row)
        val id = f["id"] ?: return null
        return RefundLinkEntity(
            id = id,
            refundedEventId = f["refundedEventId"] ?: return null,
            refundEventId = f["refundEventId"] ?: return null,
            kind = f["kind"] ?: "FULL",
            amountMinor = lng(f, "amountMinor"),
            currencyCode = f["currencyCode"] ?: "INR",
            sourceKind = f["sourceKind"] ?: "IMPORT_FILE",
            sourceVersion = f["sourceVersion"] ?: "import-v1",
            sourceReason = nul(f, "sourceReason"),
            refundIdentity = f["refundIdentity"] ?: id,
            createdAtEpochMs = lng(f, "createdAtEpochMs"),
        )
    }

    private fun decodeTransactionLink(row: String): TransactionLinkEntity? {
        val f = fields(row)
        val id = f["id"] ?: return null
        return TransactionLinkEntity(
            id = id,
            parentEventId = f["parentEventId"] ?: return null,
            childEventId = f["childEventId"] ?: return null,
            role = f["role"] ?: "FEE",
            sourceKind = f["sourceKind"] ?: "IMPORT_FILE",
            sourceVersion = f["sourceVersion"] ?: "import-v1",
            sourceReason = nul(f, "sourceReason"),
            linkIdentity = f["linkIdentity"] ?: id,
            createdAtEpochMs = lng(f, "createdAtEpochMs"),
        )
    }

    private fun decodeBudgetPeriod(row: String): BudgetPeriodEntity? {
        val f = fields(row)
        val id = f["id"] ?: return null
        return BudgetPeriodEntity(
            id = id,
            budgetId = f["budgetId"] ?: return null,
            periodStartEpochDay = lng(f, "periodStartEpochDay"),
            periodEndEpochDay = lng(f, "periodEndEpochDay"),
            rolloverInMinor = lng(f, "rolloverInMinor"),
            boundaryAction = f["boundaryAction"] ?: "NONE",
            computedAtEpochMs = lng(f, "computedAtEpochMs"),
        )
    }

    private fun decodeRecurringPattern(row: String): RecurringPatternEntity? {
        val f = fields(row)
        val id = f["id"] ?: return null
        return RecurringPatternEntity(
            id = id,
            patternIdentity = f["patternIdentity"] ?: id,
            accountId = f["accountId"] ?: return null,
            counterpartyNormalized = nul(f, "counterpartyNormalized"),
            merchant = nul(f, "merchant"),
            categoryId = nul(f, "categoryId"),
            periodicity = f["periodicity"] ?: "MONTHLY",
            intervalDays = lng(f, "intervalDays", 30).toInt(),
            canonicalAmountMinor = lng(f, "canonicalAmountMinor"),
            minObservedAmountMinor = lng(f, "minObservedAmountMinor"),
            maxObservedAmountMinor = lng(f, "maxObservedAmountMinor"),
            currencyCode = f["currencyCode"] ?: "INR",
            confidence = f["confidence"]?.toDoubleOrNull() ?: 0.0,
            firstSeenEpochMs = lng(f, "firstSeenEpochMs"),
            lastSeenEpochMs = lng(f, "lastSeenEpochMs"),
            nextExpectedEpochMs = lng(f, "nextExpectedEpochMs", -1L).takeIf { it >= 0 },
            status = f["status"] ?: "DETECTED",
            isSubscription = f["isSubscription"]?.toBooleanStrictOrNull(),
            decidedBy = f["decidedBy"] ?: "SYSTEM",
            sourceKind = f["sourceKind"] ?: "IMPORT_FILE",
            sourceVersion = f["sourceVersion"] ?: "import-v1",
            createdAtEpochMs = lng(f, "createdAtEpochMs"),
            updatedAtEpochMs = lng(f, "updatedAtEpochMs"),
        )
    }

    private fun decodeRecurringObservation(row: String): RecurringObservationEntity? {
        val f = fields(row)
        val id = f["id"] ?: return null
        return RecurringObservationEntity(
            id = id,
            patternId = f["patternId"] ?: return null,
            transactionId = f["transactionId"] ?: return null,
            amountMinor = lng(f, "amountMinor"),
            occurredAtEpochMs = lng(f, "occurredAtEpochMs"),
            observationIdentity = f["observationIdentity"] ?: id,
            createdAtEpochMs = lng(f, "createdAtEpochMs"),
        )
    }

    // Per-dataset staged-row fetchers used by the repository's commit loop.
    @Query("SELECT * FROM import_staging_rows WHERE batchId = :batchId AND dataset = 'TRANSACTIONS' ORDER BY stableId")
    suspend fun stagedTransactions(batchId: String): List<ImportStagingRowEntity>

    @Query("SELECT * FROM import_staging_rows WHERE batchId = :batchId AND dataset = 'ACCOUNTS' ORDER BY stableId")
    suspend fun stagedAccounts(batchId: String): List<ImportStagingRowEntity>

    @Query("SELECT * FROM import_staging_rows WHERE batchId = :batchId AND dataset = 'CATEGORIES' ORDER BY stableId")
    suspend fun stagedCategories(batchId: String): List<ImportStagingRowEntity>

    @Query("SELECT * FROM import_staging_rows WHERE batchId = :batchId AND dataset = 'LEDGER_ENTRIES' ORDER BY stableId")
    suspend fun stagedLedgerEntries(batchId: String): List<ImportStagingRowEntity>

    @Query("SELECT * FROM import_staging_rows WHERE batchId = :batchId AND dataset = 'BUDGETS' ORDER BY stableId")
    suspend fun stagedBudgets(batchId: String): List<ImportStagingRowEntity>

    @Query("SELECT * FROM import_staging_rows WHERE batchId = :batchId AND dataset = 'MERCHANTS' ORDER BY stableId")
    suspend fun stagedMerchants(batchId: String): List<ImportStagingRowEntity>

    // Live upserts used by the commit transaction.
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun upsertAccountIgnore(account: AccountEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAccountReplace(account: AccountEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun upsertCategoryIgnore(category: CategoryEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertCategoryReplace(category: CategoryEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun upsertTxnIgnore(txn: TransactionEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTxnReplace(txn: TransactionEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun upsertLedgerEntryIgnore(entry: LedgerEntryEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLedgerEntryReplace(entry: LedgerEntryEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun upsertBudgetIgnore(budget: BudgetEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBudgetReplace(budget: BudgetEntity): Long

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun upsertMerchantIgnore(merchant: MerchantEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMerchantReplace(merchant: MerchantEntity): Long

    companion object {
        const val DATASET_ALL_PLACEHOLDER = "__all__"
    }
    // =====================================================================
    // P24 — settings profiles
    // =====================================================================

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSettingsProfile(profile: SettingsProfileEntity)

    @Query("SELECT * FROM settings_profiles ORDER BY name")
    suspend fun allSettingsProfiles(): List<SettingsProfileEntity>

    @Query("SELECT * FROM settings_profiles WHERE name = :name LIMIT 1")
    suspend fun findProfileByName(name: String): SettingsProfileEntity?

    @Query("DELETE FROM settings_profiles WHERE id = :id")
    suspend fun deleteSettingsProfile(id: String)

    // =====================================================================
    // P24 — audit log
    // =====================================================================

    @Insert
    suspend fun insertAuditLogEntry(entry: AuditLogEntryEntity)

    @Query("SELECT * FROM audit_log ORDER BY atEpochMs DESC LIMIT :limit")
    suspend fun recentAuditEntries(limit: Int): List<AuditLogEntryEntity>

    @Query("SELECT COUNT(*) FROM audit_log")
    suspend fun auditLogCount(): Int

    /** Retention pruning: delete entries older than the cutoff. */
    @Query("DELETE FROM audit_log WHERE atEpochMs < :cutoffEpochMs AND retention != 'FOREVER'")
    suspend fun pruneAuditLog(cutoffEpochMs: Long): Int

    // =====================================================================
    // P24 — app lock state
    // =====================================================================

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAppLockState(state: AppLockStateEntity)

    @Query("SELECT * FROM app_lock_state WHERE id = 1 LIMIT 1")
    suspend fun appLockState(): AppLockStateEntity?
}
