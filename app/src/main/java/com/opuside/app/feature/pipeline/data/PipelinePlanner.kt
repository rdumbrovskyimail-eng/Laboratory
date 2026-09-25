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
 * 📋 PIPELINE PLANNER v3.2 (High-Speed & CoT Protected)
 *
 * Отвечает за парсинг промпта пользователя и составление JSON-плана задач.
 */
@Singleton
class PipelinePlanner @Inject constructor(
    private val secureSettings: SecureSettingsDataStore,
    private val appSettings: AppSettings,
    private val keyRotator: PipelineKeyRotator
) {

    companion object {
        private const val TAG = "PipelinePlanner"
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
        private const val MAX_OUTPUT_TOKENS = 16384
        private const val MAX_TASKS_LIMIT = 100

        private val PLANNER_SYSTEM_PROMPT = """
You are a HIGH-SPEED TASK PLANNER for a code modification pipeline.

═══ YOUR JOB ═══
Analyze the user prompt and match operations to existing or new files.
Return a JSON object containing an array of tasks according to the schema.

═══ TASK TYPES ═══
- "modify": Changes an existing file in the repository ("file" must be exact path from the list).
- "create": Adds a new file ("file" path or "package", "content" must be complete verbatim code).
- "delete": Deletes an existing file ("file" must be exact path from the list).

═══ STRICT RULES ═══
1. "file" for modify/delete MUST match a real file from the provided list. No guessing!
2. "content" for create MUST contain full, complete source code verbatim.
3. Return ONLY valid JSON adhering to the schema. No markdown, no explanations.
""".trimIndent()
    }

    private val responseSchema = buildJsonObject {
        put("type", "OBJECT")
        put("properties", buildJsonObject {
            put("tasks", buildJsonObject {
                put("type", "ARRAY")
                put("items", buildJsonObject {
                    put("type", "OBJECT")
                    put("properties", buildJsonObject {
                        put("operation", buildJsonObject {
                            put("type", "STRING")
                            put("enum", JsonArray(listOf(
                                JsonPrimitive("modify"),
                                JsonPrimitive("create"),
                                JsonPrimitive("delete")
                            )))
                            put("description", "Type of operation")
                        })
                        put("file", buildJsonObject {
                            put("type", "STRING")
                            put("description", "Full repository path")
                        })
                        put("instructions", buildJsonObject {
                            put("type", "STRING")
                            put("description", "Verbatim instructions from user prompt")
                        })
                        put("content", buildJsonObject {
                            put("type", "STRING")
                            put("description", "Full code content for create task")
                        })
                        put("package", buildJsonObject {
                            put("type", "STRING")
                            put("description", "Kotlin package for create task")
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

    suspend fun plan(
        userPrompt: String,
        filePaths: List<String>,
        modelApiId: String? = null
    ): Result<PlannerOutput> = withContext(Dispatchers.IO) {
        try {
            if (userPrompt.isBlank()) {
                return@withContext Result.failure(
                    IllegalArgumentException("Промпт не может быть пустым")
                )
            }
            if (filePaths.isEmpty()) {
                return@withContext Result.failure(
                    IllegalStateException("Репозиторий пуст или индекс ещё не загружен")
                )
            }

            val effectiveModel = GeminiModel.fromModelId(modelApiId ?: "") ?: GeminiModel.getDefault()

            // Каскадный подбор API-ключа
            val rotatorKeyInfo = keyRotator.currentKey()
            var currentApiKey = rotatorKeyInfo?.first
                ?: secureSettings.getActiveGeminiApiKey().first().ifBlank { null }
                ?: secureSettings.getGeminiApiKey().first().ifBlank { null }
                ?: appSettings.geminiApiKey.first().ifBlank { null }

            if (currentApiKey.isNullOrBlank()) {
                return@withContext Result.failure(
                    IllegalStateException("Gemini API ключ не найден")
                )
            }

            var currentRotatorIdx = rotatorKeyInfo?.second ?: -1

            // Оптимизация: исключаем мусор сборки и бинарники для сокращения токенов
            val cleanPaths = filePaths.filter { p ->
                !p.startsWith(".") && !p.startsWith("build/") && !p.contains("/build/") &&
                !p.endsWith(".png") && !p.endsWith(".jar") && !p.endsWith(".webp") &&
                !p.endsWith(".so") && !p.endsWith(".aar")
            }.take(1000)

            val userMessage = buildString {
                appendLine("═══ USER PROMPT ═══")
                appendLine(userPrompt)
                appendLine()
                appendLine("═══ AVAILABLE REPOSITORY FILES (${cleanPaths.size}) ═══")
                appendLine(cleanPaths.joinToString("\n"))
            }

            Log.d(TAG, "📤 Планирование [${effectiveModel.displayName}]: prompt=${userPrompt.length}ch, files=${cleanPaths.size}")

            var result: Result<Triple<String, Int, Int>> = Result.failure(Exception("not called"))
            var attempts = 0

            while (attempts < 2) {
                result = callGemini(
                    apiKey = currentApiKey!!,
                    model = effectiveModel,
                    userMessage = userMessage
                )

                if (result.isSuccess) break

                val errMsg = result.exceptionOrNull()?.message
                if (!isQuotaError(errMsg)) break

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

            val (rawJson, inputTokens, outputTokens) = result.getOrElse {
                return@withContext Result.failure(it)
            }

            val plannedTasks = parsePlanResponse(rawJson).getOrElse {
                return@withContext Result.failure(it)
            }

            if (plannedTasks.isEmpty()) {
                return@withContext Result.failure(
                    IllegalStateException("Планировщик не сформировал задачи. Проверьте формулировку промпта.")
                )
            }

            val resolvedTasks = resolvePaths(plannedTasks, filePaths)
            val cost = (inputTokens * effectiveModel.inputPricePerM + outputTokens * effectiveModel.outputPricePerM) / 1_000_000.0 * 0.92

            Log.d(TAG, "✅ План сформирован: ${resolvedTasks.size} задач [${effectiveModel.displayName}] " +
                    "(${inputTokens}in + ${outputTokens}out, €${String.format(java.util.Locale.US, "%.5f", cost)})")

            Result.success(PlannerOutput(
                tasks = resolvedTasks,
                rawJson = rawJson,
                tokensUsed = inputTokens + outputTokens,
                costEur = cost
            ))
        } catch (e: Exception) {
            Log.e(TAG, "❌ Ошибка планирования", e)
            Result.failure(e)
        }
    }

    private fun resolvePaths(
        plannedTasks: List<PlannedTask>,
        filePaths: List<String>
    ): List<PlannedTask> {
        val seen = mutableSetOf<String>()
        val result = mutableListOf<PlannedTask>()
        val pathSet = filePaths.toSet()
        val nameToFullPaths = filePaths.groupBy { it.substringAfterLast('/') }
        val sourceRoot = detectSourceRoot(filePaths)

        for ((idx, task) in plannedTasks.withIndex()) {
            when (task.operation) {
                TaskOperation.MODIFY -> {
                    val resolved = resolveModifyPath(task, pathSet, nameToFullPaths)
                    addOrMerge(result, seen, resolved)
                }
                TaskOperation.CREATE -> {
                    val resolved = resolveCreatePath(task, idx, sourceRoot, pathSet)
                    if (resolved != null) {
                        addOrMerge(result, seen, resolved)
                    }
                }
                TaskOperation.DELETE -> {
                    val resolved = resolveModifyPath(task, pathSet, nameToFullPaths)
                    if (resolved.file in pathSet) {
                        addOrMerge(result, seen, resolved)
                    }
                }
            }
        }

        return result
    }

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
                candidates.isEmpty() -> rawPath
                candidates.size == 1 -> candidates[0]
                else -> {
                    val rawSegments = rawPath.split('/').filter { it.isNotBlank() }.toSet()
                    candidates.maxByOrNull { candidate ->
                        candidate.split('/').count { it in rawSegments }
                    } ?: candidates[0]
                }
            }
        }
        return task.copy(file = resolvedPath)
    }

    private fun resolveCreatePath(
        task: PlannedTask,
        index: Int,
        sourceRoot: String,
        existingPaths: Set<String>
    ): PlannedTask? {
        val content = task.content ?: return null
        val rawPath = task.file.trim().removePrefix("/").removePrefix("./").trim().ifBlank { null }
        val providedPkg = task.packageName?.trim()?.ifBlank { null }

        val pkgFromContent = extractPackageFromContent(content)
        val effectivePkg = providedPkg ?: pkgFromContent

        val nameFromPath = rawPath?.substringAfterLast('/')?.takeIf { it.contains('.') }
        val nameFromContent = deriveFileNameFromContent(content)
        val finalName = nameFromPath ?: nameFromContent ?: "GeneratedFile${index + 1}.kt"

        val finalPath: String = when {
            rawPath != null && nameFromPath != null -> rawPath
            rawPath != null && rawPath.endsWith('/') -> "$rawPath$finalName"
            rawPath != null -> "$rawPath/$finalName"
            effectivePkg != null -> "${sourceRoot}${effectivePkg.replace('.', '/')}/$finalName"
            else -> "${sourceRoot}generated/$finalName"
        }

        return task.copy(
            file = finalPath,
            packageName = effectivePkg
        )
    }

    private fun extractPackageFromContent(content: String): String? {
        val regex = Regex("""^\s*package\s+([\w.]+)""", RegexOption.MULTILINE)
        return regex.find(content)?.groupValues?.get(1)?.trim()?.ifBlank { null }
    }

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
        return "app/src/main/java/"
    }

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
                }
            }
            return
        }
        seen.add(path)
        result.add(task)
    }

    private fun callGemini(
        apiKey: String,
        model: GeminiModel,
        userMessage: String
    ): Result<Triple<String, Int, Int>> {
        var connection: HttpURLConnection? = null
        return try {
            val url = "$BASE_URL/${model.modelId}:generateContent"

            val requestBody = buildJsonObject {
                put("systemInstruction", buildJsonObject {
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
                    // Оптимизировано: убран temperature 0.0, thinkingLevel зафиксирован на LOW
                    put("responseMimeType", "application/json")
                    put("responseJsonSchema", responseSchema)
                    put("thinkingConfig", buildJsonObject {
                        put("thinkingLevel", JsonPrimitive("LOW"))
                    })
                })
            }

            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("x-goog-api-key", apiKey)
                setRequestProperty("Accept", "application/json")
                connectTimeout = 15_000
                readTimeout = 45_000
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
                return Result.failure(Exception(formatApiError(responseCode, errorBody)))
            }

            val responseBody = BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8))
                .use { it.readText() }
            val json = Json.parseToJsonElement(responseBody).jsonObject

            val candidates = json["candidates"]?.jsonArray
                ?: return Result.failure(Exception("Планировщик: отсутствует поле candidates"))
            if (candidates.isEmpty()) {
                return Result.failure(Exception("Планировщик: пустой список candidates"))
            }

            val content = candidates[0].jsonObject["content"]?.jsonObject
                ?: return Result.failure(Exception("Планировщик: отсутствует content"))
            val parts = content["parts"]?.jsonArray
                ?: return Result.failure(Exception("Планировщик: отсутствуют parts"))

            val rawJson = parts.filter { part ->
                part.jsonObject["thought"]?.jsonPrimitive?.booleanOrNull != true
            }.joinToString("") { part ->
                part.jsonObject["text"]?.jsonPrimitive?.contentOrNull ?: ""
            }

            val usage = json["usageMetadata"]?.jsonObject
            val inputTokens = usage?.get("promptTokenCount")?.jsonPrimitive?.intOrNull ?: 0
            val outputTokens = usage?.get("candidatesTokenCount")?.jsonPrimitive?.intOrNull ?: 0

            Result.success(Triple(rawJson, inputTokens, outputTokens))

        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            try { connection?.disconnect() } catch (_: Exception) { }
        }
    }

    private fun parsePlanResponse(rawJson: String): Result<List<PlannedTask>> {
        return try {
            val cleaned = rawJson.trim()
                .removePrefix("```json").removePrefix("```")
                .removeSuffix("```")
                .trim()

            val root = Json.parseToJsonElement(cleaned).jsonObject
            val tasksArray = root["tasks"]?.jsonArray
                ?: return Result.failure(Exception("JSON плана не содержит массива 'tasks'"))

            val tasks = tasksArray.mapNotNull { element ->
                val obj = element.jsonObject

                val opStr = obj["operation"]?.jsonPrimitive?.contentOrNull?.lowercase()
                val operation = when (opStr) {
                    "create" -> TaskOperation.CREATE
                    "delete" -> TaskOperation.DELETE
                    else -> TaskOperation.MODIFY
                }

                val file = obj["file"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                val instructions = obj["instructions"]?.jsonPrimitive?.contentOrNull ?: ""
                val content = obj["content"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                val pkg = obj["package"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

                when (operation) {
                    TaskOperation.MODIFY -> {
                        if (file == null || instructions.isBlank()) null
                        else PlannedTask(TaskOperation.MODIFY, file, instructions)
                    }
                    TaskOperation.CREATE -> {
                        if (content == null) null
                        else PlannedTask(TaskOperation.CREATE, file ?: "", instructions, content, pkg)
                    }
                    TaskOperation.DELETE -> {
                        if (file == null) null
                        else PlannedTask(TaskOperation.DELETE, file, instructions.ifBlank { "Удалить файл" })
                    }
                }
            }

            Result.success(tasks)
        } catch (e: Exception) {
            Result.failure(Exception("Не удалось распарсить JSON план: ${e.message}"))
        }
    }

    private fun formatApiError(code: Int, body: String): String {
        val msg = try {
            val json = Json.parseToJsonElement(body).jsonObject
            json["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull ?: body.take(200)
        } catch (_: Exception) { body.take(200) }

        return when (code) {
            400 -> "Планировщик: ошибка параметров запроса (400): $msg"
            401 -> "Планировщик: неверный API-ключ Gemini"
            403 -> "Планировщик: доступ запрещён (403)"
            429 -> "Планировщик: превышен лимит запросов (429)"
            500, 502, 503 -> "Планировщик: сервер Gemini временно недоступен"
            else -> "Планировщик: ошибка $code ($msg)"
        }
    }

    private fun isQuotaError(message: String?): Boolean {
        val m = message?.lowercase() ?: return false
        return m.contains("429") || m.contains("quota") || m.contains("exceeded") || m.contains("rate limit")
    }
}