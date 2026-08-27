package com.example.fintrack.ui.diagnostics

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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.fintrack.application.diagnostics.DiagnosticsViewModel
import com.example.fintrack.diagnostics.DiagnosticsReport
import com.example.fintrack.parser.FinancialClass

/**
 * Stage 12 P25 — Developer diagnostics screen.
 *
 * Clearly developer-facing: the whole screen is marked "Developer
 * diagnostics" and every section is read-only with respect to the production
 * ledger. The parser playground runs synthetic SMS in memory only. The
 * export summary is redacted through the service and explicitly labeled as
 * safe to share with an agent.
 */
@Composable
fun DiagnosticsScreen(viewModel: DiagnosticsViewModel) {
    val state by viewModel.state.collectAsState()

    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text(
                "Developer diagnostics",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                "Read-only. Running any section here never writes to your ledger.",
                style = MaterialTheme.typography.bodySmall,
            )
        }

        if (state.loading) {
            item { CircularProgressIndicator() }
        }

        state.error?.let { err ->
            item {
                Text(
                    "Diagnostics error: $err",
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }

        state.report?.let { r ->
            item { EnvironmentSection(r) }
            item { CountsSection(r) }
            item { QueueSection(r) }
            item { ParserStatsSection(r) }
            item { UnresolvedSection(r) }
            item { MigrationSection(r) }

            // Export
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Safe export", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Redacted diagnostic summary — safe to paste into a bug report " +
                                "or share with a developer agent. Contains no raw SMS, no " +
                                "account numbers, no OTPs, no secrets.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(onClick = { viewModel.exportSummary() }) { Text("Generate") }
                        state.exportText?.let { text ->
                            Text(
                                text,
                                style = MaterialTheme.typography.bodySmall,
                            )
                            OutlinedButton(onClick = { viewModel.clearExport() }) {
                                Text("Clear")
                            }
                        }
                    }
                }
            }
        }

        // ---- parser playground ----
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Parser playground", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Run raw synthetic SMS through classify → normalize → extract. " +
                            "Never touches your ledger.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedTextField(
                        value = state.playgroundInput,
                        onValueChange = { viewModel.runPlayground(it) },
                        label = { Text("Raw SMS text…") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(onClick = { viewModel.runPlayground() }) {
                        Text("Run pipeline")
                    }
                    state.playgroundResult?.let { res ->
                        Text(
                            "Classification: ${res.classification}" +
                                (res.borderlineReason?.let { " ($it)" } ?: ""),
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (res.classification == FinancialClass.FINANCIAL) {
                            res.candidate?.let { c ->
                                Text(
                                    "Extracted: ${c.amountMinor} ${c.currencyCode ?: "INR"} " +
                                        "${c.direction} rail=${c.rail} " +
                                        "vpa=${c.upiVpa ?: "-"} ref=${c.bankReference ?: "-"}",
                                    style = MaterialTheme.typography.bodyMedium,
                                )
                                if (c.fieldProvenance.isNotEmpty()) {
                                    Text(
                                        "Provenance: " + c.fieldProvenance.entries.joinToString(", ") {
                                            "${it.key}@${it.value.ruleId}"
                                        },
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            } ?: Text(
                                "No deterministic rule matched (stays UNKNOWN — never guessed).",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        res.stages.forEach { s ->
                            Text(
                                "${s.stage}: ${s.output}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }

        // ---- fixture regression gate ----
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Fixture regression gate", style = MaterialTheme.typography.titleMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { viewModel.runCorpus() }) { Text("Run corpus") }
                        OutlinedButton(onClick = { viewModel.runFixtureDiff() }) {
                            Text("Diff against baseline")
                        }
                    }
                    state.corpusResult?.let { c ->
                        Text(
                            "Corpus ${c.fixtureVersion}: ${c.total} fixtures — " +
                                "precision ${"%.3f".format(c.precision)}, " +
                                "recall ${"%.3f".format(c.recall)}, " +
                                "extraction ${c.extractionMatches}/${c.extractionMatches + c.extractionMismatches}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (!c.regressionFree) {
                            c.mismatchDetails.forEach { d ->
                                Text(
                                    "REGRESSION: $d",
                                    color = MaterialTheme.colorScheme.error,
                                    style = MaterialTheme.typography.bodySmall,
                                )
                            }
                        }
                    }
                    state.diffResult?.let { d ->
                        Text(
                            d.summary(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (d.isClean) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EnvironmentSection(r: DiagnosticsReport) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Environment", style = MaterialTheme.typography.titleMedium)
            Text("${r.environment.applicationId} v${r.environment.versionName} (${r.environment.versionCode})")
            Text("schema ${r.database.schemaVersion} · locale ${r.environment.locale}")
            Text(if (r.environment.debugBuild) "debug build" else "release build")
        }
    }
}

@Composable
private fun CountsSection(r: DiagnosticsReport) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Database counts", style = MaterialTheme.typography.titleMedium)
            Text("transactions ${r.database.transactionCount} · accounts ${r.database.accountCount} · categories ${r.database.categoryCount}")
            Text("budgets ${r.database.budgetCount} · rawSms ${r.database.rawSmsCount} · reviewItems ${r.database.reviewItemCount}")
            Text("llmJobs ${r.database.llmJobCount} · processingJobs ${r.database.processingJobCount} · auditLog ${r.database.auditLogCount}")
        }
    }
}

@Composable
private fun QueueSection(r: DiagnosticsReport) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Queues", style = MaterialTheme.typography.titleMedium)
            Text(
                "SMS backfill: ${r.queues.smsBackfill.status} — " +
                    "seen ${r.queues.smsBackfill.totalSeen}, " +
                    "persisted ${r.queues.smsBackfill.totalPersisted}, " +
                    "dup ${r.queues.smsBackfill.totalDuplicate}",
            )
            Text(
                "LLM: pending ${r.queues.llm.pending} · running ${r.queues.llm.claimedOrRunning} · " +
                    "retryable ${r.queues.llm.retryableFailed} · terminal ${r.queues.llm.terminalFailed} · " +
                    "expiredLeases ${r.queues.llm.expiredLeases}",
            )
            Text(
                "Processing: pending ${r.queues.processing.pending} · running ${r.queues.processing.running} · " +
                    "stale ${r.queues.processing.stale}",
            )
        }
    }
}

@Composable
private fun ParserStatsSection(r: DiagnosticsReport) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Parser (${r.parserStats.fixtureVersion})", style = MaterialTheme.typography.titleMedium)
            Text(
                "${r.parserStats.totalFixtures} fixtures — precision " +
                    "${"%.3f".format(r.parserStats.precision)}, recall " +
                    "${"%.3f".format(r.parserStats.recall)}, extraction rate " +
                    "${"%.3f".format(r.parserStats.extractionRate)}",
            )
            Text("rails: ${r.parserStats.railBreakdown}")
            Text("creditKinds: ${r.parserStats.creditKindBreakdown}")
        }
    }
}

@Composable
private fun UnresolvedSection(r: DiagnosticsReport) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Unresolved & duplicates", style = MaterialTheme.typography.titleMedium)
            Text(
                "unknownKind ${r.unresolved.unknownKindCount} · uncategorized " +
                    "${r.unresolved.uncategorizedSpendCount} · openReview " +
                    "${r.unresolved.openReviewItemCount} · unmappedSenders " +
                    "${r.unresolved.unmappedSenderCount} · lowConf " +
                    "${r.unresolved.lowConfidenceInterpretationCount}",
            )
            Text(
                "duplicate clusters: total ${r.duplicates.totalClusters} · " +
                    "autoMerged ${r.duplicates.autoMerged} · reviewPending " +
                    "${r.duplicates.reviewPending} · rejected ${r.duplicates.rejected}",
            )
            Text(
                "reconciliation: ${r.reconciliation.totalAccounts} accounts — matched " +
                    "${r.reconciliation.matched}, explained ${r.reconciliation.explainedByLaterPostings}, " +
                    "unexplained ${r.reconciliation.unexplained}, noObservation " +
                    "${r.reconciliation.noObservation}",
            )
            if (r.recentFailures.isNotEmpty()) {
                Text("Recent failures:", style = MaterialTheme.typography.titleSmall)
                r.recentFailures.take(8).forEach { f ->
                    Text(
                        "${f.jobType} ${f.jobIdentity} [${f.status}] attempts=${f.attempts} " +
                            "err=${f.errorClass ?: "-"}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun MigrationSection(r: DiagnosticsReport) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Migration status", style = MaterialTheme.typography.titleMedium)
            Text("current ${r.migration.currentSchemaVersion} · target ${r.migration.pendingMigrations.size}")
            Text("registered migrations: ${r.migration.registeredMigrations.joinToString("→")}")
            Text(
                if (r.migration.destructiveFallbackEnabled) "⚠ destructive fallback ENABLED"
                else "destructive fallback forbidden (forward-only)",
                color = if (r.migration.destructiveFallbackEnabled) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.primary,
            )
        }
    }
}
