package com.example.fintrack.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.NotificationsNone
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.fintrack.application.insights.HomeViewModel
import com.example.fintrack.domain.model.ProgressStatus
import com.example.fintrack.ui.common.FinTrackCard
import com.example.fintrack.ui.common.GradientHeroCard
import com.example.fintrack.ui.common.IconBadge
import com.example.fintrack.ui.common.LoadingSkeleton
import com.example.fintrack.ui.common.MoneyRow
import com.example.fintrack.ui.common.MoneyRowData
import com.example.fintrack.ui.common.ProgressRing
import com.example.fintrack.ui.common.SectionHeader
import com.example.fintrack.ui.common.statusColor
import com.example.fintrack.ui.theme.Palette
import java.time.LocalTime

/**
 * Stage 9 P19 — Home dashboard.
 *
 * Local aggregates only: balances, month spend/income, budget progress and
 * review/pending counts. No decorative charts; every card is a number the
 * user can act on. Incomplete coverage is stated, never hidden.
 */
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenTransactions: () -> Unit = {},
    onOpenReview: () -> Unit = {},
    onOpenBudgets: () -> Unit = {},
    onOpenTransaction: (String) -> Unit = {},
    onAddTransaction: () -> Unit = {},
) {
    val state by viewModel.state.collectAsState()
    var balanceHidden by remember { mutableStateOf(false) }

    if (state.loading && state.recent.isEmpty()) {
        LoadingSkeleton()
        return
    }

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        item {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column {
                    Text(greeting(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Your finances", style = MaterialTheme.typography.headlineSmall)
                }
                IconButton(onClick = onOpenReview) {
                    Icon(Icons.Filled.NotificationsNone, contentDescription = "Review queue")
                }
            }
        }

        state.error?.let { err ->
            item {
                FinTrackCard(containerColor = Palette.Danger.copy(alpha = 0.12f)) {
                    Text("Error: $err", color = Palette.Danger)
                }
            }
        }

        // ---- balance hero ----
        item {
            GradientHeroCard(
                label = "Total balance",
                amountText = if (balanceHidden) {
                    "•••••"
                } else {
                    state.totalBalanceMinor?.let { "₹${paise(it)}" } ?: "No accounts yet"
                },
                trailing = {
                    IconButton(onClick = { balanceHidden = !balanceHidden }) {
                        Icon(
                            if (balanceHidden) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                            contentDescription = if (balanceHidden) "Show balance" else "Hide balance",
                            tint = Color.White,
                        )
                    }
                },
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    MoneyFlowStat(
                        icon = Icons.Filled.ArrowDownward,
                        label = "Income",
                        amountText = "+₹${paise(state.incomeNetMinor)}",
                        iconTint = Palette.Income,
                    )
                    MoneyFlowStat(
                        icon = Icons.Filled.ArrowUpward,
                        label = "Spend",
                        amountText = "₹${paise(state.spendNetMinor)}",
                        iconTint = Color.White,
                    )
                }
            }
        }

        // ---- needs attention ----
        if (state.openReviewCount > 0 || state.pendingStatusCount > 0) {
            item {
                FinTrackCard(
                    modifier = Modifier.clickable(onClick = onOpenReview),
                    containerColor = Palette.Warn.copy(alpha = 0.12f),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Icon(Icons.Filled.WarningAmber, contentDescription = null, tint = Palette.Warn)
                        Column {
                            Text("Needs attention", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                            Text(
                                "${state.openReviewCount} to review · ${state.pendingStatusCount} pending",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        // ---- budgets ----
        if (state.budgets.isNotEmpty()) {
            item { SectionHeader("Budgets", actionLabel = "See all", onAction = onOpenBudgets) }
            item {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(state.budgets, key = { it.name }) { card ->
                        BudgetMiniCard(card)
                    }
                }
            }
        }

        // ---- recent transactions ----
        item { SectionHeader("Recent", actionLabel = "See all", onAction = onOpenTransactions) }
        if (state.recent.isEmpty()) {
            item {
                Text(
                    "No transactions yet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        items(state.recent, key = { it.id }) { txn ->
            val label = txn.merchant ?: txn.counterpartyNormalized ?: "Transaction"
            MoneyRow(
                MoneyRowData(
                    title = label,
                    amountMinor = if (txn.directionDebit) -txn.amountMinor else txn.amountMinor,
                    currencyCode = txn.currencyCode,
                    isDebit = txn.directionDebit,
                    subtitle = if (txn.directionDebit) "Expense" else "Income",
                    categoryLabel = label,
                ),
                onClick = { onOpenTransaction(txn.id) },
            )
        }

        if (state.coverageIncomplete) {
            item {
                Text(
                    "! Transaction history is incomplete — figures may be understated",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MoneyFlowStat(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, amountText: String, iconTint: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Box(
            Modifier.size(28.dp).background(Color.White.copy(alpha = 0.18f), CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(16.dp))
        }
        Column {
            Text(label, style = MaterialTheme.typography.bodySmall, color = Color.White.copy(alpha = 0.75f))
            Text(amountText, style = MaterialTheme.typography.titleSmall, color = Color.White)
        }
    }
}

@Composable
private fun BudgetMiniCard(card: HomeViewModel.BudgetCard) {
    FinTrackCard(modifier = Modifier.width(160.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            ProgressRing(
                progress = card.progress.usageRatio.toFloat(),
                modifier = Modifier.size(40.dp),
                strokeWidth = 5.dp,
                progressColor = statusColor(card.progress.status),
            )
            Column {
                Text(
                    card.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    "${(card.progress.usageRatio * 100).toInt()}% used",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

private fun greeting(): String {
    val hour = LocalTime.now().hour
    return when {
        hour < 12 -> "Good morning"
        hour < 17 -> "Good afternoon"
        else -> "Good evening"
    }
}

private fun statusLabel(status: ProgressStatus): String = "${status.symbol} ${status.label}"

private fun paise(minor: Long): String {
    val abs = kotlin.math.abs(minor)
    val sign = if (minor < 0) "-" else ""
    return "$sign${abs / 100}.${(abs % 100).toString().padStart(2, '0')}"
}
