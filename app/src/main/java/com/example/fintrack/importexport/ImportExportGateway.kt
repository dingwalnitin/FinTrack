package com.example.fintrack.importexport

import com.example.fintrack.domain.model.Transaction

/** Import/export boundary. Exports must exclude secrets and raw evidence by default. */
interface ImportExportGateway {
    suspend fun exportTransactions(transactions: List<Transaction>): ExportResult
}

sealed interface ExportResult {
    data class Success(val uri: String) : ExportResult
    data class Failure(val reason: String) : ExportResult
}
