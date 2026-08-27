package com.example.fintrack.ui.transactions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
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
        TransactionDetailViewModel.State.Loading -> Text("Loading…", Modifier.padding(16.dp))
        TransactionDetailViewModel.State.Empty -> Text("Transaction not found", Modifier.padding(16.dp))
        is TransactionDetailViewModel.State.Error -> Text(
            "Error: ${s.message}",
            color = MaterialTheme.colorScheme.error,
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
        .atZone(zone).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        "${if (isDebit) "−" else "+"}$major ${transaction.currencyCode}",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text(occurred, style = MaterialTheme.typography.bodyMedium)
                    Text("Status: ${transaction.status}", style = MaterialTheme.typography.bodyMedium)
                    Text("Kind: ${transaction.kind}${transaction.subtype?.let { ".$it" } ?: ""}")
                    transaction.rail?.let { Text("Rail: $it") }
                    transaction.merchant?.let { Text("Merchant: $it") }
                    transaction.description?.let { Text("Note: $it") }
                    transaction.cardMask?.let { Text("Card: •••• $it") }
                    transaction.referenceId?.let { Text("Ref: $it") }
                }
            }
        }
        item { Text("Postings", style = MaterialTheme.typography.titleMedium) }
        if (postings.isEmpty()) {
            item { Text("No postings (event has no active posting group).") }
        } else {
            items(postings, key = { it.accountId + it.direction + it.amountMinor }) { posting ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        val signed = if (posting.direction == "CREDIT") posting.amountMinor else -posting.amountMinor
                        val majorSigned = MoneyPolicy.toMajor(kotlin.math.abs(signed), posting.currencyCode).toPlainString()
                        val sign = if (signed >= 0) "+" else "−"
                        Text("$sign$majorSigned ${posting.currencyCode} (${posting.direction})")
                        Text("Account: ${posting.accountId}", style = MaterialTheme.typography.bodySmall)
                        posting.memo?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
        item { Text("Provenance & confidence", style = MaterialTheme.typography.titleMedium) }
        item {
            Card {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Source: ${transaction.sourceKind}@${transaction.sourceVersion}")
                    transaction.sourceReason?.let { Text("Reason: $it") }
                    transaction.correctionSourceKind?.let { correction ->
                        Text(
                            "Corrected by: $correction@${transaction.correctionSourceVersion} at " +
                                Instant.ofEpochMilli(transaction.correctionCapturedAtEpochMs ?: 0L)
                                    .atZone(zone).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                        )
                    }
                    Text("Dedupe key: ${transaction.dedupeKey}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (transaction.status == "REVIEW_REQUIRED" || transaction.deletedAtEpochMs != null) {
            item {
                Card {
                    Column(Modifier.padding(12.dp)) {
                        if (transaction.status == "REVIEW_REQUIRED") {
                            Text("Review required", color = MaterialTheme.colorScheme.error)
                        }
                        transaction.deletedAtEpochMs?.let {
                            Text("Deleted (tombstoned): ${Instant.ofEpochMilli(it).atZone(zone)}")
                            transaction.deletedReason?.let { r -> Text("Reason: $r") }
                        }
                    }
                }
            }
        }

        // ---- P11 #5: Evidence (raw SMS) ----
        item { Text("Evidence (raw SMS)", style = MaterialTheme.typography.titleMedium) }
        if (evidence.isEmpty()) {
            item { Text("No raw SMS evidence linked to this event.", style = MaterialTheme.typography.bodySmall) }
        } else {
            items(evidence, key = { it.rawSmsId + it.linkKind }) { e ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("SMS id: ${e.rawSmsId}", style = MaterialTheme.typography.bodyMedium)
                        Text("Link kind: ${e.linkKind}", style = MaterialTheme.typography.bodySmall)
                        e.reason?.let { Text("Reason: $it", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }

        // ---- P11 #5: Audit history ----
        item { Text("Audit history", style = MaterialTheme.typography.titleMedium) }
        if (audit.isEmpty()) {
            item { Text("No audit events recorded for this event.", style = MaterialTheme.typography.bodySmall) }
        } else {
            items(audit, key = { "${it.action}-${it.atEpochMs}" }) { a ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text("${a.action} by ${a.actor}", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            Instant.ofEpochMilli(a.atEpochMs).atZone(zone)
                                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        a.reason?.let { Text("Reason: $it", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }

        // ---- P11 #5: Linked events (transfers / refunds / fees) ----
        item { Text("Linked events (transfers / refunds / fees)", style = MaterialTheme.typography.titleMedium) }
        if (linkedEvents.isEmpty()) {
            item { Text("No linked transfers, refunds or fees.", style = MaterialTheme.typography.bodySmall) }
        } else {
            items(linkedEvents, key = { it.eventId + it.role }) { l ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(l.role, style = MaterialTheme.typography.bodyMedium)
                        l.amountMinor?.let { amt ->
                            val cur = l.currencyCode ?: ""
                            Text(
                                "${MoneyPolicy.toMajor(kotlin.math.abs(amt), cur).toPlainString()} $cur",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Text("Event: ${l.eventId}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
