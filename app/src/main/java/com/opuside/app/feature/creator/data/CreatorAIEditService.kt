package com.opuside.app.feature.creator.data

import android.util.Log
import com.opuside.app.core.data.AppSettings
import com.opuside.app.core.security.SecureSettingsDataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 🛠️ CREATOR AI EDIT SERVICE v3.0
 *
 * Специализированный сервис пофайловых AI-замен для редактора и пайплайна.
 *
 * Особенности:
 * - Модели строго: 3.5 Flash-Lite (LOW thinking) и 3.1 Flash-Lite (MEDIUM thinking)
 * - Автоматическая фильтрация частей "thought": true перед парсингом XML
 * - Вывод до 65 536 токенов без обрезки кода
 * - Единый каскадный резолвер API-ключей (Pipeline -> Settings -> AppSettings)
 * - Умный fuzzy/normalized матчинг для безошибочного применения правок
 */
@Singleton
class CreatorAIEditService @Inject constructor(
    private val appSettings: AppSettings,
    private val secureSettings: SecureSettingsDataStore,
    private val pipelineKeyRotator: com.opuside.app.feature.pipeline.data.PipelineKeyRotator
) {

    enum class AiModel(
        val displayName: String,
        val apiId: String,
        val badge: String,
        val costPerMInputUsd: Double,
        val costPerMOutputUsd: Double,
        val forcedThinkingLevel: String
    ) {
        GEMINI_3_5_FLASH_LITE(
            displayName = "Gemini 3.5 Flash-Lite",
            apiId = "gemini-3.5-flash-lite",
            badge = "⚡ G3.5 Lite",
            costPerMInputUsd = 0.30,
            costPerMOutputUsd = 2.50,
            forcedThinkingLevel = "LOW"
        ),
        GEMINI_3_1_FLASH_LITE(
            displayName = "Gemini 3.1 Flash-Lite",
            apiId = "gemini-3.1-flash-lite",
            badge = "💨 G3.1 Lite",
            costPerMInputUsd = 0.25,
            costPerMOutputUsd = 1.50,
            forcedThinkingLevel = "MEDIUM"
        );

        companion object {
            fun fromApiId(id: String?): AiModel {
                val clean = id?.trim()?.lowercase() ?: ""
                return when {
                    clean.contains("3.1") -> GEMINI_3_1_FLASH_LITE
                    else -> GEMINI_3_5_FLASH_LITE
                }
            }
        }
    }

    companion object {
        private const val TAG = "CreatorAIEdit"
        private const val GEMINI_API_BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
        private const val MAX_OUTPUT_TOKENS = 65536
        private const val LINE_NUMBER_THRESHOLD = 300

        private val SYSTEM_PROMPT = """
You are a PRECISION CODE EDITOR. Your ONLY job is to produce exact search/replace blocks for a given source file.

═══ RESPONSE FORMAT (MANDATORY, NO EXCEPTIONS) ═══

<edits>
<block>
<search>
exact lines copied from the original file
</search>
<replace>
new replacement lines
</replace>
</block>
</edits>
<summary>One-line description of all changes made</summary>

═══ CRITICAL RULES — VIOLATION = FAILURE ═══

RULE 1 — EXACT COPY:
The content inside <search> MUST be a CHARACTER-PERFECT copy from the original file.
- Preserve EVERY space, tab, newline, comma, semicolon, bracket.
- Do NOT fix typos, reformat, or alter ANYTHING inside <search>.
- Do NOT add or remove blank lines inside <search>.
- If the file has line number prefixes like "42| ", STRIP THEM — write only the raw code.

RULE 2 — UNIQUE CONTEXT:
Each <search> block MUST match EXACTLY ONE location in the file.
- Include 3-7 surrounding context lines to guarantee uniqueness.
- If a line like "}" or "return null" appears many times, include MORE lines above/below until the block is unique.
- NEVER use a <search> block that could match multiple locations.

RULE 3 — MINIMAL CHANGES:
- Change ONLY what the user asked for. Do NOT refactor, optimize, rename, or "improve" anything else.
- Do NOT touch imports, comments, formatting, or code outside the requested scope.

RULE 4 — MULTIPLE BLOCKS:
- Use SEPARATE <block> tags for changes in DIFFERENT parts of the file.
- Order blocks from TOP of file to BOTTOM.
- Blocks MUST NOT overlap (no shared lines between blocks).

RULE 5 — SPECIAL OPERATIONS:
- DELETE code: <search>code to remove</search> <replace></replace>
- INSERT AFTER line N: put line N in <search>, put line N + new code in <replace>.
- INSERT BEFORE line N: put line N-1 and line N in <search>, put line N-1 + new code + line N in <replace>.
- REPLACE entire function: include the full function signature + body in <search>.

RULE 6 — OUTPUT DISCIPLINE:
- Output ONLY the XML structure above. No markdown. No explanations. No apologies.
- No ```xml fences. No text before <edits>. No text after </summary>.
- If no changes are needed, return: <edits></edits><summary>No changes needed: [reason]</summary>
- NEVER output the entire file. ONLY the changed blocks.

RULE 7 — INDENTATION PRESERVATION:
- Match the EXACT indentation style of the surrounding code.

RULE 8 — LANGUAGE AWARENESS:
- Respect language syntax: matching brackets, semicolons, commas in lists.
- When removing a function, remove the ENTIRE function including annotations and docs above it.
""".trimIndent()
    }

    data class EditBlock(
        val search: String,
        val replace: String,
        val matchStatus: MatchStatus = MatchStatus.PENDING
    ) {
        enum class MatchStatus {
            PENDING, EXACT, NORMALIZED, FUZZY, LINE_RANGE, NOT_FOUND
        }
    }

    data class EditResult(
        val blocks: List<EditBlock>,
        val summary: String,
        val inputTokens: Int,
        val outputTokens: Int,
        val costEUR: Double,
        val model: AiModel
    )

    sealed class EditStatus {
        data object Idle : EditStatus()
        data object Processing : EditStatus()
        data class Success(val result: EditResult, val newContent: String) : EditStatus()
        data class Error(val message: String) : EditStatus()
    }

    data class ApplyResult(
        val newContent: String,
        val appliedBlocks: List<EditBlock>,
        val failedBlockNumbers: List<Int>,
        val totalApplied: Int,
        val totalFailed: Int
    ) {
        val isFullyApplied: Boolean get() = totalFailed == 0
        val statusMessage: String
            get() = when {
                totalFailed == 0 -> "Все $totalApplied блок(ов) применены успешно"
                totalApplied == 0 -> "Не удалось применить ни одного блока"
                else -> "Применено $totalApplied из ${totalApplied + totalFailed} блоков. " +
                        "Не найдены: ${failedBlockNumbers.joinToString(", ") { "#$it" }}"
            }
    }

    suspend fun processEdit(
        fileContent: String,
        fileName: String,
        instructions: String,
        model: AiModel = AiModel.GEMINI_3_5_FLASH_LITE,
        usePipelineKeys: Boolean = false,
        customModelApiId: String? = null,
        thinkingLevelOverride: String? = null
    ): Result<EditResult> = withContext(Dispatchers.IO) {
        try {
            val lineCount = fileContent.lines().size
            val useLineNumbers = lineCount > LINE_NUMBER_THRESHOLD
            val activeModel = if (customModelApiId != null) AiModel.fromApiId(customModelApiId) else model

            Log.d(TAG, "📤 AI Edit: $fileName ($lineCount строк, модель=${activeModel.displayName})")

            val systemPrompt = buildSystemPrompt(useLineNumbers)
            val userMessage = buildUserMessage(fileContent, fileName, instructions, useLineNumbers)

            // ── Каскадное получение API-ключа ─────────────────────────────
            var currentApiKey: String
            var currentKeyIdx = -1

            val rotatorKeyInfo = pipelineKeyRotator.currentKey()
            if (usePipelineKeys && rotatorKeyInfo != null) {
                currentApiKey = rotatorKeyInfo.first
                currentKeyIdx = rotatorKeyInfo.second
            } else {
                currentApiKey = rotatorKeyInfo?.first
                    ?: secureSettings.getActiveGeminiApiKey().first().ifBlank { "" }
                if (currentApiKey.isBlank()) {
                    currentApiKey = secureSettings.getGeminiApiKey().first().ifBlank { "" }
                }
                if (currentApiKey.isBlank()) {
                    currentApiKey = appSettings.geminiApiKey.first().ifBlank { "" }
                }
            }

            if (currentApiKey.isBlank()) {
                return@withContext Result.failure(Exception("Gemini API ключ не найден ни в настройках, ни в Pipeline"))
            }

            var rawResponse: Result<Triple<String, Int, Int>> = Result.failure(Exception("not called"))
            var attempts = 0

            while (true) {
                rawResponse = callGeminiApi(systemPrompt, userMessage, activeModel, currentApiKey)
                if (rawResponse.isSuccess) break

                val errMsg = rawResponse.exceptionOrNull()?.message?.lowercase() ?: ""
                val isQuota = "429" in errMsg || "rate limit" in errMsg || "quota" in errMsg || "exceeded" in errMsg

                if (isQuota && attempts < 2) {
                    Log.w(TAG, "⚠️ Превышена квота (429), пробуем ротацию ключа...")
                    val next = pipelineKeyRotator.burnAndRotate(currentKeyIdx.coerceAtLeast(0))
                    if (next != null) {
                        currentApiKey = next.first
                        currentKeyIdx = next.second
                        attempts++
                        continue
                    }
                }
                break
            }

            val (content, inputTokens, outputTokens) = rawResponse.getOrElse {
                return@withContext Result.failure(it)
            }

            val costUSD = (inputTokens * activeModel.costPerMInputUsd +
                    outputTokens * activeModel.costPerMOutputUsd) / 1_000_000.0
            val costEUR = costUSD * 0.92

            Log.d(TAG, "✅ ${activeModel.displayName}: ${inputTokens} in + ${outputTokens} out = €${String.format(java.util.Locale.US, "%.5f", costEUR)}")

            val result = parseEditResponse(content, inputTokens, outputTokens, costEUR, activeModel)
            if (result.blocks.isEmpty()) Log.w(TAG, "⚠️ Блоки не найдены. Сводка: ${result.summary}")
            Result.success(result)
        } catch (e: java.net.SocketTimeoutException) {
            Result.failure(Exception("Таймаут соединения с Gemini API. Попробуйте снова."))
        } catch (e: java.net.UnknownHostException) {
            Result.failure(Exception("Нет подключения к интернету."))
        } catch (e: Exception) {
            Log.e(TAG, "❌ processEdit failed", e)
            Result.failure(e)
        }
    }

    private fun callGeminiApi(
        systemPrompt: String,
        userMessage: String,
        model: AiModel,
        apiKey: String
    ): Result<Triple<String, Int, Int>> {
        val url = "$GEMINI_API_BASE_URL/${model.apiId}:generateContent"

        val requestBody = JSONObject().apply {
            put("systemInstruction", JSONObject().apply {
                put("parts", JSONArray().apply {
                    put(JSONObject().apply { put("text", systemPrompt) })
                })
            })
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", userMessage) })
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("maxOutputTokens", MAX_OUTPUT_TOKENS)
                put("temperature", 0.0) // 0.0 для максимальной детерминированности и точности замены
                put("topP", 0.95)
                put("thinkingConfig", JSONObject().apply {
                    put("thinkingLevel", model.forcedThinkingLevel) // Штатно LOW (3.5) или MEDIUM (3.1)
                })
            })
        }

        return executeRequest(
            url = url,
            body = requestBody,
            apiKey = apiKey,
            parseResponse = { json ->
                val candidates = json.getJSONArray("candidates")
                val content = candidates.getJSONObject(0)
                    .getJSONObject("content")
                    .getJSONArray("parts")
                    .let { parts ->
                        val sb = StringBuilder()
                        for (i in 0 until parts.length()) {
                            val part = parts.getJSONObject(i)
                            // КРИТИЧНО: фильтруем размышления (thought: true), чтобы не ломать XML замен
                            if (part.optBoolean("thought", false)) {
                                continue
                            }
                            sb.append(part.optString("text", ""))
                        }
                        sb.toString()
                    }
                val usage = json.optJSONObject("usageMetadata")
                val inputTokens = usage?.optInt("promptTokenCount", 0) ?: 0
                val outputTokens = usage?.optInt("candidatesTokenCount", 0) ?: 0
                Triple(content, inputTokens, outputTokens)
            }
        )
    }

    private fun executeRequest(
        url: String,
        body: JSONObject,
        apiKey: String,
        parseResponse: (JSONObject) -> Triple<String, Int, Int>
    ): Result<Triple<String, Int, Int>> {
        return try {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("x-goog-api-key", apiKey)
                setRequestProperty("Accept", "application/json")
                connectTimeout = 30_000
                readTimeout = 120_000
                doOutput = true
            }

            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val responseCode = connection.responseCode
            val responseBody = if (responseCode in 200..299) {
                BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { it.readText() }
            } else {
                val errorBody = connection.errorStream?.let {
                    BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() }
                } ?: "Unknown error"
                Log.e(TAG, "❌ API error $responseCode: $errorBody")
                return Result.failure(Exception(formatApiError(responseCode, errorBody)))
            }

            Result.success(parseResponse(JSONObject(responseBody)))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun buildUserMessage(
        fileContent: String,
        fileName: String,
        instructions: String,
        useLineNumbers: Boolean
    ): String = buildString {
        appendLine("File: `$fileName`")
        appendLine()
        appendLine("```")
        if (useLineNumbers) {
            fileContent.lines().forEachIndexed { index, line ->
                appendLine("${index + 1}| $line")
            }
        } else {
            appendLine(fileContent)
        }
        appendLine("```")
        appendLine()
        appendLine("═══ INSTRUCTIONS ═══")
        appendLine(instructions)
    }

    private fun buildSystemPrompt(useLineNumbers: Boolean): String {
        return if (useLineNumbers) {
            SYSTEM_PROMPT + """

═══ LINE NUMBERS ═══
The file is provided with line number prefixes in format "N| " (e.g., "42| val x = 1").
These prefixes are for YOUR REFERENCE ONLY. NEVER include them in <search> or <replace> blocks.
"""
        } else SYSTEM_PROMPT
    }

    fun applyEdits(content: String, blocks: List<EditBlock>): Result<ApplyResult> {
        var result = content.replace("\r\n", "\n")
        val appliedBlocks = mutableListOf<EditBlock>()
        val failedBlocks = mutableListOf<Pair<Int, EditBlock>>()

        blocks.forEachIndexed { index, block ->
            val blockNum = index + 1
            val cleanSearch = stripLineNumbers(block.search).replace("\r\n", "\n")
            val cleanReplace = stripLineNumbers(block.replace).replace("\r\n", "\n")

            if (cleanSearch.isBlank()) {
                result = result.trimEnd() + "\n\n" + cleanReplace + "\n"
                appliedBlocks.add(block.copy(matchStatus = EditBlock.MatchStatus.EXACT))
                return@forEachIndexed
            }

            if (cleanSearch in result) {
                result = result.replaceFirst(cleanSearch, cleanReplace)
                appliedBlocks.add(block.copy(matchStatus = EditBlock.MatchStatus.EXACT))
                return@forEachIndexed
            }

            val normalizedSearch = normalizeWhitespace(cleanSearch)
            val normalizedContent = normalizeWhitespace(result)
            val normIdx = normalizedContent.indexOf(normalizedSearch)
            if (normIdx >= 0) {
                val originalRange = findOriginalRange(result, normalizedContent, normIdx, normalizedSearch.length)
                if (originalRange != null) {
                    result = result.substring(0, originalRange.first) + cleanReplace + result.substring(originalRange.second)
                    appliedBlocks.add(block.copy(matchStatus = EditBlock.MatchStatus.NORMALIZED))
                    return@forEachIndexed
                }
            }

            val fuzzyResult = fuzzyMatch(result, cleanSearch, cleanReplace)
            if (fuzzyResult != null) {
                result = fuzzyResult
                appliedBlocks.add(block.copy(matchStatus = EditBlock.MatchStatus.FUZZY))
                return@forEachIndexed
            }

            val lineRangeResult = lineRangeMatch(result, cleanSearch, cleanReplace)
            if (lineRangeResult != null) {
                result = lineRangeResult
                appliedBlocks.add(block.copy(matchStatus = EditBlock.MatchStatus.LINE_RANGE))
                return@forEachIndexed
            }

            failedBlocks.add(blockNum to block)
            appliedBlocks.add(block.copy(matchStatus = EditBlock.MatchStatus.NOT_FOUND))
        }

        val totalApplied = appliedBlocks.count { it.matchStatus != EditBlock.MatchStatus.NOT_FOUND }
        return Result.success(
            ApplyResult(
                newContent = result,
                appliedBlocks = appliedBlocks,
                failedBlockNumbers = failedBlocks.map { it.first },
                totalApplied = totalApplied,
                totalFailed = failedBlocks.size
            )
        )
    }

    private fun normalizeWhitespace(text: String): String =
        text.lines().joinToString("\n") { it.trimEnd() }

    private fun findOriginalRange(
        original: String,
        normalized: String,
        normStart: Int,
        normLength: Int
    ): Pair<Int, Int>? {
        return try {
            val origLines = original.lines()
            val normLines = normalized.lines()
            var normCharPos = 0
            var startLineIdx = -1
            var endLineIdx = -1

            for (i in normLines.indices) {
                val lineEnd = normCharPos + normLines[i].length
                if (startLineIdx == -1 && lineEnd >= normStart) startLineIdx = i
                if (normCharPos >= normStart + normLength && endLineIdx == -1) { endLineIdx = i; break }
                normCharPos = lineEnd + 1
            }
            if (endLineIdx == -1) endLineIdx = origLines.lastIndex + 1
            if (startLineIdx < 0 || startLineIdx >= origLines.size) return null

            val origStart = origLines.take(startLineIdx).sumOf { it.length + 1 }
            val origEnd = origLines.take(endLineIdx).sumOf { it.length + 1 }.coerceAtMost(original.length)
            origStart to origEnd
        } catch (e: Exception) {
            null
        }
    }

    private fun fuzzyMatch(content: String, search: String, replace: String): String? {
        val searchLines = search.lines().filter { it.isNotBlank() }
        if (searchLines.size < 2) {
            val singleLine = searchLines.firstOrNull()?.trim() ?: return null
            val contentLines = content.lines()
            val idx = contentLines.indexOfFirst { it.trim() == singleLine }
            if (idx < 0) return null
            val mutableLines = contentLines.toMutableList()
            mutableLines.removeAt(idx)
            mutableLines.addAll(idx, replace.lines())
            return mutableLines.joinToString("\n")
        }

        val firstLine = searchLines.first().trim()
        val lastLine = searchLines.last().trim()
        val contentLines = content.lines()
        val startIdx = contentLines.indexOfFirst { it.trim() == firstLine }
        if (startIdx < 0) return null
        val endIdx = (startIdx until contentLines.size).lastOrNull { contentLines[it].trim() == lastLine } ?: return null
        if (endIdx < startIdx) return null

        val matchedSignificant = (startIdx..endIdx).count { contentLines[it].isNotBlank() }
        if (matchedSignificant < searchLines.size - 1 || matchedSignificant > searchLines.size + 3) return null

        return (contentLines.take(startIdx) + replace.lines() + contentLines.drop(endIdx + 1)).joinToString("\n")
    }

    private fun lineRangeMatch(content: String, search: String, replace: String): String? {
        val searchLines = search.lines().filter { it.isNotBlank() }
        if (searchLines.size < 3) return null

        val contentLines = content.lines()
        val keyLines = listOf(
            searchLines.first().trim(),
            searchLines[searchLines.size / 2].trim(),
            searchLines.last().trim()
        )

        var searchFrom = 0
        val foundIndices = mutableListOf<Int>()
        for (key in keyLines) {
            val idx = (searchFrom until contentLines.size).firstOrNull { contentLines[it].trim() == key } ?: return null
            foundIndices.add(idx)
            searchFrom = idx + 1
        }

        val startIdx = foundIndices.first()
        val endIdx = foundIndices.last()
        if (endIdx - startIdx + 1 > searchLines.size * 2) return null

        return (contentLines.take(startIdx) + replace.lines() + contentLines.drop(endIdx + 1)).joinToString("\n")
    }

    private fun stripLineNumbers(text: String): String {
        if (text.isBlank()) return text
        val lines = text.lines()
        val pattern = Regex("""^\d{1,5}\|\s""")
        return if (lines.count { pattern.containsMatchIn(it) } > lines.size / 2) {
            lines.joinToString("\n") { pattern.replaceFirst(it, "") }
        } else text
    }

    private fun parseEditResponse(
        response: String,
        inputTokens: Int,
        outputTokens: Int,
        costEUR: Double,
        model: AiModel
    ): EditResult {
        // Очищаем от случайных markdown-блоков
        val cleanResponse = response.trim()
            .removePrefix("```xml").removePrefix("```")
            .removeSuffix("```").trim()

        val blocks = mutableListOf<EditBlock>()
        val blockRegex = Regex(
            """<block>\s*<search>(.*?)</search>\s*<replace>(.*?)</replace>\s*</block>""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )

        blockRegex.findAll(cleanResponse).forEach { match ->
            blocks.add(
                EditBlock(
                    search = trimXmlNewlines(match.groupValues[1]),
                    replace = trimXmlNewlines(match.groupValues[2])
                )
            )
        }

        val summary = Regex("""<summary>\s*(.*?)\s*</summary>""", RegexOption.DOT_MATCHES_ALL)
            .find(cleanResponse)?.groupValues?.get(1)?.trim()
            ?: if (blocks.isEmpty()) "AI не вернул блоков замен" else "${blocks.size} блок(ов) замен"

        return EditResult(blocks, summary, inputTokens, outputTokens, costEUR, model)
    }

    private fun trimXmlNewlines(text: String): String {
        var result = text
        if (result.startsWith("\n")) result = result.removePrefix("\n")
        if (result.endsWith("\n")) result = result.removeSuffix("\n")
        return result
    }

    private fun formatApiError(code: Int, body: String): String {
        val msg = try {
            val json = JSONObject(body)
            json.optJSONObject("error")?.optString("message")
                ?: json.optString("message", "").ifBlank { body.take(200) }
        } catch (_: Exception) { body.take(200) }

        return when (code) {
            400 -> "Ошибка запроса (400): $msg"
            401 -> "Неверный API ключ Gemini"
            403 -> "Доступ запрещён (403): $msg"
            429 -> "Превышен лимит запросов (429). Подождите несколько секунд."
            500, 502, 503 -> "Сервер Gemini временно перегружен"
            529 -> "Сервер перегружен (529)"
            else -> "Ошибка API $code: $msg"
        }
    }
}