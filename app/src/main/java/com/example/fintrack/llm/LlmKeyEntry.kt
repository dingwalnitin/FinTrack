package com.example.fintrack.llm

import java.util.UUID

/**
 * Represents a single API key entry in the multi-key pool.
 *
 * Keys are stored with a stable [id], user-supplied [label], the raw [apiKey],
 * and an [enabled] toggle so users can disable a key without deleting it.
 */
data class LlmKeyEntry(
    /** Stable random UUID, assigned on creation. */
    val id: String = UUID.randomUUID().toString(),
    /** The raw API key string (e.g. "sk-abc...1234"). */
    val apiKey: String,
    /** Human-readable label, e.g. "Work Key" or "Key 2". */
    val label: String = defaultLabel(apiKey),
    /** Whether this key participates in rotation (default: true). */
    val enabled: Boolean = true,
    /** Epoch ms when this key was added to the pool. */
    val addedAtEpochMs: Long = System.currentTimeMillis(),
) {
    /**
     * A short masked representation, e.g. "…1234" for display.
     * Shows only the last 4 characters to avoid leaking the secret.
     */
    val maskedKey: String
        get() = if (apiKey.length >= 4) "…${apiKey.takeLast(4)}" else "…***"

    companion object {
        /** Default label derived from the last-4 characters of the key. */
        fun defaultLabel(apiKey: String): String =
            if (apiKey.length >= 4) "Key …${apiKey.takeLast(4)}" else "API Key"
    }
}

