package com.example.fintrack.ui.accounts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.Receipt
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.fintrack.domain.model.AccountType
import com.example.fintrack.ui.common.EmptyState
import com.example.fintrack.ui.common.ErrorState
import com.example.fintrack.ui.common.FinTrackCard
import com.example.fintrack.ui.common.IconBadge
import com.example.fintrack.ui.common.LoadingSkeleton
import com.example.fintrack.ui.common.SectionHeader
import com.example.fintrack.ui.common.UiState
import com.example.fintrack.ui.theme.Palette
import com.example.fintrack.ui.theme.categoryColor
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
        is UiState.Loading -> LoadingSkeleton()
        is UiState.Empty -> Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("Accounts", style = MaterialTheme.typography.headlineSmall)
            EmptyState("No accounts yet — accounts are auto-detected from your SMS")
        }
        is UiState.Error -> ErrorState(message = s.message)
        is UiState.Content -> LazyColumn(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            contentPadding = PaddingValues(top = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Text("Accounts", style = MaterialTheme.typography.headlineSmall)
                Text(
                    "${s.data.active.size} active",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(s.data.active, key = { it.id }) { acct ->
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AccountCard(acct, onArchive = { viewModel.archive(acct.id) }, onClick = { onReconcile(acct.id) })
                    EditBalanceCard(acct, onEditBalance = { amount -> onEditBalance(acct.id, amount) })
                }
            }
            if (s.data.archived.isNotEmpty()) {
                item {
                    SectionHeader("Archived (history kept)", modifier = Modifier.padding(top = 8.dp))
                }
                items(s.data.archived, key = { it.id }) { acct ->
                    FinTrackCard {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            IconBadge(icon = accountTypeIcon(acct.type), containerColor = Palette.SurfaceHigh, tint = Palette.TextSecondary)
                            Column(Modifier.weight(1f)) {
                                Text(acct.displayName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("Archived", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { viewModel.restore(acct.id) }) {
                                Icon(Icons.Filled.Restore, contentDescription = "Restore")
                            }
                            OutlinedButton(onClick = { onReconcile(acct.id) }) { Text("Reconcile") }
                        }
                    }
                }
            }
            item {
                Text(
                    "Accounts are auto-detected from your bank SMS. No manual entry needed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        else -> Unit
    }
}

private fun accountTypeIcon(type: AccountType): ImageVector = when (type) {
    AccountType.BANK -> Icons.Filled.AccountBalance
    AccountType.CREDIT_CARD -> Icons.Filled.CreditCard
    AccountType.CASH -> Icons.Filled.Payments
    AccountType.OTHER_LIABILITY -> Icons.Filled.Receipt
}

@Composable
private fun AccountCard(acct: AccountUi, onArchive: () -> Unit, onClick: () -> Unit) {
    val tint = categoryColor(acct.institution ?: acct.displayName)
    FinTrackCard(modifier = Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IconBadge(icon = accountTypeIcon(acct.type), containerColor = tint.copy(alpha = 0.18f), tint = tint, size = 48.dp)
            Column(Modifier.weight(1f)) {
                Text(
                    acct.displayName,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${acct.type.name.replace('_', ' ')} · ${acct.currencyCode}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onArchive) {
                Icon(Icons.Filled.Archive, contentDescription = "Archive account")
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
    FinTrackCard(containerColor = Palette.BackgroundAlt) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(
                balance,
                { balance = it },
                label = { Text("Set current balance (${acct.currencyCode})") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = { onEditBalance(balance.trim()); balance = "" },
                enabled = balance.trim().toDoubleOrNull() != null,
            ) { Text("Save") }
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

    Column(
        Modifier
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Reconcile", style = MaterialTheme.typography.headlineSmall)
        when (val s = state) {
            is ReconcileViewModel.State.Loading -> Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            is ReconcileViewModel.State.NoData -> Text("No balances or postings yet for this account.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            is ReconcileViewModel.State.Error -> Text("Error: ${s.message}", color = MaterialTheme.colorScheme.error)
            is ReconcileViewModel.State.Ready -> {
                FinTrackCard {
                    Text(s.result.accountLabel, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Actual (latest snapshot)", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${format(s.result.actualMinor)} $currencyCode")
                    }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("Ledger-derived", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("${format(s.result.derivedMinor)} $currencyCode")
                    }
                    Spacer(Modifier.height(8.dp))
                    val diffLabel = if (s.result.differenceMinor >= 0) "+" else "−"
                    Text(
                        "Difference: $diffLabel${format(kotlin.math.abs(s.result.differenceMinor))} $currencyCode",
                        style = MaterialTheme.typography.titleMedium,
                        color = if (s.result.reconciled) Palette.Income else Palette.Danger,
                    )
                    if (!s.result.reconciled) {
                        Text("Review required: balances differ.", style = MaterialTheme.typography.labelLarge, color = Palette.Warn)
                    }
                }
            }
        }
        FinTrackCard {
            Text("Record actual balance", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                actualInput,
                { actualInput = it },
                label = { Text("Actual balance ($currencyCode)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
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
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Record actual balance") }
        }
    }
}

/** Integer minor-units formatting for display only. No float persistence. */
private fun format(minor: Long): String {
    val negative = minor < 0
    val abs = kotlin.math.abs(minor)
    return "${if (negative) "-" else ""}${abs / 100}.${(abs % 100).toString().padStart(2, '0')}"
}
