package com.opuside.app.core.network.anthropic

import android.util.Log
import com.opuside.app.BuildConfig
import com.opuside.app.core.network.anthropic.model.ClaudeMessage
import com.opuside.app.core.network.anthropic.model.ClaudeRequest
import com.opuside.app.core.network.anthropic.model.ClaudeResponse
import com.opuside.app.core.network.anthropic.model.StreamEvent
import com.opuside.app.core.network.anthropic.model.Usage
import com.opuside.app.core.network.anthropic.model.ClaudeErrorResponse
import com.opuside.app.core.security.SecureSettingsDataStore
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.utils.io.readUTF8Line
import io.ktor.utils.io.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.cancellable
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Claude API Client v2.1 (UPDATED)
 * 
 * ✅ ОБНОВЛЕНО:
 * - Поддержка выбора модели
 * - Prompt Caching
 * - Детальная статистика (Usage)
 * - Улучшенная обработка ошибок
 * - Сохранена интеграция с SecureSettingsDataStore
 * - Сохранены методы testConnection() и validateApiKey()
 */
@Singleton
class ClaudeApiClient @Inject constructor(
    @Named("anthropic") private val httpClient: HttpClient,
    private val json: Json,
    @Named("anthropicApiUrl") private val apiUrl: String = BuildConfig.ANTHROPIC_API_URL.ifBlank { 
        "https://api.anthropic.com/v1/messages" 
    },
    private val secureSettings: SecureSettingsDataStore
) {
    companion object {
        private const val TAG = "ClaudeApiClient"
        private const val API_VERSION = "2023-06-01"
        private const val ANTHROPIC_BETA = "prompt-caching-2024-07-31"
        private const val READ_TIMEOUT_MS = 30_000L
        private const val MAX_STREAMING_TIME_MS = 5 * 60 * 1000L
    }

    /**
     * ✅ СОХРАНЕНО: Получает ключ из SecureSettingsDataStore (приоритет) или BuildConfig (fallback)
     */
    private suspend fun getApiKey(): String {
        // 1. Приоритет: SecureSettingsDataStore (как GitHub Token)
        val keyFromStorage = secureSettings.getAnthropicApiKey().first()
        if (keyFromStorage.isNotBlank()) {
            Log.d(TAG, "✅ Using API key from SecureSettings")
            return keyFromStorage
        }

        // 2. Fallback: BuildConfig (для backward compatibility)
        val keyFromBuildConfig = BuildConfig.ANTHROPIC_API_KEY.takeIf { it.isNotBlank() }
        if (keyFromBuildConfig != null) {
            Log.d(TAG, "⚠️ Using API key from BuildConfig (fallback)")
            return keyFromBuildConfig
        }

        // 3. Оба пусты → ошибка
        throw IllegalStateException(
            "ANTHROPIC_API_KEY not configured. Please set it in Settings."
        )
    }

    /**
     * ✅ СОХРАНЕНО: Тест подключения к Claude API (для кнопки "Test Biometric Access")
     */
    suspend fun testConnection(): Result<String> {
        return try {
            val apiKey = getApiKey()
            
            Log.d(TAG, "🧪 Testing Claude API connection...")
            
            val testMessage = ClaudeMessage(
                role = "user",
                content = "Hi"
            )
            
            val request = ClaudeRequest(
                model = BuildConfig.CLAUDE_MODEL.ifBlank { "claude-sonnet-4-5-20250929" },
                maxTokens = 10,
                messages = listOf(testMessage),
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
                    Log.d(TAG, "✅ API connection successful!")
                    Result.success("✅ Connected successfully!\nModel: ${claudeResponse.model}")
                }
                
                HttpStatusCode.Unauthorized -> {
                    Log.e(TAG, "❌ Invalid API key")
                    Result.failure(ClaudeApiException(
                        type = "authentication_error",
                        message = "Invalid API key. Please check your Anthropic API key in Settings."
                    ))
                }
                
                HttpStatusCode.TooManyRequests -> {
                    val retryAfter = response.headers["Retry-After"]?.toIntOrNull()
                    Log.e(TAG, "❌ Rate limit exceeded")
                    Result.failure(ClaudeApiException(
                        type = "rate_limit_error",
                        message = "Rate limit exceeded. Please try again in ${retryAfter ?: 60} seconds.",
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
            Log.e(TAG, "❌ ${e.message}")
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

    /**
     * ✅ СОХРАНЕНО: Валидация наличия и корректности API ключа (для автозапуска)
     */
    suspend fun validateApiKey(): Boolean {
        return try {
            val key = getApiKey()
            val isValid = key.isNotBlank() && key.startsWith("sk-ant-")
            
            if (isValid) {
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

    /**
     * ✅ ОБНОВЛЕНО: Stream messages с поддержкой выбора модели и Prompt Caching
     * 
     * @param model ID модели Claude (например "claude-opus-4-6-20260115")
     * @param messages Список сообщений
     * @param systemPrompt Системный промпт (опционально)
     * @param maxTokens Максимум токенов в ответе
     * @param temperature Температура генерации (опционально)
     * @param enableCaching Включить Prompt Caching (экономия 90%)
     */
    fun streamMessage(
        model: String,  // ✅ НОВОЕ: параметр модели
        messages: List<ClaudeMessage>,
        systemPrompt: String? = null,
        maxTokens: Int = 4096,
        temperature: Double? = null,
        enableCaching: Boolean = false  // ✅ НОВОЕ: кеширование
    ): Flow<StreamingResult> = flow {
        // ✅ ОБНОВЛЕНО: Поддержка кеширования в system prompt
        val systemContent = if (enableCaching && systemPrompt != null) {
            // System prompt с кешированием (для второго и последующих сообщений)
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

        val request = ClaudeRequest(
            model = model,  // ✅ НОВОЕ: используем переданную модель
            maxTokens = maxTokens,
            messages = messages,
            system = if (enableCaching) null else systemPrompt,  // ✅ ИЗМЕНЕНО
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
                    header("anthropic-beta", ANTHROPIC_BETA)  // ✅ НОВОЕ: beta header для кеширования
                }
                
                // ✅ НОВОЕ: Ручное создание JSON с cache_control
                if (enableCaching && systemPrompt != null) {
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
                                // ✅ НОВОЕ: Парсим usage с кеш-статистикой
                                event.usage?.let { totalUsage = it }
                                event.delta?.stopReason?.let { reason ->
                                    emit(StreamingResult.StopReason(reason))
                                }
                            }
                            
                            "message_stop" -> {
                                // ✅ УЛУЧШЕНО: Логируем usage
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

    /**
     * ✅ ОБНОВЛЕНО: Send message с поддержкой выбора модели
     */
    suspend fun sendMessage(
        model: String,  // ✅ НОВОЕ: параметр модели
        messages: List<ClaudeMessage>,
        systemPrompt: String? = null,
        maxTokens: Int = 4096,
        temperature: Double? = null
    ): Result<ClaudeResponse> {
        val request = ClaudeRequest(
            model = model,  // ✅ НОВОЕ: используем переданную модель
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

// ✅ СОХРАНЕНО: StreamingResult без изменений
sealed class StreamingResult {
    data class Started(val messageId: String) : StreamingResult()
    data class Delta(val text: String, val accumulated: String) : StreamingResult()
    data class StopReason(val reason: String) : StreamingResult()
    data class Completed(val fullText: String, val usage: Usage?) : StreamingResult()
    data class Error(val exception: ClaudeApiException) : StreamingResult()
}

// ✅ СОХРАНЕНО: ClaudeApiException без изменений
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