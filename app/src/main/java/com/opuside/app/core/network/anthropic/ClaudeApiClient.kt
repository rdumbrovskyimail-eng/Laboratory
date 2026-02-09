package com.opuside.app.core.network.anthropic

import android.util.Log
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
import io.ktor.utils.io.readUTF8Line
import io.ktor.utils.io.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.cancellable
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Claude API Client v2.3 (FIX)
 *
 * ✅ ИСПРАВЛЕНО:
 * - Убран BuildConfig (ANTHROPIC_API_KEY, CLAUDE_MODEL)
 * - Добавлен AppSettings для получения модели
 * - Улучшено логирование
 * - Детальные сообщения об ошибках
 * - SEC-1 FIX: Логирование ключа сокращено до 8 символов
 * - FIX: Null-поля больше не попадают в JSON (см. NetworkModule + ClaudeRequest)
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

    /**
     * ✅ ИСПРАВЛЕНО: Только SecureSettingsDataStore + безопасное логирование (SEC-1)
     */
    private suspend fun getApiKey(): String {
        Log.d(TAG, "🔑 Retrieving API key from SecureSettings...")

        val key = secureSettings.getAnthropicApiKey().first()

        if (key.isBlank()) {
            Log.e(TAG, "❌ ANTHROPIC_API_KEY is empty!")
            throw IllegalStateException(
                "ANTHROPIC_API_KEY not configured. Please set it in Settings → Claude API."
            )
        }

        // ✅ SEC-1 FIX: Только 8 символов вместо 10
        Log.d(TAG, "✅ API key retrieved (length: ${key.length})")
        return key
    }

    /**
     * ✅ ИСПРАВЛЕНО v2.3: testConnection
     * - ClaudeRequest без null-полей (temperature, system, etc. не попадают в JSON)
     * - Тест всегда на Haiku для экономии
     */
    suspend fun testConnection(): Result<String> {
        return try {
            Log.d(TAG, "━".repeat(80))
            Log.d(TAG, "🧪 TESTING CLAUDE API CONNECTION")
            Log.d(TAG, "━".repeat(80))

            val apiKey = try {
                getApiKey()
            } catch (e: IllegalStateException) {
                Log.e(TAG, "❌ API key not configured: ${e.message}")
                return Result.failure(ClaudeApiException(
                    type = "configuration_error",
                    message = e.message ?: "API key not configured"
                ))
            }

            Log.d(TAG, "  ├─ API URL: $apiUrl")
            // ✅ SEC-1 FIX: Только 8 символов вместо 15
            Log.d(TAG, "  ├─ API Key: ${apiKey.take(8)}***")
            Log.d(TAG, "  └─ API Version: $API_VERSION")

            // ✅ Всегда тестируем на Haiku — самая дешёвая модель ($0.80/1M vs $5/1M)
            val model = "claude-haiku-4-5-20251001"

            Log.d(TAG, "  └─ Test Model: $model (Haiku — для экономии)")

            val testMessage = ClaudeMessage(
                role = "user",
                content = "Hi"
            )

            // ✅ FIX: Только обязательные поля. Nullable поля (system, temperature, etc.)
            // оставлены как null и НЕ попадут в JSON благодаря:
            //   encodeDefaults=false + explicitNulls=false в Json
            //   @EncodeDefault на обязательных полях в ClaudeRequest
            val request = ClaudeRequest(
                model = model,
                maxTokens = 10,
                messages = listOf(testMessage),
                stream = false
            )

            Log.d(TAG, "  ├─ Sending test request...")

            val response = httpClient.post(apiUrl) {
                contentType(ContentType.Application.Json)
                header("x-api-key", apiKey)
                header("anthropic-version", API_VERSION)
                setBody(request)
            }

            Log.d(TAG, "  └─ Response status: ${response.status}")

            when (response.status) {
                HttpStatusCode.OK -> {
                    val claudeResponse = response.body<ClaudeResponse>()
                    Log.d(TAG, "━".repeat(80))
                    Log.d(TAG, "✅ API CONNECTION SUCCESSFUL!")
                    Log.d(TAG, "   Model: ${claudeResponse.model}")
                    Log.d(TAG, "   Tokens: ${claudeResponse.usage.totalTokens}")
                    Log.d(TAG, "━".repeat(80))

                    Result.success(
                        "✅ Connected successfully!\n" +
                        "Model: ${claudeResponse.model}\n" +
                        "Tokens used: ${claudeResponse.usage.totalTokens}"
                    )
                }

                HttpStatusCode.Unauthorized -> {
                    Log.e(TAG, "❌ 401 UNAUTHORIZED - Invalid API key")
                    Result.failure(ClaudeApiException(
                        type = "authentication_error",
                        message = "Invalid API key. Please check your Anthropic API key in Settings."
                    ))
                }

                HttpStatusCode.TooManyRequests -> {
                    val retryAfter = response.headers["Retry-After"]?.toIntOrNull()
                    Log.e(TAG, "❌ 429 RATE LIMIT - Retry after ${retryAfter}s")
                    Result.failure(ClaudeApiException(
                        type = "rate_limit_error",
                        message = "Rate limit exceeded. Try again in ${retryAfter ?: 60}s.",
                        retryAfterSeconds = retryAfter
                    ))
                }

                else -> {
                    val error = parseError(response)
                    Log.e(TAG, "❌ API error: ${error.message}")
                    Result.failure(error)
                }
            }

        } catch (e: IllegalStateException) {
            Log.e(TAG, "❌ Configuration error: ${e.message}")
            Result.failure(ClaudeApiException(
                type = "configuration_error",
                message = e.message ?: "API key not configured"
            ))
        } catch (e: Exception) {
            Log.e(TAG, "❌ Connection test failed", e)
            Result.failure(ClaudeApiException(
                type = "network_error",
                message = "Connection failed: ${e.message ?: "Unknown error"}",
                cause = e
            ))
        }
    }

    suspend fun validateApiKey(): Boolean {
        return try {
            val key = getApiKey()
            val isValid = key.isNotBlank() && key.startsWith("sk-ant-")

            if (isValid) {
                // ✅ SEC-1 FIX: Не логируем ключ, только длину
                Log.d(TAG, "✅ API key validated (length: ${key.length})")
            } else {
                Log.w(TAG, "⚠️ API key format invalid")
            }

            isValid
        } catch (e: Exception) {
            Log.w(TAG, "⚠️ API key validation failed: ${e.message}")
            false
        }
    }

    fun streamMessage(
        model: String,
        messages: List<ClaudeMessage>,
        systemPrompt: String? = null,
        maxTokens: Int = 4096,
        temperature: Double? = null,
        enableCaching: Boolean = false
    ): Flow<StreamingResult> = flow {
        val systemContent = if (enableCaching && systemPrompt != null) {
            listOf(
                mapOf(
                    "type" to "text",
                    "text" to systemPrompt,
                    "cache_control" to mapOf("type" to "ephemeral")
                )
            )
        } else {
            null
        }

        // ✅ FIX: system=null и temperature=null НЕ попадут в JSON
        val request = ClaudeRequest(
            model = model,
            maxTokens = maxTokens,
            messages = messages,
            system = if (enableCaching) null else systemPrompt,
            stream = true,
            temperature = temperature
        )

        var channel: io.ktor.utils.io.ByteReadChannel? = null

        try {
            val apiKey = getApiKey()

            Log.d(TAG, "Starting stream: model=$model, messages=${messages.size}, caching=$enableCaching")

            val response = httpClient.post(apiUrl) {
                contentType(ContentType.Application.Json)
                header("x-api-key", apiKey)
                header("anthropic-version", API_VERSION)
                if (enableCaching) {
                    header("anthropic-beta", ANTHROPIC_BETA)
                }

                if (enableCaching && systemPrompt != null) {
                    // ✅ Ручная сборка JSON для caching (system как массив объектов)
                    val jsonBody = buildString {
                        append("{")
                        append("\"model\":\"$model\",")
                        append("\"max_tokens\":$maxTokens,")
                        append("\"stream\":true,")
                        if (temperature != null) {
                            append("\"temperature\":$temperature,")
                        }
                        append("\"system\":[{")
                        append("\"type\":\"text\",")
                        append("\"text\":${json.encodeToString(kotlinx.serialization.serializer(), systemPrompt)},")
                        append("\"cache_control\":{\"type\":\"ephemeral\"}")
                        append("}],")
                        append("\"messages\":${json.encodeToString(kotlinx.serialization.serializer(), messages)}")
                        append("}")
                    }
                    setBody(jsonBody)
                } else {
                    setBody(request)
                }
            }

            if (response.status != HttpStatusCode.OK) {
                emit(StreamingResult.Error(parseError(response)))
                return@flow
            }

            channel = response.bodyAsChannel()
            var currentText = StringBuilder()
            var totalUsage: Usage? = null
            val startTime = System.currentTimeMillis()

            while (!channel.isClosedForRead) {
                if (System.currentTimeMillis() - startTime > MAX_STREAMING_TIME_MS) {
                    emit(StreamingResult.Error(
                        ClaudeApiException(
                            type = "timeout",
                            message = "Streaming exceeded 5 minutes"
                        )
                    ))
                    return@flow
                }

                val line = withTimeoutOrNull(READ_TIMEOUT_MS) {
                    channel.readUTF8Line()
                }

                if (line == null) {
                    emit(StreamingResult.Error(
                        ClaudeApiException(
                            type = "timeout",
                            message = "Stream timeout after 30s"
                        )
                    ))
                    return@flow
                }

                if (line.startsWith("data: ")) {
                    val data = line.removePrefix("data: ").trim()
                    if (data.isEmpty() || data == "[DONE]") continue

                    try {
                        val event = json.decodeFromString<StreamEvent>(data)
                        when (event.type) {
                            "message_start" -> {
                                emit(StreamingResult.Started(event.message?.id ?: ""))
                            }

                            "content_block_delta" -> {
                                event.delta?.text?.let { text ->
                                    currentText.append(text)
                                    emit(StreamingResult.Delta(text, currentText.toString()))
                                }
                            }

                            "message_delta" -> {
                                event.usage?.let { totalUsage = it }
                                event.delta?.stopReason?.let { reason ->
                                    emit(StreamingResult.StopReason(reason))
                                }
                            }

                            "message_stop" -> {
                                totalUsage?.let { usage ->
                                    Log.i(TAG, "Stream completed with usage: $usage")
                                }
                                emit(StreamingResult.Completed(currentText.toString(), totalUsage))
                            }

                            "error" -> {
                                event.error?.let { error ->
                                    emit(StreamingResult.Error(
                                        ClaudeApiException(
                                            type = error.type,
                                            message = error.message
                                        )
                                    ))
                                }
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to parse SSE event: $data", e)
                    }
                }
            }

        } catch (e: IllegalStateException) {
            emit(StreamingResult.Error(
                ClaudeApiException(
                    type = "configuration_error",
                    message = e.message ?: "API key not configured"
                )
            ))
        } catch (e: Exception) {
            Log.e(TAG, "Stream failed", e)
            emit(StreamingResult.Error(
                ClaudeApiException(
                    type = "network_error",
                    message = e.message ?: "Unknown error",
                    cause = e
                )
            ))
        } finally {
            channel?.cancel()
        }
    }.cancellable()

    suspend fun sendMessage(
        model: String,
        messages: List<ClaudeMessage>,
        systemPrompt: String? = null,
        maxTokens: Int = 4096,
        temperature: Double? = null
    ): Result<ClaudeResponse> {
        // ✅ FIX: Null-поля не попадут в JSON
        val request = ClaudeRequest(
            model = model,
            maxTokens = maxTokens,
            messages = messages,
            system = systemPrompt,
            stream = false,
            temperature = temperature
        )

        return try {
            val apiKey = getApiKey()

            val response = httpClient.post(apiUrl) {
                contentType(ContentType.Application.Json)
                header("x-api-key", apiKey)
                header("anthropic-version", API_VERSION)
                setBody(request)
            }

            if (response.status == HttpStatusCode.OK) {
                Result.success(response.body<ClaudeResponse>())
            } else {
                Result.failure(parseError(response))
            }
        } catch (e: IllegalStateException) {
            Result.failure(
                ClaudeApiException(
                    type = "configuration_error",
                    message = e.message ?: "API key not configured"
                )
            )
        } catch (e: Exception) {
            Result.failure(
                ClaudeApiException(
                    type = "network_error",
                    message = e.message ?: "Unknown error",
                    cause = e
                )
            )
        }
    }

    private suspend fun parseError(response: HttpResponse): ClaudeApiException {
        return try {
            val errorBody = response.body<String>()
            val errorResponse = json.decodeFromString<ClaudeErrorResponse>(errorBody)

            val retryAfter = response.headers["Retry-After"]?.toIntOrNull()

            ClaudeApiException(
                type = errorResponse.error.type,
                message = errorResponse.error.message,
                cause = null,
                retryAfterSeconds = retryAfter
            )
        } catch (e: Exception) {
            ClaudeApiException(
                type = "http_error",
                message = "HTTP ${response.status.value}: ${response.status.description}",
                cause = e
            )
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

    override fun toString(): String = buildString {
        append("ClaudeApiException(type=$type, message=$message")
        retryAfterSeconds?.let { append(", retryAfter=${it}s") }
        append(")")
    }
}