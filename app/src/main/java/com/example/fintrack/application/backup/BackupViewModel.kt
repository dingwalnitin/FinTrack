package com.example.fintrack.application.backup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.fintrack.domain.model.ImportCommitResult
import com.example.fintrack.domain.model.ImportPreview
import com.example.fintrack.domain.model.MergePolicy
import com.example.fintrack.domain.service.AuditService
import com.example.fintrack.domain.service.BackupCrypto
import com.example.fintrack.domain.service.BackupService
import com.example.fintrack.data.repository.RoomBackupRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Stage 11 P23 — backup/restore orchestration for the UI.
 *
 * State machine: Idle → Exported | Validated → Previewed → Committed/Failed.
 * Every commit is user-initiated; nothing writes to live tables without an
 * explicit confirmation through [confirmImport].
 */
class BackupViewModel(
    private val backupService: BackupService,
    private val backupRepository: RoomBackupRepository,
    private val auditService: AuditService,
    private val appVersion: String = "0.1.0",
) : ViewModel() {

    sealed interface UiState {
        data object Idle : UiState
        data class ExportReady(val payload: String, val encrypted: Boolean) : UiState
        data class ValidationFailed(val reasons: List<String>) : UiState
        data class PreviewReady(val preview: ImportPreview) : UiState
        data class Committed(val result: ImportCommitResult.Committed) : UiState
        data class Aborted(val reason: String) : UiState
        data class Failed(val reason: String) : UiState
    }

    private val _state = MutableStateFlow<UiState>(UiState.Idle)
    val state: StateFlow<UiState> = _state.asStateFlow()

    fun export(encrypted: Boolean, password: CharArray?) {
        viewModelScope.launch {
            try {
                val payload = backupService.buildExport(appVersion)
                val body = if (encrypted && password != null) {
                    BackupCrypto.encrypt(payload.body, password)
                } else {
                    payload.body
                }
                auditService.recordAsync(
                    AuditService.ACT_EXPORT,
                    detail = "datasets=${payload.manifest.datasets.size}, encrypted=$encrypted",
                )
                _state.value = UiState.ExportReady(body, encrypted)
            } catch (e: Exception) {
                _state.value = UiState.Failed(e.message ?: "export failed")
            }
        }
    }

    /** Validate + stage a payload (plaintext or encrypted). */
    fun importPayload(raw: String, password: CharArray? = null) {
        viewModelScope.launch {
            try {
                val plaintext = when {
                    BackupCrypto.isEncryptedEnvelope(raw) && password != null ->
                        try {
                            BackupCrypto.decrypt(raw, password)
                        } catch (e: BackupCrypto.BackupCryptoException) {
                            _state.value = UiState.Failed(e.message ?: "decryption failed")
                            return@launch
                        }
                    BackupCrypto.isEncryptedEnvelope(raw) -> {
                        _state.value = UiState.Failed("password required for this encrypted backup")
                        return@launch
                    }
                    else -> raw
                }
                when (val validation = backupService.stageValidated(plaintext)) {
                    is com.example.fintrack.domain.model.ImportValidation.Invalid ->
                        _state.value = UiState.ValidationFailed(validation.reasons)
                    is com.example.fintrack.domain.model.ImportValidation.Valid -> {
                        val preview = backupService.preview()
                        _state.value = UiState.PreviewReady(preview)
                    }
                }
            } catch (e: Exception) {
                _state.value = UiState.Failed(e.message ?: "import failed")
            }
        }
    }

    /** User confirmed the preview — commit with the chosen policy. */
    fun confirmImport(policy: MergePolicy, replaceIds: Set<String>) {
        viewModelScope.launch {
            // Honor the user's per-row selection: only the checked conflict
            // ids may be replaced; everything else keeps the live value.
            val result = backupService.commit(policy, replaceIds)
            when (result) {
                is ImportCommitResult.Committed -> {
                    auditService.recordAsync(
                        AuditService.ACT_IMPORT_COMMIT,
                        detail = "inserted=${result.insertedByDataset.values.sum()}, " +
                            "replaced=${result.replacedByDataset.values.sum()}",
                    )
                    _state.value = UiState.Committed(result)
                }
                is ImportCommitResult.Aborted -> _state.value = UiState.Aborted(result.reason)
                is ImportCommitResult.Failed -> _state.value = UiState.Failed(result.reason)
            }
        }
    }

    fun cancelImport() {
        viewModelScope.launch {
            backupRepository.clearStaging()
            _state.value = UiState.Idle
        }
    }

    fun reset() {
        _state.value = UiState.Idle
    }
}
