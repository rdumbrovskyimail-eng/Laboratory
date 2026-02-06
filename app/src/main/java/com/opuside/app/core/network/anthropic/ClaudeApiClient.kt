package com.opuside.app.core.network.anthropic

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
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * ✅ ПОЛНОСТЬЮ ИСПРАВЛЕНО - Professional Level 2026
 * 
 * Изменения:
 * 1. ✅ Читает API ключ из SecureSettingsDataStore (как GitHub Token)
 * 2. ✅ Добавлен метод testConnection() для кнопки "Test"
 * 3. ✅ Добавлен метод validateApiKey() для автоматической проверки
 * 4. ✅ Улучшенная обработка ошибок с детальными сообщениями
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
        private const val ANTHROPIC_BETA = "messages-2023-12-15"
        private const val READ_TIMEOUT_MS = 30_000L
        private const val MAX_STREAMING_TIME_MS = 5 * 60 * 1000L
    }

    /**
     * ✅ ИСПРАВЛЕНО: Получает ключ из SecureSettingsDataStore (приоритет) или BuildConfig (fallback)
     */
    private suspend fun getApiKey(): String {
        // 1. Приоритет: SecureSettingsDataStore (как GitHub Token)
        val keyFromStorage = secureSettings.getAnthropicApiKey().first()
        if (keyFromStorage.isNotBlank()) {
            android.util.Log.d(TAG, "✅ Using API key from SecureSettings")
            return keyFromStorage
        }

        // 2. Fallback: BuildConfig (для backward compatibility)
        val keyFromBuildConfig = BuildConfig.ANTHROPIC_API_KEY.takeIf { it.isNotBlank() }
        if (keyFromBuildConfig != null) {
            android.util.Log.d(TAG, "⚠️ Using API key from BuildConfig (fallback)")
            return keyFromBuildConfig
        }

        // 3. Оба пусты → ошибка
        throw IllegalStateException(
            "ANTHROPIC_API_KEY not configured. Please set it in Settings."
        )
    }

    /**
     * ✅ НОВЫЙ МЕТОД: Тест подключения к Claude API (для кнопки "Test Biometric Access")
     * 
     * Отправляет минимальный запрос в API чтобы проверить:
     * - Валидность API ключа
     * - Доступность API
     * - Корректность модели
     * 
     * @return Result с сообщением об успехе или ошибке
     */
    suspend fun testConnection(): Result<String> {
        return try {
            val apiKey = getApiKey()
            
            android.util.Log.d(TAG, "🧪 Testing Claude API connection...")
            
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
                    android.util.Log.d(TAG, "✅ API connection successful!")
                    Result.success("✅ Connected successfully!\nModel: ${claudeResponse.model}")
                }
                
                HttpStatusCode.Unauthorized -> {
                    android.util.Log.e(TAG, "❌ Invalid API key")
                    Result.failure(ClaudeApiException(
                        type = "authentication_error",
                        message = "Invalid API key. Please check your Anthropic API key in Settings."
                    ))
                }
                
                HttpStatusCode.TooManyRequests -> {
                    val retryAfter = response.headers["Retry-After"]?.toIntOrNull()
                    android.util.Log.e(TAG, "❌ Rate limit exceeded")
                    Result.failure(ClaudeApiException(
                        type = "rate_limit_error",
                        message = "Rate limit exceeded. Please try again in ${retryAfter ?: 60} seconds.",
                        retryAfterSeconds = retryAfter
                    ))
                }
                
                else -> {
                    val error = parseError(response)
                    android.util.Log.e(TAG, "❌ API error: ${error.message}")
                    Result.failure(error)
                }
            }
            
        } catch (e: IllegalStateException) {
            // API ключ не настроен
            android.util.Log.e(TAG, "❌ ${e.message}")
            Result.failure(ClaudeApiException(
                type = "configuration_error",
                message = e.message ?: "API key not configured"
            ))
        } catch (e: Exception) {
            android.util.Log.e(TAG, "❌ Connection test failed", e)
            Result.failure(ClaudeApiException(
                type = "network_error",
                message = "Connection failed: ${e.message ?: "Unknown error"}",
                cause = e
            ))
        }
    }

    /**
     * ✅ НОВЫЙ МЕТОД: Валидация наличия и корректности API ключа (для автозапуска)
     * 
     * Используется при старте приложения для проверки готовности к работе.
     * НЕ отправляет запрос в API (в отличие от testConnection).
     * 
     * @return true если ключ настроен, false если нет
     */
    suspend fun validateApiKey(): Boolean {
        return try {
            val key = getApiKey()
            val isValid = key.isNotBlank() && key.startsWith("sk-ant-")
            
            if (isValid) {
                android.util.Log.d(TAG, "✅ API key validated (length: ${key.length})")
            } else {
                android.util.Log.w(TAG, "⚠️ API key format invalid")
            }
            
            isValid
        } catch (e: Exception) {
            android.util.Log.w(TAG, "⚠️ API key validation failed: ${e.message}")
            false
        }
    }

    /**
     * Stream messages from Claude API using Server-Sent Events.
     */
    fun streamMessage(
        messages: List<ClaudeMessage>,
        systemPrompt: String? = null,
        maxTokens: Int = 4096,
        temperature: Double? = null
    ): Flow<StreamingResult> = flow {
        val request = ClaudeRequest(
            model = BuildConfig.CLAUDE_MODEL.ifBlank { "claude-sonnet-4-5-20250929" },
            maxTokens = maxTokens,
            messages = messages,
            system = systemPrompt,
            stream = true,
            temperature = temperature
        )

        var channel: io.ktor.utils.io.ByteReadChannel? = null
        
        try {
            val apiKey = getApiKey()
            
            val response = httpClient.post(apiUrl) {
                contentType(ContentType.Application.Json)
                header("x-api-key", apiKey)
                header("anthropic-version", API_VERSION)
                header("anthropic-beta", ANTHROPIC_BETA)
                setBody(request)
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
                        android.util.Log.w(TAG, "Failed to parse SSE event: $data", e)
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
     * Send a non-streaming message to Claude API.
     */
    suspend fun sendMessage(
        messages: List<ClaudeMessage>,
        systemPrompt: String? = null,
        maxTokens: Int = 4096,
        temperature: Double? = null
    ): Result<ClaudeResponse> {
        val request = ClaudeRequest(
            model = BuildConfig.CLAUDE_MODEL.ifBlank { "claude-sonnet-4-5-20250929" },
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