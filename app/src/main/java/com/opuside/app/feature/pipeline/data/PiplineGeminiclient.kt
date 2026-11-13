package com.opuside.app.feature.pipeline.data

import android.util.Log
import com.opuside.app.core.data.AppSettings
import com.opuside.app.core.security.SecureSettingsDataStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 🤖 PIPELINE GEMINI CLIENT v4.0
 *
 * Единый HTTP-клиент для ВСЕХ вызовов модели в Pipeline.
 * Модель жёстко зафиксирована: [PipelineAiConfig.MODEL_ID].
 *
 * Гарантии:
 *  - ответ с finishReason != STOP (MAX_TOKENS, SAFETY, RECITATION...) НИКОГДА не
 *    возвращается как успешный → обрезанный код не может попасть в репозиторий;
 *  - thought-части отфильтровываются;
 *  - 429 → ротация ключей A/B ([PipelineKeyRotator]) + backoff;
 *  - 5xx / сетевые ошибки → экспоненциальный backoff;
 *  - отмена корутины мгновенно обрывает HTTP-соединение.
 */
@Singleton
class PipelineGeminiClient @Inject constructor(
    private val secureSettings: SecureSettingsDataStore,
    private val appSettings: AppSettings,
    private val keyRotator: PipelineKeyRotator
) {
    companion object {
        private const val TAG = "PipelineGemini"
        private const val MAX_ATTEMPTS = 4
        private const val BASE_BACKOFF_MS = 1_500L
        private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    }

    data class Request(
        val tag: String,
        val systemPrompt: String,
        val userMessage: String,
        val thinking: PipelineAiConfig.Thinking,
        val maxOutputTokens: Int,
        val readTimeoutMs: Int,
        /** Стандартный JSON Schema (типы в нижнем регистре). null → обычный текст. */
        val responseJsonSchema: JsonObject? = null
    )

    data class Response(
        val text: String,
        val inputTokens: Int,
        val outputTokens: Int,
        val thinkingTokens: Int,
        val finishReason: String,
        val attempts: Int
    ) {
        val billedOutputTokens: Int get() = outputTokens + thinkingTokens
        val totalTokens: Int get() = inputTokens + billedOutputTokens
        val costEur: Double get() = PipelineAiConfig.costEur(inputTokens, billedOutputTokens)
    }

    enum class FailureKind { NO_KEY, RATE_LIMIT, SERVER, AUTH, BAD_REQUEST, TRUNCATED, BLOCKED, EMPTY, NETWORK, PARSE }

    class GeminiCallException(
        message: String,
        val kind: FailureKind,
        val httpCode: Int = 0,
        /** Токены, потраченные на неудачную попытку (для честного учёта стоимости). */
        val spentInputTokens: Int = 0,
        val spentOutputTokens: Int = 0
    ) : Exception(message) {
        val spentCostEur: Double get() = PipelineAiConfig.costEur(spentInputTokens, spentOutputTokens)
    }

    private data class KeyRef(val key: String, val rotatorIndex: Int)

    private data class HttpResult(val code: Int, val body: String)

    suspend fun generate(req: Request): Result<Response> {
        var keyRef = resolveKey()
            ?: return Result.failure(GeminiCallException(
                "Gemini API ключ не найден. Укажите ключ A/B в блоке «Ключи Pipeline».",
                FailureKind.NO_KEY
            ))

        val body = buildBody(req).toString()
        val url = "${PipelineAiConfig.BASE_URL}/${PipelineAiConfig.MODEL_ID}:generateContent"
        var lastError: GeminiCallException? = null
        var spentIn = 0
        var spentOut = 0

        for (attempt in 1..MAX_ATTEMPTS) {
            val http = try {
                httpPost(url, keyRef.key, body, req.readTimeoutMs)
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                lastError = GeminiCallException("[${req.tag}] Сетевая ошибка: ${e.message}", FailureKind.NETWORK)
                Log.w(TAG, "${req.tag}: network error attempt $attempt: ${e.message}")
                if (attempt < MAX_ATTEMPTS) delay(backoff(attempt))
                continue
            }

            if (http.code in 200..299) {
                val parsed = parseSuccess(req.tag, http.body, attempt)
                parsed.onSuccess { return Result.success(it) }
                val err = parsed.exceptionOrNull() as? GeminiCallException
                    ?: GeminiCallException("[${req.tag}] ${parsed.exceptionOrNull()?.message}", FailureKind.PARSE)
                spentIn += err.spentInputTokens
                spentOut += err.spentOutputTokens
                lastError = err
                // Пустой ответ — модель иногда «молчит», одна повторная попытка оправдана.
                // TRUNCATED / BLOCKED не ретраим: тот же запрос даст тот же результат.
                if (err.kind == FailureKind.EMPTY && attempt < 2) continue
                return Result.failure(withSpent(err, spentIn, spentOut))
            }

            val apiMessage = extractApiError(http.body)
            when (http.code) {
                429 -> {
                    lastError = GeminiCallException(
                        "[${req.tag}] Лимит запросов Gemini (429): $apiMessage",
                        FailureKind.RATE_LIMIT, 429
                    )
                    if (keyRef.rotatorIndex >= 0) {
                        val next = keyRotator.burnAndRotate(keyRef.rotatorIndex)
                        if (next != null) {
                            Log.i(TAG, "${req.tag}: 429 → переключение на ключ ${if (next.second == 0) "A" else "B"}")
                            keyRef = KeyRef(next.first, next.second)
                            continue
                        }
                    }
                    if (attempt < MAX_ATTEMPTS) delay(backoff(attempt) * 2)
                }
                500, 502, 503, 504 -> {
                    lastError = GeminiCallException(
                        "[${req.tag}] Сервер Gemini недоступен (${http.code}): $apiMessage",
                        FailureKind.SERVER, http.code
                    )
                    if (attempt < MAX_ATTEMPTS) delay(backoff(attempt))
                }
                401, 403 -> return Result.failure(withSpent(GeminiCallException(
                    "[${req.tag}] Ключ Gemini отклонён (${http.code}): $apiMessage",
                    FailureKind.AUTH, http.code
                ), spentIn, spentOut))
                404 -> return Result.failure(withSpent(GeminiCallException(
                    "[${req.tag}] Модель ${PipelineAiConfig.MODEL_ID} недоступна для этого ключа (404): $apiMessage",
                    FailureKind.BAD_REQUEST, 404
                ), spentIn, spentOut))
                else -> return Result.failure(withSpent(GeminiCallException(
                    "[${req.tag}] Ошибка запроса (${http.code}): $apiMessage",
                    FailureKind.BAD_REQUEST, http.code
                ), spentIn, spentOut))
            }
        }

        return Result.failure(withSpent(
            lastError ?: GeminiCallException("[${req.tag}] Неизвестная ошибка", FailureKind.NETWORK),
            spentIn, spentOut
        ))
    }

    // ═══════════════════════════════════════════════════════════════════════
    // INTERNAL
    // ═══════════════════════════════════════════════════════════════════════

    private fun withSpent(e: GeminiCallException, inTok: Int, outTok: Int): GeminiCallException =
        if (inTok == 0 && outTok == 0) e
        else GeminiCallException(e.message ?: "", e.kind, e.httpCode, inTok, outTok)

    private fun backoff(attempt: Int): Long = BASE_BACKOFF_MS * (1L shl (attempt - 1).coerceAtMost(4))

    private suspend fun resolveKey(): KeyRef? {
        keyRotator.currentKey()?.let { return KeyRef(it.first, it.second) }
        val fallbacks = listOf(
            runCatching { secureSettings.getActiveGeminiApiKey().first() }.getOrNull(),
            runCatching { secureSettings.getGeminiApiKey().first() }.getOrNull(),
            runCatching { appSettings.geminiApiKey.first() }.getOrNull()
        )
        fallbacks.firstOrNull { !it.isNullOrBlank() }?.let { return KeyRef(it, -1) }
        // Оба ключа Pipeline «на остывании» после 429, а основного ключа нет:
        // поминутный лимит часто уже восстановился — пробуем активный ключ ещё раз.
        val a = runCatching { secureSettings.pipelineKeyA.first() }.getOrNull().orEmpty()
        val b = runCatching { secureSettings.pipelineKeyB.first() }.getOrNull().orEmpty()
        val active = runCatching { secureSettings.pipelineActiveKeyIndex.first() }.getOrNull() ?: 0
        return when {
            active == 1 && b.isNotBlank() -> KeyRef(b, 1)
            a.isNotBlank() -> KeyRef(a, 0)
            b.isNotBlank() -> KeyRef(b, 1)
            else -> null
        }
    }

    private fun buildBody(req: Request): JsonObject = buildJsonObject {
        put("systemInstruction", buildJsonObject {
            put("parts", JsonArray(listOf(buildJsonObject { put("text", req.systemPrompt) })))
        })
        put("contents", JsonArray(listOf(buildJsonObject {
            put("role", "user")
            put("parts", JsonArray(listOf(buildJsonObject { put("text", req.userMessage) })))
        })))
        put("generationConfig", buildJsonObject {
            put("maxOutputTokens", req.maxOutputTokens.coerceAtMost(PipelineAiConfig.MODEL_MAX_OUTPUT))
            put("thinkingConfig", buildJsonObject {
                put("thinkingLevel", JsonPrimitive(req.thinking.apiValue))
            })
            if (req.responseJsonSchema != null) {
                put("responseMimeType", "application/json")
                put("responseJsonSchema", req.responseJsonSchema)
            } else {
                put("responseMimeType", "text/plain")
            }
        })
    }

    /**
     * Блокирующий POST, который можно отменить: при отмене корутины соединение
     * закрывается, и висящий read() сразу падает.
     */
    private suspend fun httpPost(url: String, apiKey: String, body: String, readTimeoutMs: Int): HttpResult =
        coroutineScope {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("x-goog-api-key", apiKey)
                connectTimeout = PipelineAiConfig.CONNECT_TIMEOUT_MS
                readTimeout = readTimeoutMs
                doOutput = true
                doInput = true
            }
            val call = async(Dispatchers.IO) {
                conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
                val code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                HttpResult(code, text)
            }
            try {
                call.await()
            } catch (e: CancellationException) {
                runCatching { conn.disconnect() }
                throw e
            } finally {
                runCatching { conn.disconnect() }
            }
        }

    internal fun parseSuccess(tag: String, body: String, attempt: Int): Result<Response> {
        val root = try {
            json.parseToJsonElement(body).jsonObject
        } catch (e: Exception) {
            return Result.failure(GeminiCallException("[$tag] Некорректный JSON ответа: ${e.message}", FailureKind.PARSE))
        }

        val usage = root["usageMetadata"] as? JsonObject
        val inTok = usage?.get("promptTokenCount")?.jsonPrimitive?.intOrNull ?: 0
        val outTok = usage?.get("candidatesTokenCount")?.jsonPrimitive?.intOrNull ?: 0
        val thinkTok = usage?.get("thoughtsTokenCount")?.jsonPrimitive?.intOrNull ?: 0

        val blockReason = (root["promptFeedback"] as? JsonObject)
            ?.get("blockReason")?.jsonPrimitive?.contentOrNull
        if (blockReason != null) {
            return Result.failure(GeminiCallException(
                "[$tag] Запрос заблокирован фильтром Gemini: $blockReason",
                FailureKind.BLOCKED, 200, inTok, outTok + thinkTok
            ))
        }

        val candidate = (root["candidates"] as? JsonArray)?.firstOrNull() as? JsonObject
            ?: return Result.failure(GeminiCallException(
                "[$tag] Ответ без candidates", FailureKind.EMPTY, 200, inTok, outTok + thinkTok
            ))

        val finishReason = candidate["finishReason"]?.jsonPrimitive?.contentOrNull ?: "UNKNOWN"
        val parts = (candidate["content"] as? JsonObject)?.get("parts") as? JsonArray
        val text = parts.orEmpty()
            .mapNotNull { it as? JsonObject }
            .filter { it["thought"]?.jsonPrimitive?.booleanOrNull != true }
            .joinToString("") { it["text"]?.jsonPrimitive?.contentOrNull ?: "" }

        when (finishReason) {
            "STOP" -> Unit
            "MAX_TOKENS" -> return Result.failure(GeminiCallException(
                "[$tag] Ответ модели ОБРЕЗАН по лимиту токенов (MAX_TOKENS) — результат отброшен",
                FailureKind.TRUNCATED, 200, inTok, outTok + thinkTok
            ))
            "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII", "LANGUAGE" ->
                return Result.failure(GeminiCallException(
                    "[$tag] Генерация остановлена фильтром Gemini ($finishReason)",
                    FailureKind.BLOCKED, 200, inTok, outTok + thinkTok
                ))
            else -> return Result.failure(GeminiCallException(
                "[$tag] Генерация завершилась нештатно ($finishReason)",
                FailureKind.EMPTY, 200, inTok, outTok + thinkTok
            ))
        }

        if (text.isBlank()) {
            return Result.failure(GeminiCallException(
                "[$tag] Модель вернула пустой ответ", FailureKind.EMPTY, 200, inTok, outTok + thinkTok
            ))
        }

        return Result.success(Response(text, inTok, outTok, thinkTok, finishReason, attempt))
    }

    private fun extractApiError(body: String): String = try {
        json.parseToJsonElement(body).jsonObject["error"]?.jsonObject
            ?.get("message")?.jsonPrimitive?.contentOrNull?.take(240)
            ?: body.take(240)
    } catch (_: Exception) {
        body.take(240)
    }
}