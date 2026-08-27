package com.example.fintrack.ui.sms

import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.fintrack.FinTrackApplication
import com.example.fintrack.domain.repository.IngestionProgress
import com.example.fintrack.domain.repository.SmsRepository
import com.example.fintrack.sms.SmsIngestionScheduler
import com.example.fintrack.sms.SmsSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Drives [SmsConsentScreen]. Reads from the [SmsRepository] (only aggregate
 * progress is observed — no per-message recomposition) and consults the
 * [SmsSource] for permission state at the platform boundary.
 */
class SmsConsentViewModel(
    application: android.app.Application,
    private val repository: SmsRepository,
    private val source: SmsSource,
) : AndroidViewModel(application) {

    private val _permissionGranted = MutableStateFlow(source.hasPermission())
    val state: StateFlow<SmsConsentState> = combine(
        repository.observeProgress(),
        _permissionGranted,
    ) { progress: IngestionProgress, granted: Boolean ->
        SmsConsentState(
            hasPermission = granted,
            phase = when {
                !granted && progress.status == "REVOKED" -> SmsConsentPhase.REVOKED
                !granted -> SmsConsentPhase.NEEDS_CONSENT
                else -> SmsConsentPhase.READY
            },
            progress = progress,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(5_000),
        SmsConsentState(
            hasPermission = _permissionGranted.value,
            phase = SmsConsentPhase.NEEDS_CONSENT,
            progress = IngestionProgress(
                totalPersisted = 0L, status = "IDLE", lastUpdatedAtEpochMs = 0L, lastError = null,
            ),
        ),
    )

    fun onPermissionResult(granted: Boolean) {
        _permissionGranted.value = granted
        if (!granted) {
            viewModelScope.launch { repository.markStatus("REVOKED", lastError = null) }
        }
    }

    fun onRequestPermission() {
        // The actual runtime request is invoked by the host activity so it
        // receives the result. The view-model only reacts to the outcome.
    }

    fun onStartBackfill(context: Context) {
        SmsIngestionScheduler.enqueueBackfill(context.applicationContext)
    }

    fun onPauseBackfill(context: Context) {
        SmsIngestionScheduler.cancelBackfill(context.applicationContext)
    }

    fun onRevokeHandled() {
        viewModelScope.launch { repository.markStatus("IDLE") }
    }

    class Factory(private val app: FinTrackApplication) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            SmsConsentViewModel(app, app.smsRepository, app.smsSource) as T
    }
}
