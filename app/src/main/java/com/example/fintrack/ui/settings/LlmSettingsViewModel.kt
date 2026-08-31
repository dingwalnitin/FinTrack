package com.example.fintrack.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.fintrack.llm.ChatCompletionsProvider
import com.example.fintrack.llm.LlmConfig
import com.example.fintrack.llm.LlmConfigStore
import com.example.fintrack.llm.LlmKeyEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * ViewModel for the dedicated LLM settings screen.
 *
 * Supports multi-API-key pooling, per-key enablement, and key testing.
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

    /** Add a new API key to the in-memory pool. */
    fun addKey(apiKey: String, label: String? = null) {
        val trimmed = apiKey.trim()
        if (trimmed.isBlank()) return
        val entry = LlmKeyEntry(
            id = UUID.randomUUID().toString(),
            apiKey = trimmed,
            label = label?.trim()?.takeIf { it.isNotBlank() } ?: LlmKeyEntry.defaultLabel(trimmed),
            enabled = true,
            addedAtEpochMs = System.currentTimeMillis(),
        )
        val current = _config.value
        updateConfig(current.copy(keys = current.keys + entry))
    }

    /** Remove a key from the in-memory pool. */
    fun removeKey(keyId: String) {
        val current = _config.value
        val filtered = current.keys.filterNot { it.id == keyId }
        updateConfig(current.copy(keys = filtered))
    }

    /** Toggle enable/disable status for a key. */
    fun toggleKeyEnabled(keyId: String, enabled: Boolean) {
        val current = _config.value
        val updatedKeys = current.keys.map {
            if (it.id == keyId) it.copy(enabled = enabled) else it
        }
        updateConfig(current.copy(keys = updatedKeys))
    }

    /** Persist the current values to the store and mark them saved. */
    fun save() {
        val cfg = _config.value
        store.save(cfg)
        _saved.value = true
    }

    /**
     * Run a connectivity probe with the primary active key.
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

    /** Clear any previous test result. */
    fun clearTestResult() {
        _testResult.value = null
        _saved.value = false
    }
}
