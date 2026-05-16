package com.opuside.app.feature.pipeline.data

import android.util.Log
import com.opuside.app.core.ai.RepoIndexManager
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
    private val repoIndexManager: RepoIndexManager
) {

    companion object {
        private const val TAG = "PipelinePlanner"
        private const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/models"
        private const val PLANNER_MODEL = "gemini-3.1-flash-lite-preview"
        private const val MAX_OUTPUT_TOKENS = 16384      // плана хватит
        private const val MAX_TASKS_LIMIT = 30           // больше 30 файлов за раз — подозрительно

        // Системный промпт планировщика. Жёсткие правила, чтобы AI не "креативил".
        private val PLANNER_SYSTEM_PROMPT = """
You are a precision TASK PLANNER for a code modification pipeline.

═══ YOUR JOB ═══

The user will give you a large multi-file modification request. You receive:
1. The full user prompt
2. A list of all real file paths in the repository

You MUST return a JSON array of tasks, where each task is ONE file with ONE
clear set of instructions extracted from the user prompt.

═══ STRICT RULES ═══

RULE 1 — EXACT FILE PATHS:
- The "file" field MUST be a path EXACTLY as it appears in the provided file list
- NEVER invent paths. NEVER abbreviate. NEVER guess.
- If the user wrote "DataRepositories.kt" but the real path is
  "app/src/main/java/com/docs/scanner/data/repository/DataRepositories.kt" —
  use the FULL real path.
- If you cannot find a clear match for a file mentioned by the user, OMIT
  that file from the output. Do NOT include it with a guessed path.

RULE 2 — ONE FILE = ONE TASK:
- If the user describes 5 changes for 5 different files, return 5 tasks.
- If the user describes 2 changes both in file X, MERGE them into ONE task
  for file X with combined instructions.
- NEVER create multiple tasks for the same file path.

RULE 3 — INSTRUCTIONS QUALITY:
- The "instructions" field MUST be self-contained. The downstream AI editor
  will see ONLY this instructions text and the file content. It will NOT
  see the original user prompt.
- Copy the relevant section of the user prompt VERBATIM, including code blocks,
  line markers, "before/after" snippets, and explanatory text.
- Do NOT summarize. Do NOT paraphrase. Do NOT "improve" the instructions.
- If the user provided exact code to replace and exact code to insert, those
  must appear in instructions character-for-character.

RULE 4 — ORDER PRESERVATION:
- Keep tasks in the same order they appear in the user prompt.
- Don't reorder for "logical" reasons.

RULE 5 — OUTPUT FORMAT (JSON only):
Return ONLY this JSON structure, nothing else:
{
  "tasks": [
    { "file": "exact/repo/path.kt", "instructions": "verbatim instructions block" }
  ]
}

No markdown. No code fences. No commentary before or after. Pure JSON.
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
                        put("file", buildJsonObject {
                            put("type", "string")
                            put("description", "Full repository path to the file")
                        })
                        put("instructions", buildJsonObject {
                            put("type", "string")
                            put("description", "Verbatim modification instructions for this file")
                        })
                    })
                    put("required", JsonArray(listOf(JsonPrimitive("file"), JsonPrimitive("instructions"))))
                })
            })
        })
        put("required", JsonArray(listOf(JsonPrimitive("tasks"))))
    }

    // ═══════════════════════════════════════════════════════════════════════
    // MAIN ENTRY
    // ═══════════════════════════════════════════════════════════════════════

    suspend fun plan(userPrompt: String): Result<PlannerOutput> = withContext(Dispatchers.IO) {
        try {
            if (userPrompt.isBlank()) {
                return@withContext Result.failure(
                    IllegalArgumentException("Промпт не может быть пустым")
                )
            }

            // 1. Получаем индекс репозитория (список всех путей)
            val index = repoIndexManager.getOrRefresh()
                ?: return@withContext Result.failure(
                    IllegalStateException("Не удалось загрузить индекс репозитория. Проверь настройки GitHub.")
                )

            if (!index.nodes.any { it.isFile }) {
                return@withContext Result.failure(
                    IllegalStateException("Репозиторий пуст или содержит только директории")
                )
            }

            // 2. Получаем API ключ Gemini
            val apiKey = secureSettings.getGeminiApiKey().first()
            if (apiKey.isBlank()) {
                return@withContext Result.failure(
                    IllegalStateException("Gemini API key не настроен. Укажи в Settings → Gemini.")
                )
            }

            // 3. Строим компактный список путей файлов (только файлы, без директорий)
            //    Это даст планировщику возможность точно сопоставить упоминания
            val pathsText = index.nodes
                .filter { it.isFile }
                .joinToString("\n") { it.path }

            // 4. Формируем user message для планировщика
            val userMessage = buildString {
                appendLine("═══ USER PROMPT (verbatim) ═══")
                appendLine(userPrompt)
                appendLine()
                appendLine("═══ AVAILABLE FILES IN REPOSITORY (${index.totalFiles} total) ═══")
                appendLine(pathsText)
            }

            Log.d(TAG, "📤 Planning: prompt=${userPrompt.length}ch, files=${index.totalFiles}")

            // 5. Вызов Gemini API
            val (rawJson, inputTokens, outputTokens) = callGemini(apiKey, userMessage)
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

            // 7. Fuzzy-resolve путей через индекс репо
            val resolvedTasks = resolvePaths(plannedTasks, index)

            // 8. Расчёт стоимости
            val cost = (inputTokens * 0.25 + outputTokens * 1.50) / 1_000_000.0 * 0.92

            Log.d(TAG, "✅ Planned ${resolvedTasks.size} tasks " +
                    "(${inputTokens}in+${outputTokens}out, €${"%.5f".format(cost)})")

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
        index: RepoIndexManager.RepoIndex
    ): List<PlannedTask> {
        val seen = mutableSetOf<String>()
        val result = mutableListOf<PlannedTask>()
        // Pre-build path index для O(1) lookup
        val pathToNode = index.nodes.filter { it.isFile }.associateBy { it.path }

        for (task in plannedTasks) {
            val rawPath = task.file.trim().removePrefix("/").removePrefix("./").trim()
            if (rawPath.isBlank()) continue

            // Точное совпадение?
            val exactMatch = pathToNode[rawPath]
            val resolvedPath = if (exactMatch != null) {
                rawPath
            } else {
                // Fuzzy: ищем по имени файла
                val fileName = rawPath.substringAfterLast('/')
                val candidates = try {
                    index.findByName(fileName).filter { it.isFile }
                } catch (e: Throwable) {
                    // На случай если у пользователя findByName иначе называется
                    index.nodes.filter { it.isFile && it.path.substringAfterLast('/') == fileName }
                }

                when {
                    candidates.isEmpty() -> {
                        Log.w(TAG, "⚠️ Path not found in index: $rawPath — keeping as-is")
                        rawPath  // оставляем как есть, executor попробует прочитать
                    }
                    candidates.size == 1 -> {
                        Log.d(TAG, "🎯 Fuzzy-resolved: '$rawPath' → '${candidates[0].path}'")
                        candidates[0].path
                    }
                    else -> {
                        // Несколько кандидатов — берём тот у которого больше всего общих сегментов
                        val rawSegments = rawPath.split('/').filter { it.isNotBlank() }.toSet()
                        val best = candidates.maxByOrNull { candidate ->
                            candidate.path.split('/').count { it in rawSegments }
                        } ?: candidates[0]
                        Log.d(TAG, "🎯 Best-match: '$rawPath' → '${best.path}' " +
                                "(${candidates.size} candidates)")
                        best.path
                    }
                }
            }

            // Дедупликация: если две задачи на один файл, мерджим инструкции
            if (resolvedPath in seen) {
                val existing = result.indexOfFirst { it.file == resolvedPath }
                if (existing >= 0) {
                    val merged = result[existing].copy(
                        instructions = result[existing].instructions +
                                "\n\n--- ДОПОЛНИТЕЛЬНО ---\n\n" + task.instructions
                    )
                    result[existing] = merged
                    Log.d(TAG, "🔀 Merged duplicate task for: $resolvedPath")
                }
                continue
            }
            seen.add(resolvedPath)

            result.add(PlannedTask(file = resolvedPath, instructions = task.instructions))
        }

        return result
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
                val file = obj["file"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                val instructions = obj["instructions"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                if (file != null && instructions != null) {
                    PlannedTask(file = file, instructions = instructions)
                } else null
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
}
