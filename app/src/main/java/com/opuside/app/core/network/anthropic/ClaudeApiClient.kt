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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.cancellable
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.encodeToString
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Claude API Client v7.0 (ZERO-LATENCY STREAMING)
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * СТРИМИНГ БЕЗ ЗАДЕРЖКИ — ПОЛНЫЙ ПУТЬ ОПТИМИЗАЦИИ:
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * 1. flowOn(Dispatchers.IO) — HTTP запрос и чтение НЕ на Main thread
 * 2. readUTF8Line() БЕЗ withTimeoutOrNull — убирает overhead таймера
 *    (таймаут гарантируется через OkHttp read timeout + MAX_STREAMING_TIME)
 * 3. Emit каждого delta мгновенно — никакой буферизации
 * 4. isJsonContent флаг вместо startsWith("[") хака — нет JSON injection
 * 5. Validated temperature — нет NaN/Infinity в JSON
 *
 * ВАЖНО: OkHttp readTimeout в NetworkModule.kt ДОЛЖЕН быть ≤60s
 * чтобы readUTF8Line() не зависал бесконечно при разрыве соединения.
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
     * ═══════════════════════════════════════════════════════════════════════
     * ZERO-LATENCY STREAMING
     * ═══════════════════════════════════════════════════════════════════════
     *
     * Весь flow работает на Dispatchers.IO:
     * - HTTP POST отправляется мгновенно
     * - readUTF8Line() читает SSE строки как только они приходят с сервера
     * - Каждый text_delta emit'ится СРАЗУ → UI обновляется мгновенно
     * - Никакого withTimeoutOrNull overhead — таймаут через OkHttp socket timeout
     *
     * TOOL USE:
     * - content_block_start type=tool_use → накапливаем input JSON
     * - input_json_delta → аппендим partial JSON
     * - content_block_stop → собираем ToolCall
     * - message_stop с pendingToolCalls → emit ToolUse
     */
    fun streamMessage(
        model: String,
        messages: List<ClaudeMessage>,
        systemPrompt: String? = null,
        maxTokens: Int = 4096,
        temperature: Double? = null,
        enableCaching: Boolean = false,
        tools: List<JsonObject>? = null
    ): Flow<StreamingResult> = flow {
        var channel: ByteReadChannel? = null

        try {
            val apiKey = getApiKey()
            Log.d(TAG, "Stream: model=$model, msgs=${messages.size}, cache=$enableCaching, tools=${tools?.size ?: 0}")

            // ═══════════════════════════════════════════════════════════
            // HTTP POST — отправляем запрос, получаем response stream
            // ═══════════════════════════════════════════════════════════
            val response = httpClient.post(apiUrl) {
                contentType(ContentType.Application.Json)
                header("x-api-key", apiKey)
                header("anthropic-version", API_VERSION)
                if (enableCaching) {
                    header("anthropic-beta", ANTHROPIC_BETA)
                }

                val jsonBody = buildRequestJson(
                    model = model,
                    messages = messages,
                    systemPrompt = systemPrompt,
                    maxTokens = maxTokens,
                    temperature = temperature,
                    enableCaching = enableCaching,
                    tools = tools
                )
                setBody(jsonBody)
            }

            if (response.status != HttpStatusCode.OK) {
                emit(StreamingResult.Error(parseError(response)))
                return@flow
            }

            // ═══════════════════════════════════════════════════════════
            // SSE STREAM — читаем МГНОВЕННО, без буферов и таймеров
            // ═══════════════════════════════════════════════════════════
            channel = response.bodyAsChannel()

            val currentText = StringBuilder()
            var totalUsage: Usage? = null
            val startTime = System.currentTimeMillis()

            // Tool Use state
            var currentToolId: String? = null
            var currentToolName: String? = null
            val currentToolInput = StringBuilder()
            val pendingToolCalls = mutableListOf<ToolCall>()

            while (!channel.isClosedForRead) {
                // Проверка максимального времени стриминга
                if (System.currentTimeMillis() - startTime > MAX_STREAMING_TIME_MS) {
                    emit(StreamingResult.Error(ClaudeApiException("timeout", "Streaming exceeded 5 minutes")))
                    return@flow
                }

                // ═══════════════════════════════════════════════════════
                // КЛЮЧЕВОЕ ИСПРАВЛЕНИЕ: readUTF8Line() БЕЗ таймера
                // ═══════════════════════════════════════════════════════
                // withTimeoutOrNull создавал overhead ~1-5ms на КАЖДУЮ строку
                // что при 100+ SSE событий давало 100-500ms накопленной задержки.
                //
                // OkHttp readTimeout (60s в NetworkModule) гарантирует что
                // readUTF8Line() НЕ зависнет навсегда при разрыве соединения.
                // Плюс MAX_STREAMING_TIME_MS проверка выше как второй safety net.
                val line = channel.readUTF8Line() ?: break

                // Пустые строки и не-data строки — пропускаем мгновенно
                if (!line.startsWith("data: ")) continue

                val data = line.substring(6).trim()  // Быстрее чем removePrefix
                if (data.isEmpty() || data == "[DONE]") continue

                try {
                    val event = json.decodeFromString<StreamEvent>(data)
                    when (event.type) {
                        "message_start" -> {
                            event.message?.usage?.let { totalUsage = it }
                            emit(StreamingResult.Started(event.message?.id ?: ""))
                        }

                        "content_block_start" -> {
                            val block = event.contentBlock
                            if (block?.type == "tool_use") {
                                currentToolId = block.id
                                currentToolName = block.name
                                currentToolInput.clear()
                            }
                        }

                        "content_block_delta" -> {
                            val delta = event.delta
                            when (delta?.type) {
                                "text_delta" -> {
                                    delta.text?.let { text ->
                                        currentText.append(text)
                                        // МГНОВЕННЫЙ emit — UI получает каждый кусочек сразу
                                        emit(StreamingResult.Delta(text, currentText.toString()))
                                    }
                                }
                                "input_json_delta" -> {
                                    delta.partialJson?.let { partial ->
                                        currentToolInput.append(partial)
                                    }
                                }
                            }
                        }

                        "content_block_stop" -> {
                            if (currentToolId != null && currentToolName != null) {
                                val inputJson = try {
                                    json.decodeFromString<JsonObject>(currentToolInput.toString())
                                } catch (e: Exception) {
                                    Log.w(TAG, "Failed to parse tool input: $currentToolInput", e)
                                    buildJsonObject {}
                                }
                                pendingToolCalls.add(ToolCall(
                                    id = currentToolId!!,
                                    name = currentToolName!!,
                                    input = inputJson
                                ))
                                currentToolId = null
                                currentToolName = null
                                currentToolInput.clear()
                            }
                        }

                        "message_delta" -> {
                            event.usage?.let { deltaUsage ->
                                totalUsage = mergeUsage(totalUsage, deltaUsage)
                            }
                            event.delta?.stopReason?.let {
                                emit(StreamingResult.StopReason(it))
                            }
                        }

                        "message_stop" -> {
                            if (pendingToolCalls.isNotEmpty()) {
                                emit(StreamingResult.ToolUse(
                                    textSoFar = currentText.toString(),
                                    toolCalls = pendingToolCalls.toList(),
                                    usage = totalUsage
                                ))
                                pendingToolCalls.clear()
                            } else {
                                emit(StreamingResult.Completed(currentText.toString(), totalUsage))
                            }
                        }

                        "error" -> {
                            event.error?.let { error ->
                                emit(StreamingResult.Error(ClaudeApiException(error.type, error.message)))
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse SSE event", e)
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
    }
    // ═══════════════════════════════════════════════════════════════════════
    // КРИТИЧНО: flowOn(Dispatchers.IO) — весь HTTP + SSE parsing на IO thread
    // Без этого readUTF8Line() блокирует Main thread → UI фризится
    // ═══════════════════════════════════════════════════════════════════════
    .flowOn(Dispatchers.IO)
    .cancellable()

    /**
     * Unified JSON request builder.
     * Поддерживает: tools, cache_control, tool_result messages (isJsonContent).
     * Typed isJsonContent вместо startsWith("[") хака — нет JSON injection.
     */
    private fun buildRequestJson(
        model: String,
        messages: List<ClaudeMessage>,
        systemPrompt: String?,
        maxTokens: Int,
        temperature: Double?,
        enableCaching: Boolean,
        tools: List<JsonObject>?
    ): String = buildString {
        append("{")
        append("\"model\":${Json.encodeToString(model)},")
        append("\"max_tokens\":$maxTokens,")
        append("\"stream\":true")

        // Validated temperature
        if (temperature != null) {
            require(temperature.isFinite()) { "temperature must be finite: $temperature" }
            append(",\"temperature\":${Json.encodeToString(temperature)}")
        }

        // System prompt
        if (systemPrompt != null) {
            if (enableCaching) {
                append(",\"system\":[{")
                append("\"type\":\"text\",")
                append("\"text\":${Json.encodeToString(systemPrompt)},")
                append("\"cache_control\":{\"type\":\"ephemeral\"}")
                append("}]")
            } else {
                append(",\"system\":${Json.encodeToString(systemPrompt)}")
            }
        }

        // Tools
        if (!tools.isNullOrEmpty()) {
            append(",\"tools\":")
            append(Json.encodeToString(tools))
        }

        // Messages
        append(",\"messages\":[")
        messages.forEachIndexed { index, msg ->
            if (index > 0) append(",")

            // ═══════════════════════════════════════════════════════════
            // ТИПОБЕЗОПАСНАЯ проверка вместо startsWith("[") хака
            // isJsonContent=true → content уже JSON array (tool_use/tool_result)
            // isJsonContent=false → обычный текст, оборачиваем в content block
            // ═══════════════════════════════════════════════════════════
            if (msg.isJsonContent) {
                append("{")
                append("\"role\":${Json.encodeToString(msg.role)},")
                append("\"content\":${msg.content}")
                append("}")
            } else {
                append("{")
                append("\"role\":${Json.encodeToString(msg.role)},")
                append("\"content\":[{")
                append("\"type\":\"text\",")
                append("\"text\":${Json.encodeToString(msg.content)}")

                if (enableCaching && index == messages.lastIndex && msg.role == "user") {
                    append(",\"cache_control\":{\"type\":\"ephemeral\"}")
                }
                append("}]")
                append("}")
            }
        }
        append("]")
        append("}")
    }

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

// ═══════════════════════════════════════════════════════════════════════════════
// STREAMING RESULT v7.0
// ═══════════════════════════════════════════════════════════════════════════════

data class ToolCall(
    val id: String,
    val name: String,
    val input: JsonObject
)

sealed class StreamingResult {
    data class Started(val messageId: String) : StreamingResult()
    data class Delta(val text: String, val accumulated: String) : StreamingResult()
    data class StopReason(val reason: String) : StreamingResult()
    data class Completed(val fullText: String, val usage: Usage?) : StreamingResult()
    data class Error(val exception: ClaudeApiException) : StreamingResult()

    data class ToolUse(
        val textSoFar: String,
        val toolCalls: List<ToolCall>,
        val usage: Usage?
    ) : StreamingResult()
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
