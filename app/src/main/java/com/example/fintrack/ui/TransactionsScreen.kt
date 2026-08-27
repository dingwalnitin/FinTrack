package com.example.fintrack.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.fintrack.domain.model.Transaction
import com.example.fintrack.ui.common.UiState

@Composable
fun TransactionsScreen(state: UiState<List<Transaction>>) {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        when (state) {
            is UiState.Loading -> CircularProgressIndicator()
            is UiState.Empty -> Text("No transactions yet", style = MaterialTheme.typography.bodyLarge)
            is UiState.Error -> Text("Error: ${state.message}", color = MaterialTheme.colorScheme.error)
            is UiState.Review -> Text("Review needed: ${state.reason.name}")
            is UiState.Content -> Text("${state.data.size} transactions", style = MaterialTheme.typography.headlineSmall)
        }
    }
}
