package com.example.fintrack.llm

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Persistence for [LlmConfig] via app-private SharedPreferences.
 *
 * Supports multi-API-key pooling with automatic migration from legacy single-key
 * storage. Keys are stored in app-private storage, never logged or exported.
 */
class LlmConfigStore(context: Context) {

    private val prefs = context.getSharedPreferences("fintrack_llm_config", Context.MODE_PRIVATE)

    fun load(): LlmConfig {
        val baseUrl = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        val modelId = prefs.getString(KEY_MODEL_ID, DEFAULT_MODEL_ID) ?: DEFAULT_MODEL_ID
        val keysJson = prefs.getString(KEY_KEYS_JSON, null)

        val keys: List<LlmKeyEntry> = if (!keysJson.isNullOrBlank()) {
            parseKeysJson(keysJson)
        } else {
            // Migration: Check for legacy single API key
            val legacyKey = prefs.getString(KEY_API_KEY, "")?.trim() ?: ""
            if (legacyKey.isNotBlank()) {
                val migratedEntry = LlmKeyEntry(
                    id = UUID.randomUUID().toString(),
                    apiKey = legacyKey,
                    label = "Primary Key (${LlmKeyEntry.defaultLabel(legacyKey)})",
                    enabled = true,
                    addedAtEpochMs = System.currentTimeMillis(),
                )
                val migratedList = listOf(migratedEntry)
                // Persist the migrated structure
                prefs.edit().putString(KEY_KEYS_JSON, serializeKeysJson(migratedList)).apply()
                migratedList
            } else {
                emptyList()
            }
        }

        return LlmConfig(
            baseUrl = baseUrl,
            modelId = modelId,
            keys = keys,
        )
    }

    fun save(config: LlmConfig) {
        prefs.edit()
            .putString(KEY_BASE_URL, config.baseUrl)
            .putString(KEY_MODEL_ID, config.modelId)
            .putString(KEY_KEYS_JSON, serializeKeysJson(config.keys))
            // Also keep legacy key updated for any external/legacy readers
            .putString(KEY_API_KEY, config.apiKey)
            .apply()
    }

    fun addKey(apiKey: String, label: String? = null): LlmKeyEntry {
        val trimmed = apiKey.trim()
        require(trimmed.isNotBlank()) { "API key must not be blank" }
        val current = load()
        val entry = LlmKeyEntry(
            id = UUID.randomUUID().toString(),
            apiKey = trimmed,
            label = label?.trim()?.takeIf { it.isNotBlank() } ?: LlmKeyEntry.defaultLabel(trimmed),
            enabled = true,
            addedAtEpochMs = System.currentTimeMillis(),
        )
        val updatedKeys = current.keys + entry
        save(current.copy(keys = updatedKeys))
        return entry
    }

    fun removeKey(keyId: String): Boolean {
        val current = load()
        val updatedKeys = current.keys.filterNot { it.id == keyId }
        if (updatedKeys.size != current.keys.size) {
            save(current.copy(keys = updatedKeys))
            return true
        }
        return false
    }

    fun toggleKeyEnabled(keyId: String, enabled: Boolean): Boolean {
        val current = load()
        var found = false
        val updatedKeys = current.keys.map {
            if (it.id == keyId) {
                found = true
                it.copy(enabled = enabled)
            } else {
                it
            }
        }
        if (found) {
            save(current.copy(keys = updatedKeys))
        }
        return found
    }

    fun updateKey(updated: LlmKeyEntry): Boolean {
        val current = load()
        var found = false
        val updatedKeys = current.keys.map {
            if (it.id == updated.id) {
                found = true
                updated
            } else {
                it
            }
        }
        if (found) {
            save(current.copy(keys = updatedKeys))
        }
        return found
    }

    private fun serializeKeysJson(keys: List<LlmKeyEntry>): String {
        val arr = JSONArray()
        for (k in keys) {
            val obj = JSONObject()
                .put("id", k.id)
                .put("apiKey", k.apiKey)
                .put("label", k.label)
                .put("enabled", k.enabled)
                .put("addedAtEpochMs", k.addedAtEpochMs)
            arr.put(obj)
        }
        return arr.toString()
    }

    private fun parseKeysJson(json: String): List<LlmKeyEntry> {
        val list = mutableListOf<LlmKeyEntry>()
        return try {
            val arr = JSONArray(json)
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                list.add(
                    LlmKeyEntry(
                        id = obj.optString("id", UUID.randomUUID().toString()),
                        apiKey = obj.getString("apiKey"),
                        label = obj.optString("label", "Key"),
                        enabled = obj.optBoolean("enabled", true),
                        addedAtEpochMs = obj.optLong("addedAtEpochMs", System.currentTimeMillis()),
                    )
                )
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    private companion object {
        const val KEY_BASE_URL = "base_url"
        const val KEY_API_KEY = "api_key"
        const val KEY_MODEL_ID = "model_id"
        const val KEY_KEYS_JSON = "keys_json"

        const val DEFAULT_BASE_URL = "https://api.openai.com"
        const val DEFAULT_MODEL_ID = "gpt-4o-mini"
    }
}