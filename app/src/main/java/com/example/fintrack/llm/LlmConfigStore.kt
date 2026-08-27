package com.example.fintrack.llm

import android.content.Context

/**
 * Persistence for [LlmConfig] via app-private SharedPreferences.
 *
 * The API key is stored in plain app-private SharedPreferences. It is only
 * used to call the user-configured endpoint and is never logged or exported.
 */
class LlmConfigStore(context: Context) {

    private val prefs = context.getSharedPreferences("fintrack_llm_config", Context.MODE_PRIVATE)

    fun load(): LlmConfig = LlmConfig(
        baseUrl = prefs.getString(KEY_BASE_URL, LlmConfig().baseUrl) ?: LlmConfig().baseUrl,
        apiKey = prefs.getString(KEY_API_KEY, "") ?: "",
        modelId = prefs.getString(KEY_MODEL_ID, LlmConfig().modelId) ?: LlmConfig().modelId,
    )

    fun save(config: LlmConfig) {
        prefs.edit()
            .putString(KEY_BASE_URL, config.baseUrl)
            .putString(KEY_API_KEY, config.apiKey)
            .putString(KEY_MODEL_ID, config.modelId)
            .apply()
    }

    private companion object {
        const val KEY_BASE_URL = "base_url"
        const val KEY_API_KEY = "api_key"
        const val KEY_MODEL_ID = "model_id"
    }
}