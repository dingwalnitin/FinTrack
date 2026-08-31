package com.example.fintrack.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.fintrack.llm.ChatCompletionsProvider
import com.example.fintrack.llm.LlmConfig
import com.example.fintrack.llm.LlmConfigStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * ViewModel for the dedicated LLM settings screen.
 *
 * Owns the editable [LlmConfig] fields, persists them (via [LlmConfigStore])
 * on demand, and drives a live "Test connection" probe so the user can verify
 * base URL, API key and model id actually work before running a scan.
 *
 * The test builds a throwaway [ChatCompletionsProvider] from the *unsaved*
 * field values so the user can validate a candidate config without first
 * committing it; it intentionally never touches the app-wide shared instance.
 */
class LlmSettingsViewModel(
    private val store: LlmConfigStore,
) : ViewModel() {

    private val _config = MutableStateFlow(store.load())
    val config: StateFlow<LlmConfig> = _config.asStateFlow()

    private val _testing = MutableStateFlow(false)
    val testing: StateFlow<Boolean> = _testing.asStateFlow()

    sealed interface TestResult {
        data class Success(
            val diagnostics: ChatCompletionsProvider.ConnectionDiagnostics,
        ) : TestResult
        data class Failure(val message: String) : TestResult
        data object Incomplete : TestResult
    }

    private val _testResult = MutableStateFlow<TestResult?>(null)
    val testResult: StateFlow<TestResult?> = _testResult.asStateFlow()

    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved.asStateFlow()

    /** Apply an in-progress field edit without persisting yet. */
    fun updateConfig(updated: LlmConfig) {
        _config.value = updated
        _saved.value = false
        _testResult.value = null
    }

    /** Persist the current values to the store and mark them saved. */
    fun save() {
        val cfg = _config.value
        store.save(cfg)
        _saved.value = true
    }

    /**
     * Run a connectivity probe with the *current (possibly unsaved)* values.
     * A throwaway provider is used so the saved config is never mutated by a
     * test; the app-wide singleton keeps reading the persisted config.
     */
    fun testConnection() {
        val cfg = _config.value
        if (!cfg.isValid) {
            _testing.value = false
            _testResult.value = TestResult.Incomplete
            return
        }

        if (_testing.value) return
        _testing.value = true
        _testResult.value = null

        viewModelScope.launch {
            _testResult.value = try {
                val probe = ChatCompletionsProvider(cfg)
                val diagnostics = withContext(Dispatchers.IO) { probe.testConnection() }
                TestResult.Success(diagnostics)
            } catch (e: IllegalArgumentException) {
                TestResult.Incomplete
            } catch (e: Exception) {
                TestResult.Failure(message = e.message ?: e.toString())
            } finally {
                _testing.value = false
            }
        }
    }

    /** Clear any previous test result (e.g. when the user edits fields). */
    fun clearTestResult() {
        _testResult.value = null
        _saved.value = false
    }
}
