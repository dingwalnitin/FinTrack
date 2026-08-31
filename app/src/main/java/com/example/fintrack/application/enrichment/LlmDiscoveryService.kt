package com.example.fintrack.application.enrichment

import com.example.fintrack.data.db.RawSmsEntity
import com.example.fintrack.domain.model.AccountLifecycle
import com.example.fintrack.domain.model.AccountType
import com.example.fintrack.domain.model.LifecycleState
import com.example.fintrack.domain.repository.FinanceRepositoryV2
import com.example.fintrack.llm.Interpretation
import com.example.fintrack.llm.LlmResponseDecoder
import kotlinx.coroutines.flow.first
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

interface LlmDiscoverySink {
    suspend fun findAccount(normalizedName: String): FinanceRepositoryV2.AccountRow?
    suspend fun addAccount(account: FinanceRepositoryV2.AccountRow): Boolean
    suspend fun postTransaction(
        transaction: FinanceRepositoryV2.TransactionRow,
        entry: FinanceRepositoryV2.LedgerEntryRow,
    ): Boolean
}

class FinanceRepositoryLlmDiscoverySink(
    private val repository: FinanceRepositoryV2,
) : LlmDiscoverySink {
    override suspend fun findAccount(normalizedName: String): FinanceRepositoryV2.AccountRow? =
        repository.observeAccounts().first().firstOrNull { it.normalizedName == normalizedName }

    override suspend fun addAccount(account: FinanceRepositoryV2.AccountRow): Boolean =
        repository.addAccount(account)

    override suspend fun postTransaction(
        transaction: FinanceRepositoryV2.TransactionRow,
        entry: FinanceRepositoryV2.LedgerEntryRow,
    ): Boolean = repository.postTransaction(transaction, entry)
}

class LlmDiscoveryService(
    private val sink: LlmDiscoverySink,
    private val zoneId: ZoneId = ZoneId.systemDefault(),
) {
    suspend fun promote(
        row: RawSmsEntity,
        parsed: LlmResponseDecoder.RawParsed,
        sourceVersion: String,
    ): Boolean {
        val interpretation = parsed.interpretation
        val amountMinor = interpretation.amountMinor ?: return false
        val currencyCode = interpretation.currencyCode ?: return false
        val direction = interpretation.direction ?: return false
        val occurredAt = interpretation.occurredAtEpochMs ?: row.receivedAtEpochMs
        val account = resolveAccount(row, interpretation, currencyCode)
        val transactionId = stableId("llm-transaction:${row.id}")
        val dedupeKey = stableId("llm-dedupe:${row.id}")

        return sink.postTransaction(
            FinanceRepositoryV2.TransactionRow(
                id = transactionId,
                messageId = row.id,
                accountId = account.id,
                categoryId = null,
                amountMinor = amountMinor,
                currencyCode = currencyCode,
                occurredAtEpochMs = occurredAt,
                localDateEpochDay = Instant.ofEpochMilli(occurredAt).atZone(zoneId).toLocalDate().toEpochDay(),
                counterparty = interpretation.counterpartyRaw,
                counterpartyNormalized = interpretation.counterpartyNormalized,
                referenceId = null,
                state = LifecycleState.INTERPRETED.name,
                sourceKind = "LLM_INTERPRETATION",
                sourceVersion = sourceVersion,
                sourceReason = parsed.overallConfidence?.let { "Validated LLM extraction ($it)" }
                    ?: "Validated LLM extraction",
                correctionSourceKind = null,
                correctionSourceVersion = null,
                correctionSourceReason = null,
                correctionCapturedAtEpochMs = null,
                dedupeKey = dedupeKey,
            ),
            FinanceRepositoryV2.LedgerEntryRow(
                id = stableId("llm-ledger:${row.id}"),
                transactionId = transactionId,
                accountId = account.id,
                direction = direction.name,
                amountMinor = amountMinor,
                currencyCode = currencyCode,
            ),
        )
    }

    private suspend fun resolveAccount(
        row: RawSmsEntity,
        interpretation: Interpretation,
        currencyCode: String,
    ): FinanceRepositoryV2.AccountRow {
        val institution = row.sender
            ?.trim()
            ?.substringAfterLast('-')
            ?.takeIf { it.isNotBlank() }
            ?: "Unknown institution"
        val institutionKey = institution.lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
            .ifBlank { "unknown" }
        val last4 = interpretation.accountToken
            ?.filter(Char::isDigit)
            ?.takeIf { it.length >= 4 }
            ?.takeLast(4)
        val normalizedName = "detected-$institutionKey-${last4 ?: "unknown"}-${currencyCode.lowercase()}"
        sink.findAccount(normalizedName)?.let { return it }

        val account = FinanceRepositoryV2.AccountRow(
            id = stableId("llm-account:$normalizedName"),
            name = if (last4 == null) "$institution account" else "$institution account ending $last4",
            normalizedName = normalizedName,
            currencyCode = currencyCode,
            accountType = if (interpretation.rail in CARD_RAILS) {
                AccountType.CREDIT_CARD.name
            } else {
                AccountType.BANK.name
            },
            createdAtEpochMs = row.capturedAtEpochMs,
            lifecycle = AccountLifecycle.ACTIVE.name,
            last4 = last4,
            institutionName = institution,
        )
        sink.addAccount(account)
        return sink.findAccount(normalizedName) ?: account
    }

    private fun stableId(value: String): String =
        UUID.nameUUIDFromBytes(value.toByteArray(StandardCharsets.UTF_8)).toString()

    private companion object {
        val CARD_RAILS = setOf(Interpretation.Rail.CARD_POS, Interpretation.Rail.CARD_ONLINE)
    }
}