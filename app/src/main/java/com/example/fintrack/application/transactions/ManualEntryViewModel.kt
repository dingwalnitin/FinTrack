package com.example.fintrack.application.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.fintrack.domain.model.EntityId
import com.example.fintrack.domain.model.TxKind
import com.example.fintrack.domain.model.TxSubtype
import com.example.fintrack.domain.service.ManualEntryInput
import com.example.fintrack.domain.service.ManualEntryService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * P11 #3: manual entry / edit ViewModel.
 *
 * Keeps an in-memory draft; Room is only written on Save. Cancel simply
 * clears the draft. Validation errors surface as [Draft.error].
 */
class ManualEntryViewModel(
    private val service: ManualEntryService,
) : ViewModel() {

    /** In-memory draft. Nothing is written to Room until [save] is called. */
    data class Draft(
        val txnId: String? = null,          // null = create, non-null = edit
        val accountId: String = "",
        val amountText: String = "",
        val currencyCode: String = "INR",
        val occurredAtEpochMs: Long = System.currentTimeMillis(),
        val kind: TxKind = TxKind.EXPENSE,
        val subtype: TxSubtype? = null,
        val counterparty: String = "",
        val merchant: String = "",
        val note: String = "",
        val referenceId: String = "",
        val error: String? = null,
    )

    sealed class State {
        data object Idle : State()
        data class Saving(val draft: Draft) : State()
        data class Saved(val txnId: String) : State()
        data class Failed(val message: String) : State()
    }

    private val _draft = MutableStateFlow(Draft())
    val draft: StateFlow<Draft> = _draft.asStateFlow()

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    fun updateDraft(transform: (Draft) -> Draft) {
        _draft.value = transform(_draft.value).copy(error = null)
    }

    fun loadForEdit(txnId: String) {
        // The caller supplies the current values via updateDraft; this hook
        // exists so the screen can pre-fill the form.
        _draft.value = _draft.value.copy(txnId = txnId)
    }

    fun save() {
        val d = _draft.value
        val amountMinor = d.amountText.toLongOrNull() ?: -1L
        val input = ManualEntryInput(
            accountId = EntityId(d.accountId),
            amountMinor = amountMinor,
            currencyCode = d.currencyCode,
            occurredAt = Instant.ofEpochMilli(d.occurredAtEpochMs),
            kind = d.kind,
            subtype = d.subtype,
            counterparty = d.counterparty.ifBlank { null },
            merchant = d.merchant.ifBlank { null },
            note = d.note.ifBlank { null },
            referenceId = d.referenceId.ifBlank { null },
        )
        viewModelScope.launch {
            _state.value = State.Saving(d)
            val result = if (d.txnId == null) {
                service.createManual(input)
            } else {
                service.editManual(d.txnId, input)
            }
            result.fold(
                onSuccess = { txn ->
                    _state.value = State.Saved(txn.id.value)
                    _draft.value = Draft()
                },
                onFailure = { t ->
                    _state.value = State.Failed(t.message ?: "save failed")
                    _draft.value = d.copy(error = t.message)
                },
            )
        }
    }

    fun delete(reason: String? = "user-deleted") {
        val d = _draft.value
        val id = d.txnId ?: return
        viewModelScope.launch {
            service.deleteManual(id, reason).fold(
                onSuccess = { _state.value = State.Saved(id) },
                onFailure = { t -> _state.value = State.Failed(t.message ?: "delete failed") },
            )
        }
    }

    fun cancel() {
        _draft.value = Draft()
        _state.value = State.Idle
    }
}
