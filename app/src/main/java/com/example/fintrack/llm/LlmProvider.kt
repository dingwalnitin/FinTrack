package com.example.fintrack.llm

/**
 * P07: provider abstraction. Providers translate their native transport and
 * error semantics into [ParseResponse] / [LlmProviderException]. They never
 * touch Room, never see raw sender identifiers, and never write financial data.
 */
interface LlmProvider {
    val providerId: String
    val modelId: String

    /**
     * Execute one enrichment request. Implementations must:
     *  - return a raw JSON string response (validation happens in the app layer)
     *  - throw [LlmProviderException] with a normalized [LlmErrorClass] on failure
     */
    suspend fun complete(prompt: String): String
}

/** Deterministic fake for tests and offline-safe development. */
class FakeLlmProvider(
    override val providerId: String = "fake",
    override val modelId: String = "fake-model-1",
    private val responder: suspend (prompt: String) -> String,
) : LlmProvider {
    var callCount: Int = 0
        private set

    override suspend fun complete(prompt: String): String {
        callCount++
        return responder(prompt)
    }
}
