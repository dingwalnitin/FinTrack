package com.example.fintrack.application.transactions

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.fintrack.domain.service.TransferCandidateMatcher
import com.example.fintrack.domain.service.TransferProposal
import com.example.fintrack.domain.policy.TransferEngine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * P11 #1: transfer-candidates ViewModel. Surfaces ambiguous (DEBIT, CREDIT)
 * pairs to the Review queue; AUTO_LINK verdicts are already paired by the
 * TransferService and are not shown here.
 */
class TransferCandidatesViewModel(
    private val matcher: TransferCandidateMatcher,
) : ViewModel() {

    sealed class State {
        data object Loading : State()
        data object Empty : State()
        data class Ready(val proposals: List<TransferProposal>) : State()
        data class Error(val message: String) : State()
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state: StateFlow<State> = _state.asStateFlow()

    fun load(accountIds: List<String>, windowMinutes: Long = 24L * 60L) {
        viewModelScope.launch {
            try {
                val now = Instant.now()
                val proposals = matcher.findCandidates(
                    accountIds = accountIds,
                    from = now.minusSeconds(windowMinutes * 60),
                    to = now,
                )
                _state.value = if (proposals.isEmpty()) State.Empty else State.Ready(proposals)
            } catch (t: Throwable) {
                _state.value = State.Error(t.message ?: t::class.java.simpleName)
            }
        }
    }
}
