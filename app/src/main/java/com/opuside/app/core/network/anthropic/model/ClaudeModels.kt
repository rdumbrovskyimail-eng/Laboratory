package com.opuside.app.core.network.anthropic.model

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// ═══════════════════════════════════════════════════════════════════════════════
// REQUEST MODELS v4.0 (TOOL USE + TYPED CONTENT)
// ═══════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class ClaudeRequest(
    @EncodeDefault
    @SerialName("model")
    val model: String = "claude-opus-4-6",

    @EncodeDefault
    @SerialName("max_tokens")
    val maxTokens: Int = 4096,

    @EncodeDefault
    @SerialName("messages")
    val messages: List<ClaudeMessage>,

    @SerialName("system")
    val system: String? = null,

    @EncodeDefault
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
    val metadata: ClaudeMetadata? = null,

    @SerialName("tools")
    val tools: List<JsonObject>? = null,

    @SerialName("tool_choice")
    val toolChoice: JsonObject? = null
)

/**
 * Сообщение в разговоре.
 *
 * isJsonContent=true означает что content — уже сериализованный JSON array
 * (tool_use / tool_result блоки). Это ТИПОБЕЗОПАСНАЯ замена startsWith("[") хака.
 */
@Serializable
data class ClaudeMessage(
    @SerialName("role")
    val role: String,

    @SerialName("content")
    val content: String,

    // НЕ сериализуется в JSON — используется только локально для buildRequestJson
    @kotlinx.serialization.Transient
    val isJsonContent: Boolean = false
)

@Serializable
data class ClaudeMetadata(
    @SerialName("user_id")
    val userId: String? = null
)

// ═══════════════════════════════════════════════════════════════════════════════
// RESPONSE MODELS
// ═══════════════════════════════════════════════════════════════════════════════

@Serializable
data class ClaudeResponse(
    @SerialName("id")
    val id: String,

    @SerialName("type")
    val type: String,

    @SerialName("role")
    val role: String,

    @SerialName("content")
    val content: List<ContentBlock>,

    @SerialName("model")
    val model: String,

    @SerialName("stop_reason")
    val stopReason: String?,

    @SerialName("stop_sequence")
    val stopSequence: String?,

    @SerialName("usage")
    val usage: Usage
)

@Serializable
data class ContentBlock(
    @SerialName("type")
    val type: String,

    @SerialName("text")
    val text: String? = null,

    @SerialName("id")
    val id: String? = null,

    @SerialName("name")
    val name: String? = null,

    @SerialName("input")
    val input: JsonObject? = null
) {
    val isText: Boolean get() = type == "text"
    val isToolUse: Boolean get() = type == "tool_use"
}

@Serializable
data class Usage(
    @SerialName("input_tokens")
    val inputTokens: Int,

    @SerialName("output_tokens")
    val outputTokens: Int,

    @SerialName("cache_creation_input_tokens")
    val cacheCreationInputTokens: Int? = null,

    @SerialName("cache_read_input_tokens")
    val cacheReadInputTokens: Int? = null
) {
    val totalTokens: Int get() = inputTokens + outputTokens

    val hasCacheData: Boolean
        get() = cacheCreationInputTokens != null || cacheReadInputTokens != null

    val cacheHitRate: Double
        get() = if (inputTokens > 0 && cacheReadInputTokens != null) {
            (cacheReadInputTokens.toDouble() / inputTokens) * 100
        } else 0.0

    override fun toString(): String = buildString {
        append("Usage(in=$inputTokens, out=$outputTokens")
        cacheReadInputTokens?.let { append(", cached=$it") }
        cacheCreationInputTokens?.let { append(", written=$it") }
        append(")")
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// STREAMING EVENTS (SSE) v5.0 (EXTENDED THINKING SUPPORT)
// ═══════════════════════════════════════════════════════════════════════════════

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

@Serializable
data class StreamDelta(
    @SerialName("type")
    val type: String? = null,

    @SerialName("text")
    val text: String? = null,

    @SerialName("thinking")
    val thinking: String? = null,

    @SerialName("partial_json")
    val partialJson: String? = null,

    @SerialName("stop_reason")
    val stopReason: String? = null,

    @SerialName("stop_sequence")
    val stopSequence: String? = null
)

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

@Serializable
data class ClaudeErrorResponse(
    @SerialName("type")
    val type: String,

    @SerialName("error")
    val error: ClaudeError
)

@Serializable
data class ClaudeError(
    @SerialName("type")
    val type: String,

    @SerialName("message")
    val message: String
)