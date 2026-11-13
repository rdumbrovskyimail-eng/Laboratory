package com.opuside.app.feature.pipeline.data

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ✏️ PIPELINE EDIT ENGINE v4.0
 *
 * Правки существующих файлов через «якорные» диапазоны строк:
 *  - модель видит файл с номерами строк;
 *  - каждая правка = {start_line, end_line, original, replacement};
 *  - `original` обязан совпасть с текущими строками — это верификация места;
 *  - если номера строк съехали, место ищется по `original` (5 уровней нечёткости);
 *  - правка применяется только целиком (all-or-nothing) — никаких полуизменённых файлов;
 *  - модель физически не может «переписать файл короче»: она меняет только
 *    указанные диапазоны, а плейсхолдеры отлавливает [PipelineContentGuard].
 */
@Singleton
class PipelineEditEngine @Inject constructor(
    private val client: PipelineGeminiClient
) {

    data class EditRequest(
        val path: String,
        val content: String,
        val instructions: String,
        /** Контекст: релевантные блоки + промпт пользователя (уже подготовленные). */
        val referenceBlocks: List<PromptCodeBlock>,
        val promptContext: String?,
        val isRetry: Boolean,
        val retryReason: String?,
        /** Пары, уже применённые кодом (файл ниже их уже содержит). */
        val appliedPairs: List<FindReplacePair> = emptyList(),
        /** Пары, которые код не смог применить (с причиной) — модель ищет место сама. */
        val unresolvedPairs: List<Pair<FindReplacePair, String>> = emptyList()
    )

    data class EditPlan(
        val noChanges: Boolean,
        val summary: String,
        val edits: List<AnchoredEdit>,
        val response: PipelineGeminiClient.Response
    )

    data class CreateRequest(
        val path: String,
        val instructions: String,
        val referenceBlocks: List<PromptCodeBlock>,
        val promptContext: String?,
        val isRetry: Boolean,
        val retryReason: String?
    )

    data class GeneratedFile(
        val content: String,
        val response: PipelineGeminiClient.Response
    )

    // ═══════════════════════════════════════════════════════════════════════
    // MODIFY
    // ═══════════════════════════════════════════════════════════════════════

    suspend fun requestEdits(req: EditRequest): Result<EditPlan> {
        val userMessage = buildString {
            val lines = AnchoredEditApplier.splitLines(req.content).lines
            appendLine("═══ FILE: ${req.path} (${lines.size} lines) ═══")
            val width = lines.size.toString().length.coerceAtLeast(3)
            lines.forEachIndexed { idx, l ->
                append((idx + 1).toString().padStart(width)).append("| ").appendLine(l)
            }
            appendLine("═══ END OF FILE ═══")
            appendLine()
            appendLine("═══ CHANGE REQUEST FOR THIS FILE ═══")
            appendLine(req.instructions.trim())
            if (req.unresolvedPairs.isNotEmpty()) {
                appendLine()
                appendLine("═══ USER FIND/REPLACE PAIRS THAT DID NOT MATCH THE FILE EXACTLY — APPLY THEM ═══")
                appendLine("The FIND text may be slightly outdated or appear several times. Locate the place it refers to")
                appendLine("(use the change request to disambiguate) and apply the change there, using the new text VERBATIM.")
                appendLine("If the change is clearly already present in the file, do not apply it again.")
                for ((pair, reason) in req.unresolvedPairs) {
                    appendLine("--- pair #${pair.index} · ${pairAction(pair.kind)} · why code failed: $reason ---")
                    appendLine("FIND:")
                    appendLine(pair.find)
                    if (pair.kind != PairKind.DELETE) {
                        appendLine(if (pair.kind == PairKind.INSERT_AFTER || pair.kind == PairKind.INSERT_BEFORE) "TEXT TO INSERT:" else "REPLACE WITH:")
                        appendLine(pair.replacement)
                    }
                    appendLine("--- end of pair #${pair.index} ---")
                }
            }
            if (req.appliedPairs.isNotEmpty()) {
                appendLine()
                appendLine("NOTE: ${req.appliedPairs.size} other find/replace pair(s) from the user " +
                        "(${req.appliedPairs.joinToString { "#${it.index}" }}) were ALREADY applied by code — the file above " +
                        "already contains them. Do NOT apply them again and do not revert them.")
            }
            appendReferenceContext(req.referenceBlocks, req.promptContext, req.path)
            if (req.isRetry) {
                appendLine()
                appendLine("═══ PREVIOUS ATTEMPT FAILED ═══")
                appendLine(req.retryReason?.take(1500) ?: "unknown reason")
                appendLine("Fix the problem: copy \"original\" EXACTLY from the numbered file above (without the number prefix), " +
                        "use correct line numbers, and never use placeholders.")
            }
        }

        val response = client.generate(PipelineGeminiClient.Request(
            tag = "edit:${req.path.substringAfterLast('/')}",
            systemPrompt = EDIT_SYSTEM_PROMPT,
            userMessage = userMessage,
            thinking = if (req.isRetry) PipelineAiConfig.EDIT_RETRY_THINKING else PipelineAiConfig.EDIT_THINKING,
            maxOutputTokens = PipelineAiConfig.EDIT_MAX_OUTPUT,
            readTimeoutMs = PipelineAiConfig.EDIT_READ_TIMEOUT_MS,
            responseJsonSchema = EDIT_SCHEMA
        )).getOrElse { return Result.failure(it) }

        return parseEditPlan(response)
    }

    private fun parseEditPlan(response: PipelineGeminiClient.Response): Result<EditPlan> = try {
        val root = Json.parseToJsonElement(stripFences(response.text)).jsonObject
        val status = root["status"]?.jsonPrimitive?.contentOrNull?.lowercase() ?: "edit"
        val summary = root["summary"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val edits = (root["edits"] as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            AnchoredEdit(
                startLine = o["start_line"]?.jsonPrimitive?.intOrNull ?: 0,
                endLine = o["end_line"]?.jsonPrimitive?.intOrNull ?: 0,
                original = o["original"]?.jsonPrimitive?.contentOrNull ?: "",
                replacement = o["replacement"]?.jsonPrimitive?.contentOrNull ?: ""
            )
        }
        Result.success(EditPlan(
            noChanges = status == "no_changes" || edits.isEmpty(),
            summary = summary,
            edits = edits,
            response = response
        ))
    } catch (e: Exception) {
        Result.failure(PipelineGeminiClient.GeminiCallException(
            "Не удалось разобрать JSON правок: ${e.message?.take(120)}",
            PipelineGeminiClient.FailureKind.PARSE, 200,
            response.inputTokens, response.billedOutputTokens
        ))
    }

    // ═══════════════════════════════════════════════════════════════════════
    // CREATE (генерация, когда в промпте нет готового кода файла)
    // ═══════════════════════════════════════════════════════════════════════

    suspend fun generateNewFile(req: CreateRequest): Result<GeneratedFile> {
        val userMessage = buildString {
            appendLine("═══ NEW FILE TO CREATE ═══")
            appendLine(req.path)
            appendLine()
            appendLine("═══ WHAT THIS FILE MUST CONTAIN ═══")
            appendLine(req.instructions.trim())
            appendReferenceContext(req.referenceBlocks, req.promptContext, req.path)
            if (req.isRetry) {
                appendLine()
                appendLine("═══ PREVIOUS ATTEMPT FAILED ═══")
                appendLine(req.retryReason?.take(1500) ?: "unknown reason")
            }
        }

        val response = client.generate(PipelineGeminiClient.Request(
            tag = "create:${req.path.substringAfterLast('/')}",
            systemPrompt = CREATE_SYSTEM_PROMPT,
            userMessage = userMessage,
            thinking = if (req.isRetry) PipelineAiConfig.CREATE_RETRY_THINKING else PipelineAiConfig.CREATE_THINKING,
            maxOutputTokens = PipelineAiConfig.CREATE_MAX_OUTPUT,
            readTimeoutMs = PipelineAiConfig.CREATE_READ_TIMEOUT_MS,
            responseJsonSchema = null
        )).getOrElse { return Result.failure(it) }

        val content = stripFences(response.text).trimEnd() + "\n"
        return Result.success(GeneratedFile(content, response))
    }

    // ═══════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════

    private fun StringBuilder.appendReferenceContext(
        blocks: List<PromptCodeBlock>,
        promptContext: String?,
        path: String
    ) {
        if (blocks.isNotEmpty()) {
            appendLine()
            appendLine("═══ CODE BLOCKS FROM THE USER FOR THIS FILE (verbatim, authoritative) ═══")
            for (b in blocks) {
                appendLine("--- block #${b.index}${if (b.info.isNotBlank()) " · ${b.info}" else ""}${if (b.leadIn.isNotBlank()) " · context: ${b.leadIn}" else ""} ---")
                appendLine(b.content)
                appendLine("--- end of block #${b.index} ---")
            }
        }
        if (!promptContext.isNullOrBlank()) {
            appendLine()
            appendLine("═══ FULL USER REQUEST (background only — apply ONLY the parts that concern $path) ═══")
            appendLine(promptContext)
        }
    }

    private fun pairAction(kind: PairKind): String = when (kind) {
        PairKind.REPLACE -> "replace FIND with the new text"
        PairKind.REPLACE_ALL -> "replace EVERY occurrence of FIND with the new text"
        PairKind.DELETE -> "delete FIND"
        PairKind.INSERT_AFTER -> "insert the text right AFTER FIND (keep FIND unchanged)"
        PairKind.INSERT_BEFORE -> "insert the text right BEFORE FIND (keep FIND unchanged)"
    }

    private fun stripFences(text: String): String {
        var t = text.trim()
        if (t.startsWith("```") || t.startsWith("~~~")) {
            val firstNl = t.indexOf('\n')
            t = if (firstNl >= 0) t.substring(firstNl + 1) else ""
            val lastFence = maxOf(t.lastIndexOf("```"), t.lastIndexOf("~~~"))
            if (lastFence >= 0 && t.substring(lastFence).trim().let { it == "```" || it == "~~~" }) {
                t = t.substring(0, lastFence)
            }
        }
        return t
    }

    companion object {
        private val EDIT_SYSTEM_PROMPT = """
You are a precise code-editing engine. You get ONE file (shown with line numbers) and a change request for it.
Return ONLY JSON that matches the response schema.

HOW TO DESCRIBE EDITS
- Each edit replaces the inclusive line range [start_line, end_line] of the ORIGINAL file.
- "original": the exact current text of those lines, copied character-for-character from the file,
  WITHOUT the "NNN| " line-number prefix. It is used to verify the location, so it must be exact.
- "replacement": the complete new text for that range (no line-number prefixes). Use "" to delete the lines.
- To INSERT code: choose an existing anchor line, put it in "original", and put the anchor line
  (unchanged) together with the new lines in "replacement".
- Line numbers always refer to the ORIGINAL file. Edits must not overlap.
- Keep every edit small and focused. NEVER replace large unchanged regions and NEVER rewrite the whole file.
- Preserve the file's indentation style exactly (spaces vs tabs, indent width).

HARD RULES
- NEVER use placeholders: no "...", no "// rest of code", no "<!-- existing -->", no "// остальной код без изменений".
  Every replacement must be complete, real content.
- Apply ALL changes the request needs for THIS file, and nothing else. Do not reformat or refactor unrelated code.
- When the user supplied code blocks, use their code verbatim (same names, values, strings).
- Do not duplicate declarations, imports, XML elements or permissions that already exist in the file.
- If the file already satisfies the request, return status "no_changes" with an empty "edits" list.
- "summary": one short sentence describing the change.
""".trimIndent()

        private val CREATE_SYSTEM_PROMPT = """
You generate the COMPLETE content of ONE new source file.
Output ONLY the raw file content: no markdown fences, no explanations, no comments about what you did.

RULES
- The file must be complete and compilable/valid on its own. NEVER use placeholders ("...", "TODO: implement", "// rest of code").
- Follow the request exactly. When the user supplied code blocks, reuse their code verbatim.
- For Kotlin/Java: the package declaration must match the directory of the file path
  (e.g. app/src/main/java/com/foo/bar/X.kt → package com.foo.bar).
- For XML: produce well-formed XML.
""".trimIndent()

        private val EDIT_SCHEMA: JsonObject = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("status", buildJsonObject {
                    put("type", "string")
                    put("enum", JsonArray(listOf(JsonPrimitive("edit"), JsonPrimitive("no_changes"))))
                })
                put("summary", buildJsonObject { put("type", "string") })
                put("edits", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("start_line", buildJsonObject { put("type", "integer") })
                            put("end_line", buildJsonObject { put("type", "integer") })
                            put("original", buildJsonObject { put("type", "string") })
                            put("replacement", buildJsonObject { put("type", "string") })
                        })
                        put("required", JsonArray(listOf(
                            JsonPrimitive("start_line"), JsonPrimitive("end_line"),
                            JsonPrimitive("original"), JsonPrimitive("replacement")
                        )))
                    })
                })
            })
            put("required", JsonArray(listOf(JsonPrimitive("status"), JsonPrimitive("summary"), JsonPrimitive("edits"))))
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// APPLIER (чистая логика, без сети — покрыта тестами)
// ═══════════════════════════════════════════════════════════════════════════

data class AnchoredEdit(
    val startLine: Int,
    val endLine: Int,
    val original: String,
    val replacement: String
)

sealed class ApplyOutcome {
    data class Applied(
        val newContent: String,
        val appliedCount: Int,
        val noopCount: Int,
        val fuzzyCount: Int,
        val notes: List<String>
    ) : ApplyOutcome()

    data class Failed(val reasons: List<String>) : ApplyOutcome()
}

object AnchoredEditApplier {

    data class SplitResult(val lines: List<String>, val crlf: Boolean, val trailingNewline: Boolean)

    fun splitLines(content: String): SplitResult {
        val crlf = content.contains("\r\n")
        val normalized = content.replace("\r\n", "\n")
        val trailing = normalized.endsWith("\n")
        val body = if (trailing) normalized.dropLast(1) else normalized
        val lines = if (body.isEmpty() && !trailing) emptyList() else body.split('\n')
        return SplitResult(lines, crlf, trailing)
    }

    private val LINE_NO_PREFIX = Regex("""^\s*\d+\| ?""")

    /** Текст из ответа модели → строки; срезает случайно скопированные префиксы «NNN| ». */
    private fun modelLines(text: String, forceStripPrefix: Boolean = false): Pair<List<String>, Boolean> {
        val normalized = text.replace("\r\n", "\n").let { if (it.endsWith("\n")) it.dropLast(1) else it }
        if (normalized.isEmpty()) return emptyList<String>() to false
        val lines = normalized.split('\n')
        val nonBlank = lines.filter { it.isNotBlank() }
        val allPrefixed = nonBlank.isNotEmpty() && nonBlank.all { LINE_NO_PREFIX.containsMatchIn(it) }
        return if (allPrefixed || forceStripPrefix) {
            lines.map { it.replaceFirst(LINE_NO_PREFIX, "") } to true
        } else lines to false
    }

    private enum class Level(val label: String) {
        EXACT("точное"), RSTRIP("без хвостовых пробелов"), TRIM("без учёта отступов"), COLLAPSE("без учёта пробелов")
    }

    private fun norm(s: String, level: Level): String = when (level) {
        Level.EXACT -> s
        Level.RSTRIP -> s.trimEnd()
        Level.TRIM -> s.trim()
        Level.COLLAPSE -> s.trim().replace(Regex("""\s+"""), " ")
    }

    private fun matchesAt(file: List<String>, at: Int, needle: List<String>, level: Level): Boolean {
        if (at < 0 || at + needle.size > file.size) return false
        for (k in needle.indices) if (norm(file[at + k], level) != norm(needle[k], level)) return false
        return true
    }

    private data class Located(val from: Int, val toExclusive: Int, val level: Level, val viaHint: Boolean)

    private fun locate(file: List<String>, edit: AnchoredEdit, needle: List<String>): Pair<Located?, String?> {
        val hint = edit.startLine - 1
        for (level in Level.values()) {
            // 1) по start_line от модели (end_line может быть посчитан неверно — не важно,
            //    длина диапазона берётся из фактического "original")
            if (matchesAt(file, hint, needle, level)) {
                return Located(hint, hint + needle.size, level, true) to null
            }
            // 2) поиск по всему файлу
            val hits = (0..(file.size - needle.size)).filter { matchesAt(file, it, needle, level) }
            if (hits.size == 1) return Located(hits[0], hits[0] + needle.size, level, false) to null
            if (hits.size > 1) {
                if (hint in 0 until file.size) {
                    val sorted = hits.sortedBy { kotlin.math.abs(it - hint) }
                    val d0 = kotlin.math.abs(sorted[0] - hint)
                    val d1 = kotlin.math.abs(sorted[1] - hint)
                    if (d0 < d1) return Located(sorted[0], sorted[0] + needle.size, level, false) to null
                }
                return null to "фрагмент встречается ${hits.size} раз(а) — неоднозначно (строки ${hits.take(5).joinToString { (it + 1).toString() }})"
            }
        }
        return null to "фрагмент \"original\" не найден в файле (строки ${edit.startLine}-${edit.endLine})"
    }

    private fun indentOf(s: String) = s.takeWhile { it == ' ' || it == '\t' }

    /** Если совпадение найдено без учёта отступов — переносим разницу отступа на replacement. */
    private fun reindent(replacement: List<String>, modelOriginal: List<String>, fileSlice: List<String>): List<String> {
        val modelFirst = modelOriginal.firstOrNull { it.isNotBlank() } ?: return replacement
        val fileFirst = fileSlice.firstOrNull { it.isNotBlank() } ?: return replacement
        val mi = indentOf(modelFirst)
        val fi = indentOf(fileFirst)
        if (mi == fi) return replacement
        return replacement.map { line ->
            if (line.isBlank()) line
            else if (line.startsWith(mi)) fi + line.removePrefix(mi)
            else line
        }
    }

    /**
     * Сколько раз фрагмент встречается в файле — на самом точном уровне сравнения,
     * на котором он вообще находится (точно → без хвостовых пробелов → без отступов → без пробелов).
     */
    fun countMatches(content: String, fragment: String): Int {
        val file = splitLines(content).lines
        val (needle, _) = modelLines(fragment)
        if (needle.isEmpty() || needle.all { it.isBlank() }) return 0
        for (level in Level.values()) {
            val hits = (0..(file.size - needle.size)).count { matchesAt(file, it, needle, level) }
            if (hits > 0) return hits
        }
        return 0
    }

    fun apply(content: String, edits: List<AnchoredEdit>): ApplyOutcome {
        if (edits.isEmpty()) return ApplyOutcome.Failed(listOf("пустой список правок"))
        val split = splitLines(content)
        val file = split.lines
        val reasons = mutableListOf<String>()
        val notes = mutableListOf<String>()

        data class Resolved(val idx: Int, val from: Int, val to: Int, val repl: List<String>, val fuzzy: Boolean, val noop: Boolean)
        val resolved = mutableListOf<Resolved>()

        edits.forEachIndexed { i, edit ->
            val n = i + 1
            val (origLines, stripped) = modelLines(edit.original)
            val (replLines, _) = modelLines(edit.replacement, forceStripPrefix = stripped)

            if (origLines.isEmpty() || origLines.all { it.isBlank() }) {
                // Пустой файл: разрешаем просто записать содержимое
                if (file.isEmpty() || file.all { it.isBlank() }) {
                    resolved += Resolved(i, 0, file.size, replLines, fuzzy = false, noop = false)
                    return@forEachIndexed
                }
                reasons += "правка #$n: пустое поле \"original\" — место правки не верифицировать"
                return@forEachIndexed
            }

            val (loc, err) = locate(file, edit, origLines)
            if (loc == null) {
                reasons += "правка #$n: $err"
                return@forEachIndexed
            }
            val fileSlice = file.subList(loc.from, loc.toExclusive)
            val fuzzy = loc.level == Level.TRIM || loc.level == Level.COLLAPSE
            val repl = if (fuzzy) reindent(replLines, origLines, fileSlice) else replLines
            if (!loc.viaHint) notes += "правка #$n найдена по тексту (строки ${loc.from + 1}-${loc.toExclusive}, ${loc.level.label})"
            resolved += Resolved(i, loc.from, loc.toExclusive, repl, fuzzy, noop = repl == fileSlice)
        }

        if (reasons.isNotEmpty()) return ApplyOutcome.Failed(reasons)

        // Дубликаты (модель иногда повторяет ту же правку) — схлопываем
        val unique = resolved.distinctBy { Triple(it.from, it.to, it.repl) }.sortedBy { it.from }

        for (k in 1 until unique.size) {
            val a = unique[k - 1]; val b = unique[k]
            if (b.from < a.to) {
                return ApplyOutcome.Failed(listOf(
                    "правки #${a.idx + 1} и #${b.idx + 1} пересекаются (строки ${a.from + 1}-${a.to} и ${b.from + 1}-${b.to})"
                ))
            }
        }

        val result = file.toMutableList()
        for (r in unique.sortedByDescending { it.from }) {
            if (r.noop) continue
            val head = result.subList(0, r.from).toList()
            val tail = result.subList(r.to, result.size).toList()
            result.clear()
            result.addAll(head); result.addAll(r.repl); result.addAll(tail)
        }

        var text = result.joinToString("\n")
        if (split.trailingNewline || (file.isEmpty() && text.isNotEmpty())) text += "\n"
        if (split.crlf) text = text.replace("\n", "\r\n")

        return ApplyOutcome.Applied(
            newContent = text,
            appliedCount = unique.count { !it.noop },
            noopCount = unique.count { it.noop },
            fuzzyCount = unique.count { it.fuzzy },
            notes = notes
        )
    }
}