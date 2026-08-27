package com.example.fintrack.data.repository

import com.example.fintrack.data.db.AuditEventEntity
import com.example.fintrack.data.db.FinanceDaoV4
import com.example.fintrack.data.db.FinanceDaoV8
import com.example.fintrack.domain.ai.AiQueryEngine
import com.example.fintrack.domain.ai.AiQueryPlan
import com.example.fintrack.domain.ai.PlanResult
import com.example.fintrack.domain.service.LedgerTxnView
import java.security.MessageDigest
import java.util.UUID

/**
 * Stage 10 / P21 — Room-backed AI query execution + audit.
 *
 * The single Room access point for the AI query feature. Persists query
 * METADATA (plan identity, filters summary, result totals, coverage) into the
 * existing v2 `audit_events` table so no schema migration is required. Raw
 * sensitive prompts are never persisted — only the validated plan summary.
 *
 * All reads stay bounded and reuse the Stage 9 read-only DAO.
 */
class RoomAiQueryRepository(
    private val dao: FinanceDaoV8,
    private val auditDao: FinanceDaoV4,
) {

    private val engine = AiQueryEngine()

    /** Entity → domain ledger view (same projection as RoomInsightsRepository). */
    private fun com.example.fintrack.data.db.TransactionEntity.toLedgerView(): LedgerTxnView =
        LedgerTxnView(
            id = id,
            accountId = accountId,
            categoryId = categoryId?.ifBlank { null },
            kind = kind,
            directionDebit = when (kind) {
                "INCOME", "REFUND" -> false
                else -> true
            },
            amountMinor = amountMinor,
            localDateEpochDay = localDateEpochDay,
            counterpartyNormalized = counterpartyNormalized,
            merchant = merchant,
            currencyCode = currencyCode,
            occurredAtEpochMs = occurredAtEpochMs,
            subtype = subtype,
            userCorrected = correctionSourceKind != null,
            statusDeleted = status == "DELETED",
            rail = rail,
            cardMask = cardMask,
        )

    /**
     * Execute a validated plan against retrieved structured facts, then
     * persist an audit row. Returns the deterministic result.
     */
    suspend fun execute(
        plan: AiQueryPlan,
        refusedReason: String? = null,
    ): PlanResult {
        val txns = dao.transactionsBetween(
            fromDay = plan.filters.fromDay ?: 0L,
            toDay = plan.filters.toDay ?: Long.MAX_VALUE / 2,
        ).map { it.toLedgerView() }

        val result = engine.execute(plan, txns)
        persistAudit(plan, result, refusedReason)
        return result
    }

    /** Convenience for unbounded queries (no date filter). */
    suspend fun executeUnbounded(
        plan: AiQueryPlan,
        refusedReason: String? = null,
    ): PlanResult {
        val txns = dao.allActiveTransactions().map { it.toLedgerView() }
        val result = engine.execute(plan, txns)
        persistAudit(plan, result, refusedReason)
        return result
    }

    private suspend fun persistAudit(
        plan: AiQueryPlan,
        result: PlanResult,
        refusedReason: String?,
    ) {
        val now = System.currentTimeMillis()
        val filtersSummary = buildString {
            append(plan.filters.fromDay ?: "-").append('|')
            append(plan.filters.toDay ?: "-").append('|')
            append(plan.filters.accountIds?.sorted()?.joinToString(",") ?: "-").append('|')
            append(plan.filters.categoryIds?.sorted()?.joinToString(",") ?: "-").append('|')
            append(plan.filters.merchantNormalized ?: "-").append('|')
            append(plan.filters.kinds?.sorted()?.joinToString(",") { it.name } ?: "-").append('|')
            append(plan.filters.rails?.sorted()?.joinToString(",") ?: "-").append('|')
            append(plan.filters.minAmountMinor ?: "-").append('|')
            append(plan.filters.maxAmountMinor ?: "-")
        }
        val coverageSummary = buildString {
            append("ingestionIncomplete=").append(result.coverage.ingestionIncomplete).append(';')
            append("firstDay=").append(result.coverage.firstObservedDay ?: "-").append(';')
            append("lastDay=").append(result.coverage.lastObservedDay ?: "-").append(';')
            append("unknownKinds=").append(result.coverage.unknownKindCount).append(';')
            append("uncatShare=").append("%.2f".format(result.coverage.uncategorizedShare))
        }
        val logIdentity = sha256(
            listOfNotNull(
                plan.planIdentity,
                result.totalMatching.toString(),
                coverageSummary,
                refusedReason,
            ).joinToString("|"),
        )
        // Append-only audit row; re-runs add a new row with a fresh id so
        // history is preserved rather than overwritten.
        auditDao.insertAuditEvent(
            AuditEventEntity(
                id = UUID.randomUUID().toString(),
                entityId = plan.planIdentity,
                entityType = ENTITY_TYPE_AI_QUERY,
                action = if (refusedReason == null) ACTION_EXECUTED else ACTION_REFUSED,
                actor = "SYSTEM",
                detailReason = "$filtersSummary\n$coverageSummary",
                atEpochMs = now,
            ),
        )
    }

    private fun sha256(raw: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(raw.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    companion object {
        const val ENTITY_TYPE_AI_QUERY = "AI_QUERY"
        const val ACTION_EXECUTED = "EXECUTED"
        const val ACTION_REFUSED = "REFUSED"
    }
}
