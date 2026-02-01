package com.opuside.app.core.network.anthropic

import com.opuside.app.BuildConfig
import com.opuside.app.core.network.anthropic.model.ClaudeMessage
import com.opuside.app.core.network.anthropic.model.ClaudeRequest
import com.opuside.app.core.network.anthropic.model.ClaudeResponse
import com.opuside.app.core.network.anthropic.model.StreamEvent
import com.opuside.app.core.network.anthropic.model.Usage
import com.opuside.app.core.network.anthropic.model.ClaudeErrorResponse 
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

@Singleton
class ClaudeApiClient @Inject constructor(
    @Named("anthropic") private val httpClient: HttpClient,
    private val json: Json,
    @Named("anthropicApiUrl") private val apiUrl: String = BuildConfig.ANTHROPIC_API_URL.ifBlank { 
        "https://api.anthropic.com/v1/messages" 
    }
) {
    companion object {
        private const val API_VERSION = "2023-06-01"
        private const val ANTHROPIC_BETA = "messages-2023-12-15"
        private const val READ_TIMEOUT_MS = 30_000L
        private const val MAX_STREAMING_TIME_MS = 5 * 60 * 1000L
    }

    private val apiKey: String
        get() = BuildConfig.ANTHROPIC_API_KEY.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("ANTHROPIC_API_KEY not configured in local.properties")

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

        var channel: io.ktor.utils.io.ByteReadChannel? = null
        
        try {
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
                            message = "Streaming exceeded 5 minutes",
                            cause = null
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
                            message = "Stream timeout after 30s",
                            cause = null
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
                            "message_start" -> emit(StreamingResult.Started(event.message?.id ?: ""))
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
                                            message = error.message,
                                            cause = null
                                        )
                                    ))
                                }
                            }
                            else -> {}
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("ClaudeAPI", "Failed to parse SSE event: $data", e)
                    }
                }
            }

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
    }

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
            ClaudeApiException(
                type = errorResponse.error.type,
                message = errorResponse.error.message,
                cause = null
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
    cause: Throwable? = null
) : Exception(message = message, cause = cause) {
    val isRateLimitError: Boolean get() = type == "rate_limit_error"
    val isAuthError: Boolean get() = type == "authentication_error"
    val isInvalidRequest: Boolean get() = type == "invalid_request_error"
    val isOverloaded: Boolean get() = type == "overloaded_error"
}