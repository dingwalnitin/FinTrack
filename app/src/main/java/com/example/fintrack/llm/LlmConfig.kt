package com.example.fintrack.llm

/**
 * Persistable LLM config for the OpenAI-compatible Chat Completions API.
 *
 * All fields are user-supplied; the app never hard-codes any API endpoint,
 * key, or model. The defaults point to the standard OpenAI API.
 */
data class LlmConfig(
    val baseUrl: String = "https://api.openai.com",
    val apiKey: String = "",
    val modelId: String = "gpt-4o-mini",
) {
    /** The full Chat Completions URL: {baseUrl}/v1/chat/completions */
    val chatCompletionsUrl: String
        get() = "${baseUrl.trimEnd('/')}/v1/chat/completions"

    val isValid: Boolean
        get() = apiKey.isNotBlank() && modelId.isNotBlank() && baseUrl.isNotBlank()
}