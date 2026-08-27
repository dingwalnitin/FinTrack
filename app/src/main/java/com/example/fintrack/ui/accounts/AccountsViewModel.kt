package com.example.fintrack.ui.accounts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.fintrack.domain.repository.FinanceRepositoryV2
import com.example.fintrack.domain.service.BalanceCalculator
import com.example.fintrack.domain.service.InstitutionAliasRegistry
import com.example.fintrack.domain.model.AccountLifecycle
import com.example.fintrack.domain.model.AccountType
import com.example.fintrack.domain.model.EntityId
import com.example.fintrack.ui.common.UiState
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Account UI state. UI never touches Room; everything flows through the
 * repository contract.
 */
data class AccountUi(
    val id: String,
    val displayName: String,
    val type: AccountType,
    val currencyCode: String,
    val last4: String?,
    val institution: String?,
    val archived: Boolean,
)

data class AccountsUiModel(
    val active: List<AccountUi>,
    val archived: List<AccountUi>,
)

class AccountsViewModel(private val repo: FinanceRepositoryV2) : ViewModel() {

    val state: StateFlow<UiState<AccountsUiModel>> = repo.observeAccounts()
        .map { rows ->
            if (rows.isEmpty()) {
                UiState.Empty
            } else {
                UiState.Content(
                    AccountsUiModel(
                        active = rows.filter { it.lifecycle == "ACTIVE" }.map { it.toUi() },
                        archived = rows.filter { it.lifecycle == "ARCHIVED" }.map { it.toUi() },
                    )
                )
            }
        }
        .catch { UiState.Error(it.message ?: "Failed to load accounts") }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), UiState.Loading)

    /** Manual add. Ownership fields are what the user entered — confirmed by definition. */
    fun addAccount(
        nickname: String, type: AccountType, currencyCode: String,
        last4: String?, institution: String?, openingBalanceMinor: Long,
    ) = viewModelScope.launch {
        val id = EntityId.generate().value
        val now = System.currentTimeMillis()
        repo.addAccount(
            FinanceRepositoryV2.AccountRow(
                id = id,
                name = nickname,
                normalizedName = nickname.trim().lowercase(),
                currencyCode = currencyCode,
                accountType = type.name,
                createdAtEpochMs = now,
                lifecycle = AccountLifecycle.ACTIVE.name,
                nickname = nickname,
                last4 = last4?.takeIf { it.length == 4 && it.all(Char::isDigit) },
                institutionName = institution?.trim()?.lowercase(),
            )
        )
        if (openingBalanceMinor != 0L) {
            repo.setOpeningBalance(
                FinanceRepositoryV2.OpeningBalanceRow(
                    id = EntityId.generate().value, accountId = id,
                    amountMinor = openingBalanceMinor, currencyCode = currencyCode, asOfEpochMs = now,
                )
            )
        }
    }

    /**
     * Manually set the current balance of an account by recording a
     * MANUAL_ACTUAL balance snapshot. Parse the user-entered string in major
     * units (e.g. "1200.50") to minor units (paise).
     */
    fun recordActualBalance(accountId: String, currencyCode: String, amount: String) = viewModelScope.launch {
        val minor = amount.trim().toDoubleOrNull()?.let { Math.round(it * 100) } ?: return@launch
        val now = System.currentTimeMillis()
        repo.recordBalanceSnapshot(
            FinanceRepositoryV2.BalanceSnapshotRow(
                id = EntityId.generate().value,
                accountId = accountId,
                amountMinor = minor,
                currencyCode = currencyCode,
                kind = "MANUAL_ACTUAL",
                messageId = null,
                capturedAtEpochMs = now,
                sourceKind = "MANUAL_ENTRY",
                sourceVersion = "user-v1",
                snapshotIdentity = "sha:$accountId:$minor:$now",
            )
        )
    }

    fun archive(accountId: String) = viewModelScope.launch { repo.archiveAccount(accountId) }
    fun restore(accountId: String) = viewModelScope.launch { repo.restoreAccount(accountId) }

    /**
     * Detected-account wizard confirmation: user confirms a proposed identity
     * (from masked digits + sender). Creates/links the account explicitly.
     */
    fun confirmDetectedAccount(senderId: String, accountId: String) = viewModelScope.launch {
        repo.confirmSenderMapping(senderId, accountId)
    }

    private fun FinanceRepositoryV2.AccountRow.toUi() = AccountUi(
        id = id,
        displayName = listOfNotNull(nickname ?: name, institutionName).joinToString(" · ") +
            (last4?.let { " (••••$it)" } ?: ""),
        type = runCatching { AccountType.valueOf(accountType) }.getOrDefault(AccountType.OTHER_LIABILITY),
        currencyCode = currencyCode,
        last4 = last4,
        institution = institutionName,
        archived = lifecycle == AccountLifecycle.ARCHIVED.name,
    )
}

/**
 * Reconciliation view-model: compares latest actual snapshot with the
 * ledger-derived balance and exposes the explicit difference.
 */
class ReconcileViewModel(private val repo: FinanceRepositoryV2) : ViewModel() {

    data class Result(
        val accountLabel: String,
        val actualMinor: Long,
        val derivedMinor: Long,
        val differenceMinor: Long,
        val reconciled: Boolean,
    )

    sealed interface State {
        data object Loading : State
        data object NoData : State
        data class Ready(val result: Result) : State
        data class Error(val message: String) : State
    }

    suspend fun reconcile(accountId: String): State = try {
        val account = repo.getAccount(accountId)
            ?: return State.Error("Account not found")
        val openingRow = repo.snapshotsForAccount(accountId).lastOrNull() // snapshots newest-first
        val latestSnapshot = repo.latestSnapshot(accountId)
        val entries = repo.ledgerEntriesForAccount(accountId)
        val derived = BalanceCalculator.derivedBalance(null, entries.map {
            BalanceCalculator.Posting(
                direction = if (it.direction == "CREDIT") com.example.fintrack.domain.model.PostingDirection.CREDIT
                else com.example.fintrack.domain.model.PostingDirection.DEBIT,
                amountMinor = it.amountMinor,
            )
        })
        val actual = latestSnapshot?.amountMinor
        if (actual == null && openingRow == null && entries.isEmpty()) {
            State.NoData
        } else {
            val rec = BalanceCalculator.reconcile(actual ?: 0L, derived, java.time.Instant.now())
            State.Ready(
                Result(
                    accountLabel = listOfNotNull(account.nickname ?: account.name, account.institutionName)
                        .joinToString(" · "),
                    actualMinor = rec.actualMinor,
                    derivedMinor = rec.derivedMinor,
                    differenceMinor = rec.differenceMinor,
                    reconciled = rec.reconciled,
                )
            )
        }
    } catch (e: Exception) {
        State.Error(e.message ?: "Reconciliation failed")
    }

    /** Record a manual actual balance and re-run reconciliation. */
    fun recordActualBalance(accountId: String, currencyCode: String, actualMinor: Long, onDone: () -> Unit) =
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            repo.recordBalanceSnapshot(
                FinanceRepositoryV2.BalanceSnapshotRow(
                    id = EntityId.generate().value, accountId = accountId,
                    amountMinor = actualMinor, currencyCode = currencyCode,
                    kind = "MANUAL_ACTUAL", messageId = null, capturedAtEpochMs = now,
                    sourceKind = "MANUAL_ENTRY", sourceVersion = "user-v1",
                    snapshotIdentity = "sha:$accountId:$actualMinor:$now",
                )
            )
            onDone()
        }

    companion object {
        /** Alias registry built from confirmed aliases only. */
        fun aliasRegistry(rows: List<FinanceRepositoryV2.AliasRow>) =
            InstitutionAliasRegistry(rows.filter { it.confirmedByUser }.map { it.aliasRaw to it.canonicalInstitution })
    }
}
