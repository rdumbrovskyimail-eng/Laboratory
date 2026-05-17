package com.opuside.app.feature.pipeline.data

import android.util.Base64
import android.util.Log
import com.opuside.app.core.ai.RepoIndexManager
import com.opuside.app.core.data.AppSettings
import com.opuside.app.core.network.github.GitHubApiClient
import com.opuside.app.core.network.github.GitHubApiException
import com.opuside.app.feature.creator.data.CreatorAIEditService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * PIPELINE EXECUTOR v1.0
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Берёт одну FileTask, выполняет полный цикл:
 *
 *   1. getFileContent (читаем файл + берём SHA)
 *   2. CreatorAIEditService.processEdit (тот самый 99% точный движок)
 *   3. CreatorAIEditService.applyEdits (применяем блоки)
 *   4. createOrUpdateFile (коммитим)
 *   5. Если 409 → подтягиваем свежий SHA → retry (auto KEEP_MINE)
 *   6. RepoIndexManager.invalidate() (чтобы следующая задача увидела свежий SHA)
 *
 * Логика ошибок:
 *   - HTTP 401/403  → Fatal (останавливаем весь конвейер)
 *   - HTTP 429      → ждём 15с, retry (без счётчика)
 *   - HTTP 5xx      → Deferrable
 *   - 409 conflict  → auto-resolve (свежий SHA, retry)
 *   - NOT_FOUND blocks (ВСЕ блоки не нашлись) → Deferrable
 *   - blocks empty  → коммитим оригинал как NO_CHANGES_NEEDED
 *   - сеть/таймаут  → Deferrable
 */
@Singleton
class PipelineExecutor @Inject constructor(
    private val gitHubClient: GitHubApiClient,
    private val aiEditService: CreatorAIEditService,
    private val repoIndexManager: RepoIndexManager,
    private val appSettings: AppSettings
) {

    companion object {
        private const val TAG = "PipelineExecutor"
    }

    /** Per-file Mutex: гарантирует что 2 задачи на один filePath не пересекутся (read→edit→commit). */
    private val fileLocks = ConcurrentHashMap<String, Mutex>()
    fun lockFor(path: String): Mutex = fileLocks.computeIfAbsent(path) { Mutex() }

    /** Глобальный GitHub write throttle: между PUT-запросами держим минимум 800мс независимо от параллелизма. */
    private val githubWriteMutex = Mutex()
    @Volatile private var lastGithubWriteMs: Long = 0L
    private suspend fun throttleGithubWrite() {
        githubWriteMutex.withLock {
            val now = System.currentTimeMillis()
            val elapsed = now - lastGithubWriteMs
            val needed = 800L - elapsed
            if (needed > 0) delay(needed)
            lastGithubWriteMs = System.currentTimeMillis()
        }
    }
        private const val CONFLICT_RETRY_MAX = 3
        private const val NETWORK_RETRY_MAX = 2
        private const val INTER_FILE_DELAY_MS = 1500L
        private val MODEL = CreatorAIEditService.AiModel.GEMINI_3_1_FLASH_LITE

        private const val RETRY_HINT_PREFIX = """
[RETRY ATTEMPT — previous edit failed]
The previous attempt could not match the search blocks in the file.
You MUST:
- Use AT LEAST 5 context lines BEFORE and AFTER the change point
- Preserve EXACT whitespace, tabs, and indentation character-for-character
- Check that the function/class/property mentioned actually exists in the file
- If the file structure differs from what the instructions assume, adapt
  the search blocks to the ACTUAL file content

═══ ORIGINAL INSTRUCTIONS ═══

"""
    }

    // ═══════════════════════════════════════════════════════════════════════
    // EVENT TYPES
    // ═══════════════════════════════════════════════════════════════════════

    sealed class ExecutorEvent {
        data class Gemini(val log: GeminiLogEvent) : ExecutorEvent()
        data class Repo(val log: RepoLogEvent) : ExecutorEvent()
        data class Final(val result: TaskExecutionResult) : ExecutorEvent()
    }

    private sealed class ReadOutcome {
        data class Ok(val content: String, val sha: String) : ReadOutcome()
        data class FatalErr(val message: String) : ReadOutcome()
        data class DeferrableErr(val code: TaskErrorCode, val message: String) : ReadOutcome()
    }

    private sealed class CommitOutcome {
        data class Ok(val sha: String, val resolvedConflict: Boolean) : CommitOutcome()
        data class FatalErr(val message: String) : CommitOutcome()
        data class DeferrableErr(val code: TaskErrorCode, val message: String) : CommitOutcome()
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PUBLIC API
    // ═══════════════════════════════════════════════════════════════════════

    fun executeTask(task: FileTask, isRetryPass: Boolean): Flow<ExecutorEvent> = channelFlow {
        val startTime = System.currentTimeMillis()
        val taskId = task.id

        send(ExecutorEvent.Gemini(GeminiLogEvent(
            type = GeminiEventType.TASK_START,
            icon = task.operation.emoji,
            message = "${task.operation.displayName}: ${task.filePath.substringAfterLast('/')}" +
                    if (isRetryPass) " (retry-pass)" else "",
            taskId = taskId
        )))

        try {
            // ═══ CREATE BRANCH ════════════════════════════════════════
            if (task.operation == TaskOperation.CREATE) {
                executeCreateTask(task, taskId, startTime)
                return@channelFlow
            }

            // ═══ STEP 1: READ ═════════════════════════════════════════
            val readOutcome = readFile(task.filePath)
            val originalContent: String
            val originalSha: String
            when (readOutcome) {
                is ReadOutcome.FatalErr -> {
                    send(ExecutorEvent.Final(TaskExecutionResult.Fatal(
                        TaskErrorCode.UNKNOWN, readOutcome.message
                    )))
                    return@channelFlow
                }
                is ReadOutcome.DeferrableErr -> {
                    send(ExecutorEvent.Final(TaskExecutionResult.Deferrable(
                        readOutcome.code, readOutcome.message
                    )))
                    return@channelFlow
                }
                is ReadOutcome.Ok -> {
                    originalContent = readOutcome.content
                    originalSha = readOutcome.sha
                }
            }

                send(ExecutorEvent.Repo(RepoLogEvent(
                    type = RepoEventType.FILE_READ,
                    icon = "📄",
                    message = "Прочитан: ${task.filePath} (${originalContent.length / 1024}KB)",
                    taskId = taskId
                )))

                // ═══ STEP 2: AI EDIT CALL ════════════════════════════════════════
                val effectiveInstructions = if (isRetryPass) {
                    RETRY_HINT_PREFIX + task.instructions
                } else {
                    task.instructions
                }

                send(ExecutorEvent.Gemini(GeminiLogEvent(
                    type = GeminiEventType.AI_REQUEST,
                    icon = "📤",
                    message = "→ Gemini 3.1 Flash-Lite (${effectiveInstructions.length}ch)",
                    taskId = taskId
                )))

                val editResult = aiEditService.processEdit(
                    fileContent = originalContent,
                    fileName = task.filePath.substringAfterLast('/'),
                    instructions = effectiveInstructions,
                    model = MODEL,
                    usePipelineKeys = true
                ).getOrElse { e ->
                    val code = classifyAiError(e)
                    send(ExecutorEvent.Gemini(GeminiLogEvent(
                        type = GeminiEventType.AI_APPLY_FAIL,
                        icon = "❌",
                        message = "AI ошибка: ${e.message?.take(100)}",
                        taskId = taskId
                    )))
                    send(ExecutorEvent.Final(TaskExecutionResult.Deferrable(
                        code, e.message ?: "AI call failed"
                    )))
                    return@channelFlow
                }

                send(ExecutorEvent.Gemini(GeminiLogEvent(
                    type = GeminiEventType.AI_RESPONSE,
                    icon = "📥",
                    message = "AI: ${editResult.blocks.size} блок(ов), " +
                            "${editResult.inputTokens}in+${editResult.outputTokens}out, " +
                            "€${String.format(java.util.Locale.US, "%.5f", editResult.costEUR)}",
                    taskId = taskId,
                    tokens = editResult.inputTokens + editResult.outputTokens,
                    costEur = editResult.costEUR
                )))

                val tokensTotal = editResult.inputTokens + editResult.outputTokens

                // ═══ STEP 3: APPLY DECISION ══════════════════════════════════════
                if (editResult.blocks.isEmpty()) {
                    send(ExecutorEvent.Gemini(GeminiLogEvent(
                        type = GeminiEventType.INFO,
                        icon = "⚪",
                        message = "AI не нашёл изменений — пропускаем коммит, идём далее",
                        taskId = taskId
                    )))
                    send(ExecutorEvent.Repo(RepoLogEvent(
                        type = RepoEventType.INFO,
                        icon = "⚪",
                        message = "${task.filePath.substringAfterLast('/')}: без изменений (коммит не требуется)",
                        taskId = taskId
                    )))
                    send(ExecutorEvent.Final(TaskExecutionResult.NoChangesNeeded(
                        commitSha = originalSha,
                        tokensUsed = tokensTotal,
                        costEur = editResult.costEUR
                    )))
                    return@channelFlow
                }

                val applyResult = aiEditService.applyEdits(originalContent, editResult.blocks)
                    .getOrElse { e ->
                        send(ExecutorEvent.Gemini(GeminiLogEvent(
                            type = GeminiEventType.AI_APPLY_FAIL,
                            icon = "❌",
                            message = "Локальное применение упало: ${e.message?.take(80)}",
                            taskId = taskId
                        )))
                        send(ExecutorEvent.Final(TaskExecutionResult.Deferrable(
                            TaskErrorCode.AI_INVALID_RESPONSE,
                            e.message ?: "Apply failed",
                            tokensTotal
                        )))
                        return@channelFlow
                    }

                send(ExecutorEvent.Gemini(GeminiLogEvent(
                    type = GeminiEventType.AI_BLOCKS_PARSED,
                    icon = "🔧",
                    message = "Применение: ${applyResult.totalApplied}/${editResult.blocks.size} блок(ов)" +
                            if (applyResult.totalFailed > 0) ", ${applyResult.totalFailed} not found" else "",
                    taskId = taskId
                )))

                if (applyResult.totalApplied == 0 && applyResult.totalFailed > 0) {
                    send(ExecutorEvent.Gemini(GeminiLogEvent(
                        type = GeminiEventType.AI_APPLY_FAIL,
                        icon = "⏸",
                        message = "Ни один блок не применился → отложено",
                        taskId = taskId
                    )))
                    send(ExecutorEvent.Final(TaskExecutionResult.Deferrable(
                        TaskErrorCode.NOT_FOUND_BLOCKS,
                        "Ни один блок не применился (${applyResult.totalFailed} not found)",
                        tokensTotal
                    )))
                    return@channelFlow
                }

                send(ExecutorEvent.Gemini(GeminiLogEvent(
                    type = GeminiEventType.AI_APPLY_OK,
                    icon = "✏️",
                    message = "Готово к коммиту: ${applyResult.newContent.length / 1024}KB",
                    taskId = taskId
                )))

                // ═══ STEP 4: COMMIT ═══════════════════════════════════════════════
                val outcome = commit(
                    path = task.filePath,
                    content = applyResult.newContent,
                    initialSha = originalSha,
                    commitMessage = "[Pipeline] ${task.filePath.substringAfterLast('/')}",
                    taskId = taskId
                ) { event -> send(event) }

                when (outcome) {
                    is CommitOutcome.Ok -> {
                        val durationMs = System.currentTimeMillis() - startTime
                        repoIndexManager.invalidate()

                        send(ExecutorEvent.Repo(RepoLogEvent(
                            type = RepoEventType.FILE_COMMITTED,
                            icon = "💾",
                            message = "Коммит: ${outcome.sha.take(8)} (${durationMs}ms)" +
                                    if (outcome.resolvedConflict) " ⚠️ auto-resolved" else "",
                            taskId = taskId,
                            commitSha = outcome.sha
                        )))

                        send(ExecutorEvent.Final(TaskExecutionResult.Success(
                            commitSha = outcome.sha,
                            resolvedConflict = outcome.resolvedConflict,
                            tokensUsed = tokensTotal,
                            costEur = editResult.costEUR,
                            editResult = editResult
                        )))
                    }
                    is CommitOutcome.FatalErr -> {
                        send(ExecutorEvent.Final(TaskExecutionResult.Fatal(
                            TaskErrorCode.UNKNOWN, outcome.message
                        )))
                    }
                    is CommitOutcome.DeferrableErr -> {
                        send(ExecutorEvent.Final(TaskExecutionResult.Deferrable(
                            outcome.code, outcome.message, tokensTotal
                        )))
                    }
                }

                delay(INTER_FILE_DELAY_MS)
        } catch (e: CancellationException) {
            Log.d(TAG, "Task ${task.filePath} cancelled")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error in task ${task.filePath}", e)
            send(ExecutorEvent.Gemini(GeminiLogEvent(
                type = GeminiEventType.ERROR,
                icon = "💥",
                message = "Неожиданная ошибка: ${e.message?.take(100)}",
                taskId = taskId
            )))
            send(ExecutorEvent.Final(TaskExecutionResult.Deferrable(
                TaskErrorCode.UNKNOWN, e.message ?: "Unknown error"
            )))
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // CREATE TASK BRANCH
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Обрабатывает CREATE-задачу: проверяет что файл ещё не существует,
     * затем коммитит готовый контент (sha=null означает создание нового файла).
     *
     * Extension на ProducerScope чтобы можно было вызывать send() из channelFlow.
     */
    private suspend fun kotlinx.coroutines.channels.ProducerScope<ExecutorEvent>.executeCreateTask(
        task: FileTask,
        taskId: String,
        startTime: Long
    ) {
        val content = task.newFileContent
        if (content.isNullOrBlank()) {
            send(ExecutorEvent.Gemini(GeminiLogEvent(
                type = GeminiEventType.ERROR,
                icon = "❌",
                message = "CREATE задача без контента",
                taskId = taskId
            )))
            send(ExecutorEvent.Final(TaskExecutionResult.Fatal(
                TaskErrorCode.INVALID_CREATE_CONTENT,
                "CREATE task '${task.filePath}' has no content"
            )))
            return
        }

        send(ExecutorEvent.Gemini(GeminiLogEvent(
            type = GeminiEventType.INFO,
            icon = "➕",
            message = "Создание нового файла: ${task.filePath} (${content.length} ch)",
            taskId = taskId
        )))

        // Проверяем существует ли файл, чтобы получить его SHA (для возможности перезаписи)
        val branch = appSettings.gitHubConfig.first().branch
        var existingSha: String? = null
        
        try {
            existingSha = gitHubClient.getFileContent(task.filePath, branch).getOrNull()?.sha
            if (existingSha != null) {
                send(ExecutorEvent.Repo(RepoLogEvent(
                    type = RepoEventType.INFO,
                    icon = "🔄",
                    message = "CREATE→OVERWRITE existing: ${task.filePath}",
                    taskId = taskId
                )))
            } else {
                send(ExecutorEvent.Repo(RepoLogEvent(
                    type = RepoEventType.INFO,
                    icon = "✓",
                    message = "Путь свободен: ${task.filePath}",
                    taskId = taskId
                )))
            }
        } catch (e: Exception) {
            // Игнорируем ошибки сети здесь, commit() сам с ними разберется
        }

        // Коммитим файл (если sha != null, это будет OVERWRITE, иначе CREATE)
        val outcome = commit(
            path = task.filePath,
            content = content,
            initialSha = existingSha,
            commitMessage = "[Pipeline] " + (if (existingSha != null) "OVERWRITE" else "CREATE") + " ${task.filePath.substringAfterLast('/')}",
            taskId = taskId
        ) { event -> send(event) }

        when (outcome) {
            is CommitOutcome.Ok -> {
                val durationMs = System.currentTimeMillis() - startTime
                repoIndexManager.invalidate()

                send(ExecutorEvent.Repo(RepoLogEvent(
                    type = RepoEventType.FILE_COMMITTED,
                    icon = "💾",
                    message = "Создан: ${outcome.sha.take(8)} (${durationMs}ms)",
                    taskId = taskId,
                    commitSha = outcome.sha
                )))
                send(ExecutorEvent.Final(TaskExecutionResult.Success(
                    commitSha = outcome.sha,
                    resolvedConflict = false,
                    tokensUsed = 0,        // CREATE не тратит токенов Gemini
                    costEur = 0.0,
                    editResult = null
                )))
            }
            is CommitOutcome.FatalErr -> {
                send(ExecutorEvent.Final(TaskExecutionResult.Fatal(
                    TaskErrorCode.UNKNOWN, outcome.message
                )))
            }
            is CommitOutcome.DeferrableErr -> {
                send(ExecutorEvent.Final(TaskExecutionResult.Deferrable(
                    outcome.code, outcome.message
                )))
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // READ FILE
    // ═══════════════════════════════════════════════════════════════════════

    private suspend fun readFile(path: String): ReadOutcome = withContext(Dispatchers.IO) {
        var lastMsg: String? = null
        val branch = try {
            appSettings.gitHubConfig.first().branch
        } catch (e: Exception) {
            return@withContext ReadOutcome.FatalErr(
                "Не удалось получить настройки GitHub: ${e.message}"
            )
        }

        var attempt = 0
        var rate429Retries = 0
        val max429Retries = 3   // 429 не ест retry-попытки

        while (attempt <= NETWORK_RETRY_MAX) {
            try {
                val file = gitHubClient.getFileContent(path, branch).getOrThrow()
                val rawContent = file.content ?: ""
                // GitHub возвращает base64 с переносами строк каждые 60 символов
                // Убираем всё whitespace для безопасной decoding
                val cleanedB64 = rawContent.filter { !it.isWhitespace() }
                val content = if (cleanedB64.isEmpty()) {
                    ""
                } else {
                    try {
                        String(
                            Base64.decode(cleanedB64, Base64.NO_WRAP),
                            Charsets.UTF_8
                        )
                    } catch (e: IllegalArgumentException) {
                        return@withContext ReadOutcome.DeferrableErr(
                            TaskErrorCode.NETWORK_ERROR,
                            "Не удалось декодировать содержимое файла $path: ${e.message}"
                        )
                    }
                }
                return@withContext ReadOutcome.Ok(content, file.sha)
            } catch (e: GitHubApiException) {
                lastMsg = e.message
                when {
                    e.isNotFound -> return@withContext ReadOutcome.DeferrableErr(
                        TaskErrorCode.FILE_NOT_FOUND, "Файл не найден: $path"
                    )
                    e.isUnauthorized -> return@withContext ReadOutcome.FatalErr(
                        "GitHub: 401 Unauthorized — проверь токен"
                    )
                    e.isForbidden -> return@withContext ReadOutcome.FatalErr(
                        "GitHub: 403 Forbidden — нет доступа к репо"
                    )
                    e.statusCode == 429 -> {
                        // 429 НЕ ест попытку retry, но имеет свой лимит
                        if (rate429Retries < max429Retries) {
                            rate429Retries++
                            delay(15_000L)
                            continue  // не увеличиваем attempt
                        } else {
                            return@withContext ReadOutcome.DeferrableErr(
                                TaskErrorCode.HTTP_429_RATE_LIMIT,
                                "Rate limit GitHub превышен после $max429Retries попыток"
                            )
                        }
                    }
                    e.statusCode in 500..599 -> {
                        if (attempt < NETWORK_RETRY_MAX) delay(2000L * (attempt + 1))
                    }
                    else -> {
                        if (attempt < NETWORK_RETRY_MAX) delay(2000L * (attempt + 1))
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastMsg = e.message
                if (attempt < NETWORK_RETRY_MAX) delay(2000L * (attempt + 1))
            }
            attempt++
        }

        ReadOutcome.DeferrableErr(
            TaskErrorCode.NETWORK_ERROR,
            lastMsg ?: "Не удалось прочитать файл: $path"
        )
    }

    // ═══════════════════════════════════════════════════════════════════════
    // COMMIT WITH AUTO CONFLICT RESOLUTION
    // ═══════════════════════════════════════════════════════════════════════

    private suspend fun commit(
        path: String,
        content: String,
        initialSha: String?,
        commitMessage: String,
        taskId: String,
        notify: suspend (ExecutorEvent) -> Unit
    ): CommitOutcome = withContext(Dispatchers.IO) {
        var currentSha = initialSha
        var resolvedConflict = false
        var lastMsg: String? = null
        val branch = try {
            appSettings.gitHubConfig.first().branch
        } catch (e: Exception) {
            return@withContext CommitOutcome.FatalErr(
                "Не удалось получить настройки GitHub: ${e.message}"
            )
        }

        var attempt = 0
        var rate429Retries = 0
        val max429Retries = 3
        val isCreate = initialSha == null

        while (attempt < CONFLICT_RETRY_MAX) {
            try {
                throttleGithubWrite()
                val result = gitHubClient.createOrUpdateFile(
                    path = path,
                    content = content,
                    message = commitMessage,
                    sha = currentSha,
                    branch = branch
                ).getOrThrow()

                return@withContext CommitOutcome.Ok(result.content.sha, resolvedConflict)

            } catch (e: GitHubApiException) {
                lastMsg = e.message
                when {
                    // 422 для CREATE — обычно означает что файл уже существует (race condition)
                    e.statusCode == 422 && isCreate -> {
                        val fresh = gitHubClient.getFileContent(path, branch).getOrNull()
                        if (fresh != null) {
                            currentSha = fresh.sha
                            attempt++
                            delay(1500L * attempt)
                            continue
                        } else {
                            return@withContext CommitOutcome.DeferrableErr(
                                TaskErrorCode.FILE_ALREADY_EXISTS,
                                "422 — файл уже существует и не получили свежий SHA"
                            )
                        }
                    }
                    e.statusCode == 409 ||
                            e.message.contains("does not match", ignoreCase = true) -> {
                        notify(ExecutorEvent.Repo(RepoLogEvent(
                            type = RepoEventType.COMMIT_CONFLICT,
                            icon = "⚠️",
                            message = "409 Conflict (попытка ${attempt + 1}/$CONFLICT_RETRY_MAX) — auto KEEP_MINE" +
                                    if (isCreate) " (CREATE→OVERWRITE)" else "",
                            taskId = taskId
                        )))
                        // Получаем свежий SHA из ТОЙ ЖЕ ветки
                        val fresh = gitHubClient.getFileContent(path, branch).getOrNull()
                        if (fresh != null) {
                            currentSha = fresh.sha
                            resolvedConflict = true
                            delay(1500L * (attempt + 1))
                            attempt++
                        } else {
                            return@withContext CommitOutcome.DeferrableErr(
                                TaskErrorCode.HTTP_409_UNRESOLVED,
                                "409 conflict — не удалось получить свежий SHA"
                            )
                        }
                    }
                    e.isUnauthorized -> return@withContext CommitOutcome.FatalErr(
                        "GitHub: 401 Unauthorized"
                    )
                    e.isForbidden -> return@withContext CommitOutcome.FatalErr(
                        "GitHub: 403 Forbidden"
                    )
                    e.statusCode == 429 -> {
                        // 429 НЕ ест попытку retry, но имеет свой лимит
                        if (rate429Retries < max429Retries) {
                            rate429Retries++
                            notify(ExecutorEvent.Repo(RepoLogEvent(
                                type = RepoEventType.INFO,
                                icon = "⏱",
                                message = "Rate limit (${rate429Retries}/$max429Retries) — ждём 15с",
                                taskId = taskId
                            )))
                            delay(15_000L)
                            // не увеличиваем attempt
                        } else {
                            return@withContext CommitOutcome.DeferrableErr(
                                TaskErrorCode.HTTP_429_RATE_LIMIT,
                                "Rate limit GitHub превышен"
                            )
                        }
                    }
                    e.statusCode in 500..599 -> {
                        if (attempt < CONFLICT_RETRY_MAX - 1) {
                            delay(3000L * (attempt + 1))
                        }
                        attempt++
                    }
                    else -> return@withContext CommitOutcome.DeferrableErr(
                        TaskErrorCode.NETWORK_ERROR, e.message
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                lastMsg = e.message
                if (attempt < CONFLICT_RETRY_MAX - 1) delay(2000L * (attempt + 1))
                attempt++
            }
        }

        if (resolvedConflict) {
            CommitOutcome.DeferrableErr(
                TaskErrorCode.HTTP_409_UNRESOLVED,
                "409 не разрешён после $CONFLICT_RETRY_MAX попыток"
            )
        } else {
            CommitOutcome.DeferrableErr(
                TaskErrorCode.NETWORK_ERROR,
                lastMsg ?: "Commit failed after $CONFLICT_RETRY_MAX attempts"
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // ERROR CLASSIFICATION
    // ═══════════════════════════════════════════════════════════════════════

    private fun classifyAiError(e: Throwable): TaskErrorCode {
        val msg = e.message?.lowercase() ?: ""
        return when {
            "429" in msg || "rate limit" in msg || "лимит" in msg ->
                TaskErrorCode.HTTP_429_RATE_LIMIT
            "503" in msg || "502" in msg || "500" in msg ||
                    "перегружен" in msg || "overload" in msg ->
                TaskErrorCode.HTTP_5XX_SERVER
            "timeout" in msg || "таймаут" in msg -> TaskErrorCode.NETWORK_ERROR
            "unknownhost" in msg || "интернет" in msg || "network" in msg ->
                TaskErrorCode.NETWORK_ERROR
            else -> TaskErrorCode.AI_EMPTY_RESPONSE
        }
    }
}
