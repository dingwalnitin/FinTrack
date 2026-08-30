package com.example.fintrack.ui.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.fintrack.application.transactions.TransactionDetailViewModel
import com.example.fintrack.domain.policy.MoneyPolicy
import com.example.fintrack.ui.common.FinTrackCard
import com.example.fintrack.ui.common.IconBadge
import com.example.fintrack.ui.common.LoadingSkeleton
import com.example.fintrack.ui.common.SectionHeader
import com.example.fintrack.ui.theme.Palette
import com.example.fintrack.ui.theme.categoryColor
import com.example.fintrack.ui.theme.categoryIcon
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * P10 #6: transaction detail screen.
 *
 * Surfaces:
 *  - Amount, currency, occurred-at, status
 *  - Kind / subtype / rail / merchant / description (unknown stays unknown)
 *  - Advanced posting section
 *  - Provenance / confidence and edit history (correction source)
 *  - Status/review cues (REVIEW_REQUIRED banner, tombstone notice)
 */
@Composable
fun TransactionDetailScreen(
    transactionId: String,
    viewModel: TransactionDetailViewModel,
    zone: ZoneId = ZoneId.systemDefault(),
) {
    LaunchedEffect(transactionId) { viewModel.observe(transactionId) }
    val state by viewModel.state.collectAsState()

    when (val s = state) {
        TransactionDetailViewModel.State.Loading -> LoadingSkeleton()
        TransactionDetailViewModel.State.Empty -> Text("Transaction not found", Modifier.padding(16.dp))
        is TransactionDetailViewModel.State.Error -> Text(
            "Error: ${s.message}",
            color = Palette.Danger,
            modifier = Modifier.padding(16.dp),
        )
        is TransactionDetailViewModel.State.Content -> TransactionDetail(
            transaction = s.transaction,
            postings = s.postings,
            evidence = s.evidence,
            audit = s.audit,
            linkedEvents = s.linkedEvents,
            zone = zone,
        )
    }
}

@Composable
private fun TransactionDetail(
    transaction: TransactionDetailViewModel.TransactionUi,
    postings: List<TransactionDetailViewModel.PostingUi>,
    evidence: List<TransactionDetailViewModel.EvidenceUi>,
    audit: List<TransactionDetailViewModel.AuditUi>,
    linkedEvents: List<TransactionDetailViewModel.LinkedEventUi>,
    zone: ZoneId,
) {
    val isDebit = transaction.amountMinor < 0L
    val absAmount = kotlin.math.abs(transaction.amountMinor)
    val major = MoneyPolicy.toMajor(absAmount, transaction.currencyCode).toPlainString()
    val occurred = Instant.ofEpochMilli(transaction.occurredAtEpochMs)
        .atZone(zone).format(DateTimeFormatter.ofPattern("MMM d, yyyy · h:mm a"))
    val label = transaction.merchant ?: transaction.description ?: "Transaction"
    val tint = categoryColor(label)

    LazyColumn(
        Modifier.fillMaxSize().padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 16.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item {
            Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                IconBadge(icon = categoryIcon(label), containerColor = tint.copy(alpha = 0.18f), tint = tint, size = 64.dp)
                Spacer(Modifier.height(12.dp))
                Text(
                    "${if (isDebit) "−" else "+"}$major ${transaction.currencyCode}",
                    style = MaterialTheme.typography.headlineLarge,
                    color = if (isDebit) MaterialTheme.colorScheme.onSurface else Palette.Income,
                )
                Text(label, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 4.dp))
                Text(occurred, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        item {
            FinTrackCard {
                DetailRow("Status", transaction.status)
                DetailRow("Kind", "${transaction.kind}${transaction.subtype?.let { ".$it" } ?: ""}")
                transaction.rail?.let { DetailRow("Rail", it) }
                transaction.merchant?.let { DetailRow("Merchant", it) }
                transaction.description?.let { DetailRow("Note", it) }
                transaction.cardMask?.let { DetailRow("Card", "•••• $it") }
                transaction.referenceId?.let { DetailRow("Ref", it) }
            }
        }
        item { SectionHeader("Postings") }
        if (postings.isEmpty()) {
            item {
                Text(
                    "No postings (event has no active posting group).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(postings, key = { it.accountId + it.direction + it.amountMinor }) { posting ->
                FinTrackCard {
                    val signed = if (posting.direction == "CREDIT") posting.amountMinor else -posting.amountMinor
                    val majorSigned = MoneyPolicy.toMajor(kotlin.math.abs(signed), posting.currencyCode).toPlainString()
                    val sign = if (signed >= 0) "+" else "−"
                    Text(
                        "$sign$majorSigned ${posting.currencyCode} (${posting.direction})",
                        color = if (signed >= 0) Palette.Income else MaterialTheme.colorScheme.onSurface,
                    )
                    Text("Account: ${posting.accountId}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    posting.memo?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }
        item { SectionHeader("Provenance & confidence") }
        item {
            FinTrackCard {
                DetailRow("Source", "${transaction.sourceKind}@${transaction.sourceVersion}")
                transaction.sourceReason?.let { DetailRow("Reason", it) }
                transaction.correctionSourceKind?.let { correction ->
                    DetailRow(
                        "Corrected by",
                        "$correction@${transaction.correctionSourceVersion} at " +
                            Instant.ofEpochMilli(transaction.correctionCapturedAtEpochMs ?: 0L)
                                .atZone(zone).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                    )
                }
                Text("Dedupe key: ${transaction.dedupeKey}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (transaction.status == "REVIEW_REQUIRED" || transaction.deletedAtEpochMs != null) {
            item {
                FinTrackCard(containerColor = Palette.Warn.copy(alpha = 0.12f)) {
                    if (transaction.status == "REVIEW_REQUIRED") {
                        Text("Review required", color = Palette.Warn, style = MaterialTheme.typography.titleSmall)
                    }
                    transaction.deletedAtEpochMs?.let {
                        Text("Deleted (tombstoned): ${Instant.ofEpochMilli(it).atZone(zone)}")
                        transaction.deletedReason?.let { r -> Text("Reason: $r") }
                    }
                }
            }
        }

        // ---- P11 #5: Evidence (raw SMS) ----
        item { SectionHeader("Evidence (raw SMS)") }
        if (evidence.isEmpty()) {
            item {
                Text(
                    "No raw SMS evidence linked to this event.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(evidence, key = { it.rawSmsId + it.linkKind }) { e ->
                FinTrackCard {
                    Text("SMS id: ${e.rawSmsId}", style = MaterialTheme.typography.bodyMedium)
                    Text("Link kind: ${e.linkKind}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    e.reason?.let { Text("Reason: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }

        // ---- P11 #5: Audit history ----
        item { SectionHeader("Audit history") }
        if (audit.isEmpty()) {
            item {
                Text(
                    "No audit events recorded for this event.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(audit, key = { "${it.action}-${it.atEpochMs}" }) { a ->
                FinTrackCard {
                    Text("${a.action} by ${a.actor}", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        Instant.ofEpochMilli(a.atEpochMs).atZone(zone)
                            .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    a.reason?.let { Text("Reason: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
            }
        }

        // ---- P11 #5: Linked events (transfers / refunds / fees) ----
        item { SectionHeader("Linked events") }
        if (linkedEvents.isEmpty()) {
            item {
                Text(
                    "No linked transfers, refunds or fees.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            items(linkedEvents, key = { it.eventId + it.role }) { l ->
                FinTrackCard {
                    Text(l.role, style = MaterialTheme.typography.bodyMedium)
                    l.amountMinor?.let { amt ->
                        val cur = l.currencyCode ?: ""
                        Text(
                            "${MoneyPolicy.toMajor(kotlin.math.abs(amt), cur).toPlainString()} $cur",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text("Event: ${l.eventId}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}
