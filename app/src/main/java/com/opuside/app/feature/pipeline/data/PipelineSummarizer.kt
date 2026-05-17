package com.opuside.app.feature.pipeline.data

import android.util.Log
import com.opuside.app.core.security.SecureSettingsDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PipelineSummarizer @Inject constructor(
    private val secureSettings: SecureSettingsDataStore,
    private val keyRotator: PipelineKeyRotator
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
2. SUCCESSFUL CHANGES (bulleted list, Russian)
3. FAILED FILES (bulleted list, Russian, only if any failed)

Russian language throughout. Markdown formatting. 300-600 words.
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
            // Получаем активный ключ через ротатор
            var currentKeyInfo = keyRotator.currentKey()
                ?: return@withContext fallbackReport(tasks, totalCostEur, totalTokens)

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

            // Цикл попыток с ротацией ключей при 429
            var attempts = 0
            while (attempts < 2) {
                val (apiKey, idx) = currentKeyInfo!!
                val result = callOnce(apiKey, userMessage)
                if (result != null) return@withContext result

                // Запрос упал — пробуем понять, был ли это 429
                val next = keyRotator.burnAndRotate(idx)
                if (next == null) break
                currentKeyInfo = next
                attempts++
            }
            fallbackReport(tasks, totalCostEur, totalTokens)
        } catch (e: Exception) {
            Log.w(TAG, "Summarizer failed: ${e.message}")
            fallbackReport(tasks, totalCostEur, totalTokens)
        }
    }

    /** Один HTTP-запрос к Gemini. null = ошибка (включая 429). */
    private fun callOnce(apiKey: String, userMessage: String): String? {
        var connection: HttpURLConnection? = null
        return try {
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

            connection = (URL("$BASE_URL/$MODEL:generateContent").openConnection() as HttpURLConnection).apply {
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
                Log.w(TAG, "Summarizer HTTP ${connection.responseCode}")
                return null
            }

            val responseBody = BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8))
                .use { it.readText() }
            val json = Json.parseToJsonElement(responseBody).jsonObject
            val candidate = json["candidates"]?.jsonArray?.firstOrNull() ?: return null
            val finishReason = candidate.jsonObject["finishReason"]?.jsonPrimitive?.contentOrNull
            if (finishReason != null && finishReason != "STOP" && finishReason != "MAX_TOKENS") return null

            val text = candidate.jsonObject["content"]?.jsonObject
                ?.get("parts")?.jsonArray
                ?.joinToString("") { part ->
                    part.jsonObject["text"]?.jsonPrimitive?.contentOrNull ?: ""
                }
                ?.takeIf { it.isNotBlank() }
                ?: return null

            text.trim()
        } catch (e: Exception) {
            Log.w(TAG, "callOnce failed: ${e.message}")
            null
        } finally {
            try { connection?.disconnect() } catch (_: Exception) {}
        }
    }

    private fun fallbackReport(
        tasks: List<FileTask>, totalCostEur: Double, totalTokens: Int
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
        appendLine("  • Создано: ${tasks.count { it.operation == TaskOperation.CREATE && it.status == TaskStatus.SUCCESS }}")
        appendLine("  • Изменено: ${tasks.count { it.operation == TaskOperation.MODIFY && it.status == TaskStatus.SUCCESS }}")
        appendLine("  • Без изменений: ${noChanges.size}")
        appendLine("  • Провалено: ${failed.size}")
        appendLine("Стоимость: €${String.format(java.util.Locale.US, "%.4f", totalCostEur)}")
        appendLine("Токенов: $totalTokens")
        appendLine()
        success.filter { it.operation == TaskOperation.CREATE }.takeIf { it.isNotEmpty() }?.let {
            appendLine("**➕ Созданы файлы**")
            for (t in it) appendLine("• `${t.filePath}` → `${t.commitSha?.take(8) ?: "—"}`")
            appendLine()
        }
        success.filter { it.operation == TaskOperation.MODIFY }.takeIf { it.isNotEmpty() }?.let {
            appendLine("**✏️ Изменены файлы**")
            for (t in it) {
                val conflict = if (t.resolvedConflict) " ⚠️ auto-resolved" else ""
                appendLine("• `${t.filePath}` → `${t.commitSha?.take(8) ?: "—"}`$conflict")
            }
            appendLine()
        }
        if (failed.isNotEmpty()) {
            appendLine("**Провалены**")
            for (t in failed) {
                appendLine("• `${t.filePath}`")
                appendLine("  Код: `${t.errorCode?.name ?: "UNKNOWN"}` — ${t.errorCode?.displayName ?: ""}")
                t.lastError?.let { appendLine("  Ошибка: ${it.take(200)}") }
            }
        }
    }
}