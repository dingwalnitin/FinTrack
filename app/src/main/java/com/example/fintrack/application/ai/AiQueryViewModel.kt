package com.example.fintrack.application.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.fintrack.data.repository.RoomAiQueryRepository
import com.example.fintrack.domain.ai.AiQueryParser
import com.example.fintrack.domain.ai.AiQueryPlan
import com.example.fintrack.domain.ai.AiSafetyPolicy
import com.example.fintrack.domain.ai.AiSummaryGenerator
import com.example.fintrack.domain.ai.PlanResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * Stage 10 / P21 — AI query ViewModel.
 *
 * Owns the NL-query flow: safety check → parse → (confirm range) → execute
 * plan over retrieved structured facts → grounded, cited summary. The LLM is
 * never in the critical path: parsing and execution are deterministic and
 * fully offline; an optional narrative rewrite can be layered later behind
 * the existing [com.example.fintrack.llm.LlmProvider] interface without
 * changing this state machine.
 */
class AiQueryViewModel(
    private val repository: RoomAiQueryRepository,
    private val parser: AiQueryParser,
    private val summaryGenerator: AiSummaryGenerator = AiSummaryGenerator(),
) : ViewModel() {

    data class State(
        val loading: Boolean = false,
        val query: String = "",
        /** Interpreted date range shown BEFORE risky/ambiguous execution. */
        val interpretedRangeLabel: String? = null,
        val needsConfirmation: Boolean = false,
        val confirmationReason: String? = null,
        val result: PlanResult? = null,
        val summary: AiSummaryGenerator.Summary? = null,
        val refused: Boolean = false,
        val refusalMessage: String? = null,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var pendingPlan: AiQueryPlan? = null

    fun updateQuery(text: String) {
        _state.value = _state.value.copy(query = text)
    }

    /**
     * Run the full pipeline for the current query. Safety policy runs first;
     * refusals never reach retrieval or execution.
     */
    fun run(today: LocalDate = LocalDate.now()) {
        val q = _state.value.query.trim()
        if (q.isEmpty()) return

        // 1. Safety gate (module 85).
        val safety = AiSafetyPolicy.evaluate(q)
        if (safety.verdict == AiSafetyPolicy.Verdict.REFUSE) {
            _state.value = _state.value.copy(
                refused = true,
                refusalMessage = safety.message,
                result = null,
                summary = null,
                needsConfirmation = false,
            )
            return
        }

        // 2. Deterministic parse into a validated plan.
        when (val outcome = parser.parse(q, today)) {
            is AiQueryParser.ParseOutcome.Unparsed -> {
                _state.value = _state.value.copy(
                    error = "Couldn't understand that query. Try 'spending by category last month'.",
                    result = null,
                    summary = null,
                    refused = false,
                )
            }
            is AiQueryParser.ParseOutcome.NeedsConfirmation -> {
                pendingPlan = outcome.plan
                _state.value = _state.value.copy(
                    needsConfirmation = true,
                    confirmationReason = outcome.reason,
                    result = null,
                    summary = null,
                    error = null,
                    refused = false,
                )
            }
            is AiQueryParser.ParseOutcome.Parsed -> {
                execute(outcome.plan)
            }
        }
    }

    /** User confirmed the interpreted range — run the pending plan. */
    fun confirmPendingPlan() {
        pendingPlan?.let { execute(it) }
        pendingPlan = null
        _state.value = _state.value.copy(needsConfirmation = false, confirmationReason = null)
    }

    fun cancelPendingPlan() {
        pendingPlan = null
        _state.value = _state.value.copy(needsConfirmation = false, confirmationReason = null)
    }

    /**
     * Execute a validated plan against retrieved structured facts only.
     * Failures here are isolated — local finance functionality is untouched.
     */
    private fun execute(plan: AiQueryPlan) {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            try {
                val result = if (plan.filters.fromDay != null || plan.filters.toDay != null) {
                    repository.execute(plan)
                } else {
                    repository.executeUnbounded(plan)
                }
                val summary = summaryGenerator.summarize(
                    result = result,
                    plan = plan,
                )
                _state.value = _state.value.copy(
                    loading = false,
                    result = result,
                    summary = summary,
                    interpretedRangeLabel = plan.filters.fromDay?.let { f ->
                        "from ${LocalDate.ofEpochDay(f)}" +
                            (plan.filters.toDay?.let { " to ${LocalDate.ofEpochDay(it)}" } ?: "")
                    },
                    refused = false,
                )
            } catch (t: Throwable) {
                // AI query failure must stay isolated from local finance data.
                _state.value = _state.value.copy(
                    loading = false,
                    error = t.message ?: t::class.java.simpleName,
                )
            }
        }
    }

    fun clear() {
        pendingPlan = null
        _state.value = State()
    }
}
