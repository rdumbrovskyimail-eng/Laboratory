package com.opuside.app.core.network.gemini

import android.util.Log
import com.opuside.app.core.ai.GeminiModelConfig
import com.opuside.app.core.ai.GeminiModelConfig.FinishReason
import com.opuside.app.core.ai.GeminiModelConfig.GenerationConfig
import com.opuside.app.core.ai.GeminiModelConfig.GeminiModel
import com.opuside.app.core.ai.GeminiModelConfig.ThinkingLevel
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
 * 🔷 GEMINI API CLIENT v3.0 (REST SSE Streaming)
 *
 * Особенности версии:
 * - Жесткая привязка thinkingLevel к модели (3.1 -> MEDIUM, 3.5 -> LOW)
 * - Разделение обычного текста ответа и внутренних мыслей (thought: true)
 * - 10-минутный read timeout для генерации до 65 536 токенов
 * - Корректная трансляция OpenAPI схем инструментов без 400 Bad Request
 * - Поддержка x-goog-api-key и отмены запросов через AtomicReference
 */
@Singleton
class GeminiApiClient @Inject constructor() {

    companion object {
        private const val TAG = "GeminiApiClient"
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta"
    }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.MINUTES) // Запас для генерации длинных файлов до 64K токенов
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    private val currentCall = AtomicReference<Call?>(null)

    fun cancelCurrentRequest() {
        currentCall.getAndSet(null)?.cancel()
        Log.d(TAG, "Текущий HTTP-запрос отменён")
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

        Log.d(TAG, "→ POST ${model.modelId} (msgs=${messages.size}, thinking=${model.forcedThinkingLevel.apiName}, maxOutput=${config.maxOutputTokens})")

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
                Log.d(TAG, "Запрос отменен пользователем")
                currentCall.set(null)
                return@flow
            }
            throw e
        } catch (e: kotlinx.coroutines.CancellationException) {
            currentCall.set(null)
            return@flow
        }

        try {
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "Unknown error"
                val errorMsg = parseErrorMessage(errorBody)

                when (response.code) {
                    429 -> {
                        val retryAfter = response.header("Retry-After")?.toIntOrNull() ?: 15
                        emit(GeminiStreamResult.Error(
                            GeminiApiException(
                                "Лимит запросов API исчерпан (HTTP 429). Ожидание ~${retryAfter}с.",
                                429
                            )
                        ))
                    }
                    503, 529 -> {
                        emit(GeminiStreamResult.Error(
                            GeminiApiException(
                                "Сервер Gemini временно перегружен (HTTP ${response.code}): $errorMsg",
                                response.code
                            )
                        ))
                    }
                    403 -> {
                        val isBilling = errorBody.contains("billing", ignoreCase = true)
                        emit(GeminiStreamResult.Error(
                            GeminiApiException(
                                if (isBilling) "Включите Billing на Google Cloud для этой модели."
                                else "API ключ отклонен (HTTP 403). Проверьте ключ.",
                                403
                            )
                        ))
                    }
                    400 -> {
                        emit(GeminiStreamResult.Error(
                            GeminiApiException("Ошибка формата запроса (HTTP 400): $errorMsg", 400)
                        ))
                    }
                    else -> {
                        emit(GeminiStreamResult.Error(
                            GeminiApiException("Ошибка Gemini API (${response.code}): $errorMsg", response.code)
                        ))
                    }
                }
                return@flow
            }

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

            response.body!!.byteStream().use { inputStream ->
                val reader = BufferedReader(InputStreamReader(inputStream, Charsets.UTF_8))
                val eventData = StringBuilder()

                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    val l = line ?: continue

                    if (l.startsWith("data: ")) {
                        eventData.append(l.removePrefix("data: "))
                    } else if (l.isEmpty() && eventData.isNotEmpty()) {
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

                            if (fullText.isNotEmpty()) {
                                emit(GeminiStreamResult.Delta(
                                    delta = "",
                                    accumulated = fullText.toString()
                                ))
                            }
                        } catch (e: Exception) {
                            Log.w(TAG, "Ошибка парсинга чанка: ${e.message}")
                        }
                    }
                }

                if (eventData.isNotEmpty()) {
                    val jsonStr = eventData.toString().trim()
                    if (jsonStr.isNotEmpty()) {
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
                        } catch (e: Exception) {
                            Log.w(TAG, "Ошибка финального чанка: ${e.message}")
                        }
                    }
                }
            }

            val usage = GeminiUsage(
                inputTokens = totalInputTokens,
                outputTokens = totalOutputTokens,
                thinkingTokens = totalThinkingTokens,
                cachedTokens = totalCachedTokens
            )

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
                            GeminiApiException("Ответ заблокирован настройками безопасности Google.", -1)
                        ))
                    }
                    FinishReason.RECITATION -> {
                        emit(GeminiStreamResult.Error(
                            GeminiApiException("Ответ заблокирован из-за ограничений авторских прав (Recitation).", -1)
                        ))
                    }
                    FinishReason.MAX_TOKENS -> {
                        emit(GeminiStreamResult.Completed(
                            fullText = fullText.toString() + "\n\n⚠️ Ответ обрезан (достигнут лимит maxOutputTokens)",
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
            if (call.isCanceled()) return@flow
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
    // CHUNK PARSER (Thinking-aware)
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

                    // 1. Проверяем, является ли часть размышлением
                    val isThought = partObj["thought"]?.jsonPrimitive?.booleanOrNull == true
                    val explicitThoughtText = partObj["thought"]?.jsonPrimitive?.contentOrNull

                    if (isThought) {
                        // Токены мыслей НЕ выводим в ответ пользователю
                        val thoughtSnippet = partObj["text"]?.jsonPrimitive?.contentOrNull ?: ""
                        if (thoughtSnippet.isNotEmpty()) {
                            Log.d(TAG, "Thinking: ${thoughtSnippet.take(60)}...")
                        }
                    } else if (explicitThoughtText != null) {
                        Log.d(TAG, "Thinking: ${explicitThoughtText.take(60)}...")
                    } else {
                        // Обычный чистый текст ответа
                        partObj["text"]?.jsonPrimitive?.contentOrNull?.let { text ->
                            fullText.append(text)
                        }
                    }

                    // Сигнатура мыслей
                    partObj["thoughtSignature"]?.jsonPrimitive?.contentOrNull?.let { sig ->
                        lastThoughtSignature.clear()
                        lastThoughtSignature.append(sig)
                    }

                    // Вызовы функций
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

            candObj["finishReason"]?.jsonPrimitive?.contentOrNull?.let { reason ->
                onFinishReason(FinishReason.fromApi(reason))
            }
        }

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
                put("stopSequences", JsonArray(config.stopSequences.map { JsonPrimitive(it) }))
            }
            config.responseMimeType?.let {
                put("responseMimeType", JsonPrimitive(it))
            }
            if (config.seed != null && model.supportsSeed) {
                put("seed", JsonPrimitive(config.seed))
            }

            // Принудительный режим Thinking, зашитый в модель:
            // 3.1 Flash-Lite -> MEDIUM, 3.5 Flash-Lite -> LOW
            if (model.supportsThinking && model.forcedThinkingLevel != ThinkingLevel.NONE) {
                put("thinkingConfig", buildJsonObject {
                    put("thinkingLevel", JsonPrimitive(model.forcedThinkingLevel.apiName))
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

        // ── Tools (function declarations) ───────────────────────────
        if (!tools.isNullOrEmpty()) {
            put("tools", JsonArray(listOf(
                buildJsonObject {
                    put("functionDeclarations", JsonArray(tools.map { tool ->
                        buildJsonObject {
                            put("name", tool["name"]!!)
                            put("description", tool["description"]!!)

                            // Подхватываем параметры из ToolExecutor
                            val parameters = (tool["parameters"] ?: tool["input_schema"])?.jsonObject
                            if (parameters != null) {
                                put("parameters", parameters)
                            } else {
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

class GeminiApiException(
    message: String,
    val httpCode: Int
) : Exception(message)

data class GeminiMessage(
    val role: String,
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