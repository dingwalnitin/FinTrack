package com.example.fintrack.llm

import com.example.fintrack.application.enrichment.LlmDiscoveryService
import com.example.fintrack.application.enrichment.LlmDiscoverySink
import com.example.fintrack.data.db.RawSmsEntity
import com.example.fintrack.domain.repository.FinanceRepositoryV2
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

class LlmDiscoveryServiceTest {
    private class FakeSink : LlmDiscoverySink {
        val accounts = linkedMapOf<String, FinanceRepositoryV2.AccountRow>()
        val transactions = linkedMapOf<String, FinanceRepositoryV2.TransactionRow>()

        override suspend fun findAccount(normalizedName: String) = accounts[normalizedName]

        override suspend fun addAccount(account: FinanceRepositoryV2.AccountRow): Boolean =
            accounts.putIfAbsent(account.normalizedName, account) == null

        override suspend fun postTransaction(
            transaction: FinanceRepositoryV2.TransactionRow,
            entry: FinanceRepositoryV2.LedgerEntryRow,
        ): Boolean = transactions.putIfAbsent(transaction.dedupeKey, transaction) == null
    }

    @Test
    fun `validated interpretation creates one account and transaction idempotently`() = runTest {
        val sink = FakeSink()
        val service = LlmDiscoveryService(sink, ZoneId.of("UTC"))
        val row = RawSmsEntity(
            id = "sms-1",
            providerId = 1,
            sender = "VM-HDFCBK",
            receivedAtEpochMs = 1_700_000_000_000,
            body = "INR 250 debited from account XX1234",
            contentHash = "hash",
            sourceKind = "BACKFILL",
            sourceVersion = "v1",
            capturedAtEpochMs = 1_700_000_000_100,
        )
        val parsed = LlmResponseDecoder.RawParsed(
            interpretation = Interpretation(
                amountMinor = 25_000,
                currencyCode = "INR",
                direction = Interpretation.Direction.DEBIT,
                accountToken = "XX1234",
                rail = Interpretation.Rail.UPI,
                counterpartyRaw = "Merchant",
                counterpartyNormalized = "merchant",
                categorySuggestion = "Shopping",
                transferTargetToken = null,
                recurring = false,
                emiDetail = null,
                occurredAtEpochMs = null,
                confidenceAmount = null,
                confidenceDirection = null,
                confidenceAccount = null,
                confidenceRail = null,
                confidenceCounterparty = null,
                confidenceCategory = null,
                confidenceTransferTarget = null,
                confidenceRecurring = null,
                confidenceEmi = null,
            ),
            overallConfidence = 0.95,
        )

        assertTrue(service.promote(row, parsed, "prompt-v1"))
        service.promote(row, parsed, "prompt-v1")

        assertEquals(1, sink.accounts.size)
        assertEquals("1234", sink.accounts.values.single().last4)
        assertEquals("HDFCBK", sink.accounts.values.single().institutionName)
        assertEquals(1, sink.transactions.size)
        assertEquals("sms-1", sink.transactions.values.single().messageId)
        assertEquals("INTERPRETED", sink.transactions.values.single().state)
    }
}