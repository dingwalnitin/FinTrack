package com.example.fintrack.ui.transactions

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("New transaction", style = MaterialTheme.typography.titleLarge)

        OutlinedTextField(
            value = draft.accountId,
            onValueChange = { v -> viewModel.updateDraft { it.copy(accountId = v) } },
            label = { Text("Account id") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = draft.amountText,
            onValueChange = { v -> viewModel.updateDraft { it.copy(amountText = v) } },
            label = { Text("Amount (minor units)") },
            isError = draft.error?.contains("amount", ignoreCase = true) == true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = draft.currencyCode,
            onValueChange = { v -> viewModel.updateDraft { it.copy(currencyCode = v) } },
            label = { Text("Currency") },
            isError = draft.error?.contains("currency", ignoreCase = true) == true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = draft.merchant,
            onValueChange = { v -> viewModel.updateDraft { it.copy(merchant = v) } },
            label = { Text("Merchant") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = draft.counterparty,
            onValueChange = { v -> viewModel.updateDraft { it.copy(counterparty = v) } },
            label = { Text("Counterparty") },
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = draft.note,
            onValueChange = { v -> viewModel.updateDraft { it.copy(note = v) } },
            label = { Text("Note") },
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TxKind.entries.forEach { kind ->
                OutlinedButton(onClick = { viewModel.updateDraft { it.copy(kind = kind) } }) {
                    Text(if (draft.kind == kind) "[$kind]" else "$kind")
                }
            }
        }

        draft.error?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { viewModel.save() }) { Text("Save") }
            OutlinedButton(onClick = { viewModel.cancel(); onSaved() }) { Text("Cancel") }
        }
    }
}
