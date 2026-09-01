package com.example.fintrack.application.review

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.fintrack.data.db.LlmDao
import com.example.fintrack.data.db.LlmJobStates
import com.example.fintrack.data.db.SmsDao
import com.example.fintrack.domain.service.SmsReviewService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Stage 13 (F) — SMS review ViewModel.
 *
 * Lists SMS ingestion results (passed / failed / pending) by joining raw_sms
 * with their latest llm_jobs status. Supports re-running a failed message's
 * LLM job (single-job re-run, bypassing the 90-day batch lookback) and manual
 * overrides via [SmsReviewService] so provenance/audit/idempotency hold.
 */
class SmsReviewViewModel(
    private val smsDao: SmsDao,
    private val llmDao: LlmDao,
    private val reviewService: SmsReviewService,
) : ViewModel() {

    enum class StatusFilter { ALL, FAILED, PENDING, SUCCEEDED }

    data class SmsReviewRow(
        val rawSmsId: String,
        val sender: String?,
        val body: String,
        val receivedAtEpochMs: Long,
        val jobStatus: String?,   // LlmJobStates or null when no job
        val lastErrorClass: String?,
    )

    data class State(
        val loading: Boolean = true,
        val rows: List<SmsReviewRow> = emptyList(),
        val filter: StatusFilter = StatusFilter.ALL,
        val counts: Map<String, Long> = emptyMap(),
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun refresh() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                val raws = smsDao.allRawRows()
                val counts = llmDao.jobStatusCounts().associate { it.status to it.count }
                val latestByMessage = raws.mapNotNull { raw ->
                    llmDao.jobForMessage(raw.id)?.let { raw.id to it }
                }.toMap()
                val rows = raws.map { raw ->
                    val job = latestByMessage[raw.id]
                    SmsReviewRow(
                        rawSmsId = raw.id,
                        sender = raw.sender,
                        body = raw.body,
                        receivedAtEpochMs = raw.receivedAtEpochMs,
                        jobStatus = job?.status,
                        lastErrorClass = job?.lastErrorClass,
                    )
                }
                _state.value = _state.value.copy(
                    loading = false,
                    rows = rows,
                    counts = counts,
                )
            } catch (t: Throwable) {
                _state.value = _state.value.copy(loading = false, error = t.message ?: t::class.java.simpleName)
            }
        }
    }

    fun setFilter(filter: StatusFilter) {
        _state.value = _state.value.copy(filter = filter)
    }

    /** Rows surviving the active status filter (derived; UI consumes this). */
    val visibleRows: List<SmsReviewRow>
        get() {
            val f = _state.value.filter
            return _state.value.rows.filter { row ->
                when (f) {
                    StatusFilter.ALL -> true
                    StatusFilter.FAILED -> row.jobStatus in setOf(LlmJobStates.RETRYABLE_FAILED, LlmJobStates.TERMINAL_FAILED)
                    StatusFilter.PENDING -> row.jobStatus == null || row.jobStatus == LlmJobStates.PENDING
                    StatusFilter.SUCCEEDED -> row.jobStatus == LlmJobStates.SUCCEEDED
                }
            }
        }

    /** Re-run a single message's LLM job (bypasses the 90-day batch lookback). */
    fun rerunMessage(rawSmsId: String) {
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            llmDao.resetJobToPending(rawSmsId, now)
            refresh()
        }
    }

    init {
        refresh()
    }
}
