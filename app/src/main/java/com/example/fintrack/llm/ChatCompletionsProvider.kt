package com.example.fintrack.llm

import org.json.JSONArray
import org.json.JSONObject
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
 *
 * Config is supplied lazily via [configProvider] rather than captured once.
 * This is essential: the user can edit and save the base URL / API key /
 * model in Settings at any time, and the same provider instance must pick up
 * the fresh values on the next request (otherwise a first-run provider built
 * from an empty config would stay misconfigured forever).
 */
class ChatCompletionsProvider(
    private val configProvider: () -> LlmConfig,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 180_000,
) : LlmProvider {

    /**
     * Convenience constructor for callers that already have a concrete
     * [LlmConfig] (e.g. a freshly constructed probe for the test button).
     */
    constructor(config: LlmConfig) : this({ config })

    override val providerId: String = "chat-completions"

    override val modelId: String
        get() = configProvider().modelId

    /** The currently-configured [LlmConfig] (re-read on each access). */
    val config: LlmConfig
        get() = configProvider()

    data class ConnectionDiagnostics(
        val modelId: String,
        val reply: String,
        val rawResponse: String,
        val statusCode: Int,
        val latencyMs: Long,
        val timeToFirstTokenMs: Long?,
        val promptTokens: Int?,
        val completionTokens: Int,
        val totalTokens: Int?,
        val tokensPerSecond: Double,
        val streamed: Boolean,
        /**
         * True when the server reported no `usage` block and
         * [completionTokens] (and therefore [tokensPerSecond]) is a chars/4
         * approximation rather than a measured count.
         */
        val completionTokensEstimated: Boolean,
        /**
         * What [tokensPerSecond] is divided by. Only the streaming path can
         * isolate decode time; without streaming the figure includes connect,
         * upload and prompt processing.
         */
        val throughputBasis: String,
        /** Output cap used for the probe — throughput over so few tokens is indicative only. */
        val maxTokens: Int,
    )

    /**
     * Execute one enrichment request via the Chat Completions API.
     *
     * The [prompt] is sent as a single system message instructing the model
     * to return strict JSON; the model's `content` field is returned raw.
     */
    override suspend fun complete(prompt: String): String {
        val cfg = configProvider()
        require(cfg.isValid) { "LLM not configured: set base URL, API key and model in Settings" }

        val payload = JSONObject()
            .put("model", cfg.modelId)
            .put("temperature", 0.0)
            .put("messages", JSONArray().put(
                JSONObject()
                    .put("role", "system")
                    .put("content", prompt)
            ))

        val conn = URL(cfg.chatCompletionsUrl).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            conn.setRequestProperty("Authorization", "Bearer ${cfg.apiKey}")
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

    /**
     * Verify that the current config reaches a working chat-completions
     * endpoint and returns a usable response. Sends a trivial "ping" prompt
     * so connectivity + auth + model id are all exercised in one call.
     *
     * Returns the raw model reply on success, or throws [LlmProviderException]
     * (or an [IllegalArgumentException] if the config is incomplete) on failure.
     */
    suspend fun testConnection(): ConnectionDiagnostics {
        val cfg = configProvider()
        require(cfg.isValid) {
            "LLM not configured: set base URL, API key and model id"
        }

        return try {
            executeConnectionTest(cfg, stream = true)
        } catch (_: StreamingUnsupportedException) {
            executeConnectionTest(cfg, stream = false)
        }
    }

    private fun executeConnectionTest(cfg: LlmConfig, stream: Boolean): ConnectionDiagnostics {
        val minimalPayload = JSONObject()
            .put("model", cfg.modelId)
            .put("temperature", 0.0)
            .put("max_tokens", PROBE_MAX_TOKENS)
            .put("stream", stream)
            .put("messages", JSONArray().put(
                JSONObject()
                    .put("role", "user")
                    .put("content", "Reply with OK.")
            ))
        if (stream) {
            // Without this most OpenAI-compatible servers omit `usage` from the
            // stream entirely, which would silently downgrade the reported
            // token counts (and tokens/sec) to a chars/4 guess.
            minimalPayload.put("stream_options", JSONObject().put("include_usage", true))
        }

        val conn = URL(cfg.chatCompletionsUrl).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = connectTimeoutMs
            conn.readTimeout = readTimeoutMs
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", if (stream) "text/event-stream" else "application/json")
            conn.setRequestProperty("Authorization", "Bearer ${cfg.apiKey}")
            conn.doOutput = true

            val startedAt = System.nanoTime()
            conn.outputStream.use { os: OutputStream ->
                os.write(minimalPayload.toString().toByteArray(Charsets.UTF_8))
            }

            val code = conn.responseCode
            if (code !in 200..299) {
                val errBody = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                if (stream && rejectsStreaming(code, errBody)) {
                    throw StreamingUnsupportedException()
                }
                throw classifyHttpError(code, errBody)
            }

            return if (stream && conn.contentType.orEmpty().contains("text/event-stream", ignoreCase = true)) {
                readStreamingDiagnostics(conn, cfg.modelId, code, startedAt)
            } else {
                val raw = conn.inputStream.bufferedReader().use { it.readText() }
                val latencyMs = elapsedMs(startedAt)
                val reply = extractContent(raw)
                val usage = JSONObject(raw).optJSONObject("usage")
                val reportedCompletion = usage?.optInt("completion_tokens", -1)?.takeIf { it >= 0 }
                val completionTokens = reportedCompletion ?: estimateTokens(reply)
                ConnectionDiagnostics(
                    modelId = cfg.modelId,
                    reply = reply,
                    rawResponse = raw,
                    statusCode = code,
                    latencyMs = latencyMs,
                    timeToFirstTokenMs = null,
                    promptTokens = usage?.optInt("prompt_tokens", -1)?.takeIf { it >= 0 },
                    completionTokens = completionTokens,
                    totalTokens = usage?.optInt("total_tokens", -1)?.takeIf { it >= 0 },
                    tokensPerSecond = tokensPerSecond(completionTokens, latencyMs),
                    streamed = false,
                    completionTokensEstimated = reportedCompletion == null,
                    throughputBasis = "end-to-end round trip",
                    maxTokens = PROBE_MAX_TOKENS,
                )
            }
        } catch (e: LlmProviderException) {
            throw e
        } catch (e: StreamingUnsupportedException) {
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

    private fun readStreamingDiagnostics(
        conn: HttpURLConnection,
        modelId: String,
        statusCode: Int,
        startedAt: Long,
    ): ConnectionDiagnostics {
        val raw = StringBuilder()
        val reply = StringBuilder()
        var firstTokenMs: Long? = null
        var promptTokens: Int? = null
        var completionTokens: Int? = null
        var totalTokens: Int? = null

        conn.inputStream.bufferedReader().useLines { lines ->
            lines.forEach { line ->
                raw.appendLine(line)
                val payload = line.removePrefix("data:").trim()
                if (!line.startsWith("data:") || payload.isEmpty() || payload == "[DONE]") return@forEach
                val chunk = JSONObject(payload)
                val content = chunk.optJSONArray("choices")
                    ?.optJSONObject(0)
                    ?.optJSONObject("delta")
                    ?.optString("content")
                    .orEmpty()
                if (content.isNotEmpty()) {
                    if (firstTokenMs == null) firstTokenMs = elapsedMs(startedAt)
                    reply.append(content)
                }
                chunk.optJSONObject("usage")?.let { usage ->
                    promptTokens = usage.optInt("prompt_tokens", -1).takeIf { it >= 0 }
                    completionTokens = usage.optInt("completion_tokens", -1).takeIf { it >= 0 }
                    totalTokens = usage.optInt("total_tokens", -1).takeIf { it >= 0 }
                }
            }
        }

        if (reply.isEmpty()) {
            throw LlmProviderException(LlmErrorClass.BAD_JSON, "Streaming response contained no model output")
        }
        val latencyMs = elapsedMs(startedAt)
        val measuredCompletionTokens = completionTokens ?: estimateTokens(reply.toString())
        val generationMs = (latencyMs - (firstTokenMs ?: 0L)).coerceAtLeast(1L)
        return ConnectionDiagnostics(
            modelId = modelId,
            reply = reply.toString().trim(),
            rawResponse = raw.toString().trim(),
            statusCode = statusCode,
            latencyMs = latencyMs,
            timeToFirstTokenMs = firstTokenMs,
            promptTokens = promptTokens,
            completionTokens = measuredCompletionTokens,
            totalTokens = totalTokens,
            tokensPerSecond = tokensPerSecond(measuredCompletionTokens, generationMs),
            streamed = true,
            completionTokensEstimated = completionTokens == null,
            throughputBasis = "decode only (latency minus TTFT)",
            maxTokens = PROBE_MAX_TOKENS,
        )
    }

    /**
     * Whether a rejection looks like "this endpoint does not do SSE" rather
     * than a real config/auth problem, so we only spend a second request when
     * a non-streaming retry can actually succeed.
     */
    private fun rejectsStreaming(code: Int, body: String): Boolean =
        code in STREAM_REJECTION_CODES || body.contains("stream", ignoreCase = true)

    private fun elapsedMs(startedAt: Long): Long =
        ((System.nanoTime() - startedAt) / 1_000_000L).coerceAtLeast(1L)

    private fun estimateTokens(text: String): Int = (text.length / 4).coerceAtLeast(1)

    private fun tokensPerSecond(tokens: Int, durationMs: Long): Double =
        tokens.toDouble() * 1_000.0 / durationMs.coerceAtLeast(1L)

    private class StreamingUnsupportedException : Exception()

    private companion object {
        /** Enough tokens for TTFT and throughput to mean something, still a cheap probe. */
        const val PROBE_MAX_TOKENS = 64
        val STREAM_REJECTION_CODES = setOf(400, 404, 405, 415, 422, 501)
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