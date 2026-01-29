package com.opuside.app.core.network.anthropic.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// ═══════════════════════════════════════════════════════════════════════════════
// REQUEST MODELS
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Запрос к Claude API.
 * https://docs.anthropic.com/en/api/messages
 */
@Serializable
data class ClaudeRequest(
    @SerialName("model")
    val model: String = "claude-opus-4-5-20251101",
    
    @SerialName("max_tokens")
    val maxTokens: Int = 4096,
    
    @SerialName("messages")
    val messages: List<ClaudeMessage>,
    
    @SerialName("system")
    val system: String? = null,
    
    @SerialName("stream")
    val stream: Boolean = true,
    
    @SerialName("temperature")
    val temperature: Double? = null,
    
    @SerialName("top_p")
    val topP: Double? = null,
    
    @SerialName("top_k")
    val topK: Int? = null,
    
    @SerialName("stop_sequences")
    val stopSequences: List<String>? = null,
    
    @SerialName("metadata")
    val metadata: ClaudeMetadata? = null
)

/**
 * Сообщение в разговоре.
 */
@Serializable
data class ClaudeMessage(
    @SerialName("role")
    val role: String, // "user" или "assistant"
    
    @SerialName("content")
    val content: String
)

/**
 * Метаданные запроса (опционально).
 */
@Serializable
data class ClaudeMetadata(
    @SerialName("user_id")
    val userId: String? = null
)

// ═══════════════════════════════════════════════════════════════════════════════
// RESPONSE MODELS (Non-streaming)
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Ответ от Claude API (non-streaming).
 */
@Serializable
data class ClaudeResponse(
    @SerialName("id")
    val id: String,
    
    @SerialName("type")
    val type: String, // "message"
    
    @SerialName("role")
    val role: String, // "assistant"
    
    @SerialName("content")
    val content: List<ContentBlock>,
    
    @SerialName("model")
    val model: String,
    
    @SerialName("stop_reason")
    val stopReason: String?, // "end_turn", "max_tokens", "stop_sequence"
    
    @SerialName("stop_sequence")
    val stopSequence: String?,
    
    @SerialName("usage")
    val usage: Usage
)

/**
 * Блок контента в ответе.
 */
@Serializable
data class ContentBlock(
    @SerialName("type")
    val type: String, // "text"
    
    @SerialName("text")
    val text: String? = null
)

/**
 * Использование токенов.
 */
@Serializable
data class Usage(
    @SerialName("input_tokens")
    val inputTokens: Int,
    
    @SerialName("output_tokens")
    val outputTokens: Int
)

// ═══════════════════════════════════════════════════════════════════════════════
// STREAMING EVENTS (SSE)
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Базовый SSE event.
 */
@Serializable
data class StreamEvent(
    @SerialName("type")
    val type: String,
    
    @SerialName("index")
    val index: Int? = null,
    
    @SerialName("message")
    val message: StreamMessage? = null,
    
    @SerialName("content_block")
    val contentBlock: ContentBlock? = null,
    
    @SerialName("delta")
    val delta: StreamDelta? = null,
    
    @SerialName("usage")
    val usage: Usage? = null,
    
    @SerialName("error")
    val error: StreamError? = null
)

/**
 * Сообщение в stream event.
 */
@Serializable
data class StreamMessage(
    @SerialName("id")
    val id: String,
    
    @SerialName("type")
    val type: String,
    
    @SerialName("role")
    val role: String,
    
    @SerialName("model")
    val model: String,
    
    @SerialName("usage")
    val usage: Usage? = null
)

/**
 * Delta (кусочек текста) в streaming.
 */
@Serializable
data class StreamDelta(
    @SerialName("type")
    val type: String? = null,
    
    @SerialName("text")
    val text: String? = null,
    
    @SerialName("stop_reason")
    val stopReason: String? = null,
    
    @SerialName("stop_sequence")
    val stopSequence: String? = null
)

/**
 * Ошибка в stream.
 */
@Serializable
data class StreamError(
    @SerialName("type")
    val type: String,
    
    @SerialName("message")
    val message: String
)

// ═══════════════════════════════════════════════════════════════════════════════
// ERROR RESPONSE
// ═══════════════════════════════════════════════════════════════════════════════

/**
 * Ответ с ошибкой от API.
 */
@Serializable
data class ClaudeErrorResponse(
    @SerialName("type")
    val type: String, // "error"
    
    @SerialName("error")
    val error: ClaudeError
)

/**
 * Детали ошибки.
 */
@Serializable
data class ClaudeError(
    @SerialName("type")
    val type: String, // "invalid_request_error", "authentication_error", "rate_limit_error", etc.
    
    @SerialName("message")
    val message: String
)
