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
import kotlinx.coroutines.flow.cancellable
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

/**
 * Claude API Client for streaming and non-streaming completions.
 * 
 * Features:
 * - SSE (Server-Sent Events) streaming support
 * - Automatic retry-after handling for rate limits
 * - Cancellation-aware flows
 * - Comprehensive error handling
 * - Resource cleanup guarantees
 * 
 * @property httpClient Ktor HTTP client configured for Anthropic API
 * @property json Kotlinx.serialization JSON instance
 * @property apiUrl Anthropic API endpoint URL
 * 
 * @since 1.0.0
 */
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

    /**
     * Stream messages from Claude API using Server-Sent Events.
     * 
     * This method is cancellation-aware - when the collector cancels the flow,
     * the underlying HTTP connection is properly closed.
     * 
     * @param messages Conversation history (user/assistant messages)
     * @param systemPrompt Optional system prompt for context
     * @param maxTokens Maximum tokens to generate (default: 4096)
     * @param temperature Sampling temperature (0.0-1.0, optional)
     * @return Flow of [StreamingResult] events
     * 
     * @throws ClaudeApiException on API errors
     * 
     * Example:
     * ```kotlin
     * claudeClient.streamMessage(
     *     messages = listOf(ClaudeMessage("user", "Hello!")),
     *     systemPrompt = "You are a helpful assistant"
     * ).collect { result ->
     *     when (result) {
     *         is StreamingResult.Delta -> println(result.text)
     *         is StreamingResult.Completed -> println("Done!")
     *         is StreamingResult.Error -> handleError(result.exception)
     *     }
     * }
     * ```
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
                // Timeout protection for entire streaming session
                if (System.currentTimeMillis() - startTime > MAX_STREAMING_TIME_MS) {
                    emit(StreamingResult.Error(
                        ClaudeApiException(
                            type = "timeout",
                            message = "Streaming exceeded 5 minutes"
                        )
                    ))
                    return@flow
                }

                // Read line with timeout
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
                
                // Parse SSE events
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
            // Ensure channel is always closed
            channel?.cancel()
        }
    }.cancellable() // ✅ FIX #1: Cancellation support

    /**
     * Send a non-streaming message to Claude API.
     * 
     * @param messages Conversation history
     * @param systemPrompt Optional system prompt
     * @param maxTokens Maximum tokens to generate
     * @param temperature Sampling temperature (0.0-1.0)
     * @return Result containing [ClaudeResponse] or error
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

    /**
     * Parse error response from Anthropic API.
     * 
     * Handles:
     * - Rate limit errors with Retry-After header
     * - Authentication errors
     * - Invalid request errors
     * - Server errors
     * 
     * @param response HTTP response with error status
     * @return [ClaudeApiException] with parsed error details
     */
    private suspend fun parseError(response: HttpResponse): ClaudeApiException {
        return try {
            val errorBody = response.body<String>()
            val errorResponse = json.decodeFromString<ClaudeErrorResponse>(errorBody)
            
            // ✅ FIX #2: Extract Retry-After header for rate limits
            val retryAfter = response.headers["Retry-After"]?.toIntOrNull()
            
            ClaudeApiException(
                type = errorResponse.error.type,
                message = errorResponse.error.message,
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

// ═══════════════════════════════════════════════════════════════════════════════
// STREAMING RESULT TYPES
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Sealed class representing streaming events from Claude API.
 */
sealed class StreamingResult {
    /**
     * Streaming started with message ID.
     */
    data class Started(val messageId: String) : StreamingResult()
    
    /**
     * Delta (chunk) of text received.
     * 
     * @property text New text chunk
     * @property accumulated All accumulated text so far
     */
    data class Delta(val text: String, val accumulated: String) : StreamingResult()
    
    /**
     * Stop reason received (e.g., "end_turn", "max_tokens").
     */
    data class StopReason(val reason: String) : StreamingResult()
    
    /**
     * Streaming completed successfully.
     * 
     * @property fullText Complete generated text
     * @property usage Token usage statistics
     */
    data class Completed(val fullText: String, val usage: Usage?) : StreamingResult()
    
    /**
     * Error occurred during streaming.
     */
    data class Error(val exception: ClaudeApiException) : StreamingResult()
}

// ═══════════════════════════════════════════════════════════════════════════════
// EXCEPTION CLASS (Production-ready, 2026 level)
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Exception thrown by Claude API operations.
 * 
 * Provides structured error information including:
 * - Error type classification
 * - Human-readable message
 * - Retry-After header for rate limits
 * - Original cause exception
 * 
 * @property type Error type from Anthropic API
 * @property retryAfterSeconds Seconds to wait before retry (for rate limits)
 * 
 * @constructor Creates exception with type, message, and optional cause
 * @param type Error type (e.g., "rate_limit_error", "invalid_request_error")
 * @param message Human-readable error message
 * @param cause Original exception that caused this error (optional)
 * @param retryAfterSeconds Seconds to wait before retry (optional)
 */
class ClaudeApiException(
    val type: String,
    message: String,
    cause: Throwable? = null,
    val retryAfterSeconds: Int? = null
) : Exception(message, cause) {
    
    /**
     * Convenience constructor without retry-after.
     * ✅ FIX #3: This ensures backward compatibility
     */
    constructor(
        type: String,
        message: String
    ) : this(type, message, null, null)
    
    /** True if this is a rate limit error. */
    val isRateLimitError: Boolean get() = type == "rate_limit_error"
    
    /** True if this is an authentication error. */
    val isAuthError: Boolean get() = type == "authentication_error"
    
    /** True if this is an invalid request error. */
    val isInvalidRequest: Boolean get() = type == "invalid_request_error"
    
    /** True if the API is overloaded. */
    val isOverloaded: Boolean get() = type == "overloaded_error"
    
    /**
     * User-friendly error message with retry information.
     */
    override fun toString(): String = buildString {
        append("ClaudeApiException(type=$type, message=$message")
        retryAfterSeconds?.let { append(", retryAfter=${it}s") }
        append(")")
    }
}
