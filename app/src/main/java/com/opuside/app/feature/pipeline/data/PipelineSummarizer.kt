package com.opuside.app.feature.pipeline.data

import android.util.Log
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
 * ═══════════════════════════════════════════════════════════════════════════
 * PIPELINE SUMMARIZER v1.0
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Третий Gemini-вызов в конвейере. Получает на вход результаты выполнения
 * всех задач и возвращает человекочитаемый отчёт на русском языке для
 * экрана "Результат".
 *
 * Это не критичный компонент — если он упадёт, ViewModel сгенерирует
 * fallback-отчёт обычной конкатенацией текста.
 *
 * Стоимость: ~3K input + ~1K output = €0.00002 за вызов.
 */
@Singleton
class PipelineSummarizer @Inject constructor(
    private val secureSettings: SecureSettingsDataStore
) {
    companion object {
        private const val TAG = "PipelineSummarizer"
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
        private const val MODEL = "gemini-3.1-flash-lite-preview"
        private const val MAX_OUTPUT_TOKENS = 4096

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
   - What was the overall goal of this pipeline run (infer from task names)
   - How many files were modified successfully
   - How many were unchanged (no edits needed)
   - How many failed
   - Total cost in EUR

2. SUCCESSFUL CHANGES (bulleted list, Russian)
   - For each successful file: brief description of what was changed
   - Include commit SHA (first 8 chars)
   - Mark "auto-resolved conflict" if applicable

3. FAILED FILES (bulleted list, Russian, only if any failed)
   - For each failed file:
     • File path
     • Error code and what it means
     • Last error message (if useful)
     • Suggested action for the user (concrete, actionable)

═══ STYLE RULES ═══

- Russian language throughout
- Use markdown formatting: **bold** for emphasis, `code` for paths/SHAs
- Be concise but specific
- If a file had a NOT_FOUND_BLOCKS error, suggest verifying the AI
  understood the file structure correctly
- If a file had HTTP_409_UNRESOLVED, suggest checking for parallel edits
- If HTTP_429_RATE_LIMIT, suggest waiting and re-running
- Total report length: 300-600 words

OUTPUT only the report text. No JSON, no XML, no code fences.
""".trimIndent()
    }

    suspend fun summarize(
        userPrompt: String,
        tasks: List<FileTask>,
        totalCostEur: Double,
        totalTokens: Int
    ): String = withContext(Dispatchers.IO) {
        try {
            val apiKey = secureSettings.getGeminiApiKey().first()
            if (apiKey.isBlank()) {
                return@withContext fallbackReport(tasks, totalCostEur, totalTokens)
            }

            val userMessage = buildString {
                appendLine("═══ ORIGINAL USER PROMPT ═══")
                appendLine(userPrompt.take(2000))   // обрезаем чтобы не раздувать токены
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
                    task.errorCode?.let {
                        appendLine("Error code: ${it.name} (${it.displayName})")
                    }
                    task.lastError?.let {
                        appendLine("Last error: ${it.take(200)}")
                    }
                    if (task.operation == TaskOperation.CREATE) {
                        appendLine("Content size: ${task.newFileContent?.length ?: 0} characters")
                    } else {
                        appendLine("Instructions preview: ${task.instructions.take(150)}...")
                    }
                }
            }

            val requestBody = buildJsonObject {
                put("system_instruction", buildJsonObject {
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
                    put("temperature", 0.3)
                })
            }

            val url = "$BASE_URL/$MODEL:generateContent"
            var connection: HttpURLConnection? = null
            try {
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
                    Log.w(TAG, "Summarizer HTTP ${connection.responseCode} — using fallback")
                    return@withContext fallbackReport(tasks, totalCostEur, totalTokens)
                }

                val responseBody = BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8))
                    .use { it.readText() }
                val json = Json.parseToJsonElement(responseBody).jsonObject

                // finishReason check (SAFETY/RECITATION → пустой ответ)
                val candidate = json["candidates"]?.jsonArray?.firstOrNull()
                    ?: return@withContext fallbackReport(tasks, totalCostEur, totalTokens)
                val finishReason = candidate.jsonObject["finishReason"]?.jsonPrimitive?.contentOrNull
                if (finishReason != null && finishReason != "STOP" && finishReason != "MAX_TOKENS") {
                    Log.w(TAG, "Summarizer finishReason=$finishReason — using fallback")
                    return@withContext fallbackReport(tasks, totalCostEur, totalTokens)
                }

                val text = candidate.jsonObject["content"]?.jsonObject
                    ?.get("parts")?.jsonArray
                    ?.joinToString("") { part ->
                        part.jsonObject["text"]?.jsonPrimitive?.contentOrNull ?: ""
                    }
                    ?.takeIf { it.isNotBlank() }
                    ?: return@withContext fallbackReport(tasks, totalCostEur, totalTokens)

                text.trim()
            } finally {
                try { connection?.disconnect() } catch (_: Exception) {}
            }

        } catch (e: Exception) {
            Log.w(TAG, "Summarizer failed: ${e.message} — using fallback")
            fallbackReport(tasks, totalCostEur, totalTokens)
        }
    }

    /**
     * Fallback-отчёт: если AI-summarizer недоступен, генерируем простой текст.
     */
    private fun fallbackReport(
        tasks: List<FileTask>,
        totalCostEur: Double,
        totalTokens: Int
    ): String = buildString {
        val success = tasks.filter { it.status == TaskStatus.SUCCESS }
        val noChanges = tasks.filter { it.status == TaskStatus.NO_CHANGES_NEEDED }
        val failed = tasks.filter { it.status == TaskStatus.FAILED_FINAL }
        val headline = when {
            failed.isEmpty() && success.isNotEmpty() -> "✅ Pipeline complete"
            success.isEmpty() && failed.isNotEmpty() -> "🔴 Pipeline failed"
            else -> "🟡 Pipeline complete with errors"
        }

        appendLine("$headline: ${success.size + noChanges.size}/${tasks.size} success, ${failed.size} failed")
        appendLine()
        appendLine("**Сводка**")
        appendLine("Обработано задач: ${tasks.size}")
        appendLine("  • Создано новых файлов: ${tasks.count { it.operation == TaskOperation.CREATE && it.status == TaskStatus.SUCCESS }}")
        appendLine("  • Изменено существующих: ${tasks.count { it.operation == TaskOperation.MODIFY && it.status == TaskStatus.SUCCESS }}")
        appendLine("  • Без изменений: ${noChanges.size}")
        appendLine("  • Провалено: ${failed.size}")
        appendLine("Общая стоимость: €${String.format(java.util.Locale.US, "%.4f", totalCostEur)}")
        appendLine("Токенов потрачено: $totalTokens")
        appendLine()

        val createdSuccess = success.filter { it.operation == TaskOperation.CREATE }
        val modifiedSuccess = success.filter { it.operation == TaskOperation.MODIFY }

        if (createdSuccess.isNotEmpty()) {
            appendLine("**➕ Созданы новые файлы**")
            for (t in createdSuccess) {
                val sha = t.commitSha?.take(8) ?: "—"
                appendLine("• `${t.filePath}` → `$sha`")
            }
            appendLine()
        }

        if (modifiedSuccess.isNotEmpty()) {
            appendLine("**✏️ Изменены существующие файлы**")
            for (t in modifiedSuccess) {
                val sha = t.commitSha?.take(8) ?: "—"
                val conflict = if (t.resolvedConflict) " ⚠️ auto-resolved" else ""
                appendLine("• `${t.filePath}` → `$sha`$conflict")
            }
            appendLine()
        }

        if (noChanges.isNotEmpty()) {
            appendLine("**Без изменений**")
            for (t in noChanges) {
                appendLine("• `${t.filePath}` — AI не нашёл что менять")
            }
            appendLine()
        }

        if (failed.isNotEmpty()) {
            appendLine("**Провалены окончательно**")
            for (t in failed) {
                appendLine("• `${t.filePath}`")
                appendLine("  Код: `${t.errorCode?.name ?: "UNKNOWN"}` — ${t.errorCode?.displayName ?: "неизвестная ошибка"}")
                t.lastError?.let { appendLine("  Ошибка: ${it.take(200)}") }
                appendLine("  Попыток: ${t.attempts}")
            }
        }
    }
}
