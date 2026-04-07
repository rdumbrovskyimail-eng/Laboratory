package com.opuside.app.core.network.gemini

import android.util.Log
import com.opuside.app.core.ai.GeminiModelConfig
import com.opuside.app.core.ai.GeminiModelConfig.FinishReason
import com.opuside.app.core.ai.GeminiModelConfig.GenerationConfig
import com.opuside.app.core.ai.GeminiModelConfig.GeminiModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 🔷 GEMINI API CLIENT v1.1 (REST SSE Streaming)
 *
 * Endpoint: generativelanguage.googleapis.com/v1beta
 * Streaming via SSE: streamGenerateContent?alt=sse
 *
 * Fixes v1.1:
 * - 🔥 RAM: rawJsonBytes вместо toString().toRequestBody() — нет GC-фризов на больших историях
 * - 🔥 OpenAPI: строгая конвертация input_schema (Anthropic) → parameters (Gemini)
 *   Ключ: type=OBJECT + только properties/required — убирает Bad Request / 503 у Pro Preview
 * - 🔥 Умная обработка 429 (Retry-After с сервера) и 503/529
 */
@Singleton
class GeminiApiClient @Inject constructor() {

    companion object {
        private const val TAG = "GeminiApiClient"
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.MINUTES)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    /**
     * Reference to the current OkHttp Call for cancellation support.
     * AtomicReference for thread safety.
     */
    private val currentCall = AtomicReference<Call?>(null)

    /**
     * Cancel the currently running HTTP request.
     * Safe to call from any thread.
     */
    fun cancelCurrentRequest() {
        currentCall.getAndSet(null)?.cancel()
        Log.d(TAG, "Current request cancelled")
    }

    // ═══════════════════════════════════════════════════════════════════
    // STREAMING
    // ═══════════════════════════════════════════════════════════════════

    fun streamGenerate(
        model: GeminiModel,
        messages: List<GeminiMessage>,
        systemPrompt: String?,
        config: GenerationConfig,
        tools: List<JsonObject>? = null,
        sendTools: Boolean = true,
        sendSystemPrompt: Boolean = true,
        apiKey: String
    ): Flow<GeminiStreamResult> = flow {
        val url = "$BASE_URL/models/${model.modelId}:streamGenerateContent?alt=sse"

        val requestBody = buildRequestJson(
            model = model,
            messages = messages,
            systemPrompt = if (sendSystemPrompt) systemPrompt else null,
            config = config,
            tools = if (sendTools) tools else null
        )

        // Redact API key in logs
        Log.d(TAG, "→ POST ${model.modelId} (${messages.size} msgs, " +
                "maxTokens=${config.maxOutputTokens}, temp=${config.temperature})")

        // 🔥 FIX RAM: прямая конвертация в байты — избегаем двойного toString() на огромных историях
        val rawJsonBytes = requestBody.toString().toByteArray(Charsets.UTF_8)

        val request = Request.Builder()
            .url(url)
            .post(rawJsonBytes.toRequestBody("application/json".toMediaType()))
            .header("Content-Type", "application/json")
            .header("x-goog-api-key", apiKey)
            .build()

        val call = httpClient.newCall(request)
        currentCall.set(call)

        kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]?.invokeOnCompletion {
            if (it is kotlinx.coroutines.CancellationException) {
                call.cancel()
            }
        }

        val response: Response = try {
            withContext(Dispatchers.IO) {
                suspendCancellableCoroutine { cont ->
                    cont.invokeOnCancellation { call.cancel() }
                    try {
                        val resp = call.execute()
                        cont.resume(resp)
                    } catch (e: Exception) {
                        if (cont.isActive) cont.resumeWithException(e)
                    }
                }
            }
        } catch (e: java.io.IOException) {
            if (call.isCanceled()) {
                Log.d(TAG, "Request cancelled by user during execute")
                currentCall.set(null)
                return@flow
            }
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            currentCall.set(null)
            return@flow
        }

        try {
            // ── Error handling ──────────────────────────────────────
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                val errorMsg = parseErrorMessage(errorBody)

                when (response.code) {
                    // 🔥 FIX: берём Retry-After с сервера, не хардкодим
                    429 -> {
                        val retryAfter = response.header("Retry-After")?.toIntOrNull() ?: 15
                        Log.w(TAG, "Rate limited. Retry after ${retryAfter}s")
                        emit(GeminiStreamResult.Error(
                            GeminiApiException(
                                "Квота (Rate limit) API исчерпана: HTTP 429. " +
                                "Ждем ~${retryAfter}s. Если у вас Pro модель — попробуйте Flash/Lite версию.",
                                429
                            )
                        ))
                    }
                    // 🔥 FIX: 503 и 529 — перегрев сервера (огромный контекст / шторм сети)
                    503, 529 -> {
                        emit(GeminiStreamResult.Error(
                            GeminiApiException(
                                "API сервер перегружен: HTTP ${response.code}. " +
                                "Огромный размер контекста или шторм сети. $errorMsg",
                                response.code
                            )
                        ))
                    }
                    403 -> {
                        val isBilling = errorBody.contains("billing", ignoreCase = true) ||
                                errorBody.contains("BILLING")
                        emit(GeminiStreamResult.Error(
                            GeminiApiException(
                                if (isBilling) "Включите Billing на Google Cloud для этой модели."
                                else "Ключ отклонен (403). Проверьте ключ.",
                                403
                            )
                        ))
                    }
                    400 -> {
                        emit(GeminiStreamResult.Error(
                            GeminiApiException("Bad request: $errorMsg", response.code)
                        ))
                    }
                    else -> {
                        Log.e(TAG, "API error ${response.code}: $errorMsg")
                        emit(GeminiStreamResult.Error(
                            GeminiApiException(
                                "Gemini API error ${response.code}: $errorMsg",
                                response.code
                            )
                        ))
                    }
                }
                return@flow
            }

            // ── Success: parse SSE stream ───────────────────────────
            emit(GeminiStreamResult.Started)

            val fullText = StringBuilder()
            val lastThoughtSignature = StringBuilder()
            var totalInputTokens = 0
            var totalOutputTokens = 0
            var totalThinkingTokens = 0
            var totalCachedTokens = 0
            val pendingToolCalls = mutableListOf<GeminiToolCall>()
            var hasToolCalls = false
            var lastFinishReason: FinishReason? = null

            // Proper SSE parsing: accumulate data lines until empty line
            response.body!!.byteStream().use { inputStream ->
                val reader = BufferedReader(InputStreamReader(inputStream))
                val eventData = StringBuilder()

                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val l = line ?: continue

                    if (l.startsWith("data: ")) {
                        eventData.append(l.removePrefix("data: "))
                    } else if (l.isEmpty() && eventData.isNotEmpty()) {
                        // Empty line = end of SSE event
                        val jsonStr = eventData.toString().trim()
                        eventData.clear()

                        if (jsonStr.isEmpty()) continue

                        try {
                            parseChunk(
                                jsonStr = jsonStr,
                                fullText = fullText,
                                pendingToolCalls = pendingToolCalls,
                                onHasToolCalls = { hasToolCalls = true },
                                onFinishReason = { lastFinishReason = it },
                                onUsage = { inp, out, think, cached ->
                                    totalInputTokens = inp
                                    totalOutputTokens = out
                                    totalThinkingTokens = think
                                    totalCachedTokens = cached
                                },
                                lastThoughtSignature = lastThoughtSignature
                            )

                            // Emit text delta
                            if (fullText.isNotEmpty()) {
                                emit(GeminiStreamResult.Delta(
                                    delta = "",
                                    accumulated = fullText.toString()
                                ))
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Parse chunk error: ${e.message}")
                        }
                    }
                }

                // Handle any remaining data
                if (eventData.isNotEmpty()) {
                    val jsonStr = eventData.toString().trim()
                    if (jsonStr.isNotEmpty()) {
                        try {
                            parseChunk(jsonStr, fullText, pendingToolCalls,
                                { hasToolCalls = true },
                                { lastFinishReason = it },
                                { inp, out, think, cached ->
                                    totalInputTokens = inp
                                    totalOutputTokens = out
                                    totalThinkingTokens = think
                                    totalCachedTokens = cached
                                },
                                lastThoughtSignature)
                        } catch (e: Exception) {
                            Log.w(TAG, "Parse final chunk error: ${e.message}")
                        }
                    }
                }
            }

            // ── Build usage ─────────────────────────────────────────
            val usage = GeminiUsage(
                inputTokens = totalInputTokens,
                outputTokens = totalOutputTokens,
                thinkingTokens = totalThinkingTokens,
                cachedTokens = totalCachedTokens
            )

            // ── Emit final result based on finish reason ────────────
            if (hasToolCalls) {
                emit(GeminiStreamResult.ToolUse(
                    textSoFar = fullText.toString(),
                    toolCalls = pendingToolCalls.toList(),
                    usage = usage
                ))
            } else {
                when (lastFinishReason) {
                    FinishReason.SAFETY -> {
                        emit(GeminiStreamResult.Error(
                            GeminiApiException(
                                "Response blocked by safety settings. " +
                                "Try adjusting safety thresholds in Settings → Tune.",
                                -1
                            )
                        ))
                    }
                    FinishReason.RECITATION -> {
                        emit(GeminiStreamResult.Error(
                            GeminiApiException(
                                "Response blocked due to recitation/copyright concerns.",
                                -1
                            )
                        ))
                    }
                    FinishReason.BLOCKLIST, FinishReason.PROHIBITED_CONTENT, FinishReason.SPII -> {
                        emit(GeminiStreamResult.Error(
                            GeminiApiException(
                                "Response blocked: ${lastFinishReason?.apiName}. " +
                                "Adjust safety settings or rephrase your request.",
                                -1
                            )
                        ))
                    }
                    FinishReason.MAX_TOKENS -> {
                        emit(GeminiStreamResult.Completed(
                            fullText = fullText.toString() +
                                    "\n\n⚠️ Response truncated (max tokens reached)",
                            usage = usage
                        ))
                    }
                    else -> {
                        emit(GeminiStreamResult.Completed(
                            fullText = fullText.toString(),
                            usage = usage
                        ))
                    }
                }
            }
        } catch (e: java.io.IOException) {
            if (call.isCanceled()) {
                Log.d(TAG, "Request cancelled by user during stream read")
                return@flow
            }
            Log.e(TAG, "Stream IO error", e)
            emit(GeminiStreamResult.Error(e))
        } catch (e: Exception) {
            Log.e(TAG, "Stream error", e)
            emit(GeminiStreamResult.Error(e))
        } finally {
            response.close()
            currentCall.set(null)
        }
    }.onCompletion {
        currentCall.set(null)
    }

    // ═══════════════════════════════════════════════════════════════════
    // CHUNK PARSER
    // ═══════════════════════════════════════════════════════════════════

    private fun parseChunk(
        jsonStr: String,
        fullText: StringBuilder,
        pendingToolCalls: MutableList<GeminiToolCall>,
        onHasToolCalls: () -> Unit,
        onFinishReason: (FinishReason?) -> Unit,
        onUsage: (inputTokens: Int, outputTokens: Int, thinkingTokens: Int, cachedTokens: Int) -> Unit,
        lastThoughtSignature: StringBuilder
    ) {
        val chunk = Json.parseToJsonElement(jsonStr).jsonObject
        val candidates = chunk["candidates"]?.jsonArray ?: return

        for (candidate in candidates) {
            val candObj = candidate.jsonObject
            val content = candObj["content"]?.jsonObject
            val parts = content?.get("parts")?.jsonArray

            if (parts != null) {
                for (part in parts) {
                    val partObj = part.jsonObject

                    // Text content
                    partObj["text"]?.jsonPrimitive?.contentOrNull?.let { text ->
                        fullText.append(text)
                    }

                    // Thinking text (for logging/debug only)
                    partObj["thought"]?.jsonPrimitive?.contentOrNull?.let { thought ->
                        Log.d(TAG, "Thinking: ${thought.take(80)}...")
                    }

                    // Thinking signature
                    partObj["thoughtSignature"]?.jsonPrimitive?.contentOrNull?.let { sig ->
                        lastThoughtSignature.clear()
                        lastThoughtSignature.append(sig)
                        Log.d(TAG, "Got thoughtSignature: ${sig.take(20)}...")
                    }

                    // Function call
                    partObj["functionCall"]?.jsonObject?.let { fc ->
                        val name = fc["name"]?.jsonPrimitive?.content ?: "unknown"
                        val args = fc["args"]?.jsonObject ?: buildJsonObject {}
                        pendingToolCalls.add(GeminiToolCall(
                            name = name,
                            args = args,
                            thoughtSignature = lastThoughtSignature.toString().ifEmpty { null }
                        ))
                        onHasToolCalls()
                    }
                }
            }

            // Finish reason
            candObj["finishReason"]?.jsonPrimitive?.contentOrNull?.let { reason ->
                Log.d(TAG, "Finish reason: $reason")
                onFinishReason(FinishReason.fromApi(reason))
            }
        }

        // Usage metadata (appears in last chunk typically)
        chunk["usageMetadata"]?.jsonObject?.let { usage ->
            onUsage(
                usage["promptTokenCount"]?.jsonPrimitive?.intOrNull ?: 0,
                usage["candidatesTokenCount"]?.jsonPrimitive?.intOrNull ?: 0,
                usage["thoughtsTokenCount"]?.jsonPrimitive?.intOrNull ?: 0,
                usage["cachedContentTokenCount"]?.jsonPrimitive?.intOrNull ?: 0
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // REQUEST BUILDER
    // ═══════════════════════════════════════════════════════════════════

    private fun buildRequestJson(
        model: GeminiModel,
        messages: List<GeminiMessage>,
        systemPrompt: String?,
        config: GenerationConfig,
        tools: List<JsonObject>?
    ): JsonObject = buildJsonObject {

        // ── Contents ────────────────────────────────────────────────
        put("contents", JsonArray(messages.map { msg ->
            buildJsonObject {
                put("role", JsonPrimitive(msg.role))
                put("parts", JsonArray(msg.parts.map { part ->
                    when (part) {
                        is GeminiPart.Text -> buildJsonObject {
                            put("text", JsonPrimitive(part.text))
                        }
                        is GeminiPart.InlineData -> buildJsonObject {
                            put("inlineData", buildJsonObject {
                                put("mimeType", JsonPrimitive(part.mimeType))
                                put("data", JsonPrimitive(part.base64Data))
                            })
                        }
                        is GeminiPart.FunctionResponse -> buildJsonObject {
                            put("functionResponse", buildJsonObject {
                                put("name", JsonPrimitive(part.name))
                                put("response", part.response)
                            })
                        }
                        is GeminiPart.FunctionCall -> buildJsonObject {
                            put("functionCall", buildJsonObject {
                                put("name", JsonPrimitive(part.name))
                                put("args", part.args)
                            })
                            if (part.thoughtSignature != null) {
                                put("thoughtSignature", JsonPrimitive(part.thoughtSignature))
                            }
                        }
                    }
                }))
            }
        }))

        // ── System instruction ──────────────────────────────────────
        if (systemPrompt != null) {
            put("systemInstruction", buildJsonObject {
                put("parts", JsonArray(listOf(
                    buildJsonObject { put("text", JsonPrimitive(systemPrompt)) }
                )))
            })
        }

        // ── Generation config ───────────────────────────────────────
        put("generationConfig", buildJsonObject {
            put("temperature", JsonPrimitive(config.temperature))
            put("topP", JsonPrimitive(config.topP))
            put("topK", JsonPrimitive(config.topK))
            put("maxOutputTokens", JsonPrimitive(config.maxOutputTokens))

            if (config.stopSequences.isNotEmpty()) {
                put("stopSequences", JsonArray(
                    config.stopSequences.map { JsonPrimitive(it) }
                ))
            }
            config.responseMimeType?.let {
                put("responseMimeType", JsonPrimitive(it))
            }
            if (config.presencePenalty != 0f && model.supportsPresencePenalty) {
                put("presencePenalty", JsonPrimitive(config.presencePenalty))
            }
            if (config.frequencyPenalty != 0f && model.supportsFrequencyPenalty) {
                put("frequencyPenalty", JsonPrimitive(config.frequencyPenalty))
            }
            if (config.seed != null && model.supportsSeed) {
                put("seed", JsonPrimitive(config.seed))
            }

            if (model.supportsThinking &&
                config.thinkingLevel != GeminiModelConfig.ThinkingLevel.NONE
            ) {
                put("thinkingConfig", buildJsonObject {
                    put("thinkingLevel", JsonPrimitive(config.thinkingLevel.apiName))
                })
            }
        })

        // ── Safety settings ─────────────────────────────────────────
        put("safetySettings", JsonArray(config.safetySettings.map { (cat, threshold) ->
            buildJsonObject {
                put("category", JsonPrimitive(cat.apiName))
                put("threshold", JsonPrimitive(threshold.apiName))
            }
        }))

        // ── Tools (function calling) ────────────────────────────────
        // 🔥 FIX OpenAPI: строгая конвертация Anthropic input_schema → Gemini parameters.
        // Проблема: Claude хранит схему в "input_schema", Gemini ожидает "parameters".
        // Кроме того, Gemini 3.1 Pro Preview жёстко валидирует: type должен быть "OBJECT" (uppercase),
        // и допускаются только поля properties + required. Лишние поля → Bad Request / 503.
        if (!tools.isNullOrEmpty()) {
            put("tools", JsonArray(listOf(
                buildJsonObject {
                    put("functionDeclarations", JsonArray(tools.map { tool ->
                        buildJsonObject {
                            put("name", tool["name"]!!)
                            put("description", tool["description"]!!)
                            // Клонируем только "внутренности" input_schema — убираем лишние поля
                            val schema = tool["input_schema"]?.jsonObject
                            if (schema != null) {
                                put("parameters", buildJsonObject {
                                    put("type", JsonPrimitive("OBJECT"))
                                    schema["properties"]?.let { put("properties", it) }
                                    schema["required"]?.let { put("required", it) }
                                })
                            } else {
                                // Нет схемы — пустой объект (безопасный фолбэк)
                                put("parameters", buildJsonObject {
                                    put("type", JsonPrimitive("OBJECT"))
                                    put("properties", buildJsonObject {})
                                })
                            }
                        }
                    }))
                }
            )))
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════

    private fun parseErrorMessage(body: String): String {
        return try {
            val json = Json.parseToJsonElement(body).jsonObject
            json["error"]?.jsonObject?.get("message")?.jsonPrimitive?.content
                ?: body.take(300)
        } catch (_: Exception) {
            body.take(300)
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// DATA CLASSES
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Typed exception for Gemini API errors, preserving HTTP status code.
 */
class GeminiApiException(
    message: String,
    val httpCode: Int
) : Exception(message)

data class GeminiMessage(
    val role: String,       // "user" or "model"
    val parts: List<GeminiPart>
) {
    companion object {
        fun user(text: String) = GeminiMessage("user", listOf(GeminiPart.Text(text)))
        fun model(text: String) = GeminiMessage("model", listOf(GeminiPart.Text(text)))
    }
}

sealed class GeminiPart {
    data class Text(val text: String) : GeminiPart()
    data class InlineData(val mimeType: String, val base64Data: String) : GeminiPart()
    data class FunctionCall(val name: String, val args: JsonObject, val thoughtSignature: String? = null) : GeminiPart()
    data class FunctionResponse(val name: String, val response: JsonObject) : GeminiPart()
}

data class GeminiToolCall(
    val name: String,
    val args: JsonObject,
    val thoughtSignature: String? = null
)

data class GeminiUsage(
    val inputTokens: Int,
    val outputTokens: Int,
    val thinkingTokens: Int = 0,
    val cachedTokens: Int = 0
)

sealed class GeminiStreamResult {
    data object Started : GeminiStreamResult()
    data class Delta(val delta: String, val accumulated: String) : GeminiStreamResult()
    data class ToolUse(
        val textSoFar: String,
        val toolCalls: List<GeminiToolCall>,
        val usage: GeminiUsage?
    ) : GeminiStreamResult()
    data class Completed(
        val fullText: String,
        val usage: GeminiUsage?
    ) : GeminiStreamResult()
    data class Error(val exception: Exception) : GeminiStreamResult()
}