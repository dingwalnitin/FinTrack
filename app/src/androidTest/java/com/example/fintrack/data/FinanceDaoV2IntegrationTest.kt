package com.example.fintrack.data

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.fintrack.data.db.AccountEntity
import com.example.fintrack.data.db.FinTrackDatabaseV2
import com.example.fintrack.data.db.LedgerEntryEntity
import com.example.fintrack.data.db.MessageEntity
import com.example.fintrack.data.db.TransactionEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Integration tests on an in-memory v2 database:
 * idempotency, transactional posting, corrections, and job queues.
 */
@RunWith(AndroidJUnit4::class)
class FinanceDaoV2IntegrationTest {

    private lateinit var db: FinTrackDatabaseV2

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            FinTrackDatabaseV2::class.java,
        ).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun txn(id: String, dedupeKey: String, amountMinor: Long = 500L) = TransactionEntity(
        id = id, messageId = null, accountId = "acc1", categoryId = null,
        amountMinor = amountMinor, currencyCode = "INR",
        occurredAtEpochMs = 0L, localDateEpochDay = 0L,
        counterparty = "Merchant", counterpartyNormalized = "merchant",
        referenceId = null, state = "INTERPRETED",
        sourceKind = "SMS", sourceVersion = "sms-v1", sourceReason = null,
        correctionSourceKind = null, correctionSourceVersion = null,
        correctionSourceReason = null, correctionCapturedAtEpochMs = null,
        dedupeKey = dedupeKey,
    )

    private fun entry(txnId: String) = LedgerEntryEntity(
        id = "le-$txnId", transactionId = txnId, accountId = "acc1",
        direction = "DEBIT", amountMinor = 500L, currencyCode = "INR",
    )

    @Test
    fun freshInstallCreatesValidSchema() = runTest {
        val dao = db.financeDaoV2()
        assertTrue(
            dao.insertAccount(
                AccountEntity(
                    "acc1", "HDFC", "hdfc", "INR", "BANK", 0L, "ACTIVE",
                    nickname = null, last4 = null, institutionName = null,
                )
            ) != -1L
        )
        assertEquals(0, dao.transactionCount())
    }

    @Test
    fun duplicateDedupeKeyIsIgnored_idempotentWrite() = runTest {
        val dao = db.financeDaoV2()
        assertTrue(dao.insertTransaction(txn("t1", "dk1")) != -1L)
        assertFalse(dao.insertTransaction(txn("t2", "dk1")) != -1L) // same dedupe key rejected
        assertEquals(1, dao.transactionCount())
    }

    @Test
    fun duplicateEvidenceHashIsIgnored() = runTest {
        val dao = db.financeDaoV2()
        val msg = MessageEntity("m1", "body", "sender", 0L, "hash1", "SMS", "v1", 0L)
        assertTrue(dao.insertMessage(msg) != -1L)
        assertFalse(dao.insertMessage(msg.copy(id = "m2")) != -1L) // same hash ignored
        assertEquals("m1", dao.findBySourceHash("hash1")?.id)
    }

    @Test
    fun userCorrectionOverwritesAndSurvives() = runTest {
        val dao = db.financeDaoV2()
        dao.insertTransaction(txn("t1", "dk1"))
        dao.applyUserCorrection(
            id = "t1", amountMinor = 999L, currencyCode = "INR",
            counterparty = "Real Merchant", counterpartyNormalized = "real merchant",
            categoryId = "cat-food", state = "CONFIRMED",
            correctionKind = "USER_CORRECTION", correctionVersion = "user-v1",
            correctionReason = "wrong merchant", correctionAt = 42L,
        )
        val t = dao.getTransaction("t1")!!
        assertEquals(999L, t.amountMinor)
        assertEquals("USER_CORRECTION", t.correctionSourceKind)
        assertEquals("cat-food", t.categoryId)
    }

    @Test
    fun dueJobsReturnsOnlyPendingDue() = runTest {
        val dao = db.financeDaoV2()
        dao.insertJob(
            com.example.fintrack.data.db.ProcessingJobEntity(
                id = "j1", jobIdentity = "enrich:t1", jobType = "ENRICH",
                payloadRef = "t1", status = "PENDING", attempts = 0, maxAttempts = 3,
                lastError = null, nextAttemptAtEpochMs = 100L,
            )
        )
        // Re-enqueue with same identity is a no-op (idempotent).
        assertFalse(
            dao.insertJob(
                com.example.fintrack.data.db.ProcessingJobEntity(
                    id = "j2", jobIdentity = "enrich:t1", jobType = "ENRICH",
                    payloadRef = "t1", status = "PENDING", attempts = 0, maxAttempts = 3,
                    lastError = null, nextAttemptAtEpochMs = 100L,
                )
            ) != -1L
        )
        assertEquals(0, dao.dueJobs("PENDING", now = 50L, limit = 10).size)
        assertEquals(1, dao.dueJobs("PENDING", now = 150L, limit = 10).size)
    }
}
