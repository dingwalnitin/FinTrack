package com.example.fintrack.ui.accounts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.fintrack.domain.model.AccountType
import com.example.fintrack.ui.common.UiState
import kotlinx.coroutines.launch

/**
 * Account screens: list, add (manual + detected-account wizard confirmation),
 * archive/restore and reconcile. All states (empty/loading/error) are explicit.
 */

@Composable
fun AccountsScreen(
    viewModel: AccountsViewModel,
    onReconcile: (String) -> Unit = {},
    onEditBalance: (String, String) -> Unit = { _, _ -> },
) {
    val state by viewModel.state.collectAsState()
    when (val s = state) {
        is UiState.Loading -> Text("Loading accounts…", Modifier.padding(16.dp))
        is UiState.Empty -> Column(Modifier.padding(16.dp)) {
            Text("No accounts yet")
            AddAccountForm(viewModel)
        }
        is UiState.Error -> Column(Modifier.padding(16.dp)) {
            Text("Error: ${s.message}", color = MaterialTheme.colorScheme.error)
        }
        is UiState.Content -> LazyColumn(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item { Text("Accounts", style = MaterialTheme.typography.titleLarge) }
            items(s.data.active) { acct ->
                AccountCard(acct, onArchive = { viewModel.archive(acct.id) })
                EditBalanceCard(acct, onEditBalance = { amount -> onEditBalance(acct.id, amount) })
            }
            if (s.data.archived.isNotEmpty()) {
                item { Text("Archived (history kept)", style = MaterialTheme.typography.titleMedium) }
                items(s.data.archived) { acct ->
                    Card {
                        Column(Modifier.padding(12.dp)) {
                            Text(acct.displayName)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { viewModel.restore(acct.id) }) { Text("Restore") }
                                OutlinedButton(onClick = { onReconcile(acct.id) }) { Text("Reconcile") }
                            }
                        }
                    }
                }
            }
            item { AddAccountForm(viewModel) }
        }
        else -> Unit
    }
}

@Composable
private fun AccountCard(acct: AccountUi, onArchive: () -> Unit) {
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(acct.displayName, style = MaterialTheme.typography.titleMedium)
            Text("${acct.type.name} · ${acct.currencyCode}")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onArchive) { Text("Archive") }
            }
        }
    }
}

/**
 * Inline "Edit balance" field on each active account. Allows setting the
 * current balance directly (records a MANUAL_ACTUAL snapshot). Save is only
 * enabled when a valid number was entered.
 */
@Composable
private fun EditBalanceCard(acct: AccountUi, onEditBalance: (String) -> Unit) {
    var balance by remember { mutableStateOf("") }
    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Set current balance", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                balance,
                { balance = it },
                label = { Text("Current balance (${acct.currencyCode})") },
                modifier = Modifier.fillMaxWidth(),
            )
            Button(
                onClick = { onEditBalance(balance.trim()); balance = "" },
                enabled = balance.trim().toDoubleOrNull() != null,
            ) { Text("Save balance") }
        }
    }
}

/**
 * Manual add form. last4 is optional — unknown suffix stays unknown; the same
 * last4 may be reused across accounts without conflict.
 */
@Composable
private fun AddAccountForm(viewModel: AccountsViewModel) {
    var nickname by remember { mutableStateOf("") }
    var institution by remember { mutableStateOf("") }
    var last4 by remember { mutableStateOf("") }
    var currency by remember { mutableStateOf("INR") }
    var type by remember { mutableStateOf(AccountType.BANK) }
    var opening by remember { mutableStateOf("") }

    Card {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Add account", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(nickname, { nickname = it }, label = { Text("Nickname") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(institution, { institution = it }, label = { Text("Bank / institution (optional)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(last4, { if (it.length <= 4 && it.all(Char::isDigit)) last4 = it }, label = { Text("Last 4 digits (optional)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(currency, { currency = it }, label = { Text("Currency (INR/USD)") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(opening, { opening = it }, label = { Text("Opening balance (major units, optional)") }, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AccountType.entries.forEach { t ->
                    OutlinedButton(onClick = { type = t }, enabled = type != t) { Text(t.name.removePrefix("OTHER_")) }
                }
            }
            Button(
                onClick = {
                    val openingMinor = opening.toDoubleOrNull()
                        ?.let { Math.round(it * 100) } ?: 0L
                    viewModel.addAccount(
                        nickname = nickname.trim(), type = type,
                        currencyCode = currency.trim().uppercase().ifBlank { "INR" },
                        last4 = last4.ifBlank { null },
                        institution = institution.trim().ifBlank { null },
                        openingBalanceMinor = openingMinor,
                    )
                    nickname = ""; institution = ""; last4 = ""; opening = ""
                },
                enabled = nickname.isNotBlank(),
            ) { Text("Save account") }
        }
    }
}

/**
 * Reconciliation screen: shows actual vs ledger-derived balance and the
 * explicit difference. Never auto-adjusts; user records an actual balance.
 */
@Composable
fun ReconcileScreen(viewModel: ReconcileViewModel, accountId: String, currencyCode: String) {
    var state by remember { mutableStateOf<ReconcileViewModel.State>(ReconcileViewModel.State.Loading) }
    var actualInput by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    androidx.compose.runtime.LaunchedEffect(accountId) {
        state = viewModel.reconcile(accountId)
    }

    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Reconcile", style = MaterialTheme.typography.titleLarge)
        when (val s = state) {
            is ReconcileViewModel.State.Loading -> Text("Loading…")
            is ReconcileViewModel.State.NoData -> Text("No balances or postings yet for this account.")
            is ReconcileViewModel.State.Error -> Text("Error: ${s.message}", color = MaterialTheme.colorScheme.error)
            is ReconcileViewModel.State.Ready -> {
                Text(s.result.accountLabel)
                Text("Actual (latest snapshot): ${format(s.result.actualMinor)} $currencyCode")
                Text("Ledger-derived: ${format(s.result.derivedMinor)} $currencyCode")
                val diffLabel = if (s.result.differenceMinor >= 0) "+" else "−"
                Text(
                    "Difference: $diffLabel${format(kotlin.math.abs(s.result.differenceMinor))} $currencyCode",
                    color = if (s.result.reconciled) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.error,
                )
                if (!s.result.reconciled) Text("Review required: balances differ.", style = MaterialTheme.typography.labelLarge)
            }
        }
        OutlinedTextField(actualInput, { actualInput = it }, label = { Text("Actual balance ($currencyCode)") })
        Button(
            onClick = {
                val minor = actualInput.toDoubleOrNull()?.let { Math.round(it * 100) } ?: return@Button
                scope.launch {
                    viewModel.recordActualBalance(accountId, currencyCode, minor) {
                        state = ReconcileViewModel.State.Loading
                    }
                    state = viewModel.reconcile(accountId)
                }
            },
            enabled = actualInput.toDoubleOrNull() != null,
        ) { Text("Record actual balance") }
    }
}

/** Integer minor-units formatting for display only. No float persistence. */
private fun format(minor: Long): String {
    val negative = minor < 0
    val abs = kotlin.math.abs(minor)
    return "${if (negative) "-" else ""}${abs / 100}.${(abs % 100).toString().padStart(2, '0')}"
}
