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
 * PIPELINE PLANNER v1.0
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Первый Gemini-вызов в конвейере. Берёт большой промпт пользователя + список
 * всех файлов репозитория и возвращает структурированный JSON-план:
 *
 *   { "tasks": [
 *       { "file": "app/src/.../File.kt", "instructions": "..." },
 *       ...
 *   ] }
 *
 * Использует Gemini 3.1 Flash-Lite Preview (та же модель что в AI Edit) с
 * responseMimeType="application/json" + responseJsonSchema — это гарантирует
 * валидный JSON на выходе.
 *
 * Если AI вернул короткий путь типа "MainActivity.kt" вместо полного,
 * выполняется fuzzy-resolve через RepoIndex.findByName().
 *
 * Стоимость одного планирования: ~5K input + ~2K output = €0.00002. Бесплатно.
 */
@Singleton
class PipelinePlanner @Inject constructor(
    private val secureSettings: SecureSettingsDataStore,
    private val keyRotator: PipelineKeyRotator
) {

    companion object {
        private const val TAG = "PipelinePlanner"
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
        private const val PLANNER_MODEL = "gemini-3.1-flash-lite-preview"
        private const val MAX_OUTPUT_TOKENS = 16384      // плана хватит
        private const val MAX_TASKS_LIMIT = 100          // макс задач за один прогон

        // Системный промпт планировщика. Жёсткие правила, чтобы AI не "креативил".
        private val PLANNER_SYSTEM_PROMPT = """
You are a precision TASK PLANNER for a code modification pipeline.

═══ YOUR JOB ═══

The user will give you a large multi-file modification request. You receive:
1. The full user prompt
2. A list of all real file paths in the repository

You MUST return a JSON object containing an array of tasks. Each task is
ONE file with ONE operation extracted from the user prompt.

═══ TASK TYPES ═══

Each task has an "operation" field — either "modify" or "create".

▸ MODIFY task (default):
  - Modifies an existing file in the repository
  - "operation": "modify"
  - "file": EXACT path from the provided file list (no guessing!)
  - "instructions": verbatim instructions from user prompt for THIS file
  - "content": null
  - "package": null

▸ CREATE task:
  - Creates a NEW file that does not yet exist in the repository
  - "operation": "create"
  - "file": full path to the new file IF user specified one; otherwise null
  - "instructions": brief description (e.g. "New utility class") — optional
  - "content": THE COMPLETE FILE CONTENT, character-for-character from user
    prompt, including package declaration, imports, all code
  - "package": Kotlin package name IF mentioned by user (e.g. "com.x.y.feature")
    or extractable from content's package declaration; otherwise null

═══ STRICT RULES ═══

RULE 1 — DETECT CREATE TASKS:
Look for phrases like:
- "Создать новый файл..."
- "Создай файл по пути..."
- "Создать XxxScreen.kt с этим кодом:"
- "Новый файл в пакете com.x.y"
- "Add a new file..."
- "Create file..."
These signal a CREATE task. If unsure, prefer MODIFY (safer).

RULE 2 — CREATE PATH/PACKAGE HANDLING:
- If user gave a FULL path like "app/src/main/java/com/x/Y.kt" → use it as "file"
- If user gave only a package like "com.opuside.app.feature.x" → put it in
  "package" field, leave "file" null (system will derive the path)
- If user gave BOTH a path AND a package → include both; system will validate
- If user gave NEITHER but provided content → leave both null;
  system will derive from the content's package declaration

RULE 3 — CREATE CONTENT IS VERBATIM:
The "content" field MUST contain the EXACT content user provided.
Do NOT clean up. Do NOT reformat. Do NOT remove blank lines.
Include the package declaration if present.
Include ALL imports.
Include EVERY character verbatim.

RULE 4 — MODIFY EXACT FILE PATHS:
For MODIFY tasks, "file" MUST be a path EXACTLY as it appears in the file list.
NEVER invent paths. NEVER abbreviate. NEVER guess.
If user wrote "DataRepositories.kt" but the real path is
"app/src/main/java/com/docs/scanner/data/repository/DataRepositories.kt" —
use the FULL real path.
If you cannot find a clear match for a MODIFY task, OMIT that task.

RULE 5 — ONE FILE = ONE TASK:
- If user describes 5 changes for 5 different files, return 5 tasks
- If user describes 2 changes both in file X, MERGE them into ONE MODIFY task
- NEVER create multiple tasks for the same file path

RULE 6 — MODIFY INSTRUCTIONS VERBATIM:
For MODIFY tasks, copy the relevant section of the user prompt verbatim,
including code blocks, line markers, "before/after" snippets. Do NOT summarize.
The downstream AI editor will see ONLY this instructions text + the file.

RULE 7 — ORDER PRESERVATION:
Keep tasks in the same order they appear in the user prompt.

RULE 8 — OUTPUT FORMAT (JSON only):
{
  "tasks": [
    {
      "operation": "modify",
      "file": "exact/repo/path.kt",
      "instructions": "verbatim instructions",
      "content": null,
      "package": null
    },
    {
      "operation": "create",
      "file": "app/src/main/java/com/x/y/NewFile.kt",
      "instructions": "Helper class for X",
      "content": "package com.x.y\n\nclass NewFile { ... }",
      "package": "com.x.y"
    }
  ]
}

No markdown. No code fences. No commentary. Pure JSON.
""".trimIndent()
    }

    // ═══════════════════════════════════════════════════════════════════════
    // RESPONSE SCHEMA — гарантия валидного JSON
    // ═══════════════════════════════════════════════════════════════════════

    private val responseSchema = buildJsonObject {
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
                                JsonPrimitive("modify"),
                                JsonPrimitive("create")
                            )))
                            put("description", "Type of operation on the file")
                        })
                        // file, content, package — все просто string и НЕ обязательны.
                        // Парсер трактует пустые/отсутствующие как null.
                        // Это работает универсально и для responseJsonSchema, и для responseSchema.
                        put("file", buildJsonObject {
                            put("type", "string")
                            put("description", "Full repository path (empty for create-by-package)")
                        })
                        put("instructions", buildJsonObject {
                            put("type", "string")
                            put("description", "Verbatim modification instructions (for modify) or short description (for create)")
                        })
                        put("content", buildJsonObject {
                            put("type", "string")
                            put("description", "Full content for create task; empty for modify")
                        })
                        put("package", buildJsonObject {
                            put("type", "string")
                            put("description", "Kotlin package for create task; empty otherwise")
                        })
                    })
                    put("required", JsonArray(listOf(
                        JsonPrimitive("operation"),
                        JsonPrimitive("instructions")
                    )))
                })
            })
        })
        put("required", JsonArray(listOf(JsonPrimitive("tasks"))))
    }

    // ═══════════════════════════════════════════════════════════════════════
    // MAIN ENTRY
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Запускает планирование.
     *
     * @param userPrompt сам промпт от пользователя
     * @param filePaths полный список путей файлов в репозитории.
     *                  ViewModel извлекает его из своего RepoIndexManager
     *                  (этим избегаем зависимости Planner от внутренней структуры RepoIndex).
     */
    suspend fun plan(
        userPrompt: String,
        filePaths: List<String>
    ): Result<PlannerOutput> = withContext(Dispatchers.IO) {
        try {
            if (userPrompt.isBlank()) {
                return@withContext Result.failure(
                    IllegalArgumentException("Промпт не может быть пустым")
                )
            }

            // 1. Проверяем что список путей не пуст
            if (filePaths.isEmpty()) {
                return@withContext Result.failure(
                    IllegalStateException("Репозиторий пуст или индекс ещё не загружен")
                )
            }

            // 2. Получаем API ключ Gemini с ротацией
            var apiKey = keyRotator.currentKey()
                ?: return@withContext Result.failure(
                    IllegalStateException("Добавьте Gemini API ключ на экране Pipeline.")
                )

            // 3. Строим компактный список путей для AI
            val pathsText = filePaths.joinToString("\n")

            // 4. Формируем user message для планировщика
            val userMessage = buildString {
                appendLine("═══ USER PROMPT (verbatim) ═══")
                appendLine(userPrompt)
                appendLine()
                appendLine("═══ AVAILABLE FILES IN REPOSITORY (${filePaths.size} total) ═══")
                appendLine(pathsText)
            }

            Log.d(TAG, "📤 Planning: prompt=${userPrompt.length}ch, files=${filePaths.size}")

            // 5. Вызов Gemini API с возможной ротацией ключа
            var result = callGemini(apiKey, userMessage)
            if (result.isFailure && isQuotaError(result.exceptionOrNull()?.message)) {
                val nextKey = keyRotator.burnAndRotate(apiKey)
                if (nextKey != null) {
                    Log.w(TAG, "⚠️ Quota exceeded, rotating key...")
                    apiKey = nextKey
                    result = callGemini(apiKey, userMessage)
                } else {
                    return@withContext Result.failure(
                        Exception("Оба Gemini ключа исчерпали квоту, попробуйте через 5 минут")
                    )
                }
            }

            val (rawJson, inputTokens, outputTokens) = result
                .getOrElse { return@withContext Result.failure(it) }

            // 6. Парсинг JSON
            val plannedTasks = parsePlanResponse(rawJson)
                .getOrElse { return@withContext Result.failure(it) }

            if (plannedTasks.isEmpty()) {
                return@withContext Result.failure(
                    IllegalStateException("Планировщик не вернул ни одной задачи. Проверь промпт.")
                )
            }

            if (plannedTasks.size > MAX_TASKS_LIMIT) {
                Log.w(TAG, "⚠️ Planner returned ${plannedTasks.size} tasks (limit=$MAX_TASKS_LIMIT)")
            }

            // 7. Резолвинг путей через локальный индекс из filePaths
            val resolvedTasks = resolvePaths(plannedTasks, filePaths)

            // 8. Расчёт стоимости
            val cost = (inputTokens * 0.25 + outputTokens * 1.50) / 1_000_000.0 * 0.92

            Log.d(TAG, "✅ Planned ${resolvedTasks.size} tasks " +
                    "(${inputTokens}in+${outputTokens}out, €${String.format(java.util.Locale.US, "%.5f", cost)})")

            Result.success(
                PlannerOutput(
                    tasks = resolvedTasks,
                    rawJson = rawJson,
                    tokensUsed = inputTokens + outputTokens,
                    costEur = cost
                )
            )

        } catch (e: java.net.SocketTimeoutException) {
            Result.failure(Exception("Таймаут планировщика. Попробуй ещё раз."))
        } catch (e: java.net.UnknownHostException) {
            Result.failure(Exception("Нет подключения к интернету"))
        } catch (e: Exception) {
            Log.e(TAG, "❌ Planning failed", e)
            Result.failure(e)
        }
    }

    /**
     * Резолвит fuzzy-пути от AI в реальные пути из индекса.
     * Если AI дал короткий путь "MainActivity.kt", найдёт полный
     * "app/src/main/java/.../MainActivity.kt".
     *
     * Если файл не найден вообще — задача всё равно включается, но с
     * пометкой originalPathFromAi != filePath (UI покажет предупреждение).
     */
    private fun resolvePaths(
        plannedTasks: List<PlannedTask>,
        filePaths: List<String>
    ): List<PlannedTask> {
        val seen = mutableSetOf<String>()
        val result = mutableListOf<PlannedTask>()
        // Pre-build path set для O(1) lookup точных совпадений
        val pathSet: Set<String> = filePaths.toSet()
        // Pre-build name index для O(1) fuzzy lookup
        val nameToFullPaths: Map<String, List<String>> = filePaths.groupBy {
            it.substringAfterLast('/')
        }
        // Source root (например "app/src/main/java/") — выводим из реальных путей
        val sourceRoot = detectSourceRoot(filePaths)

        for ((idx, task) in plannedTasks.withIndex()) {
            when (task.operation) {
                TaskOperation.MODIFY -> {
                    val resolved = resolveModifyPath(task, pathSet, nameToFullPaths)
                    addOrMerge(result, seen, resolved)
                }
                TaskOperation.CREATE -> {
                    val resolved = resolveCreatePath(task, idx, sourceRoot, pathSet)
                    if (resolved == null) {
                        Log.w(TAG, "Skipping CREATE task #$idx: cannot resolve path")
                        continue
                    }
                    addOrMerge(result, seen, resolved)
                }
            }
        }

        return result
    }

    // ─── MODIFY resolve ────────────────────────────────────────────────────

    private fun resolveModifyPath(
        task: PlannedTask,
        pathSet: Set<String>,
        nameToFullPaths: Map<String, List<String>>
    ): PlannedTask {
        val rawPath = task.file.trim().removePrefix("/").removePrefix("./").trim()
        if (rawPath.isBlank()) return task

        val resolvedPath = if (pathSet.contains(rawPath)) {
            rawPath
        } else {
            val fileName = rawPath.substringAfterLast('/')
            val candidates = nameToFullPaths[fileName] ?: emptyList()
            when {
                candidates.isEmpty() -> {
                    Log.w(TAG, "⚠️ Path not found in index: $rawPath — keeping as-is")
                    rawPath
                }
                candidates.size == 1 -> {
                    Log.d(TAG, "🎯 Fuzzy-resolved: '$rawPath' → '${candidates[0]}'")
                    candidates[0]
                }
                else -> {
                    val rawSegments = rawPath.split('/').filter { it.isNotBlank() }.toSet()
                    val best = candidates.maxByOrNull { candidatePath ->
                        candidatePath.split('/').count { it in rawSegments }
                    } ?: candidates[0]
                    Log.d(TAG, "🎯 Best-match: '$rawPath' → '$best' (${candidates.size} candidates)")
                    best
                }
            }
        }
        return task.copy(file = resolvedPath)
    }

    // ─── CREATE resolve ────────────────────────────────────────────────────

    /**
     * Резолвит путь для CREATE-задачи на основе данных от AI:
     * - явный path → используется как есть (с warning если не соответствует package)
     * - только package → выводим путь
     * - только content → извлекаем package из content + имя из объявления
     * - ничего → fallback на sourceRoot + сгенерированное имя
     */
    private fun resolveCreatePath(
        task: PlannedTask,
        index: Int,
        sourceRoot: String,
        existingPaths: Set<String>
    ): PlannedTask? {
        val content = task.content ?: return null
        val rawPath = task.file.trim().removePrefix("/").removePrefix("./").trim().ifBlank { null }
        val providedPkg = task.packageName?.trim()?.ifBlank { null }

        // Извлекаем package из самого контента (как fallback и для валидации)
        val pkgFromContent = extractPackageFromContent(content)
        val effectivePkg = providedPkg ?: pkgFromContent

        // Имя файла
        val nameFromPath = rawPath?.substringAfterLast('/')?.takeIf {
            it.contains('.') // Принимаем абсолютно любые файлы с расширением (.json, .txt, .kts и т.д.)
        }
        val nameFromContent = deriveFileNameFromContent(content)
        val finalName = nameFromPath ?: nameFromContent ?: "GeneratedFile${index + 1}.kt"

        // Финальный путь
        val finalPath: String = when {
            // Полный путь предоставлен и валиден
            rawPath != null && nameFromPath != null -> {
                if (effectivePkg != null && !pathMatchesPackage(rawPath, effectivePkg)) {
                    Log.w(TAG, "⚠️ Path/package mismatch: path='$rawPath' vs package='$effectivePkg' — using path")
                }
                rawPath
            }
            rawPath != null && rawPath.endsWith('/') -> "$rawPath$finalName"
            rawPath != null -> "$rawPath/$finalName"
            effectivePkg != null -> "${sourceRoot}${effectivePkg.replace('.', '/')}/$finalName"
            else -> {
                Log.w(TAG, "⚠️ CREATE without path/package — using fallback location")
                "${sourceRoot}generated/$finalName"
            }
        }

        if (existingPaths.contains(finalPath)) {
            Log.w(TAG, "⚠️ CREATE target already exists: $finalPath — keeping task, executor will fail with FILE_ALREADY_EXISTS")
        }

        return task.copy(
            file = finalPath,
            packageName = effectivePkg
        )
    }

    /**
     * Извлекает package declaration из контента Kotlin/Java файла.
     */
    private fun extractPackageFromContent(content: String): String? {
        val regex = Regex("""^\s*package\s+([\w.]+)""", RegexOption.MULTILINE)
        return regex.find(content)?.groupValues?.get(1)?.trim()?.ifBlank { null }
    }

    /**
     * Пытается извлечь имя файла из содержимого:
     * первый класс / object / interface / @Composable fun / top-level fun.
     */
    private fun deriveFileNameFromContent(content: String): String? {
        val patterns = listOf(
            Regex("""(?:public\s+|internal\s+|private\s+)?(?:abstract\s+|sealed\s+|open\s+|data\s+|enum\s+)?class\s+(\w+)"""),
            Regex("""(?:public\s+|internal\s+|private\s+)?object\s+(\w+)"""),
            Regex("""(?:public\s+|internal\s+|private\s+)?interface\s+(\w+)"""),
            Regex("""@Composable\s+(?:public\s+|internal\s+|private\s+)?fun\s+(\w+)\s*\("""),
            Regex("""(?:public\s+|internal\s+)?fun\s+(\w+)\s*\(""")
        )
        for (pattern in patterns) {
            val m = pattern.find(content)
            if (m != null) {
                val name = m.groupValues[1]
                if (name.isNotBlank()) return "$name.kt"
            }
        }
        return null
    }

    /**
     * Проверяет соответствует ли путь package.
     */
    private fun pathMatchesPackage(path: String, packageName: String): Boolean {
        val pkgAsPath = packageName.replace('.', '/')
        return path.contains("/$pkgAsPath/")
    }

    /**
     * Определяет source root репозитория из списка путей.
     * Стратегия: берём первый .kt файл и обрезаем его путь до начала package.
     */
    private fun detectSourceRoot(filePaths: List<String>): String {
        val packageRoots = setOf("com", "org", "io", "net", "ru", "de", "tech")
        for (path in filePaths) {
            if (!path.endsWith(".kt")) continue
            val parts = path.split('/')
            val pkgStartIdx = parts.indexOfFirst { it in packageRoots }
            if (pkgStartIdx > 0) {
                return parts.take(pkgStartIdx).joinToString("/") + "/"
            }
        }
        Log.w(TAG, "⚠️ Could not detect source root — falling back to default")
        return "app/src/main/java/"
    }

    // ─── Dedup / merge ─────────────────────────────────────────────────────

    private fun addOrMerge(
        result: MutableList<PlannedTask>,
        seen: MutableSet<String>,
        task: PlannedTask
    ) {
        val path = task.file
        if (path.isBlank()) return

        if (path in seen) {
            val existingIdx = result.indexOfFirst { it.file == path }
            if (existingIdx >= 0) {
                val existing = result[existingIdx]
                if (existing.operation == TaskOperation.MODIFY && task.operation == TaskOperation.MODIFY) {
                    result[existingIdx] = existing.copy(
                        instructions = existing.instructions +
                                "\n\n--- ДОПОЛНИТЕЛЬНО ---\n\n" + task.instructions
                    )
                    Log.d(TAG, "🔀 Merged duplicate MODIFY task for: $path")
                } else {
                    Log.w(TAG, "⚠️ Duplicate task for $path with conflicting operations — keeping first")
                }
            }
            return
        }
        seen.add(path)
        result.add(task)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // GEMINI API CALL
    // ═══════════════════════════════════════════════════════════════════════

    private fun callGemini(
        apiKey: String,
        userMessage: String
    ): Result<Triple<String, Int, Int>> {
        var connection: HttpURLConnection? = null
        return try {
            val url = "$BASE_URL/$PLANNER_MODEL:generateContent"

            val requestBody = buildJsonObject {
                put("system_instruction", buildJsonObject {
                    put("parts", JsonArray(listOf(
                        buildJsonObject { put("text", PLANNER_SYSTEM_PROMPT) }
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
                    put("temperature", 0.0)               // максимальная детерминированность
                    put("topP", 0.95)
                    put("responseMimeType", "application/json")
                    put("responseJsonSchema", responseSchema)
                })
            }

            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("x-goog-api-key", apiKey)  // безопаснее чем ?key=...
                setRequestProperty("Accept", "application/json")
                connectTimeout = 30_000
                readTimeout = 120_000
                doOutput = true
                doInput = true
            }

            connection.outputStream.use {
                it.write(requestBody.toString().toByteArray(Charsets.UTF_8))
            }

            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                val errorBody = connection.errorStream?.let {
                    BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() }
                } ?: "HTTP $responseCode"
                Log.e(TAG, "❌ Planner API error $responseCode: ${errorBody.take(500)}")
                return Result.failure(Exception(formatApiError(responseCode, errorBody)))
            }

            val responseBody = BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8))
                .use { it.readText() }
            val json = Json.parseToJsonElement(responseBody).jsonObject

            // Извлекаем текст ответа (это будет JSON план)
            val candidates = json["candidates"]?.jsonArray
                ?: return Result.failure(Exception("Planner: no candidates in response"))
            if (candidates.isEmpty()) {
                return Result.failure(Exception("Planner: empty candidates"))
            }

            // Проверка finishReason — если SAFETY/RECITATION, response может быть пустой
            val finishReason = candidates[0].jsonObject["finishReason"]
                ?.jsonPrimitive?.contentOrNull
            if (finishReason != null && finishReason != "STOP" && finishReason != "MAX_TOKENS") {
                return Result.failure(Exception("Planner отклонил запрос: finishReason=$finishReason"))
            }

            val content = candidates[0].jsonObject["content"]?.jsonObject
                ?: return Result.failure(Exception("Planner: no content in candidate"))
            val parts = content["parts"]?.jsonArray
                ?: return Result.failure(Exception("Planner: no parts in content"))

            val rawJson = parts.joinToString("") { part ->
                part.jsonObject["text"]?.jsonPrimitive?.contentOrNull ?: ""
            }

            if (rawJson.isBlank()) {
                return Result.failure(Exception("Planner returned empty response"))
            }

            // Usage metadata
            val usage = json["usageMetadata"]?.jsonObject
            val inputTokens = usage?.get("promptTokenCount")?.jsonPrimitive?.intOrNull ?: 0
            val outputTokens = usage?.get("candidatesTokenCount")?.jsonPrimitive?.intOrNull ?: 0

            Result.success(Triple(rawJson, inputTokens, outputTokens))

        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            try {
                connection?.disconnect()
            } catch (_: Exception) { /* ignore */ }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PARSE PLAN JSON
    // ═══════════════════════════════════════════════════════════════════════

    private fun parsePlanResponse(rawJson: String): Result<List<PlannedTask>> {
        return try {
            // Иногда Gemini в structured-output режиме всё равно может обернуть в фенсы.
            // Очищаем на всякий случай.
            val cleaned = rawJson.trim()
                .removePrefix("```json").removePrefix("```")
                .removeSuffix("```")
                .trim()

            val root = Json.parseToJsonElement(cleaned).jsonObject
            val tasksArray = root["tasks"]?.jsonArray
                ?: return Result.failure(Exception("Plan JSON: missing 'tasks' field"))

            val tasks = tasksArray.mapNotNull { element ->
                val obj = element.jsonObject

                // Operation: modify / create (default modify для backward-compat)
                val opStr = obj["operation"]?.jsonPrimitive?.contentOrNull?.lowercase()
                val operation = when (opStr) {
                    "create" -> TaskOperation.CREATE
                    "modify", null -> TaskOperation.MODIFY
                    else -> {
                        Log.w(TAG, "Unknown operation '$opStr', falling back to MODIFY")
                        TaskOperation.MODIFY
                    }
                }

                // File path может быть null для CREATE (когда только package)
                val file = obj["file"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                val instructions = obj["instructions"]?.jsonPrimitive?.contentOrNull ?: ""
                val content = obj["content"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                val pkg = obj["package"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

                when (operation) {
                    TaskOperation.MODIFY -> {
                        // MODIFY требует file + instructions
                        if (file == null || instructions.isBlank()) {
                            Log.w(TAG, "Skipping invalid MODIFY task: file=$file, instr=${instructions.length}ch")
                            null
                        } else {
                            PlannedTask(
                                operation = TaskOperation.MODIFY,
                                file = file,
                                instructions = instructions
                            )
                        }
                    }
                    TaskOperation.CREATE -> {
                        // CREATE требует content (file/package будет разрешён в resolveCreatePath)
                        if (content == null) {
                            Log.w(TAG, "Skipping CREATE task without content")
                            null
                        } else {
                            // file может быть null — будет вычислен в resolvePaths
                            PlannedTask(
                                operation = TaskOperation.CREATE,
                                file = file ?: "",   // пустой = нужно вычислить
                                instructions = instructions,
                                content = content,
                                packageName = pkg
                            )
                        }
                    }
                }
            }

            Result.success(tasks)
        } catch (e: Exception) {
            Log.e(TAG, "❌ Failed to parse plan JSON: ${rawJson.take(500)}", e)
            Result.failure(Exception("Не удалось распарсить план: ${e.message}"))
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ERROR FORMATTING
    // ═══════════════════════════════════════════════════════════════════════

    private fun formatApiError(code: Int, body: String): String {
        val msg = try {
            val json = Json.parseToJsonElement(body).jsonObject
            json["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
                ?: body.take(200)
        } catch (_: Exception) { body.take(200) }

        return when (code) {
            400 -> "Планировщик: ошибка запроса — $msg"
            401 -> "Планировщик: неверный API ключ Gemini"
            403 -> "Планировщик: доступ запрещён — $msg"
            429 -> "Планировщик: превышен лимит API. Подожди ~30 секунд."
            500, 502, 503 -> "Планировщик: сервер Gemini временно недоступен"
            else -> "Планировщик: ошибка $code — $msg"
        }
    }

    private fun isQuotaError(message: String?): Boolean {
        val m = message?.lowercase() ?: return false
        return m.contains("429") || m.contains("quota") || m.contains("exceeded") || m.contains("rate limit")
    }
}
