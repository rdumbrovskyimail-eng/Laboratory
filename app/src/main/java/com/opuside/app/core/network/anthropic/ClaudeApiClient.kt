package com.opuside.app.core.network.anthropic

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 🤖 CLAUDE API CLIENT v12.0 (CACHE + FIRST MESSAGE CACHING)
 *
 * ✅ ИСПРАВЛЕНИЯ:
 * 1. System с cache_control в Cache Mode
 * 2. Tools с cache_control на последнем tool
 * 3. Первое user сообщение с cache_control
 * 4. Правильная обработка cache read/write токенов
 */
@Singleton
class ClaudeApiClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val apiKey: String
) {
    companion object {
        private const val TAG = "ClaudeApiClient"
        private const val API_BASE_URL = "https://api.anthropic.com/v1"
        private const val API_VERSION = "2023-06-01"
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }

    /**
     * ✅ MAIN STREAMING METHOD
     */
    suspend fun streamMessage(
        model: String,
        messages: List<ClaudeMessage>,
        systemPrompt: String? = null,
        maxTokens: Int = 4096,
        temperature: Double? = null,
        enableCaching: Boolean = false,
        tools: List<JsonObject>? = null
    ): Flow<StreamingResult> = flow {
        require(messages.isNotEmpty()) { "Messages cannot be empty" }
        require(maxTokens > 0) { "maxTokens must be positive" }

        val requestBody = buildRequestJson(
            model = model,
            messages = messages,
            systemPrompt = systemPrompt,
            maxTokens = maxTokens,
            temperature = temperature,
            enableCaching = enableCaching,
            tools = tools
        )

        Log.d(TAG, "Request body: $requestBody")

        val request = Request.Builder()
            .url("$API_BASE_URL/messages")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", API_VERSION)
            .addHeader("content-type", "application/json")
            .post(requestBody.toRequestBody(JSON_MEDIA_TYPE))
            .build()

        try {
            withContext(Dispatchers.IO) {
                okHttpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val errorBody = response.body?.string() ?: "Unknown error"
                        Log.e(TAG, "API Error: ${response.code} - $errorBody")
                        emit(StreamingResult.Error(IOException("API Error: ${response.code} - $errorBody")))
                        return@use
                    }

                    val source = response.body?.source()
                        ?: throw IOException("Response body is null")

                    val parser = StreamingParser()
                    
                    while (!source.exhausted()) {
                        val line = source.readUtf8Line() ?: break
                        
                        if (line.startsWith("data: ")) {
                            val data = line.substring(6).trim()
                            if (data == "[DONE]") break
                            
                            try {
                                val result = parser.parseEvent(data)
                                result?.let { emit(it) }
                            } catch (e: Exception) {
                                Log.e(TAG, "Parse error: ${e.message}", e)
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Streaming error", e)
            emit(StreamingResult.Error(e))
        }
    }

    /**
     * ✅ BUILD REQUEST JSON WITH CACHING
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

        if (temperature != null) {
            require(temperature.isFinite()) { "temperature must be finite: $temperature" }
            append(",\"temperature\":${Json.encodeToString(temperature)}")
        }

        // ═══════════════════════════════════════════════════════════════
        // ✅ System с cache_control в Cache Mode
        // ═══════════════════════════════════════════════════════════════
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

        // ═══════════════════════════════════════════════════════════════
        // ✅ Tools с cache_control на ПОСЛЕДНЕМ tool
        // ═══════════════════════════════════════════════════════════════
        if (!tools.isNullOrEmpty()) {
            if (enableCaching) {
                append(",\"tools\":[")
                tools.forEachIndexed { index, tool ->
                    if (index > 0) append(",")
                    
                    if (index == tools.lastIndex) {
                        val toolWithCache = buildJsonObject {
                            tool.forEach { (key, value) -> put(key, value) }
                            put("cache_control", buildJsonObject {
                                put("type", "ephemeral")
                            })
                        }
                        append(Json.encodeToString(toolWithCache))
                    } else {
                        append(Json.encodeToString(tool))
                    }
                }
                append("]")
            } else {
                append(",\"tools\":")
                append(Json.encodeToString(tools))
            }
        }

        // ═══════════════════════════════════════════════════════════════
        // ✅ Messages с cache_control на ПЕРВОМ user сообщении
        // ═══════════════════════════════════════════════════════════════
        append(",\"messages\":[")
        
        // Находим индекс первого user сообщения
        val firstUserIndex = messages.indexOfFirst { it.role == "user" }
        
        messages.forEachIndexed { index, msg ->
            if (index > 0) append(",")

            if (msg.isJsonContent) {
                // Tool use/result блоки — уже JSON
                append("{")
                append("\"role\":${Json.encodeToString(msg.role)},")
                append("\"content\":${msg.content}")
                append("}")
            } else {
                // Обычные текстовые сообщения
                append("{")
                append("\"role\":${Json.encodeToString(msg.role)},")
                
                // ✅ Если это первое user сообщение И включен кеш — добавляем cache_control
                if (enableCaching && index == firstUserIndex && msg.role == "user") {
                    append("\"content\":[{")
                    append("\"type\":\"text\",")
                    append("\"text\":${Json.encodeToString(msg.content)},")
                    append("\"cache_control\":{\"type\":\"ephemeral\"}")
                    append("}]")
                } else {
                    append("\"content\":[{")
                    append("\"type\":\"text\",")
                    append("\"text\":${Json.encodeToString(msg.content)}")
                    append("}]")
                }
                
                append("}")
            }
        }
        append("]")
        append("}")
    }

    /**
     * ✅ STREAMING PARSER
     */
    private class StreamingParser {
        private val textBuffer = StringBuilder()
        private var currentToolCalls = mutableListOf<ToolCall>()
        private var currentUsage: Usage? = null
        private var stopReason: String? = null

        fun parseEvent(data: String): StreamingResult? {
            val json = try {
                Json.parseToJsonElement(data).jsonObject
            } catch (e: Exception) {
                Log.e(TAG, "JSON parse error: $data", e)
                return null
            }

            val type = json["type"]?.jsonPrimitive?.content ?: return null

            return when (type) {
                "message_start" -> {
                    val message = json["message"]?.jsonObject
                    message?.get("usage")?.jsonObject?.let { usage ->
                        currentUsage = Usage(
                            inputTokens = usage["input_tokens"]?.jsonPrimitive?.int ?: 0,
                            outputTokens = usage["output_tokens"]?.jsonPrimitive?.int ?: 0,
                            cacheCreationInputTokens = usage["cache_creation_input_tokens"]?.jsonPrimitive?.int,
                            cacheReadInputTokens = usage["cache_read_input_tokens"]?.jsonPrimitive?.int
                        )
                    }
                    StreamingResult.Started
                }

                "content_block_start" -> {
                    val block = json["content_block"]?.jsonObject
                    val blockType = block?.get("type")?.jsonPrimitive?.content
                    
                    if (blockType == "tool_use") {
                        val toolCall = ToolCall(
                            id = block["id"]?.jsonPrimitive?.content ?: "",
                            name = block["name"]?.jsonPrimitive?.content ?: "",
                            input = JsonObject(emptyMap())
                        )
                        currentToolCalls.add(toolCall)
                    }
                    null
                }

                "content_block_delta" -> {
                    val delta = json["delta"]?.jsonObject
                    val deltaType = delta?.get("type")?.jsonPrimitive?.content

                    when (deltaType) {
                        "text_delta" -> {
                            val text = delta["text"]?.jsonPrimitive?.content ?: ""
                            textBuffer.append(text)
                            StreamingResult.Delta(textBuffer.toString())
                        }
                        "input_json_delta" -> {
                            val partialJson = delta["partial_json"]?.jsonPrimitive?.content ?: ""
                            if (currentToolCalls.isNotEmpty()) {
                                val lastTool = currentToolCalls.last()
                                try {
                                    val currentInput = lastTool.input.toString()
                                    val combinedJson = currentInput.removeSuffix("}") + partialJson
                                    val updatedInput = Json.parseToJsonElement(combinedJson + "}").jsonObject
                                    currentToolCalls[currentToolCalls.lastIndex] = lastTool.copy(input = updatedInput)
                                } catch (e: Exception) {
                                    // Partial JSON, will be completed later
                                }
                            }
                            null
                        }
                        else -> null
                    }
                }

                "content_block_stop" -> null

                "message_delta" -> {
                    val delta = json["delta"]?.jsonObject
                    stopReason = delta?.get("stop_reason")?.jsonPrimitive?.content
                    
                    val usage = json["usage"]?.jsonObject
                    usage?.let {
                        val outputTokens = it["output_tokens"]?.jsonPrimitive?.int ?: 0
                        currentUsage = currentUsage?.copy(outputTokens = outputTokens)
                    }
                    
                    if (currentToolCalls.isNotEmpty()) {
                        StreamingResult.ToolUse(
                            toolCalls = currentToolCalls.toList(),
                            textSoFar = textBuffer.toString(),
                            usage = currentUsage
                        )
                    } else {
                        null
                    }
                }

                "message_stop" -> {
                    StreamingResult.Completed(
                        fullText = textBuffer.toString(),
                        stopReason = stopReason,
                        usage = currentUsage
                    )
                }

                "error" -> {
                    val error = json["error"]?.jsonObject
                    val message = error?.get("message")?.jsonPrimitive?.content ?: "Unknown error"
                    StreamingResult.Error(IOException(message))
                }

                else -> {
                    Log.d(TAG, "Unknown event type: $type")
                    null
                }
            }
        }
    }
}

/**
 * ✅ DATA CLASSES
 */
data class ClaudeMessage(
    val role: String,
    val content: String,
    val isJsonContent: Boolean = false
)

data class ToolCall(
    val id: String,
    val name: String,
    val input: JsonObject
)

data class Usage(
    val inputTokens: Int,
    val outputTokens: Int,
    val cacheCreationInputTokens: Int? = null,
    val cacheReadInputTokens: Int? = null
)

/**
 * ✅ STREAMING RESULTS
 */
sealed class StreamingResult {
    data object Started : StreamingResult()
    data class Delta(val accumulated: String) : StreamingResult()
    data class ToolUse(
        val toolCalls: List<ToolCall>,
        val textSoFar: String,
        val usage: Usage?
    ) : StreamingResult()
    data class Completed(
        val fullText: String,
        val stopReason: String?,
        val usage: Usage?
    ) : StreamingResult()
    data class Error(val exception: Exception) : StreamingResult()
}