package com.example.fintrack.llm

import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/**
 * OpenAI-compatible Chat Completions provider.
 *
 * Implements [LlmProvider] against any OpenAI-compatible endpoint
 * (OpenAI, Azure OpenAI, Groq, OpenRouter, local llama.cpp/Ollama gateways,
 * etc.) using the standard `POST {baseUrl}/v1/chat/completions` contract.
 *
 * The raw JSON response body is returned; validation happens in the app
 * layer ([LlmResponseDecoder]).
 */
class ChatCompletionsProvider(
    private val config: LlmConfig,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 60_000,
) : LlmProvider {

    override val providerId: String = "chat-completions"

    override val modelId: String
        get() = config.modelId

    /**
     * Execute one enrichment request via the Chat Completions API.
     *
     * The [prompt] is sent as a single system message instructing the model
     * to return strict JSON; the model's `content` field is returned raw.
     */
    override suspend fun complete(prompt: String): String {
        require(config.isValid) { "LLM not configured: set base URL, API key and model in Settings" }

        val payload = JSONObject()
            .put("model", config.modelId)
            .put("temperature", 0.0)
            .put("messages", JSONArray().put(
                JSONObject()
                    .put("role", "system")
                    .put("content", prompt)
            ))
            .put("response_format", JSONObject().put("type", "json_object"))

        val conn = URL(config.chatCompletionsUrl).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Authorization", "Bearer ${config.apiKey}")
            conn.doOutput = true

            conn.outputStream.use { os: OutputStream ->
                os.write(payload.toString().toByteArray(Charsets.UTF_8))
            }

            val code = conn.responseCode
            if (code !in 200..299) {
                val errBody = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                throw classifyHttpError(code, errBody)
            }

            val raw = conn.inputStream.bufferedReader().use { it.readText() }
            return extractContent(raw)
        } catch (e: LlmProviderException) {
            throw e
        } catch (e: SocketTimeoutException) {
            throw LlmProviderException(
                errorClass = LlmErrorClass.TIMEOUT,
                message = "LLM request timed out after ${readTimeoutMs}ms",
                cause = e,
            )
        } catch (e: Exception) {
            throw LlmProviderException(
                errorClass = LlmErrorClass.PROVIDER_UNAVAILABLE,
                message = "LLM request failed: ${e.message}",
                cause = e,
            )
        } finally {
            conn.disconnect()
        }
    }

    /** Map an HTTP status to a normalized [LlmErrorClass]. */
    private fun classifyHttpError(code: Int, body: String): LlmProviderException = when (code) {
        401, 403 -> LlmProviderException(
            errorClass = LlmErrorClass.SCHEMA_VALIDATION_FAILED,
            message = "LLM API rejected the request (HTTP $code): check your API key / base URL",
        )
        429 -> LlmProviderException(
            errorClass = LlmErrorClass.RATE_LIMITED,
            message = "LLM API rate limit hit (HTTP 429)",
        )
        in 500..599 -> LlmProviderException(
            errorClass = LlmErrorClass.PROVIDER_UNAVAILABLE,
            message = "LLM API unavailable (HTTP $code)",
        )
        else -> LlmProviderException(
            errorClass = LlmErrorClass.SCHEMA_VALIDATION_FAILED,
            message = "LLM API returned HTTP $code: $body",
        )
    }

    /** Pull the `choices[0].message.content` string out of a Chat Completions response. */
    private fun extractContent(raw: String): String {
        val root = JSONObject(raw)
        val choices = root.optJSONArray("choices")
            ?: throw LlmProviderException(
                errorClass = LlmErrorClass.BAD_JSON,
                message = "Chat Completions response missing 'choices'",
            )
        if (choices.length() == 0) {
            throw LlmProviderException(
                errorClass = LlmErrorClass.BAD_JSON,
                message = "Chat Completions response had no choices",
            )
        }
        val content = choices.getJSONObject(0)
            .optJSONObject("message")
            ?.optString("content")
            ?: throw LlmProviderException(
                errorClass = LlmErrorClass.BAD_JSON,
                message = "Chat Completions response missing message content",
            )
        return content.trim()
    }
}