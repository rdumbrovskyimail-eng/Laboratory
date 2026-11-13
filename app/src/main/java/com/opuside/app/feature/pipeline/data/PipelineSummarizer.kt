package com.opuside.app.feature.pipeline.data

import android.util.Log
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 📊 PIPELINE SUMMARIZER v4.0
 *
 * Русскоязычный итоговый отчёт. Модель — только [PipelineAiConfig.MODEL_ID]
 * (thinking = minimal). При любой ошибке — детерминированный локальный отчёт.
 */
@Singleton
class PipelineSummarizer @Inject constructor(
    private val client: PipelineGeminiClient
) {
    companion object {
        private const val TAG = "PipelineSummarizer"

        private val SUMMARIZER_PROMPT = """
You are a technical pipeline reporter. Summarize the results in Russian, plain text with simple markdown.
Start with exactly one of: "✅ Pipeline завершён", "🟡 Pipeline завершён частично", "🔴 Pipeline не выполнен" — based on task statuses.

Sections:
1. Краткий итог (1 абзац, по фактам из данных)
2. Обработанные файлы, сгруппированные: ➕ Созданные, ✏️ Изменённые, 🗑 Удалённые, ⚪ Без изменений
3. Ошибки (если есть) — файл и причина простыми словами

Use ONLY facts from the data. Do not invent files or changes. No code blocks, no JSON.
""".trimIndent()
    }

    data class Summary(val text: String, val tokensUsed: Int, val costEur: Double)

    suspend fun summarize(
        userPrompt: String,
        tasks: List<FileTask>,
        totalCostEur: Double,
        totalTokens: Int
    ): Summary {
        val userMessage = buildString {
            appendLine("═══ ORIGINAL USER PROMPT (first 1500 chars) ═══")
            appendLine(userPrompt.take(1500))
            appendLine()
            appendLine("═══ PIPELINE RESULTS ═══")
            appendLine("Tasks: ${tasks.size}")
            appendLine("Success: ${tasks.count { it.status == TaskStatus.SUCCESS }}")
            appendLine("No changes needed: ${tasks.count { it.status == TaskStatus.NO_CHANGES_NEEDED }}")
            appendLine("Failed: ${tasks.count { it.status == TaskStatus.FAILED_FINAL }}")
            appendLine()
            appendLine("═══ TASK DETAILS ═══")
            tasks.forEach { t ->
                append("• [${t.operation}] ${t.filePath}: ${t.status}")
                t.commitSha?.takeIf { it.length >= 7 && !it.startsWith("pending") }?.let { append(" (${it.take(7)})") }
                appendLine()
                t.resultSummary?.let { appendLine("  Done: ${it.take(200)}") }
                t.warning?.let { appendLine("  Planner warning: ${it.take(200)}") }
                t.lastError?.takeIf { t.status == TaskStatus.FAILED_FINAL }?.let { appendLine("  Error: ${it.take(200)}") }
            }
        }

        val response = client.generate(PipelineGeminiClient.Request(
            tag = "summary",
            systemPrompt = SUMMARIZER_PROMPT,
            userMessage = userMessage,
            thinking = PipelineAiConfig.SUMMARY_THINKING,
            maxOutputTokens = PipelineAiConfig.SUMMARY_MAX_OUTPUT,
            readTimeoutMs = PipelineAiConfig.SUMMARY_READ_TIMEOUT_MS
        )).getOrElse { e ->
            Log.w(TAG, "Summarizer failed: ${e.message} — fallback to local report")
            val spent = e as? PipelineGeminiClient.GeminiCallException
            return Summary(
                fallbackReport(tasks, totalCostEur, totalTokens),
                spent?.let { it.spentInputTokens + it.spentOutputTokens } ?: 0,
                spent?.spentCostEur ?: 0.0
            )
        }

        val text = response.text.trim() +
                "\n\nМодель: ${PipelineAiConfig.DISPLAY_NAME} · Расход: €${String.format(Locale.US, "%.4f", totalCostEur + response.costEur)} " +
                "(${totalTokens + response.totalTokens} токенов)"
        return Summary(text, response.totalTokens, response.costEur)
    }

    fun fallbackReport(tasks: List<FileTask>, cost: Double, tokens: Int): String = buildString {
        val success = tasks.filter { it.status == TaskStatus.SUCCESS || it.status == TaskStatus.NO_CHANGES_NEEDED }
        val failed = tasks.filter { it.status == TaskStatus.FAILED_FINAL }

        appendLine(
            when {
                failed.isEmpty() -> "✅ Pipeline завершён: ${success.size}/${tasks.size} успешно"
                success.isEmpty() -> "🔴 Pipeline не выполнен: ${failed.size}/${tasks.size} с ошибками"
                else -> "🟡 Pipeline завершён частично: ${success.size}/${tasks.size} успешно, ${failed.size} сбоев"
            }
        )
        appendLine("\nОбработанные файлы:")
        tasks.forEach { t ->
            val commit = t.commitSha?.takeIf { it.length >= 7 && !it.startsWith("pending") }?.let { " [${it.take(7)}]" } ?: ""
            appendLine("• ${t.operation.emoji} ${t.filePath} — ${t.status.emoji} ${t.status.name}$commit")
            t.resultSummary?.let { appendLine("  ${it.take(150)}") }
            if (t.status == TaskStatus.FAILED_FINAL && t.lastError != null) {
                appendLine("  Причина: ${t.lastError.take(200)}")
            }
        }
        appendLine("\nМодель: ${PipelineAiConfig.DISPLAY_NAME} · Расход: €${String.format(Locale.US, "%.4f", cost)} ($tokens токенов)")
    }
}