package com.opuside.app.core.network.anthropic

import android.util.Log
import com.opuside.app.core.ai.ClaudeModelConfig
import com.opuside.app.core.data.AppSettings
import com.opuside.app.core.network.anthropic.model.*
import com.opuside.app.core.security.SecureSettingsDataStore
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.cancellable
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Claude API Client v5.0 (FIXED STREAMING LATENCY)
 *
 * ✅ FIXES:
 * 1. **INSTANT STREAMING**: readUTF8Line() с таймаутом
 *    - Читаем строки SSE событий мгновенно как только они доступны
 *    - Мгновенная отправка каждого delta пользователю
 * 2. Multi-turn cache support (уже был)
 * 3. Proper JSON serialization (уже был)
 *
 * ВАЖНО: SSE события парсятся построчно с таймаутом на чтение.
 */
@Singleton
class ClaudeApiClient @Inject constructor(
    @Named("anthropic") private val httpClient: HttpClient,
    private val json: Json,
    @Named("anthropicApiUrl") private val apiUrl: String,
    private val secureSettings: SecureSettingsDataStore,
    private val appSettings: AppSettings
) {
    companion object {
        private const val TAG = "ClaudeApiClient"
        private const val API_VERSION = "2023-06-01"
        private const val ANTHROPIC_BETA = "prompt-caching-2024-07-31"
        private const val READ_TIMEOUT_MS = 30_000L
        private const val MAX_STREAMING_TIME_MS = 5 * 60 * 1000L
    }

    private suspend fun getApiKey(): String {
        val key = secureSettings.getAnthropicApiKey().first()
        if (key.isBlank()) {
            throw IllegalStateException("ANTHROPIC_API_KEY not configured. Please set it in Settings.")
        }
        return key
    }

    suspend fun testConnection(): Result<String> {
        return try {
            val apiKey = try { getApiKey() } catch (e: IllegalStateException) {
                return Result.failure(ClaudeApiException(type = "configuration_error", message = e.message ?: "API key not configured"))
            }

            val savedModelId = appSettings.claudeModel.first()
            val modelConfig = ClaudeModelConfig.ClaudeModel.fromModelId(savedModelId)

            val request = ClaudeRequest(
                model = savedModelId,
                maxTokens = 10,
                messages = listOf(ClaudeMessage("user", "Hi")),
                stream = false
            )

            val response = httpClient.post(apiUrl) {
                contentType(ContentType.Application.Json)
                header("x-api-key", apiKey)
                header("anthropic-version", API_VERSION)
                setBody(request)
            }

            when (response.status) {
                HttpStatusCode.OK -> {
                    val claudeResponse = response.body<ClaudeResponse>()
                    val displayInfo = if (modelConfig != null) {
                        "${modelConfig.emoji} ${modelConfig.displayName}\n" +
                        "Price: \$${modelConfig.inputPricePerM}/\$${modelConfig.outputPricePerM} per 1M tokens\n" +
                        "Cache Read: \$${modelConfig.cacheReadPricePerM}/1M tokens"
                    } else {
                        claudeResponse.model
                    }
                    Result.success(
                        "✅ Connected!\nModel: $displayInfo\nAPI ID: ${claudeResponse.model}\nTokens: ${claudeResponse.usage.totalTokens}"
                    )
                }
                HttpStatusCode.Unauthorized -> Result.failure(ClaudeApiException("authentication_error", "Invalid API key"))
                HttpStatusCode.TooManyRequests -> {
                    val retry = response.headers["Retry-After"]?.toIntOrNull()
                    Result.failure(ClaudeApiException("rate_limit_error", "Rate limit. Retry in ${retry ?: 60}s.", retryAfterSeconds = retry))
                }
                else -> Result.failure(parseError(response))
            }
        } catch (e: IllegalStateException) {
            Result.failure(ClaudeApiException("configuration_error", e.message ?: "API key not configured"))
        } catch (e: Exception) {
            Result.failure(ClaudeApiException("network_error", "Connection failed: ${e.message}", cause = e))
        }
    }

    suspend fun validateApiKey(): Boolean {
        return try {
            val key = getApiKey()
            key.isNotBlank() && key.startsWith("sk-ant-")
        } catch (e: Exception) { false }
    }

    /**
     * ✅ FIXED: Supports multi-turn conversations with prompt caching.
     * ✅ FIXED: INSTANT streaming — no more 2-5 second delays!
     *
     * When enableCaching=true:
     * - System prompt gets cache_control: {"type": "ephemeral"}
     * - LAST user message gets cache_control: {"type": "ephemeral"}
     * - All prior messages are sent as-is (they form the cached prefix)
     * - Cache TTL = 5 minutes, refreshes on each hit
     */
    fun streamMessage(
        model: String,
        messages: List<ClaudeMessage>,
        systemPrompt: String? = null,
        maxTokens: Int = 4096,
        temperature: Double? = null,
        enableCaching: Boolean = false
    ): Flow<StreamingResult> = flow {
        var channel: ByteReadChannel? = null

        try {
            val apiKey = getApiKey()
            Log.d(TAG, "Stream: model=$model, msgs=${messages.size}, cache=$enableCaching")

            val response = httpClient.post(apiUrl) {
                contentType(ContentType.Application.Json)
                header("x-api-key", apiKey)
                header("anthropic-version", API_VERSION)
                if (enableCaching) {
                    header("anthropic-beta", ANTHROPIC_BETA)
                }

                if (enableCaching) {
                    // Manual JSON construction for cache_control support
                    val jsonBody = buildCachedRequestJson(
                        model = model,
                        messages = messages,
                        systemPrompt = systemPrompt,
                        maxTokens = maxTokens,
                        temperature = temperature
                    )
                    Log.d(TAG, "Cache enabled: system=${systemPrompt != null}, msgs=${messages.size}")
                    setBody(jsonBody)
                } else {
                    val request = ClaudeRequest(
                        model = model,
                        maxTokens = maxTokens,
                        messages = messages,
                        system = systemPrompt,
                        stream = true,
                        temperature = temperature
                    )
                    setBody(request)
                }
            }

            if (response.status != HttpStatusCode.OK) {
                emit(StreamingResult.Error(parseError(response)))
                return@flow
            }

            channel = response.bodyAsChannel()
            
            // ═══════════════════════════════════════════════════════════════════
            // ✅ FIX: INSTANT STREAMING WITH LINE-BY-LINE READING
            // ═══════════════════════════════════════════════════════════════════
            
            val currentText = StringBuilder()
            var totalUsage: Usage? = null
            val startTime = System.currentTimeMillis()

            while (!channel.isClosedForRead) {
                if (System.currentTimeMillis() - startTime > MAX_STREAMING_TIME_MS) {
                    emit(StreamingResult.Error(ClaudeApiException("timeout", "Streaming exceeded 5 minutes")))
                    return@flow
                }

                // ✅ ИСПРАВЛЕНО: Используем readUTF8Line с таймаутом
                val line = withTimeoutOrNull<String?>(READ_TIMEOUT_MS) {
                    channel.readUTF8Line()
                }

                if (line == null) {
                    // Timeout или конец потока
                    break
                }

                val trimmedLine = line.trim()
                
                // Парсим SSE событие
                if (trimmedLine.startsWith("data: ")) {
                    val data = trimmedLine.removePrefix("data: ").trim()
                    if (data.isEmpty() || data == "[DONE]") {
                        continue
                    }

                    try {
                        val event = json.decodeFromString<StreamEvent>(data)
                        when (event.type) {
                            "message_start" -> {
                                event.message?.usage?.let { totalUsage = it }
                                emit(StreamingResult.Started(event.message?.id ?: ""))
                            }
                            "content_block_delta" -> {
                                event.delta?.text?.let { text ->
                                    currentText.append(text)
                                    // ✅ МГНОВЕННАЯ ОТПРАВКА каждого delta
                                    emit(StreamingResult.Delta(text, currentText.toString()))
                                }
                            }
                            "message_delta" -> {
                                event.usage?.let { deltaUsage ->
                                    totalUsage = mergeUsage(totalUsage, deltaUsage)
                                }
                                event.delta?.stopReason?.let { emit(StreamingResult.StopReason(it)) }
                            }
                            "message_stop" -> {
                                emit(StreamingResult.Completed(currentText.toString(), totalUsage))
                            }
                            "error" -> {
                                event.error?.let { error ->
                                    emit(StreamingResult.Error(ClaudeApiException(error.type, error.message)))
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse SSE: $data", e)
                    }
                }
            }

        } catch (e: IllegalStateException) {
            emit(StreamingResult.Error(ClaudeApiException("configuration_error", e.message ?: "API key not configured")))
        } catch (e: Exception) {
            Log.e(TAG, "Stream failed", e)
            emit(StreamingResult.Error(ClaudeApiException("network_error", e.message ?: "Unknown error", cause = e)))
        } finally {
            channel?.cancel()
        }
    }.cancellable()

    /**
     * Build JSON request with cache_control for multi-turn conversations.
     */
    private fun buildCachedRequestJson(
        model: String,
        messages: List<ClaudeMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?
    ): String = buildString {
        append("{")
        append("\"model\":${Json.encodeToString(model)},")
        append("\"max_tokens\":$maxTokens,")
        append("\"stream\":true")
        if (temperature != null) append(",\"temperature\":$temperature")

        // System prompt with cache_control
        if (systemPrompt != null) {
            append(",\"system\":[{")
            append("\"type\":\"text\",")
            append("\"text\":${Json.encodeToString(systemPrompt)},")
            append("\"cache_control\":{\"type\":\"ephemeral\"}")
            append("}]")
        }

        // Messages with cache_control on LAST user message
        append(",\"messages\":[")
        messages.forEachIndexed { index, msg ->
            if (index > 0) append(",")
            append("{")
            append("\"role\":${Json.encodeToString(msg.role)},")
            append("\"content\":[{")
            append("\"type\":\"text\",")
            append("\"text\":${Json.encodeToString(msg.content)}")

            // Cache the LAST user message (contains the latest context)
            if (index == messages.lastIndex && msg.role == "user") {
                append(",\"cache_control\":{\"type\":\"ephemeral\"}")
            }
            append("}]")
            append("}")
        }
        append("]")
        append("}")
    }

    /**
     * Merge usage from message_start and message_delta events.
     */
    private fun mergeUsage(existing: Usage?, delta: Usage?): Usage {
        if (existing == null && delta == null) return Usage(0, 0)
        if (existing == null) return delta!!
        if (delta == null) return existing
        return Usage(
            inputTokens = maxOf(existing.inputTokens, delta.inputTokens),
            outputTokens = maxOf(existing.outputTokens, delta.outputTokens),
            cacheCreationInputTokens = existing.cacheCreationInputTokens ?: delta.cacheCreationInputTokens,
            cacheReadInputTokens = existing.cacheReadInputTokens ?: delta.cacheReadInputTokens
        )
    }

    suspend fun sendMessage(
        model: String,
        messages: List<ClaudeMessage>,
        systemPrompt: String? = null,
        maxTokens: Int = 4096,
        temperature: Double? = null
    ): Result<ClaudeResponse> {
        val request = ClaudeRequest(
            model = model, maxTokens = maxTokens, messages = messages,
            system = systemPrompt, stream = false, temperature = temperature
        )
        return try {
            val apiKey = getApiKey()
            val response = httpClient.post(apiUrl) {
                contentType(ContentType.Application.Json)
                header("x-api-key", apiKey)
                header("anthropic-version", API_VERSION)
                setBody(request)
            }
            if (response.status == HttpStatusCode.OK) Result.success(response.body<ClaudeResponse>())
            else Result.failure(parseError(response))
        } catch (e: IllegalStateException) {
            Result.failure(ClaudeApiException("configuration_error", e.message ?: "API key not configured"))
        } catch (e: Exception) {
            Result.failure(ClaudeApiException("network_error", e.message ?: "Unknown error", cause = e))
        }
    }

    private suspend fun parseError(response: HttpResponse): ClaudeApiException {
        return try {
            val errorBody = response.body<String>()
            val errorResponse = json.decodeFromString<ClaudeErrorResponse>(errorBody)
            val retryAfter = response.headers["Retry-After"]?.toIntOrNull()
            ClaudeApiException(errorResponse.error.type, errorResponse.error.message, retryAfterSeconds = retryAfter)
        } catch (e: Exception) {
            ClaudeApiException("http_error", "HTTP ${response.status.value}: ${response.status.description}", cause = e)
        }
    }
}

sealed class StreamingResult {
    data class Started(val messageId: String) : StreamingResult()
    data class Delta(val text: String, val accumulated: String) : StreamingResult()
    data class StopReason(val reason: String) : StreamingResult()
    data class Completed(val fullText: String, val usage: Usage?) : StreamingResult()
    data class Error(val exception: ClaudeApiException) : StreamingResult()
}

class ClaudeApiException(
    val type: String,
    message: String,
    cause: Throwable? = null,
    val retryAfterSeconds: Int? = null
) : Exception(message, cause) {
    constructor(type: String, message: String) : this(type, message, null, null)
    val isRateLimitError: Boolean get() = type == "rate_limit_error"
    val isAuthError: Boolean get() = type == "authentication_error"
    val isInvalidRequest: Boolean get() = type == "invalid_request_error"
    val isOverloaded: Boolean get() = type == "overloaded_error"
    val isConfigurationError: Boolean get() = type == "configuration_error"
}