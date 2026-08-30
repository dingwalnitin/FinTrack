package com.example.fintrack.ui.transactions

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.fintrack.application.transactions.ManualEntryViewModel
import com.example.fintrack.domain.model.TxKind
import com.example.fintrack.ui.common.BrandFilterChip
import com.example.fintrack.ui.common.FinTrackCard
import com.example.fintrack.ui.theme.Palette

/**
 * P11 #3: manual transaction quick entry / edit screen.
 *
 * Save / Cancel flow: the ViewModel keeps an in-memory draft; Room is only
 * written on Save. Validation errors surface inline next to the offending
 * field (via [ManualEntryViewModel.Draft.error]).
 */
@Composable
fun ManualEntryScreen(
    viewModel: ManualEntryViewModel,
    onSaved: () -> Unit,
) {
    val draft by viewModel.draft.collectAsState()
    val state by viewModel.state.collectAsState()

    if (state is ManualEntryViewModel.State.Saved) {
        onSaved()
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("New transaction", style = MaterialTheme.typography.headlineSmall)

        FinTrackCard {
            Text("Kind", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                TxKind.entries.forEach { kind ->
                    BrandFilterChip(
                        selected = draft.kind == kind,
                        onClick = { viewModel.updateDraft { it.copy(kind = kind) } },
                        label = kind.name,
                    )
                }
            }
        }

        FinTrackCard {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = draft.accountId,
                    onValueChange = { v -> viewModel.updateDraft { it.copy(accountId = v) } },
                    label = { Text("Account id") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = draft.amountText,
                    onValueChange = { v -> viewModel.updateDraft { it.copy(amountText = v) } },
                    label = { Text("Amount (minor units)") },
                    isError = draft.error?.contains("amount", ignoreCase = true) == true,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = draft.currencyCode,
                    onValueChange = { v -> viewModel.updateDraft { it.copy(currencyCode = v) } },
                    label = { Text("Currency") },
                    isError = draft.error?.contains("currency", ignoreCase = true) == true,
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = draft.merchant,
                    onValueChange = { v -> viewModel.updateDraft { it.copy(merchant = v) } },
                    label = { Text("Merchant") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = draft.counterparty,
                    onValueChange = { v -> viewModel.updateDraft { it.copy(counterparty = v) } },
                    label = { Text("Counterparty") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = draft.note,
                    onValueChange = { v -> viewModel.updateDraft { it.copy(note = v) } },
                    label = { Text("Note") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        draft.error?.let {
            Text(it, color = Palette.Danger)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { viewModel.save() }, modifier = Modifier.weight(1f)) { Text("Save") }
            OutlinedButton(onClick = { viewModel.cancel(); onSaved() }, modifier = Modifier.weight(1f)) { Text("Cancel") }
        }
    }
}
