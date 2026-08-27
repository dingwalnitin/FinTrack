package com.example.fintrack.data

import com.example.fintrack.data.db.AccountEntity
import com.example.fintrack.data.db.AccountOpeningBalanceEntity
import com.example.fintrack.data.db.AuditEventEntity
import com.example.fintrack.data.db.BalanceSnapshotEntity
import com.example.fintrack.data.db.CategoryEntity
import com.example.fintrack.data.db.FinanceDaoV2
import com.example.fintrack.data.db.InstitutionAliasEntity
import com.example.fintrack.data.db.LedgerEntryEntity
import com.example.fintrack.data.db.MessageEntity
import com.example.fintrack.data.db.ProcessingJobEntity
import com.example.fintrack.data.db.SenderAccountMappingEntity
import com.example.fintrack.data.db.TransactionEntity
import com.example.fintrack.data.db.TransferEntity
import com.example.fintrack.data.repository.RoomFinanceRepositoryV2
import com.example.fintrack.domain.repository.FinanceRepositoryV2
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Full in-memory fake of the DAO surface for JVM tests. */
private class FakeDao : FinanceDaoV2 {
    val accounts = linkedMapOf<String, AccountEntity>()
    val opening = mutableMapOf<String, AccountOpeningBalanceEntity>()
    val snapshots = mutableListOf<BalanceSnapshotEntity>()
    val mappings = mutableMapOf<Pair<String, String>, SenderAccountMappingEntity>()
    val aliases = mutableListOf<InstitutionAliasEntity>()

    override fun observeAccounts(): Flow<List<AccountEntity>> = flowOf(accounts.values.toList())
    override suspend fun getAccount(id: String): AccountEntity? = accounts[id]
    override suspend fun updateAccount(
        id: String, name: String, normalizedName: String, currencyCode: String,
        accountType: String, lifecycle: String, nickname: String?, last4: String?,
        institutionName: String?,
    ) {
        accounts[id]?.let {
            accounts[id] = it.copy(
                name = name, normalizedName = normalizedName, currencyCode = currencyCode,
                accountType = accountType, lifecycle = lifecycle, nickname = nickname,
                last4 = last4, institutionName = institutionName,
            )
        }
    }
    override suspend fun insertAccount(account: AccountEntity): Long {
        if (accounts.containsKey(account.id)) return -1L
        accounts[account.id] = account; return 1L
    }
    override suspend fun insertOpeningBalance(ob: AccountOpeningBalanceEntity): Long =
        if (opening.containsKey(ob.accountId)) -1L else { opening[ob.accountId] = ob; 1L }
    override suspend fun getOpeningBalance(accountId: String) = opening[accountId]
    override suspend fun insertBalanceSnapshot(s: BalanceSnapshotEntity): Long =
        if (snapshots.any { it.snapshotIdentity == s.snapshotIdentity }) -1L
        else { snapshots += s; 1L }
    override suspend fun snapshotsForAccount(accountId: String) =
        snapshots.filter { it.accountId == accountId }.sortedByDescending { it.capturedAtEpochMs }
    override suspend fun latestSnapshot(accountId: String) = snapshotsForAccount(accountId).firstOrNull()
    override suspend fun ledgerEntriesForAccount(accountId: String) = emptyList<LedgerEntryEntity>()
    override suspend fun insertSenderMapping(m: SenderAccountMappingEntity): Long {
        val key = m.senderId to m.accountId
        return if (mappings.containsKey(key)) -1L else { mappings[key] = m; 1L }
    }
    override suspend fun confirmSenderMapping(senderId: String, accountId: String) {
        mappings[senderId to accountId]?.let { mappings[senderId to accountId] = it.copy(confirmedByUser = true) }
    }
    override suspend fun confirmedMappingsForSender(senderId: String) =
        mappings.filterKeys { it.first == senderId }.values.filter { it.confirmedByUser }
    override suspend fun mappingsForSender(senderId: String) =
        mappings.filterKeys { it.first == senderId }.values.toList()
    override suspend fun insertInstitutionAlias(a: InstitutionAliasEntity): Long =
        if (aliases.any { it.aliasNormalized == a.aliasNormalized }) -1L
        else { aliases += a; 1L }
    override suspend fun confirmedAliases() = aliases.filter { it.confirmedByUser }

    // Legacy surface — not exercised by these tests.
    override suspend fun insertMessage(message: MessageEntity): Long = 1L
    override suspend fun findBySourceHash(hash: String): MessageEntity? = null
    override suspend fun insertTransaction(txn: TransactionEntity): Long = 1L
    override suspend fun applyUserCorrection(
        id: String, amountMinor: Long, currencyCode: String, counterparty: String?,
        counterpartyNormalized: String?, categoryId: String?, state: String,
        correctionKind: String, correctionVersion: String, correctionReason: String?, correctionAt: Long,
    ) {}
    override suspend fun getTransaction(id: String): TransactionEntity? = null
    override fun observeTransactions(): Flow<List<TransactionEntity>> = flowOf(emptyList())
    override suspend fun transactionsForAccountBetween(accountId: String, fromDay: Long, toDay: Long) =
        emptyList<TransactionEntity>()
    override suspend fun transactionsInStates(states: List<String>) = emptyList<TransactionEntity>()
    override suspend fun insertCategory(category: CategoryEntity): Long = 1L
    override suspend fun insertLedgerEntry(entry: LedgerEntryEntity): Long = 1L
    override suspend fun insertTransfer(transfer: TransferEntity): Long = 1L
    override suspend fun insertJob(job: ProcessingJobEntity): Long = 1L
    override suspend fun updateJobProgress(jobIdentity: String, status: String, error: String?, nextAttemptAt: Long) {}
    override suspend fun dueJobs(status: String, now: Long, limit: Int) = emptyList<ProcessingJobEntity>()
    override suspend fun insertAuditEvent(event: AuditEventEntity) {}
    override suspend fun transactionCount(): Int = 0
}

/**
 * Repository-level tests for the account-authority increment using an
 * in-memory fake DAO (no Android/Room runtime needed on JVM).
 */
class AccountRepositoryTestV3 {

    private lateinit var dao: FakeDao
    private fun repo() = RoomFinanceRepositoryV2(FakeDao().also { dao = it })

    private fun row(
        id: String, nickname: String, last4: String?, institution: String?,
        lifecycle: String = "ACTIVE",
    ) = FinanceRepositoryV2.AccountRow(
        id = id, name = nickname, normalizedName = nickname.lowercase(), currencyCode = "INR",
        accountType = "BANK", createdAtEpochMs = 0L, lifecycle = lifecycle,
        nickname = nickname, last4 = last4, institutionName = institution,
    )

    @Test
    fun `duplicate last4 across same bank is allowed`() = runTest {
        val r = repo()
        assertTrue(r.addAccount(row("a1", "Salary", "1234", "hdfc")))
        assertTrue(r.addAccount(row("a2", "Savings", "1234", "hdfc")))
        assertEquals("1234", r.getAccount("a1")?.last4)
        assertEquals("1234", r.getAccount("a2")?.last4)
    }

    @Test
    fun `add same account id twice is rejected`() = runTest {
        val r = repo()
        assertTrue(r.addAccount(row("a1", "Main", null, null)))
        assertFalse(r.addAccount(row("a1", "Dup", null, null)))
    }

    @Test
    fun `opening balance is idempotent per account`() = runTest {
        val r = repo()
        r.addAccount(row("a1", "Main", null, null))
        val ob = FinanceRepositoryV2.OpeningBalanceRow("ob1", "a1", 10_000L, "INR", 0L)
        assertTrue(r.setOpeningBalance(ob))
        assertFalse(r.setOpeningBalance(ob.copy(id = "ob2")))
    }

    @Test
    fun `snapshot recording is idempotent via snapshotIdentity`() = runTest {
        val r = repo()
        r.addAccount(row("a1", "Main", null, null))
        val snap = FinanceRepositoryV2.BalanceSnapshotRow(
            id = "s1", accountId = "a1", amountMinor = 5_000L, currencyCode = "INR",
            kind = "MANUAL_ACTUAL", messageId = null, capturedAtEpochMs = 42L,
            sourceKind = "MANUAL_ENTRY", sourceVersion = "user-v1", snapshotIdentity = "sha:a1:5000:42",
        )
        assertTrue(r.recordBalanceSnapshot(snap))
        assertFalse(r.recordBalanceSnapshot(snap.copy(id = "s2")))
        assertEquals(1, r.snapshotsForAccount("a1").size)
        assertEquals(5_000L, r.latestSnapshot("a1")?.amountMinor)
    }

    @Test
    fun `sender mapping proposal stays unconfirmed until user confirms`() = runTest {
        val r = repo()
        r.addAccount(row("a1", "Main", "1234", "hdfc"))
        assertTrue(
            r.proposeSenderMapping(
                FinanceRepositoryV2.SenderMappingRow("m1", "HX-HDFC", "a1", false, "HEURISTIC", "v1", 0L)
            )
        )
        assertTrue(r.confirmedAccountsForSender("HX-HDFC").isEmpty())
        r.confirmSenderMapping("HX-HDFC", "a1")
        assertEquals(listOf("a1"), r.confirmedAccountsForSender("HX-HDFC"))
    }

    @Test
    fun `archive keeps history queryable and restore works`() = runTest {
        val r = repo()
        r.addAccount(row("a1", "Old", "9999", "icici"))
        r.archiveAccount("a1")
        assertEquals("ARCHIVED", r.getAccount("a1")?.lifecycle)
        assertNotNull(r.getAccount("a1"))
        r.restoreAccount("a1")
        assertEquals("ACTIVE", r.getAccount("a1")?.lifecycle)
    }

    @Test
    fun `alias learning dedupes by normalized alias`() = runTest {
        val r = repo()
        assertTrue(r.learnInstitutionAlias("HDFC Bank", "hdfc", confirmedByUser = true))
        assertFalse(r.learnInstitutionAlias("hdfc bank ", "hdfc", confirmedByUser = true))
        assertEquals(1, r.confirmedAliases().size)
    }

    @Test
    fun `unknown account returns null without fabricating`() = runTest {
        assertNull(repo().getAccount("missing"))
    }
}
