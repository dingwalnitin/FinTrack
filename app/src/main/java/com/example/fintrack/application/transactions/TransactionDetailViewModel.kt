package com.example.fintrack.application.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.fintrack.data.db.FinanceDaoV2
import com.example.fintrack.data.db.FinanceDaoV3
import com.example.fintrack.data.db.FinanceDaoV4
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * P10 #6 + P11 #5: transaction detail ViewModel.
 *
 * Lives in the application layer so the UI never touches Room types
 * directly (architecture rule). Surfaces the advanced posting section,
 * provenance/confidence and status/review cues via [DetailState], a
 * UI-facing projection with no data-layer types.
 *
 * P11 additions:
 *  - Evidence links (raw SMS rows supporting this event)
 *  - Audit history (every state change / correction)
 *  - Linked events (transfer siblings, refunds, fees)
 */
class TransactionDetailViewModel(
    private val dao: FinanceDaoV3,
    private val daoV4: FinanceDaoV4? = null,
    private val daoV2: FinanceDaoV2? = null,
) : ViewModel() {

    /** UI-facing posting projection (no Room types). */
    data class PostingUi(
        val accountId: String,
        val direction: String,
        val amountMinor: Long,
        val currencyCode: String,
        val memo: String?,
    )

    /** UI-facing transaction projection (no Room types). */
    data class TransactionUi(
        val id: String,
        val amountMinor: Long,
        val currencyCode: String,
        val occurredAtEpochMs: Long,
        val status: String,
        val kind: String,
        val subtype: String?,
        val rail: String?,
        val merchant: String?,
        val description: String?,
        val cardMask: String?,
        val referenceId: String?,
        val sourceKind: String,
        val sourceVersion: String,
        val sourceReason: String?,
        val correctionSourceKind: String?,
        val correctionSourceVersion: String?,
        val correctionCapturedAtEpochMs: Long?,
        val dedupeKey: String,
        val transferGroupId: String?,
        val deletedAtEpochMs: Long?,
        val deletedReason: String?,
    )

    /** P11 #5: raw SMS evidence projection. */
    data class EvidenceUi(
        val rawSmsId: String,
        val linkKind: String,
        val reason: String?,
    )

    /** P11 #5: audit-history projection. */
    data class AuditUi(
        val action: String,
        val actor: String,
        val reason: String?,
        val atEpochMs: Long,
    )

    /** P11 #5: linked-event projection (transfer sibling / refund / fee). */
    data class LinkedEventUi(
        val eventId: String,
        val role: String,       // TRANSFER_SIBLING | REFUND | FEE
        val amountMinor: Long?,
        val currencyCode: String?,
    )

    sealed class State {
        data object Loading : State()
        data object Empty : State()
        data class Error(val message: String) : State()
        data class Content(
            val transaction: TransactionUi,
            val postings: List<PostingUi>,
            val evidence: List<EvidenceUi>,
            val audit: List<AuditUi>,
            val linkedEvents: List<LinkedEventUi>,
        ) : State()
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state.asStateFlow()

    fun load(txnId: String) {
        viewModelScope.launch {
            try {
                val txn = withContext(Dispatchers.IO) { dao.getTransactionV6(txnId) }
                if (txn == null) {
                    _state.value = State.Empty
                } else {
                    _state.value = buildContent(txn)
                }
            } catch (t: Throwable) {
                _state.value = State.Error(t.message ?: t::class.java.simpleName)
            }
        }
    }

    /** Observe live edits to the transaction row. */
    fun observe(txnId: String) {
        viewModelScope.launch {
            dao.observeTransaction(txnId).collectLatest { txn ->
                if (txn == null) {
                    _state.value = State.Empty
                } else {
                    _state.value = runCatching { buildContent(txn) }.getOrElse {
                        State.Error(it.message ?: it::class.java.simpleName)
                    }
                }
            }
        }
    }

    private suspend fun buildContent(
        txn: com.example.fintrack.data.db.TransactionEntity,
    ): State.Content {
        val postings = withContext(Dispatchers.IO) {
            dao.ledgerEntriesForGroup(txn.postingGroupId ?: "\u0000none")
        }

        // ---- P11 #5: evidence ----
        val evidence = withContext(Dispatchers.IO) {
            runCatching { dao.evidenceLinksForEvent(txn.id) }.getOrDefault(emptyList())
        }.map { EvidenceUi(rawSmsId = it.rawSmsId, linkKind = it.linkKind, reason = it.sourceReason) }

        // ---- P11 #5: audit history ----
        val audit = withContext(Dispatchers.IO) {
            daoV4?.auditEventsForEntity(txn.id)
                ?: daoV2?.let { d -> d.let { emptyList<com.example.fintrack.data.db.AuditEventEntity>() } }
                ?: emptyList()
        }.map { AuditUi(action = it.action, actor = it.actor, reason = it.detailReason, atEpochMs = it.atEpochMs) }

        // ---- P11 #5: linked events ----
        val linked = mutableListOf<LinkedEventUi>()
        // Transfer siblings share the same transferGroupId.
        txn.transferGroupId?.let { groupId ->
            withContext(Dispatchers.IO) {
                runCatching { dao.transactionsForPostingGroup(groupId) }.getOrDefault(emptyList())
            }.filter { it.id != txn.id }.forEach { sibling ->
                linked += LinkedEventUi(
                    eventId = sibling.id,
                    role = "TRANSFER_SIBLING",
                    amountMinor = sibling.amountMinor,
                    currencyCode = sibling.currencyCode,
                )
            }
        }
        // Refunds + fees via the v7 tables.
        daoV4?.let { d4 ->
            withContext(Dispatchers.IO) {
                runCatching { d4.childLinksForParent(txn.id) }.getOrDefault(emptyList())
            }.forEach { link ->
                val child = withContext(Dispatchers.IO) { dao.getTransactionV6(link.childEventId) }
                linked += LinkedEventUi(
                    eventId = link.childEventId,
                    role = link.role,
                    amountMinor = child?.amountMinor,
                    currencyCode = child?.currencyCode,
                )
            }
            withContext(Dispatchers.IO) {
                runCatching { d4.parentLinksForChild(txn.id) }.getOrDefault(emptyList())
            }.forEach { link ->
                val parent = withContext(Dispatchers.IO) { dao.getTransactionV6(link.parentEventId) }
                linked += LinkedEventUi(
                    eventId = link.parentEventId,
                    role = "${link.role}_PARENT",
                    amountMinor = parent?.amountMinor,
                    currencyCode = parent?.currencyCode,
                )
            }
        }

        return State.Content(
            transaction = txn.toUi(),
            postings = postings.map { p ->
                PostingUi(
                    accountId = p.accountId,
                    direction = p.direction,
                    amountMinor = p.amountMinor,
                    currencyCode = p.currencyCode,
                    memo = p.memo,
                )
            },
            evidence = evidence,
            audit = audit,
            linkedEvents = linked,
        )
    }

    private fun com.example.fintrack.data.db.TransactionEntity.toUi() = TransactionUi(
        id = id,
        amountMinor = amountMinor,
        currencyCode = currencyCode,
        occurredAtEpochMs = occurredAtEpochMs,
        status = status,
        kind = kind,
        subtype = subtype,
        rail = rail,
        merchant = merchant,
        description = description,
        cardMask = cardMask,
        referenceId = referenceId,
        sourceKind = sourceKind,
        sourceVersion = sourceVersion,
        sourceReason = sourceReason,
        correctionSourceKind = correctionSourceKind,
        correctionSourceVersion = correctionSourceVersion,
        correctionCapturedAtEpochMs = correctionCapturedAtEpochMs,
        dedupeKey = dedupeKey,
        transferGroupId = transferGroupId,
        deletedAtEpochMs = deletedAtEpochMs,
        deletedReason = deletedReason,
    )
}
