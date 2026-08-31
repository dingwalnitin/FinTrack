package com.example.fintrack.llm

/**
 * Persistable LLM config for the OpenAI-compatible Chat Completions API.
 *
 * Supports multi-API-key pooling. All fields are user-supplied; the app never
 * hard-codes any API endpoint, key, or model. The defaults point to the
 * standard OpenAI API.
 */
data class LlmConfig(
    val baseUrl: String = "https://api.openai.com",
    val modelId: String = "gpt-4o-mini",
    val keys: List<LlmKeyEntry> = emptyList(),
) {
    /** Secondary constructor for backward compatibility with single-key callers/tests. */
    constructor(baseUrl: String, apiKey: String, modelId: String) : this(
        baseUrl = baseUrl,
        modelId = modelId,
        keys = if (apiKey.isNotBlank()) {
            listOf(LlmKeyEntry(apiKey = apiKey.trim(), label = LlmKeyEntry.defaultLabel(apiKey.trim())))
        } else {
            emptyList()
        },
    )

    /** Backward-compatible access to the primary active API key. */
    val apiKey: String
        get() = activeKeys.firstOrNull()?.apiKey ?: keys.firstOrNull()?.apiKey ?: ""

    /** Enabled keys with non-blank secrets. */
    val activeKeys: List<LlmKeyEntry>
        get() = keys.filter { it.enabled && it.apiKey.isNotBlank() }

    /** The full Chat Completions URL: {baseUrl}/v1/chat/completions */
    val chatCompletionsUrl: String
        get() = "${baseUrl.trimEnd('/')}/v1/chat/completions"

    val isValid: Boolean
        get() = activeKeys.isNotEmpty() && modelId.isNotBlank() && baseUrl.isNotBlank()
}