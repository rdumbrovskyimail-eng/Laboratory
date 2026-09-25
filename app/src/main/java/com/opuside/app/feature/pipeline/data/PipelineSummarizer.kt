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
 * 📊 PIPELINE SUMMARIZER v3.0 (Flash-Lite Powered)
 *
 * Отвечает за генерацию технического русскоязычного отчёта о результатах
 * выполнения конвейера (сводка изменений, список файлов, ошибки).
 *
 * Особенности:
 * - Модели: 3.5 Flash-Lite (LOW thinking) или 3.1 Flash-Lite (MEDIUM thinking)
 * - Автоматическая фильтрация thought-блоков модели
 * - Каскадный резолвер API-ключей без ложных падений
 * - Подробный локальный fallback-генератор на случай отсутствия сети
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
        private const val MAX_OUTPUT_TOKENS = 8192

        private val SUMMARIZER_PROMPT = """
You are a technical pipeline reporter. You are given the results of an
automated code modification pipeline that processed multiple files using
AI editing. Your job is to produce a clear, professional final report
in Russian language for the user.

═══ REPORT STRUCTURE ═══

Start with a one-line headline:
  ✅ Pipeline complete: X/Y success, Z failed
  (or 🟡 partial / 🔴 fully failed depending on numbers)

Then THREE sections:

1. SUMMARY (one paragraph, Russian)
2. SUCCESSFUL CHANGES (bulleted list, Russian)
   Group by operation type: ➕ Созданы, ✏️ Изменены, 🗑 Удалены.
3. FAILED FILES (bulleted list, Russian, only if any failed)

Russian language throughout. Markdown formatting. 300-600 words.
OUTPUT only the report text. No JSON, no XML, no code fences.
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
            val effectiveModel = GeminiModel.fromModelId(modelApiId ?: "") ?: GeminiModel.getDefault()
            val thinkingLevel = effectiveModel.forcedThinkingLevel.apiName // LOW для 3.5, MEDIUM для 3.1

            // Каскадный подбор API-ключа
            val rotatorKeyInfo = keyRotator.currentKey()
            var currentApiKey = rotatorKeyInfo?.first
                ?: secureSettings.getActiveGeminiApiKey().first().ifBlank { null }
                ?: secureSettings.getGeminiApiKey().first().ifBlank { null }
                ?: appSettings.geminiApiKey.first().ifBlank { null }

            if (currentApiKey.isNullOrBlank()) {
                Log.w(TAG, "API ключ не найден — используем локальный fallback-отчёт")
                return@withContext fallbackReport(tasks, totalCostEur, totalTokens)
            }

            var currentRotatorIdx = rotatorKeyInfo?.second ?: -1

            val userMessage = buildString {
                appendLine("═══ ORIGINAL USER PROMPT ═══")
                appendLine(userPrompt.take(2000))
                if (userPrompt.length > 2000) appendLine("... (truncated)")
                appendLine()
                appendLine("═══ PIPELINE RESULTS ═══")
                appendLine("Total tasks: ${tasks.size}")
                appendLine("Success: ${tasks.count { it.status == TaskStatus.SUCCESS }}")
                appendLine("No changes: ${tasks.count { it.status == TaskStatus.NO_CHANGES_NEEDED }}")
                appendLine("Failed: ${tasks.count { it.status == TaskStatus.FAILED_FINAL }}")
                appendLine("Total cost: €${String.format(java.util.Locale.US, "%.4f", totalCostEur)}")
                appendLine("Total tokens: $totalTokens")
                appendLine()
                appendLine("═══ TASK DETAILS ═══")
                for ((idx, task) in tasks.withIndex()) {
                    appendLine()
                    appendLine("--- Task ${idx + 1} ---")
                    appendLine("Operation: ${task.operation.name}")
                    appendLine("File: ${task.filePath}")
                    task.packageName?.let { appendLine("Package: $it") }
                    appendLine("Status: ${task.status.name}")
                    appendLine("Attempts: ${task.attempts}")
                    task.commitSha?.let { appendLine("Commit: ${it.take(12)}") }
                    if (task.resolvedConflict) appendLine("⚠️ Conflict auto-resolved")
                    task.errorCode?.let { appendLine("Error: ${it.name} (${it.displayName})") }
                    task.lastError?.let { appendLine("Last error: ${it.take(200)}") }
                    if (task.operation == TaskOperation.CREATE) {
                        appendLine("Content size: ${task.newFileContent?.length ?: 0} chars")
                    } else {
                        appendLine("Instructions preview: ${task.instructions.take(150)}...")
                    }
                }
            }

            var attempts = 0
            while (attempts < 2) {
                val result = callOnce(currentApiKey!!, effectiveModel, thinkingLevel, userMessage)
                if (result != null) return@withContext result

                if (currentRotatorIdx >= 0) {
                    val next = keyRotator.burnAndRotate(currentRotatorIdx)
                    if (next != null) {
                        currentApiKey = next.first
                        currentRotatorIdx = next.second
                        attempts++
                        continue
                    }
                }
                break
            }

            fallbackReport(tasks, totalCostEur, totalTokens)
        } catch (e: Exception) {
            Log.w(TAG, "Summarizer failed: ${e.message} — fallback to local report")
            fallbackReport(tasks, totalCostEur, totalTokens)
        }
    }

    private fun callOnce(
        apiKey: String,
        model: GeminiModel,
        thinkingLevel: String,
        userMessage: String
    ): String? {
        var connection: HttpURLConnection? = null
        return try {
            val url = "$BASE_URL/${model.modelId}:generateContent"

            val requestBody = buildJsonObject {
                put("systemInstruction", buildJsonObject {
                    put("parts", JsonArray(listOf(
                        buildJsonObject { put("text", SUMMARIZER_PROMPT) }
                    )))
                })
                put("contents", JsonArray(listOf(
                    buildJsonObject {
                        put("role", "user")
                        put("parts", JsonArray(listOf(
                            buildJsonObject { put("text", userMessage) }
                        )))
                    }
                )))
                put("generationConfig", buildJsonObject {
                    put("maxOutputTokens", MAX_OUTPUT_TOKENS)
                    put("temperature", 0.2)
                    put("thinkingConfig", buildJsonObject {
                        put("thinkingLevel", JsonPrimitive(thinkingLevel))
                    })
                })
            }

            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("x-goog-api-key", apiKey)
                setRequestProperty("Accept", "application/json")
                connectTimeout = 30_000
                readTimeout = 60_000
                doOutput = true
                doInput = true
            }

            connection.outputStream.use {
                it.write(requestBody.toString().toByteArray(Charsets.UTF_8))
            }

            if (connection.responseCode !in 200..299) {
                Log.w(TAG, "Summarizer HTTP error: ${connection.responseCode}")
                return null
            }

            val responseBody = BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8))
                .use { it.readText() }
            val json = Json.parseToJsonElement(responseBody).jsonObject
            val candidate = json["candidates"]?.jsonArray?.firstOrNull()?.jsonObject ?: return null

            val finishReason = candidate["finishReason"]?.jsonPrimitive?.contentOrNull
            if (finishReason != null && finishReason != "STOP" && finishReason != "MAX_TOKENS") {
                return null
            }

            val parts = candidate["content"]?.jsonObject?.get("parts")?.jsonArray ?: return null

            // КРИТИЧНО: фильтруем thought: true, забирая только финальный текст отчета
            val text = parts.filter { part ->
                part.jsonObject["thought"]?.jsonPrimitive?.booleanOrNull != true
            }.joinToString("") { part ->
                part.jsonObject["text"]?.jsonPrimitive?.contentOrNull ?: ""
            }.takeIf { it.isNotBlank() } ?: return null

            text.trim()
        } catch (e: Exception) {
            Log.w(TAG, "callOnce failed: ${e.message}")
            null
        } finally {
            try { connection?.disconnect() } catch (_: Exception) { }
        }
    }

    private fun fallbackReport(
        tasks: List<FileTask>,
        totalCostEur: Double,
        totalTokens: Int
    ): String = buildString {
        val success = tasks.filter { it.status == TaskStatus.SUCCESS }
        val noChanges = tasks.filter { it.status == TaskStatus.NO_CHANGES_NEEDED }
        val failed = tasks.filter { it.status == TaskStatus.FAILED_FINAL }
        val createdSuccess = success.filter { it.operation == TaskOperation.CREATE }
        val modifiedSuccess = success.filter { it.operation == TaskOperation.MODIFY }
        val deletedSuccess = success.filter { it.operation == TaskOperation.DELETE }

        val headline = when {
            failed.isEmpty() && success.isNotEmpty() -> "✅ Pipeline complete"
            success.isEmpty() && failed.isNotEmpty() -> "🔴 Pipeline failed"
            else -> "🟡 Pipeline complete with warnings"
        }

        appendLine("$headline: ${success.size + noChanges.size}/${tasks.size} успешно, ${failed.size} ошибок")
        appendLine()
        appendLine("**Сводка выполнения**")
        appendLine("Всего обработано задач: ${tasks.size}")
        appendLine("  • ➕ Создано: ${createdSuccess.size}")
        appendLine("  • ✏️ Изменено: ${modifiedSuccess.size}")
        appendLine("  • 🗑 Удалено: ${deletedSuccess.size}")
        appendLine("  • ⚪ Без изменений: ${noChanges.size}")
        appendLine("  • ❌ Ошибок: ${failed.size}")
        appendLine("Расход: €${String.format(java.util.Locale.US, "%.4f", totalCostEur)} (${totalTokens} токенов)")
        appendLine()

        if (createdSuccess.isNotEmpty()) {
            appendLine("**➕ Созданные файлы:**")
            for (t in createdSuccess) {
                appendLine("• `${t.filePath}` (коммит: `${t.commitSha?.take(8) ?: "—"}`)")
            }
            appendLine()
        }

        if (modifiedSuccess.isNotEmpty()) {
            appendLine("**✏️ Изменённые файлы:**")
            for (t in modifiedSuccess) {
                val conflict = if (t.resolvedConflict) " ⚠️ авто-разрешён конфликт" else ""
                appendLine("• `${t.filePath}` (коммит: `${t.commitSha?.take(8) ?: "—"}`$conflict)")
            }
            appendLine()
        }

        if (deletedSuccess.isNotEmpty()) {
            appendLine("**🗑 Удалённые файлы:**")
            for (t in deletedSuccess) {
                appendLine("• `${t.filePath}`")
            }
            appendLine()
        }

        if (failed.isNotEmpty()) {
            appendLine("**❌ Файлы с ошибками:**")
            for (t in failed) {
                appendLine("• `${t.filePath}`")
                appendLine("  Причина: ${t.errorCode?.displayName ?: "Неизвестная ошибка"}")
                t.lastError?.let { appendLine("  Лог: ${it.take(200)}") }
            }
        }
    }
}