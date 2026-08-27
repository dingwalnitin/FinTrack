package com.example.fintrack.ui.search

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.fintrack.application.search.SearchViewModel
import com.example.fintrack.domain.model.TxKind
import com.example.fintrack.domain.service.ReconciliationService
import com.example.fintrack.domain.service.SortDirection
import com.example.fintrack.domain.service.SortField
import com.example.fintrack.ui.common.MoneyRow
import com.example.fintrack.ui.common.MoneyRowData

/**
 * Stage 9 P20 — search + diagnostics screen.
 *
 * Sections:
 *  1. Search & filters (text, account, kind, tag, sort) with bounded paging.
 *  2. Reconciliation workbench — read-only comparison of observed vs derived.
 *  3. Unresolved-data report — counts of everything awaiting resolution.
 *  4. Raw evidence viewer — immutable SMS text + parser/LLM provenance;
 *     copy-out is always redacted through the redaction engine.
 *
 * Evidence and interpretation are always visually separated: raw text is
 * shown in a distinct card labelled "Raw evidence (immutable)" while LLM /
 * provenance rows are labelled "Interpretation".
 */
@Composable
fun SearchScreen(viewModel: SearchViewModel) {
    val state by viewModel.state.collectAsState()

    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { Text("Search & diagnostics", style = MaterialTheme.typography.titleLarge) }

        // ---- 1. search ----
        item {
            OutlinedTextField(
                value = state.filters.textQuery,
                onValueChange = { q -> viewModel.updateFilters { it.copy(textQuery = q) } },
                label = { Text("Search merchant, note, reference…") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.filters.accountId == null,
                    onClick = { viewModel.updateFilters { it.copy(accountId = null) } },
                    label = { Text("All accounts") },
                )
                state.accounts.filter { it.lifecycle == "ACTIVE" }.take(3).forEach { acct ->
                    FilterChip(
                        selected = state.filters.accountId == acct.id,
                        onClick = { viewModel.updateFilters { f -> f.copy(accountId = acct.id) } },
                        label = { Text(acct.nickname) },
                    )
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.filters.kind == null,
                    onClick = { viewModel.updateFilters { it.copy(kind = null) } },
                    label = { Text("Any kind") },
                )
                listOf(TxKind.EXPENSE, TxKind.INCOME, TxKind.TRANSFER, TxKind.REFUND).forEach { k ->
                    FilterChip(
                        selected = state.filters.kind == k,
                        onClick = { viewModel.updateFilters { it.copy(kind = k) } },
                        label = { Text(k.name.lowercase().replaceFirstChar { c -> c.uppercase() }) },
                    )
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = state.filters.sortField == SortField.OCCURRED_AT &&
                        state.filters.sortDirection == SortDirection.DESC,
                    onClick = { viewModel.updateFilters {
                        it.copy(sortField = SortField.OCCURRED_AT, sortDirection = SortDirection.DESC)
                    } },
                    label = { Text("Newest") },
                )
                FilterChip(
                    selected = state.filters.sortField == SortField.AMOUNT &&
                        state.filters.sortDirection == SortDirection.DESC,
                    onClick = { viewModel.updateFilters {
                        it.copy(sortField = SortField.AMOUNT, sortDirection = SortDirection.DESC)
                    } },
                    label = { Text("Largest") },
                )
                if (state.availableTags.isNotEmpty()) {
                    FilterChip(
                        selected = state.filters.tag != null,
                        onClick = {
                            viewModel.updateFilters { it.copy(tag = if (it.tag == null) state.availableTags.first() else null) }
                        },
                        label = { Text(state.filters.tag ?: "Tag: ${state.availableTags.first()}") },
                    )
                }
            }
        }
        item {
            Text(
                "${state.totalMatching} matching transaction(s)",
                style = MaterialTheme.typography.labelLarge,
            )
        }
        items(state.rows, key = { it.txn.id }) { row ->
            Column {
                MoneyRow(
                    MoneyRowData(
                        title = row.txn.merchant ?: row.txn.counterpartyNormalized ?: "Transaction",
                        amountMinor = if (row.txn.directionDebit) -row.txn.amountMinor else row.txn.amountMinor,
                        currencyCode = row.txn.currencyCode,
                        isDebit = row.txn.directionDebit,
                    )
                )
                if (row.tags.isNotEmpty() || row.latestNote != null) {
                    Text(
                        buildString {
                            if (row.tags.isNotEmpty()) append("tags: ${row.tags.joinToString(", ")}")
                            if (row.latestNote != null) {
                                if (isNotEmpty()) append(" · ")
                                append(row.latestNote)
                            }
                        },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 12.dp),
                    )
                }
                OutlinedButton(onClick = { viewModel.openEvidence(row.txn.id) }) {
                    Text("View evidence")
                }
            }
        }
        if (state.hasMoreInferred()) {
            item {
                OutlinedButton(onClick = { viewModel.loadMore() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Load more")
                }
            }
        }

        // ---- 2. reconciliation workbench ----
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Reconciliation workbench", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Read-only comparison of observed snapshots vs ledger-derived balances.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Button(onClick = { viewModel.loadReconciliation() }) { Text("Run check") }
                    state.reconciliation.forEach { rec ->
                        val verdictLabel = when (val v = reconciliationVerdict(rec)) {
                            is ReconciliationService.Verdict.Matched -> "matched"
                            is ReconciliationService.Verdict.ExplainedByLaterPostings ->
                                "explained by later postings"
                            is ReconciliationService.Verdict.NoObservation -> "no snapshot to compare"
                            is ReconciliationService.Verdict.Unexplained ->
                                "UNEXPLAINED difference ₹${paise(v.differenceMinor)}"
                        }
                        Text("${rec.accountLabel}: $verdictLabel", style = MaterialTheme.typography.bodyMedium)
                        if (rec.snapshotStale) {
                            Text(
                                "snapshot predates ${rec.postingsAfterSnapshot} newer posting(s)",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }

        // ---- 3. unresolved-data report ----
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Unresolved data", style = MaterialTheme.typography.titleMedium)
                    Button(onClick = { viewModel.loadUnresolvedReport() }) { Text("Refresh report") }
                    state.unresolved?.let { r ->
                        Text("Unknown economic meaning: ${r.unknownEconomicMeaning}")
                        Text("Uncategorized spend events: ${r.uncategorizedTransactions}")
                        Text("Open review items: ${r.openReviewItems}")
                        Text("Low-confidence interpretations: ${r.lowConfidenceFields}")
                        Text("Failed AI jobs: ${r.llmFailures}")
                        Text("Stale processing jobs: ${r.staleProcessingJobs}")
                        Text("Senders without account mapping: ${r.transactionsWithoutAccountMapping}")
                    }
                }
            }
        }

        // ---- 4. raw evidence viewer ----
        if (state.evidence.isNotEmpty()) {
            item { Text("Evidence for selected transaction", style = MaterialTheme.typography.titleMedium) }
            items(state.evidence, key = { it.rawSmsId }) { e ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Raw evidence (immutable)", style = MaterialTheme.typography.labelLarge)
                        Text(e.body, style = MaterialTheme.typography.bodySmall)
                        Text(
                            "received ${e.receivedAtEpochMs} · sender ${e.sender?.take(6) ?: "unknown"}…",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (e.interpretations.isNotEmpty()) {
                            Text("Interpretation (advisory)", style = MaterialTheme.typography.labelLarge)
                            e.interpretations.forEach { i ->
                                Text(
                                    "${i.modelId} @ ${i.promptVersion}" +
                                        (i.overallConfidence?.let { " · confidence ${(it * 100).toInt()}%" } ?: ""),
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        } else {
                            Text(
                                "No AI interpretation stored for this evidence.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
            item {
                Button(onClick = { viewModel.copyEvidenceRedacted() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Copy evidence (redacted)")
                }
                state.evidenceRedactedForCopy?.let {
                    Text("Redacted copy prepared — amounts, VPAs, phones, accounts masked.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

private fun reconciliationVerdict(rec: ReconciliationService.AccountReconciliation) =
    when {
        rec.observedMinor == null -> ReconciliationService.Verdict.NoObservation
        rec.differenceMinor == 0L && !rec.snapshotStale -> ReconciliationService.Verdict.Matched
        rec.differenceMinor == 0L && rec.snapshotStale ->
            ReconciliationService.Verdict.ExplainedByLaterPostings(0L)
        else -> ReconciliationService.Verdict.Unexplained(rec.differenceMinor)
    }

private fun SearchViewModel.State.hasMoreInferred(): Boolean = rows.size < totalMatching

private fun paise(minor: Long): String {
    val abs = kotlin.math.abs(minor)
    val sign = if (minor < 0) "-" else ""
    return "$sign${abs / 100}.${(abs % 100).toString().padStart(2, '0')}"
}
