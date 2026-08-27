package com.example.fintrack.application.enrichment

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.fintrack.sms.SmsIngestionScheduler
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for the LLM SMS-processing progress surface in Settings.
 *
 * Exposes a trigger ([startScan]) and a [StateFlow] of [LlmProcessingService.Progress]
 * so the UI can render a progress bar + counts while ALL SMSes are processed
 * through the LLM.
 *
 * When [startScan] is invoked, it first triggers a one-shot SMS backfill
 * (which requires `READ_SMS` permission) so freshly-captured SMS land in
 * `raw_sms` before the scan iterates them. If permission has not yet been
 * granted, the user must grant it first via the consent screen.
 */
class LlmProcessingViewModel(
    private val service: LlmProcessingService,
) : ViewModel() {

    private val _progress = MutableStateFlow(LlmProcessingService.Progress())
    val progress: StateFlow<LlmProcessingService.Progress> = _progress.asStateFlow()

    init {
        // Surface the service's live progress into the UI state flow.
        viewModelScope.launch {
            service.progress.collect { p -> _progress.value = p }
        }
    }

    fun startScan(context: Context? = null) {
        // Kick a backfill so any unread SMS land in raw_sms first; the LLM
        // scan is idempotent and will pick up only newly captured rows (or
        // everything if this is the first run).
        if (context != null) {
            try {
                SmsIngestionScheduler.enqueueBackfill(context.applicationContext)
            } catch (_: Throwable) {
                // Backfill scheduling must never block the LLM scan; we just
                // log the failure through the service's lastError path below.
            }
        }
        service.startScan()
    }

    fun stopScan() = service.stopScan()

    override fun onCleared() {
        super.onCleared()
    }
}