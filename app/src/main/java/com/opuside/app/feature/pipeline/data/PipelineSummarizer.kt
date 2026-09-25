package com.opuside.app.feature.pipeline.data

import android.util.Log
import com.opuside.app.core.ai.GeminiModelConfig.GeminiModel
import com.opuside.app.core.data.AppSettings
import com.opuside.app.core.security.SecureSettingsDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 📊 PIPELINE SUMMARIZER v3.2 (High-Speed & CoT Protected)
 *
 * Отвечает за генерацию технического русскоязычного отчёта о результатах выполнения конвейера.
 */
@Singleton
class PipelineSummarizer @Inject constructor(
    private val secureSettings: SecureSettingsDataStore,
    private val appSettings: AppSettings,
    private val keyRotator: PipelineKeyRotator
) {
    companion object {
        private const val TAG = "PipelineSummarizer"
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
        private const val MAX_OUTPUT_TOKENS = 4096

        private val SUMMARIZER_PROMPT = """
You are a technical pipeline reporter. Summarize the changes made across files clearly in Russian markdown.
Start with: ✅ Pipeline complete (or 🟡 / 🔴 depending on task statuses).

Sections:
1. Краткий итог (1 абзац)
2. Обработанные файлы:
   - ➕ Созданные
   - ✏️ Изменённые
   - 🗑 Удалённые
3. Ошибки (если есть)

Output ONLY the report text in Russian. No code blocks, no JSON.
""".trimIndent()
    }

    suspend fun summarize(
        userPrompt: String,
        tasks: List<FileTask>,
        totalCostEur: Double,
        totalTokens: Int,
        modelApiId: String? = null
    ): String = withContext(Dispatchers.IO) {
        try {
            val model = GeminiModel.fromModelId(modelApiId ?: "") ?: GeminiModel.getDefault()
            val apiKey = keyRotator.currentKey()?.first
                ?: secureSettings.getActiveGeminiApiKey().first().ifBlank { null }
                ?: secureSettings.getGeminiApiKey().first().ifBlank { null }
                ?: appSettings.geminiApiKey.first().ifBlank { null }

            if (apiKey.isNullOrBlank()) {
                Log.w(TAG, "API ключ не найден — используем локальный fallback-отчёт")
                return@withContext fallbackReport(tasks, totalCostEur, totalTokens)
            }

            val userMessage = buildString {
                appendLine("═══ ORIGINAL USER PROMPT ═══")
                appendLine(userPrompt.take(1000))
                appendLine()
                appendLine("═══ PIPELINE RESULTS ═══")
                appendLine("Tasks: ${tasks.size}")
                appendLine("Success: ${tasks.count { it.status == TaskStatus.SUCCESS || it.status == TaskStatus.NO_CHANGES_NEEDED }}")
                appendLine("Failed: ${tasks.count { it.status == TaskStatus.FAILED_FINAL }}")
                appendLine()
                appendLine("═══ TASK DETAILS ═══")
                tasks.forEach { t ->
                    appendLine("• [${t.operation}] ${t.filePath}: ${t.status} ${if (t.commitSha != null) "(${t.commitSha.take(7)})" else ""}")
                    if (t.lastError != null) appendLine("  Error: ${t.lastError.take(150)}")
                }
            }

            callOnce(apiKey, model, userMessage) ?: fallbackReport(tasks, totalCostEur, totalTokens)
        } catch (e: Exception) {
            Log.w(TAG, "Summarizer failed: ${e.message} — fallback to local report")
            fallbackReport(tasks, totalCostEur, totalTokens)
        }
    }

    private fun callOnce(apiKey: String, model: GeminiModel, userMessage: String): String? {
        var conn: HttpURLConnection? = null
        return try {
            val url = "$BASE_URL/${model.modelId}:generateContent"
            val body = buildJsonObject {
                put("systemInstruction", buildJsonObject {
                    put("parts", JsonArray(listOf(buildJsonObject { put("text", SUMMARIZER_PROMPT) })))
                })
                put("contents", JsonArray(listOf(buildJsonObject {
                    put("role", "user")
                    put("parts", JsonArray(listOf(buildJsonObject { put("text", userMessage) })))
                })))
                put("generationConfig", buildJsonObject {
                    put("maxOutputTokens", MAX_OUTPUT_TOKENS)
                    // Оптимизировано: LOW thinking исключает долгие рассуждения
                    put("thinkingConfig", buildJsonObject {
                        put("thinkingLevel", JsonPrimitive("LOW"))
                    })
                })
            }

            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("x-goog-api-key", apiKey)
                connectTimeout = 15_000
                readTimeout = 30_000
                doOutput = true
            }

            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            if (conn.responseCode !in 200..299) return null

            val res = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
            val json = Json.parseToJsonElement(res).jsonObject
            val candidate = json["candidates"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null
            val parts = candidate["content"]?.jsonObject?.get("parts")?.jsonArray ?: return null

            parts.filter { it.jsonObject["thought"]?.jsonPrimitive?.booleanOrNull != true }
                .joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull ?: "" }.trim()
        } catch (_: Exception) {
            null
        } finally {
            conn?.disconnect()
        }
    }

    private fun fallbackReport(tasks: List<FileTask>, cost: Double, tokens: Int): String = buildString {
        val success = tasks.filter { it.status == TaskStatus.SUCCESS || it.status == TaskStatus.NO_CHANGES_NEEDED }
        val failed = tasks.filter { it.status == TaskStatus.FAILED_FINAL }

        appendLine(
            if (failed.isEmpty()) "✅ Пайплайн завершён: ${success.size}/${tasks.size} успешно"
            else "🟡 Пайплайн завершён с ошибками: ${success.size}/${tasks.size} успешно, ${failed.size} сбоев"
        )
        appendLine("\n**Обработанные файлы:**")
        tasks.forEach { t ->
            val conflict = if (t.resolvedConflict) " (авто-разрешён)" else ""
            val commit = if (t.commitSha != null) " [${t.commitSha.take(7)}]" else ""
            appendLine("• ${t.operation.emoji} `${t.filePath}` — ${t.status.name}$commit$conflict")
            if (t.lastError != null) {
                appendLine("  Причина: ${t.lastError.take(150)}")
            }
        }
        appendLine("\nРасход: €${String.format(java.util.Locale.US, "%.4f", cost)} ($tokens токенов)")
    }
}