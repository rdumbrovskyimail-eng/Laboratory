package com.opuside.app.feature.pipeline.data

import android.util.Log
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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 📋 PIPELINE PLANNER v4.0
 *
 * Разбивает промпт пользователя на пофайловые задачи. Работает только на
 * [PipelineAiConfig.MODEL_ID].
 *
 * Ключевые отличия от v3.2 (из-за которых ломался AndroidManifest.xml):
 *  - планировщик НЕ пишет код: содержимое файлов берётся из блоков промпта по номеру
 *    или генерируется исполнителем отдельно, с собственным лимитом токенов;
 *  - CREATE для существующего пути автоматически превращается в MODIFY;
 *  - планировщику показывается весь список файлов (до 6000), а не первые 1000;
 *  - пути с `..` и абсолютные пути отбрасываются.
 */
@Singleton
class PipelinePlanner @Inject constructor(
    private val client: PipelineGeminiClient
) {

    companion object {
        private const val TAG = "PipelinePlanner"
        private const val MAX_TASKS_LIMIT = 100

        private val EXCLUDED_SEGMENTS = setOf(
            ".git", ".gradle", ".idea", "build", ".cxx", ".externalNativeBuild",
            "node_modules", ".kotlin", "captures", ".DS_Store"
        )
        private val BINARY_EXT = setOf(
            "png", "jpg", "jpeg", "webp", "gif", "ico", "bmp", "jar", "aar", "so", "apk", "aab",
            "keystore", "jks", "ttf", "otf", "woff", "woff2", "mp3", "mp4", "wav", "ogg", "zip",
            "gz", "7z", "pdf", "bin", "dex", "class", "lock"
        )

        private val PLANNER_SYSTEM_PROMPT = """
You are the PLANNER of a multi-file code modification pipeline for a software repository.
Split the user's request into per-file tasks. You do NOT write code — separate executors do that.

OPERATIONS
- "modify": change an EXISTING file. "file" must be copied exactly from the REPOSITORY FILES list.
- "create": add a NEW file that is NOT in the list. Choose a path that fits the project structure
  (for Kotlin/Java the directory must match the package).
- "delete": remove an existing file — ONLY when the user explicitly asks to delete/remove that file.

CRITICAL RULES
1. If a file already exists in REPOSITORY FILES, the operation MUST be "modify" — never "create",
   even when the user provides its complete new content.
2. Exactly ONE task per file. Merge all changes for the same file into a single task.
3. "instructions": a complete, self-contained description of EVERY change for this file, so that an engineer
   who sees ONLY this file plus these instructions can do it. Copy concrete details from the request verbatim:
   identifiers, signatures, values, strings, XML attributes, where exactly to insert. Never drop details.
   Do not describe changes that belong to other files. Write in the language of the user's request.
4. "code_blocks": numbers N of the user's code blocks (⟦CODE BLOCK #N⟧) that contain code for this file. [] if none.
5. "full_content_block": number of a code block that contains the COMPLETE final content of this file —
   the entire file from the first line to the last, not a fragment, snippet or diff. Otherwise -1.
   When in doubt, use -1.
6. Include only files the request asks to change, plus files whose change is strictly required for the
   requested change to work (e.g. registering a new Activity/Service in AndroidManifest.xml, a new dependency
   in build.gradle). For such required files describe ONLY the minimal addition — never ask to rewrite them.
7. Never invent paths for modify/delete. If a file the user mentions is not in the list, explain it in "notes".
8. "notes": short remarks for the user (ambiguities, missing files). Empty string if none.

EDIT PAIRS (explicit "Найти / Заменить на" instructions)
9. The user's explicit find/replace instructions were already parsed by code into numbered pairs (⟦EDIT PAIRS INDEX⟧).
   Code applies them VERBATIM. Put each pair number into "edit_pairs" of the task for the file it belongs to —
   every pair exactly once. A pair marked "(explicit)" belongs to the file written next to it.
10. Never copy a pair's text into "instructions" and never use a pair's code block as "full_content_block".
11. "pairs_only": true when the pairs cover ALL changes the user wants in this file (then "instructions" is a one-line
    summary). If the user ALSO describes other changes for this file in words, set false and describe ONLY those other
    changes in "instructions". When in doubt, false. Tasks without pairs: "edit_pairs": [] and "pairs_only": false.
""".trimIndent()

        private val PLAN_SCHEMA: JsonObject = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("tasks", buildJsonObject {
                    put("type", "array")
                    put("items", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("operation", buildJsonObject {
                                put("type", "string")
                                put("enum", JsonArray(listOf(
                                    JsonPrimitive("modify"), JsonPrimitive("create"), JsonPrimitive("delete")
                                )))
                            })
                            put("file", buildJsonObject {
                                put("type", "string")
                                put("description", "Full repository path")
                            })
                            put("instructions", buildJsonObject {
                                put("type", "string")
                                put("description", "Complete, detailed instructions for this file only")
                            })
                            put("code_blocks", buildJsonObject {
                                put("type", "array")
                                put("items", buildJsonObject { put("type", "integer") })
                            })
                            put("full_content_block", buildJsonObject {
                                put("type", "integer")
                                put("description", "Block with the COMPLETE file content, or -1")
                            })
                            put("edit_pairs", buildJsonObject {
                                put("type", "array")
                                put("items", buildJsonObject { put("type", "integer") })
                                put("description", "Numbers of EDIT PAIRS for this file")
                            })
                            put("pairs_only", buildJsonObject {
                                put("type", "boolean")
                                put("description", "true if edit_pairs cover ALL changes for this file")
                            })
                        })
                        put("required", JsonArray(listOf(
                            JsonPrimitive("operation"), JsonPrimitive("file"), JsonPrimitive("instructions"),
                            JsonPrimitive("code_blocks"), JsonPrimitive("full_content_block"),
                            JsonPrimitive("edit_pairs"), JsonPrimitive("pairs_only")
                        )))
                    })
                })
                put("notes", buildJsonObject { put("type", "string") })
            })
            put("required", JsonArray(listOf(JsonPrimitive("tasks"), JsonPrimitive("notes"))))
        }
    }

    suspend fun plan(ctx: PipelineRunContext, filePaths: List<String>): Result<PlannerOutput> {
        if (ctx.userPrompt.isBlank()) {
            return Result.failure(IllegalArgumentException("Промпт не может быть пустым"))
        }
        if (filePaths.isEmpty()) {
            return Result.failure(IllegalStateException("Репозиторий пуст или индекс ещё не загружен"))
        }

        val plannerPaths = selectPathsForPlanner(filePaths)
        val userMessage = buildString {
            appendLine("═══ USER REQUEST ═══")
            appendLine(PromptBlockExtractor.renderForPlanner(ctx))
            appendLine()
            if (ctx.blocks.isNotEmpty()) {
                appendLine("═══ CODE BLOCKS INDEX ═══")
                ctx.blocks.forEach { b ->
                    val firstLine = b.content.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(100).orEmpty()
                    appendLine("#${b.index} · ${b.info.ifBlank { "no info" }} · ${b.lineCount} lines" +
                            (if (b.leadIn.isNotBlank()) " · context: ${b.leadIn.take(140)}" else "") +
                            " · first line: $firstLine")
                }
                appendLine()
            }
            if (ctx.pairs.isNotEmpty()) {
                appendLine("═══ EDIT PAIRS INDEX (parsed by code, applied verbatim — assign each to a file) ═══")
                ctx.pairs.forEach { p ->
                    appendLine("⟦EDIT PAIR #${p.index}⟧ ${p.kind.label}" +
                            (p.fileHint?.let { " · file: $it${if (p.strongHint) " (explicit)" else " (mentioned nearby)"}" } ?: " · file: ?") +
                            " · find: \"${p.findPreview}\" (${p.findLines} lines)" +
                            (if (p.kind != PairKind.DELETE) " · new: \"${p.replacementPreview}\" (${p.replacementLines} lines)" else "") +
                            (if (p.blockIndices.isNotEmpty()) " · blocks ${p.blockIndices.sorted().joinToString { "#$it" }}" else ""))
                }
                appendLine()
            }
            appendLine("═══ REPOSITORY FILES (${plannerPaths.size}${if (plannerPaths.size < filePaths.size) " of ${filePaths.size}, binaries/build outputs hidden" else ""}) ═══")
            plannerPaths.forEach { appendLine(it) }
        }

        Log.d(TAG, "📤 Планирование [${PipelineAiConfig.MODEL_ID}]: prompt=${ctx.userPrompt.length}ch, " +
                "blocks=${ctx.blocks.size}, files=${plannerPaths.size}")

        val response = client.generate(PipelineGeminiClient.Request(
            tag = "planner",
            systemPrompt = PLANNER_SYSTEM_PROMPT,
            userMessage = userMessage,
            thinking = PipelineAiConfig.PLANNER_THINKING,
            maxOutputTokens = PipelineAiConfig.PLANNER_MAX_OUTPUT,
            readTimeoutMs = PipelineAiConfig.PLANNER_READ_TIMEOUT_MS,
            responseJsonSchema = PLAN_SCHEMA
        )).getOrElse { e ->
            val msg = if (e is PipelineGeminiClient.GeminiCallException &&
                e.kind == PipelineGeminiClient.FailureKind.TRUNCATED
            ) "План не поместился в ответ модели. Разбейте промпт на несколько запусков." else e.message
            return Result.failure(Exception("Планировщик: $msg", e))
        }

        val (rawTasks, modelNotes) = parsePlan(response.text).getOrElse { return Result.failure(it) }
        if (rawTasks.isEmpty()) {
            return Result.failure(IllegalStateException(
                "Планировщик не сформировал задачи." + (if (!modelNotes.isNullOrBlank()) " Комментарий: $modelNotes" else "")
            ))
        }

        val resolverNotes = mutableListOf<String>()
        val resolved = resolvePaths(rawTasks, filePaths, ctx, resolverNotes).take(MAX_TASKS_LIMIT)
        if (resolved.isEmpty()) {
            return Result.failure(IllegalStateException(
                "Ни одну задачу не удалось сопоставить с файлами репозитория. " + resolverNotes.joinToString(" ")
            ))
        }

        val notes = buildList {
            if (!modelNotes.isNullOrBlank()) add(modelNotes.trim())
            addAll(resolverNotes)
        }.joinToString("\n").ifBlank { null }

        Log.d(TAG, "✅ План: ${resolved.size} задач (${response.inputTokens}in + ${response.billedOutputTokens}out, " +
                "€${String.format(java.util.Locale.US, "%.5f", response.costEur)})")

        return Result.success(PlannerOutput(
            tasks = resolved,
            rawJson = response.text,
            tokensUsed = response.totalTokens,
            costEur = response.costEur,
            notes = notes
        ))
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PATHS FOR PLANNER
    // ═══════════════════════════════════════════════════════════════════════

    private fun selectPathsForPlanner(all: List<String>): List<String> {
        val filtered = all.filter { p ->
            val segments = p.split('/')
            segments.none { it in EXCLUDED_SEGMENTS } &&
                    p.substringAfterLast('.', "").lowercase() !in BINARY_EXT
        }
        if (filtered.size <= PipelineAiConfig.PLANNER_MAX_PATHS) return filtered
        // Если файлов слишком много — приоритет исходникам и конфигам
        val priorityExt = setOf("kt", "kts", "java", "xml", "gradle", "toml", "json", "yml", "yaml", "properties", "pro", "md")
        val (prio, rest) = filtered.partition { it.substringAfterLast('.', "").lowercase() in priorityExt }
        return (prio + rest).take(PipelineAiConfig.PLANNER_MAX_PATHS)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PARSE
    // ═══════════════════════════════════════════════════════════════════════

    private fun parsePlan(raw: String): Result<Pair<List<PlannedTask>, String?>> = try {
        val cleaned = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        val root = Json.parseToJsonElement(cleaned).jsonObject
        val tasksArray = root["tasks"] as? JsonArray
            ?: return Result.failure(Exception("JSON плана не содержит массива 'tasks'"))
        val notes = root["notes"]?.jsonPrimitive?.contentOrNull

        val tasks = tasksArray.mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val operation = when (obj["operation"]?.jsonPrimitive?.contentOrNull?.lowercase()) {
                "create" -> TaskOperation.CREATE
                "delete" -> TaskOperation.DELETE
                else -> TaskOperation.MODIFY
            }
            val file = obj["file"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val instructions = obj["instructions"]?.jsonPrimitive?.contentOrNull.orEmpty()
            val blocks = (obj["code_blocks"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.intOrNull }
            val full = obj["full_content_block"]?.jsonPrimitive?.intOrNull?.takeIf { it > 0 }
            val pairs = (obj["edit_pairs"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.intOrNull }
            val pairsOnly = obj["pairs_only"]?.jsonPrimitive?.booleanOrNull ?: false

            if (file.isBlank()) return@mapNotNull null
            if (operation == TaskOperation.MODIFY && instructions.isBlank() && full == null && pairs.isEmpty()) return@mapNotNull null

            PlannedTask(
                operation = operation,
                file = file,
                instructions = instructions.ifBlank {
                    when {
                        operation == TaskOperation.DELETE -> "Удалить файл"
                        pairs.isNotEmpty() -> "Применить замены ${pairs.joinToString { "#$it" }}"
                        else -> "Привести файл к содержимому из блока #$full"
                    }
                },
                codeBlocks = blocks,
                fullContentBlock = full,
                editPairs = pairs,
                pairsOnly = pairsOnly && pairs.isNotEmpty()
            )
        }
        Result.success(tasks to notes)
    } catch (e: Exception) {
        Result.failure(Exception("Не удалось разобрать JSON плана: ${e.message?.take(160)}"))
    }

    // ═══════════════════════════════════════════════════════════════════════
    // RESOLVE
    // ═══════════════════════════════════════════════════════════════════════

    private fun normalizePath(raw: String): String? {
        var p = raw.trim().replace('\\', '/').trim('`', '"', '\'')
        while (p.startsWith("./")) p = p.removePrefix("./")
        p = p.removePrefix("/").replace(Regex("/{2,}"), "/")
        if (p.isBlank()) return null
        if (p.split('/').any { it == ".." }) return null
        return p
    }

    private class PathIndex(paths: List<String>) {
        val exact: Set<String> = paths.toSet()
        val lower: Map<String, String> = paths.associateBy { it.lowercase() }
        val byName: Map<String, List<String>> = paths.groupBy { it.substringAfterLast('/') }

        fun resolveExisting(path: String): String? {
            if (path in exact) return path
            lower[path.lowercase()]?.let { return it }
            val name = path.substringAfterLast('/')
            val candidates = byName[name].orEmpty()
            if (candidates.isEmpty()) return null
            if (candidates.size == 1) return candidates[0]
            // Несколько файлов с таким именем — выбираем по совпадающим сегментам пути,
            // но только если победитель однозначен.
            val rawSegments = path.split('/').filter { it.isNotBlank() }.toSet()
            val scored = candidates.map { c -> c to c.split('/').count { it in rawSegments } }
                .sortedByDescending { it.second }
            return if (scored[0].second > scored[1].second) scored[0].first else null
        }
    }

    internal fun resolvePaths(
        planned: List<PlannedTask>,
        filePaths: List<String>,
        ctx: PipelineRunContext,
        notes: MutableList<String>
    ): List<PlannedTask> {
        val index = PathIndex(filePaths)
        val sourceRoot = detectSourceRoot(filePaths)
        val result = LinkedHashMap<String, PlannedTask>()
        val pairBlocks = ctx.pairBlockIndices
        val validBlocks = ctx.blocks.map { it.index }.toSet()
        val validPairs = ctx.pairs.map { it.index }.toSet()

        for (task0 in planned) {
            val path = normalizePath(task0.file)
            if (path == null) {
                notes += "Отброшена задача с некорректным путём «${task0.file}»."
                continue
            }
            // Блоки пар не могут быть «полным файлом» и не дублируются как справочные блоки
            val full = task0.fullContentBlock?.takeIf { it in validBlocks && it !in pairBlocks }
            val blocks = (task0.codeBlocks.filter { it in validBlocks && it !in pairBlocks } + listOfNotNull(full)).distinct()
            val pairs = task0.editPairs.filter { it in validPairs }.distinct()
            val task = task0.copy(
                file = path, codeBlocks = blocks, fullContentBlock = full,
                editPairs = pairs, pairsOnly = task0.pairsOnly && pairs.isNotEmpty()
            )

            val finalTask: PlannedTask? = when (task.operation) {
                TaskOperation.MODIFY -> {
                    val existing = index.resolveExisting(path)
                    when {
                        existing != null -> task.copy(
                            file = existing,
                            warning = if (existing != path) "Путь уточнён: $path → $existing" else null
                        )
                        full != null -> task.copy(
                            operation = TaskOperation.CREATE,
                            warning = "Файл не найден в репозитории — будет создан из блока #$full"
                        )
                        else -> task.copy(warning = "Файл не найден в репозитории — задача, скорее всего, завершится ошибкой")
                    }
                }
                TaskOperation.CREATE -> resolveCreate(task, index, sourceRoot, ctx, notes)
                TaskOperation.DELETE -> {
                    val existing = index.resolveExisting(path)
                    if (existing == null) {
                        notes += "Удаление «$path» пропущено: файла нет в репозитории."
                        null
                    } else task.copy(file = existing)
                }
            }
            if (finalTask != null) merge(result, finalTask, notes)
        }
        if (ctx.pairs.isNotEmpty()) assignPairs(result, index, ctx, notes)
        return result.values.toList()
    }

    /**
     * Окончательная привязка пар «Найти / Заменить на» к файлам:
     *  1) явно указанный файл («Файл: …», заголовок, info-строка блока) важнее мнения модели;
     *  2) иначе — как решил планировщик;
     *  3) не привязанные — по упоминанию файла рядом или к единственной задаче-правке.
     */
    private fun assignPairs(
        result: LinkedHashMap<String, PlannedTask>,
        index: PathIndex,
        ctx: PipelineRunContext,
        notes: MutableList<String>
    ) {
        val owner = LinkedHashMap<Int, String>()
        for ((path, t) in result) {
            if (t.operation != TaskOperation.MODIFY) continue
            for (p in t.editPairs) owner.putIfAbsent(p, path)
        }
        for (pair in ctx.pairs) {
            if (!pair.strongHint) continue
            val target = pair.fileHint?.let { normalizePath(it) }?.let { index.resolveExisting(it) } ?: continue
            val prev = owner[pair.index]
            if (prev != target) {
                if (prev != null) notes += "Пара #${pair.index} привязана к $target (файл указан в промпте явно), а не к $prev."
                owner[pair.index] = target
            }
        }
        val modifyPaths = result.values.filter { it.operation == TaskOperation.MODIFY }.map { it.file }
        for (pair in ctx.pairs) {
            if (owner.containsKey(pair.index)) continue
            val nearby = pair.fileHint?.let { normalizePath(it) }?.let { index.resolveExisting(it) }
            val target = nearby ?: modifyPaths.singleOrNull()
            if (target != null) owner[pair.index] = target
            else notes += "Пара #${pair.index} («${pair.findPreview.take(50)}») не привязана к файлу — укажите «Файл: путь» перед «Найти»."
        }

        val byPath = owner.entries.groupBy({ it.value }, { it.key })
        for ((path, t) in result.toList()) {
            val mine = byPath[path].orEmpty().sorted()
            when {
                // Задача существовала только ради пар, которые уехали в другой файл — она лишняя
                t.pairsOnly && t.editPairs.isNotEmpty() && mine.isEmpty() -> {
                    result.remove(path)
                    notes += "Задача для $path убрана: её замены относятся к другому файлу."
                }
                t.operation != TaskOperation.MODIFY && t.editPairs.isNotEmpty() -> {
                    result[path] = t.copy(editPairs = emptyList(), pairsOnly = false)
                }
                else -> result[path] = t.copy(editPairs = mine, pairsOnly = t.pairsOnly && mine.isNotEmpty())
            }
        }
        for ((path, ps) in byPath) {
            if (path in result) continue
            result[path] = PlannedTask(
                operation = TaskOperation.MODIFY,
                file = path,
                instructions = "Применить замены ${ps.sorted().joinToString { "#$it" }}",
                editPairs = ps.sorted(),
                pairsOnly = true
            )
        }
    }

    private fun resolveCreate(
        task: PlannedTask,
        index: PathIndex,
        sourceRoot: String,
        ctx: PipelineRunContext,
        notes: MutableList<String>
    ): PlannedTask? {
        var path = task.file

        // 1) Точное совпадение с существующим файлом → это правка, а не создание.
        //    Именно здесь раньше перезаписывался AndroidManifest.xml.
        (path.takeIf { it in index.exact } ?: index.lower[path.lowercase()])?.let { existing ->
            return task.copy(
                operation = TaskOperation.MODIFY,
                file = existing,
                warning = "Файл уже существует — «Создать» заменено на «Изменить» (оригинал не будет затёрт)"
            )
        }

        // 2) Указано только имя файла без папки, и такой файл в репо один → тоже правка.
        if (!path.contains('/')) {
            val candidates = index.byName[path].orEmpty()
            if (candidates.size == 1) {
                return task.copy(
                    operation = TaskOperation.MODIFY,
                    file = candidates[0],
                    warning = "Файл «$path» уже есть в ${candidates[0]} — «Создать» заменено на «Изменить»"
                )
            }
        }

        // 3) Путь без имени файла → выводим имя из кода блока.
        val blockContent = ctx.block(task.fullContentBlock)?.content
            ?: task.codeBlocks.firstNotNullOfOrNull { ctx.block(it)?.content }
        val hasFileName = path.substringAfterLast('/').contains('.')
        if (!hasFileName) {
            val name = blockContent?.let { deriveFileNameFromContent(it) }
            if (name == null) {
                notes += "Создание «$path» пропущено: не удалось определить имя файла."
                return null
            }
            path = path.trimEnd('/') + "/" + name
        }

        // 4) Только имя файла, папки нет → кладём по package.
        if (!path.contains('/')) {
            val pkg = blockContent?.let { extractPackage(it) }
            path = if (pkg != null && (path.endsWith(".kt") || path.endsWith(".java"))) {
                "$sourceRoot${pkg.replace('.', '/')}/$path"
            } else path
        }

        return task.copy(file = path)
    }

    private fun merge(result: LinkedHashMap<String, PlannedTask>, task: PlannedTask, notes: MutableList<String>) {
        val existing = result[task.file]
        if (existing == null) {
            result[task.file] = task
            return
        }
        if (existing.operation == TaskOperation.DELETE || task.operation == TaskOperation.DELETE) {
            if (existing.operation != task.operation) {
                notes += "Для «${task.file}» план содержит и удаление, и изменение — оставлено первое действие (${existing.operation.displayName})."
            }
            return
        }
        result[task.file] = existing.copy(
            instructions = existing.instructions + "\n\n--- ДОПОЛНИТЕЛЬНО ---\n\n" + task.instructions,
            codeBlocks = (existing.codeBlocks + task.codeBlocks).distinct(),
            fullContentBlock = existing.fullContentBlock ?: task.fullContentBlock,
            editPairs = (existing.editPairs + task.editPairs).distinct(),
            pairsOnly = existing.pairsOnly && task.pairsOnly,
            warning = listOfNotNull(existing.warning, task.warning).distinct().joinToString("; ").ifBlank { null }
        )
    }

    private fun extractPackage(content: String): String? =
        Regex("""^\s*package\s+([\w.]+)""", RegexOption.MULTILINE).find(content)?.groupValues?.get(1)

    private fun deriveFileNameFromContent(content: String): String? {
        val isJava = Regex("""^\s*(public\s+)?(final\s+)?(abstract\s+)?class\s+\w+\s*(extends|implements|\{)""", RegexOption.MULTILINE)
            .containsMatchIn(content) && content.contains(';')
        val ext = if (isJava) "java" else "kt"
        val patterns = listOf(
            Regex("""(?:public\s+|internal\s+|private\s+)?(?:abstract\s+|sealed\s+|open\s+|data\s+|enum\s+|annotation\s+|value\s+)?class\s+(\w+)"""),
            Regex("""(?:public\s+|internal\s+|private\s+)?(?:data\s+)?object\s+(\w+)"""),
            Regex("""(?:public\s+|internal\s+|private\s+)?(?:fun\s+)?interface\s+(\w+)"""),
            Regex("""@Composable\s+(?:public\s+|internal\s+|private\s+)?fun\s+(\w+)\s*\(""")
        )
        for (p in patterns) {
            val name = p.find(content)?.groupValues?.get(1)
            if (!name.isNullOrBlank()) return "$name.$ext"
        }
        return null
    }

    private fun detectSourceRoot(filePaths: List<String>): String {
        val packageRoots = setOf("com", "org", "io", "net", "ru", "de", "tech", "dev", "app", "me")
        for (path in filePaths) {
            if (!path.endsWith(".kt") && !path.endsWith(".java")) continue
            val parts = path.split('/')
            val javaIdx = parts.indexOfFirst { it == "java" || it == "kotlin" }
            if (javaIdx > 0 && javaIdx + 1 < parts.size && parts[javaIdx + 1] in packageRoots) {
                return parts.take(javaIdx + 1).joinToString("/") + "/"
            }
        }
        return "app/src/main/java/"
    }
}