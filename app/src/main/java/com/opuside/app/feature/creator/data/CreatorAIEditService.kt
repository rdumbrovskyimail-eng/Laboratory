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

@Singleton
class CreatorAIEditService @Inject constructor(
    private val appSettings: AppSettings,
    private val secureSettings: SecureSettingsDataStore
) {
    companion object {
        private const val TAG = "CreatorAIEdit"
        private const val API_URL = "https://api.anthropic.com/v1/messages"
        private const val MODEL = "claude-haiku-4-5-20251001"
        private const val MAX_OUTPUT_TOKENS = 8192
        private const val API_VERSION = "2023-06-01"
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
- If the user says "change X to Y", change ONLY X to Y — nothing more.

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
- If the file uses 4 spaces, use 4 spaces. If tabs, use tabs.
- In <replace>, the new code MUST follow the same indent level as the code it replaces.

RULE 8 — LANGUAGE AWARENESS:
- Respect language syntax: matching brackets, semicolons, commas in lists.
- When adding/removing items from a list or parameters, handle trailing commas correctly.
- When removing a function, remove the ENTIRE function including annotations and docs above it.

═══ EXAMPLES ═══

Example 1 — Simple rename:
User: "rename variable oldName to newName"
<edits>
<block>
<search>
    val oldName = repository.getData()
    processResult(oldName)
</search>
<replace>
    val newName = repository.getData()
    processResult(newName)
</replace>
</block>
</edits>
<summary>Renamed variable oldName to newName (2 occurrences)</summary>

Example 2 — Delete a function:
User: "delete function processLegacy"
<edits>
<block>
<search>
    /** Legacy processor - deprecated */
    private fun processLegacy(data: String): Boolean {
        return data.isNotEmpty()
    }
</search>
<replace>
</replace>
</block>
</edits>
<summary>Deleted function processLegacy and its doc comment</summary>

Example 3 — Add null check:
User: "add null check before calling api.fetch()"
<edits>
<block>
<search>
        val result = api.fetch(query)
        handleResult(result)
</search>
<replace>
        val api = api ?: run {
            Log.e(TAG, "API client is null")
            return
        }
        val result = api.fetch(query)
        handleResult(result)
</replace>
</block>
</edits>
<summary>Added null check for api before fetch() call</summary>
""".trimIndent()
    }

    data class EditBlock(
        val search: String,
        val replace: String,
        val matchStatus: MatchStatus = MatchStatus.PENDING
    ) {
        enum class MatchStatus {
            PENDING,
            EXACT,
            NORMALIZED,
            FUZZY,
            LINE_RANGE,
            NOT_FOUND
        }
    }

    data class EditResult(
        val blocks: List<EditBlock>,
        val summary: String,
        val inputTokens: Int,
        val outputTokens: Int,
        val costEUR: Double
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
        instructions: String
    ): Result<EditResult> = withContext(Dispatchers.IO) {
        try {
            val apiKey = try {
                secureSettings.getAnthropicApiKey().first()
            } catch (e: Exception) {
                ""
            }
            if (apiKey.isBlank()) {
                return@withContext Result.failure(
                    Exception("Claude API key не настроен. Укажите ключ в Settings.")
                )
            }

            val lineCount = fileContent.lines().size
            val useLineNumbers = lineCount > LINE_NUMBER_THRESHOLD

            Log.d(TAG, "📤 Edit request: $fileName ($lineCount lines, lineNumbers=$useLineNumbers)")
            Log.d(TAG, "📤 Instructions: ${instructions.take(100)}...")

            val userMessage = buildUserMessage(fileContent, fileName, instructions, useLineNumbers)

            val requestBody = JSONObject().apply {
                put("model", MODEL)
                put("max_tokens", MAX_OUTPUT_TOKENS)
                put("system", buildSystemPrompt(useLineNumbers))
                put("messages", JSONArray().apply {
                    put(JSONObject().apply {
                        put("role", "user")
                        put("content", userMessage)
                    })
                })
            }

            val connection = (URL(API_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("x-api-key", apiKey)
                setRequestProperty("anthropic-version", API_VERSION)
                connectTimeout = 30_000
                readTimeout = 120_000
                doOutput = true
            }

            connection.outputStream.use { os ->
                os.write(requestBody.toString().toByteArray(Charsets.UTF_8))
            }

            val responseCode = connection.responseCode
            val responseBody = if (responseCode in 200..299) {
                BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
            } else {
                val errorBody = connection.errorStream?.let {
                    BufferedReader(InputStreamReader(it)).use { r -> r.readText() }
                } ?: "Unknown error"
                Log.e(TAG, "❌ API error $responseCode: $errorBody")
                return@withContext Result.failure(
                    Exception(formatApiError(responseCode, errorBody))
                )
            }

            val json = JSONObject(responseBody)

            val content = json.getJSONArray("content").let { arr ->
                (0 until arr.length())
                    .map { arr.getJSONObject(it) }
                    .filter { it.getString("type") == "text" }
                    .joinToString("") { it.getString("text") }
            }

            val usage = json.getJSONObject("usage")
            val inputTokens = usage.getInt("input_tokens")
            val outputTokens = usage.getInt("output_tokens")

            val costUSD = (inputTokens * 0.80 + outputTokens * 4.00) / 1_000_000.0
            val costEUR = costUSD * 0.92

            Log.d(TAG, "✅ Response: ${inputTokens}in + ${outputTokens}out = €${String.format("%.5f", costEUR)}")
            Log.d(TAG, "📝 Raw response (first 500 chars): ${content.take(500)}")

            val result = parseEditResponse(content, inputTokens, outputTokens, costEUR)

            if (result.blocks.isEmpty()) {
                Log.w(TAG, "⚠️ No edit blocks parsed. Summary: ${result.summary}")
            }

            Result.success(result)

        } catch (e: java.net.SocketTimeoutException) {
            Log.e(TAG, "❌ Timeout", e)
            Result.failure(Exception("Таймаут: файл слишком большой или сеть медленная. Попробуйте ещё раз."))
        } catch (e: java.net.UnknownHostException) {
            Log.e(TAG, "❌ No network", e)
            Result.failure(Exception("Нет подключения к интернету."))
        } catch (e: Exception) {
            Log.e(TAG, "❌ Edit request failed", e)
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

        if (useLineNumbers) {
            appendLine("```")
            fileContent.lines().forEachIndexed { index, line ->
                appendLine("${index + 1}| $line")
            }
            appendLine("```")
        } else {
            appendLine("```")
            appendLine(fileContent)
            appendLine("```")
        }

        appendLine()
        appendLine("═══ INSTRUCTIONS ═══")
        appendLine(instructions)
    }

    private fun buildSystemPrompt(useLineNumbers: Boolean): String {
        return if (useLineNumbers) {
            SYSTEM_PROMPT + """

═══ LINE NUMBERS ═══
The file is provided with line number prefixes in format "N| " (e.g., "42| val x = 1").
These prefixes are for YOUR REFERENCE ONLY to navigate the file precisely.

CRITICAL: NEVER include line number prefixes in <search> or <replace> blocks.
Write only the raw source code without "N| " prefixes.

CORRECT:
<search>
    val x = 1
    val y = 2
</search>

WRONG (includes line numbers — this will FAIL):
<search>
42| val x = 1
43| val y = 2
</search>

You may reference line numbers in <summary> for clarity, e.g.:
<summary>Changed variable name at lines 42-43</summary>
"""
        } else {
            SYSTEM_PROMPT
        }
    }

    fun applyEdits(content: String, blocks: List<EditBlock>): Result<ApplyResult> {
        var result = content
        val appliedBlocks = mutableListOf<EditBlock>()
        val failedBlocks = mutableListOf<Pair<Int, EditBlock>>()

        blocks.forEachIndexed { index, block ->
            val blockNum = index + 1

            val cleanSearch = stripLineNumbers(block.search)
            val cleanReplace = stripLineNumbers(block.replace)

            if (cleanSearch.isBlank()) {
                result = result.trimEnd() + "\n\n" + cleanReplace + "\n"
                appliedBlocks.add(block.copy(matchStatus = EditBlock.MatchStatus.EXACT))
                Log.d(TAG, "  ✓ Block $blockNum: appended to end of file")
                return@forEachIndexed
            }

            if (cleanSearch in result) {
                result = result.replaceFirst(cleanSearch, cleanReplace)
                appliedBlocks.add(block.copy(matchStatus = EditBlock.MatchStatus.EXACT))
                Log.d(TAG, "  ✓ Block $blockNum: EXACT match")
                return@forEachIndexed
            }

            val normalizedSearch = normalizeWhitespace(cleanSearch)
            val normalizedContent = normalizeWhitespace(result)
            val normIdx = normalizedContent.indexOf(normalizedSearch)

            if (normIdx >= 0) {
                val originalRange = findOriginalRange(result, normalizedContent, normIdx, normalizedSearch.length)
                if (originalRange != null) {
                    result = result.substring(0, originalRange.first) +
                            cleanReplace +
                            result.substring(originalRange.second)
                    appliedBlocks.add(block.copy(matchStatus = EditBlock.MatchStatus.NORMALIZED))
                    Log.d(TAG, "  ✓ Block $blockNum: NORMALIZED match")
                    return@forEachIndexed
                }
            }

            val fuzzyResult = fuzzyMatch(result, cleanSearch, cleanReplace)
            if (fuzzyResult != null) {
                result = fuzzyResult
                appliedBlocks.add(block.copy(matchStatus = EditBlock.MatchStatus.FUZZY))
                Log.d(TAG, "  ✓ Block $blockNum: FUZZY match")
                return@forEachIndexed
            }

            val lineRangeResult = lineRangeMatch(result, cleanSearch, cleanReplace)
            if (lineRangeResult != null) {
                result = lineRangeResult
                appliedBlocks.add(block.copy(matchStatus = EditBlock.MatchStatus.LINE_RANGE))
                Log.d(TAG, "  ✓ Block $blockNum: LINE_RANGE match")
                return@forEachIndexed
            }

            failedBlocks.add(blockNum to block)
            appliedBlocks.add(block.copy(matchStatus = EditBlock.MatchStatus.NOT_FOUND))
            Log.w(TAG, "  ✗ Block $blockNum: NOT FOUND")
            Log.w(TAG, "    Search (first 120): ${cleanSearch.take(120)}")
        }

        val totalApplied = appliedBlocks.count { it.matchStatus != EditBlock.MatchStatus.NOT_FOUND }
        val totalFailed = failedBlocks.size
        Log.d(TAG, "📊 Applied: $totalApplied/${blocks.size}, Failed: $totalFailed")

        return Result.success(
            ApplyResult(
                newContent = result,
                appliedBlocks = appliedBlocks,
                failedBlockNumbers = failedBlocks.map { it.first },
                totalApplied = totalApplied,
                totalFailed = totalFailed
            )
        )
    }

    private fun normalizeWhitespace(text: String): String {
        return text.lines().joinToString("\n") { it.trimEnd() }
    }

    private fun findOriginalRange(
        original: String,
        normalized: String,
        normStart: Int,
        normLength: Int
    ): Pair<Int, Int>? {
        try {
            val origLines = original.lines()
            val normLines = normalized.lines()

            var normCharPos = 0
            var startLineIdx = -1
            var endLineIdx = -1

            for (i in normLines.indices) {
                val lineStart = normCharPos
                val lineEnd = normCharPos + normLines[i].length

                if (startLineIdx == -1 && lineEnd >= normStart) {
                    startLineIdx = i
                }
                if (lineStart >= normStart + normLength && endLineIdx == -1) {
                    endLineIdx = i
                    break
                }
                normCharPos = lineEnd + 1
            }
            if (endLineIdx == -1) endLineIdx = origLines.lastIndex + 1

            if (startLineIdx < 0 || startLineIdx >= origLines.size) return null

            val origStart = origLines.take(startLineIdx).sumOf { it.length + 1 }
            val origEnd = origLines.take(endLineIdx).sumOf { it.length + 1 }.coerceAtMost(original.length)

            return origStart to origEnd
        } catch (e: Exception) {
            Log.w(TAG, "findOriginalRange error: ${e.message}")
            return null
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

        val endIdx = (startIdx until contentLines.size).lastOrNull { idx ->
            contentLines[idx].trim() == lastLine
        } ?: return null

        if (endIdx < startIdx) return null

        val matchedSignificant = (startIdx..endIdx).count { contentLines[it].isNotBlank() }
        if (matchedSignificant < searchLines.size - 1 || matchedSignificant > searchLines.size + 3) {
            return null
        }

        val before = contentLines.take(startIdx)
        val after = contentLines.drop(endIdx + 1)
        return (before + replace.lines() + after).joinToString("\n")
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
            val idx = (searchFrom until contentLines.size).firstOrNull { i ->
                contentLines[i].trim() == key
            } ?: return null
            foundIndices.add(idx)
            searchFrom = idx + 1
        }

        val startIdx = foundIndices.first()
        val endIdx = foundIndices.last()
        val rangeSize = endIdx - startIdx + 1

        if (rangeSize > searchLines.size * 2) return null

        val before = contentLines.take(startIdx)
        val after = contentLines.drop(endIdx + 1)
        return (before + replace.lines() + after).joinToString("\n")
    }

    private fun stripLineNumbers(text: String): String {
        if (text.isBlank()) return text

        val lines = text.lines()
        val lineNumberPattern = Regex("""^\d{1,5}\|\s""")
        val matchCount = lines.count { lineNumberPattern.containsMatchIn(it) }

        return if (matchCount > lines.size / 2) {
            lines.joinToString("\n") { line ->
                lineNumberPattern.replaceFirst(line, "")
            }
        } else {
            text
        }
    }

    private fun parseEditResponse(
        response: String,
        inputTokens: Int,
        outputTokens: Int,
        costEUR: Double
    ): EditResult {
        val blocks = mutableListOf<EditBlock>()

        val blockRegex = Regex(
            """<block>\s*<search>(.*?)</search>\s*<replace>(.*?)</replace>\s*</block>""",
            setOf(RegexOption.DOT_MATCHES_ALL)
        )

        blockRegex.findAll(response).forEach { match ->
            val searchRaw = match.groupValues[1]
            val replaceRaw = match.groupValues[2]

            val search = trimXmlNewlines(searchRaw)
            val replace = trimXmlNewlines(replaceRaw)

            blocks.add(EditBlock(search = search, replace = replace))
        }

        val summaryRegex = Regex("""<summary>\s*(.*?)\s*</summary>""", RegexOption.DOT_MATCHES_ALL)
        val summary = summaryRegex.find(response)?.groupValues?.get(1)?.trim()
            ?: if (blocks.isEmpty()) "AI не вернул блоков замен" else "${blocks.size} блок(ов) замен"

        Log.d(TAG, "📝 Parsed: ${blocks.size} blocks, summary: $summary")

        return EditResult(
            blocks = blocks,
            summary = summary,
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            costEUR = costEUR
        )
    }

    private fun trimXmlNewlines(text: String): String {
        var result = text
        if (result.startsWith("\n")) result = result.removePrefix("\n")
        if (result.endsWith("\n")) result = result.removeSuffix("\n")
        return result
    }

    private fun formatApiError(code: Int, body: String): String {
        val msg = try {
            JSONObject(body).optJSONObject("error")?.optString("message") ?: body.take(200)
        } catch (_: Exception) {
            body.take(200)
        }

        return when (code) {
            400 -> "Ошибка запроса: $msg"
            401 -> "Неверный API ключ. Проверьте настройки."
            403 -> "Доступ запрещён. Проверьте API ключ."
            429 -> "Превышен лимит запросов. Подождите минуту."
            500, 502, 503 -> "Сервер Anthropic временно недоступен. Попробуйте позже."
            529 -> "API перегружен. Попробуйте через 30 секунд."
            else -> "Ошибка $code: $msg"
        }
    }
}