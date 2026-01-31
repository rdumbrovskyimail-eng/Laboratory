package com.opuside.app.core.network.anthropic

import com.opuside.app.BuildConfig
import com.opuside.app.core.network.anthropic.model.ClaudeError
import com.opuside.app.core.network.anthropic.model.ClaudeMessage
import com.opuside.app.core.network.anthropic.model.ClaudeRequest
import com.opuside.app.core.network.anthropic.model.ClaudeResponse
import com.opuside.app.core.network.anthropic.model.StreamDelta
import com.opuside.app.core.network.anthropic.model.StreamEvent
import com.opuside.app.core.network.anthropic.model.Usage
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
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Клиент для работы с Anthropic Claude API.
 * 
 * Поддерживает:
 * - Streaming ответы через SSE
 * - Non-streaming ответы
 * - Обработка ошибок
 * 
 * ✅ ИСПРАВЛЕНО:
 * - Проблема #3: Channel Leak - добавлен channel.cancel() для корректного закрытия
 * - Проблема #17: Hard-Coded Dependencies - API_URL вынесен в конфигурацию
 * - Проблема №4: Добавлена валидация API ключа
 * - Проблема №9: Добавлены timeouts для предотвращения зависания streaming
 */
@Singleton
class ClaudeApiClient @Inject constructor(
    @Named("anthropic") private val httpClient: HttpClient,
    private val json: Json,
    // ✅ ИСПРАВЛЕНО (Проблема #17): API URL теперь инжектируется, а не hardcoded
    @Named("anthropicApiUrl") private val apiUrl: String = BuildConfig.ANTHROPIC_API_URL.ifBlank { 
        "https://api.anthropic.com/v1/messages" 
    }
) {
    companion object {
        // ✅ ИСПРАВЛЕНО (Проблема #17): Оставлен только fallback, основное значение инжектируется
        private const val DEFAULT_API_URL = "https://api.anthropic.com/v1/messages"
        private const val API_VERSION = "2023-06-01"
        private const val ANTHROPIC_BETA = "messages-2023-12-15"
        private const val READ_TIMEOUT_MS = 30_000L // ✅ ДОБАВЛЕНО: 30 секунд на строку
        private const val MAX_STREAMING_TIME_MS = 5 * 60 * 1000L // ✅ ДОБАВЛЕНО: 5 минут макс
    }

    // ✅ ИСПРАВЛЕНО: Проблема №4 - Валидация API ключа
    private val apiKey: String
        get() = BuildConfig.ANTHROPIC_API_KEY.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("ANTHROPIC_API_KEY not configured in local.properties")

    // ═══════════════════════════════════════════════════════════════════════════
    // STREAMING API
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Отправить запрос и получить streaming ответ.
     * 
     * @param messages История сообщений
     * @param systemPrompt Системный промпт (опционально)
     * @param maxTokens Максимум токенов в ответе
     * @return Flow<StreamingResult> — поток событий
     */
    fun streamMessage(
        messages: List<ClaudeMessage>,
        systemPrompt: String? = null,
        maxTokens: Int = 4096,
        temperature: Double? = null
    ): Flow<StreamingResult> = flow {
        val request = ClaudeRequest(
            model = BuildConfig.CLAUDE_MODEL,
            maxTokens = maxTokens,
            messages = messages,
            system = systemPrompt,
            stream = true,
            temperature = temperature
        )

        // ✅ ИСПРАВЛЕНО (Проблема #3): Используем try-finally для гарантированной очистки
        var channel: io.ktor.utils.io.ByteReadChannel? = null
        
        try {
            // ✅ ИСПРАВЛЕНО (Проблема #17): Используем инжектированный apiUrl
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

            // Читаем SSE stream
            channel = response.bodyAsChannel()
            var currentText = StringBuilder()
            var totalUsage: Usage? = null
            
            // ✅ ДОБАВЛЕНО: Проблема №9 - Отслеживание времени streaming
            val startTime = System.currentTimeMillis()

            while (!channel.isClosedForRead) {
                // ✅ ИСПРАВЛЕНО (Проблема #3): Проверка общего времени с корректным выходом
                if (System.currentTimeMillis() - startTime > MAX_STREAMING_TIME_MS) {
                    emit(StreamingResult.Error(
                        ClaudeApiException("timeout", "Streaming exceeded 5 minutes")
                    ))
                    return@flow // ✅ finally блок очистит channel
                }

                // ✅ ИСПРАВЛЕНО (Проблема #3): Timeout на чтение строки с корректным выходом
                val line = withTimeoutOrNull(READ_TIMEOUT_MS) {
                    channel.readUTF8Line()
                }

                if (line == null) {
                    // Timeout - завершаем с ошибкой
                    emit(StreamingResult.Error(
                        ClaudeApiException("timeout", "Stream timeout after 30s")
                    ))
                    return@flow // ✅ finally блок очистит channel
                }
                
                // SSE формат: "data: {...}"
                if (line.startsWith("data: ")) {
                    val data = line.removePrefix("data: ").trim()
                    
                    // Пропускаем пустые строки и [DONE]
                    if (data.isEmpty() || data == "[DONE]") continue

                    try {
                        val event = json.decodeFromString<StreamEvent>(data)
                        
                        when (event.type) {
                            "message_start" -> {
                                // Начало сообщения
                                emit(StreamingResult.Started(event.message?.id ?: ""))
                            }
                            
                            "content_block_start" -> {
                                // Начало блока контента
                            }
                            
                            "content_block_delta" -> {
                                // Кусочек текста
                                event.delta?.text?.let { text ->
                                    currentText.append(text)
                                    emit(StreamingResult.Delta(text, currentText.toString()))
                                }
                            }
                            
                            "content_block_stop" -> {
                                // Блок завершён
                            }
                            
                            "message_delta" -> {
                                // Финальная delta с usage
                                event.usage?.let { totalUsage = it }
                                event.delta?.stopReason?.let { reason ->
                                    emit(StreamingResult.StopReason(reason))
                                }
                            }
                            
                            "message_stop" -> {
                                // Сообщение завершено
                                emit(StreamingResult.Completed(
                                    fullText = currentText.toString(),
                                    usage = totalUsage
                                ))
                            }
                            
                            "error" -> {
                                event.error?.let { error ->
                                    emit(StreamingResult.Error(
                                        ClaudeApiException(error.type, error.message)
                                    ))
                                }
                            }
                        }
                    } catch (e: Exception) {
                        // Игнорируем ошибки парсинга отдельных событий
                        android.util.Log.w("ClaudeAPI", "Failed to parse SSE event: $data", e)
                    }
                }
            }

        } catch (e: Exception) {
            emit(StreamingResult.Error(
                ClaudeApiException("network_error", e.message ?: "Unknown error")
            ))
        } finally {
            // ✅ ИСПРАВЛЕНО (Проблема #3): Гарантированная очистка channel
            channel?.cancel()
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // NON-STREAMING API
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Отправить запрос и получить полный ответ (без streaming).
     */
    suspend fun sendMessage(
        messages: List<ClaudeMessage>,
        systemPrompt: String? = null,
        maxTokens: Int = 4096,
        temperature: Double? = null
    ): Result<ClaudeResponse> {
        val request = ClaudeRequest(
            model = BuildConfig.CLAUDE_MODEL,
            maxTokens = maxTokens,
            messages = messages,
            system = systemPrompt,
            stream = false,
            temperature = temperature
        )

        return try {
            // ✅ ИСПРАВЛЕНО (Проблема #17): Используем инжектированный apiUrl
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

        } catch (e: Exception) {
            Result.failure(ClaudeApiException("network_error", e.message ?: "Unknown error"))
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════════

    private suspend fun parseError(response: HttpResponse): ClaudeApiException {
        return try {
            val errorBody = response.body<String>()
            val errorResponse = json.decodeFromString<com.opuside.app.core.network.anthropic.model.ClaudeErrorResponse>(errorBody)
            ClaudeApiException(errorResponse.error.type, errorResponse.error.message)
        } catch (e: Exception) {
            ClaudeApiException(
                type = "http_error",
                message = "HTTP ${response.status.value}: ${response.status.description}"
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// RESULT TYPES
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Результат streaming запроса.
 */
sealed class StreamingResult {
    /** Streaming начался */
    data class Started(val messageId: String) : StreamingResult()
    
    /** Получен кусочек текста */
    data class Delta(val text: String, val accumulated: String) : StreamingResult()
    
    /** Причина остановки */
    data class StopReason(val reason: String) : StreamingResult()
    
    /** Streaming завершён успешно */
    data class Completed(val fullText: String, val usage: Usage?) : StreamingResult()
    
    /** Ошибка */
    data class Error(val exception: ClaudeApiException) : StreamingResult()
}

/**
 * Exception для ошибок Claude API.
 */
class ClaudeApiException(
    val type: String,
    override val message: String
) : Exception("[$type] $message") {
    
    val isRateLimitError: Boolean get() = type == "rate_limit_error"
    val isAuthError: Boolean get() = type == "authentication_error"
    val isInvalidRequest: Boolean get() = type == "invalid_request_error"
    val isOverloaded: Boolean get() = type == "overloaded_error"
}