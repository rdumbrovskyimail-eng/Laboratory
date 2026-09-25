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
 * ⚡ PIPELINE EXECUTOR v3.2 (Zero-Conflict & Race-Condition Protected)
 *
 * Отвечает за пофайловое выполнение плана:
 * - Модели: Gemini 3.5 Flash-Lite (LOW thinking) или 3.1 Flash-Lite (LOW thinking)
 * - Режимы: Online (сериализованные коммиты без 409 ошибок) и Offline (локальный клон)
 * - Передача оригинального и финального контента для генерации монолитного TXT-отчета
 */
@Singleton
class PipelineExecutor @Inject constructor(
    private val gitHubClient: GitHubApiClient,
    private val aiEditService: CreatorAIEditService,
    private val repoIndexManager: RepoIndexManager,
    private val appSettings: AppSettings,
    private val localRepoManager: LocalRepoManager
) {

    companion object {
        private const val TAG = "PipelineExecutor"
        private const val CONFLICT_RETRY_MAX = 2
        private const val NETWORK_RETRY_MAX = 2
        private const val INTER_FILE_DELAY_MS = 200L
        private val DEFAULT_MODEL = CreatorAIEditService.AiModel.GEMINI_3_5_FLASH_LITE

        private const val RETRY_HINT_PREFIX = """
[RETRY ATTEMPT — previous edit failed]
The previous attempt could not match the search blocks in the file.
You MUST:
- Use AT LEAST 4 context lines BEFORE and AFTER the change point
- Preserve EXACT whitespace, tabs, and indentation character-for-character
- Adapt the search blocks to the ACTUAL file content

═══ ORIGINAL INSTRUCTIONS ═══

"""
    }

    private val fileLocks = ConcurrentHashMap<String, Mutex>()
    fun lockFor(path: String): Mutex = fileLocks.computeIfAbsent(path) { Mutex() }
    fun clearFileLocks() { fileLocks.clear() }

    // КРИТИЧЕСКИЙ ФИКС: Мьютекс сериализации коммитов в ветку.
    // Параллельная генерация AI сохраняется, но запись в git-ветку выполняется по очереди,
    // что гарантированно устраняет HTTP 409 Conflict и ретраи с 3 раза!
    private val commitLock = Mutex()

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

    fun executeTask(
        task: FileTask,
        isRetryPass: Boolean,
        overrideModelApiId: String? = null,
        thinkingLevelOverride: String? = null,
        offlineMode: Boolean = false
    ): Flow<ExecutorEvent> = channelFlow {
        val startTime = System.currentTimeMillis()
        val taskId = task.id
        val effectiveModel = if (overrideModelApiId != null) {
            CreatorAIEditService.AiModel.fromApiId(overrideModelApiId)
        } else {
            DEFAULT_MODEL
        }

        send(ExecutorEvent.Gemini(GeminiLogEvent(
            type = GeminiEventType.TASK_START,
            icon = task.operation.emoji,
            message = "${task.operation.displayName}: ${task.filePath.substringAfterLast('/')}" +
                    if (isRetryPass) " (retry-pass)" else "",
            taskId = taskId
        )))

        try {
            // ── Задача CREATE ──────────────────────────────────────────
            if (task.operation == TaskOperation.CREATE) {
                if (offlineMode) executeCreateTaskOffline(task, taskId, startTime)
                else executeCreateTask(task, taskId, startTime)
                return@channelFlow
            }

            // ── Задача DELETE ──────────────────────────────────────────
            if (task.operation == TaskOperation.DELETE) {
                if (offlineMode) executeDeleteTaskOffline(task, taskId, startTime)
                else executeDeleteTaskOnline(task, taskId, startTime)
                return@channelFlow
            }

            // ── Задача MODIFY: чтение исходного кода ───────────────────
            val readOutcome = if (offlineMode) readFileOffline(task.filePath)
                              else readFile(task.filePath)
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
                message = "Прочитан: ${task.filePath.substringAfterLast('/')} (${originalContent.length / 1024}KB)",
                taskId = taskId
            )))

            val effectiveInstructions = if (isRetryPass) RETRY_HINT_PREFIX + task.instructions
                                        else task.instructions

            send(ExecutorEvent.Gemini(GeminiLogEvent(
                type = GeminiEventType.AI_REQUEST,
                icon = "📤",
                message = "→ ${effectiveModel.displayName} (${effectiveInstructions.length}ch)",
                taskId = taskId
            )))

            // ── Вызов AI-сервиса правок ────────────────────────────────
            val editResult = aiEditService.processEdit(
                fileContent = originalContent,
                fileName = task.filePath.substringAfterLast('/'),
                instructions = effectiveInstructions,
                model = effectiveModel,
                usePipelineKeys = true,
                customModelApiId = effectiveModel.apiId,
                thinkingLevelOverride = "LOW"
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
                message = "${effectiveModel.badge}: ${editResult.blocks.size} блок(ов), " +
                        "${editResult.inputTokens}in+${editResult.outputTokens}out, " +
                        "€${String.format(java.util.Locale.US, "%.5f", editResult.costEUR)}",
                taskId = taskId,
                tokens = editResult.inputTokens + editResult.outputTokens,
                costEur = editResult.costEUR
            )))

            val tokensTotal = editResult.inputTokens + editResult.outputTokens

            // ── Если модель не нашла изменений ────────────────────────
            if (editResult.blocks.isEmpty()) {
                send(ExecutorEvent.Gemini(GeminiLogEvent(
                    type = GeminiEventType.INFO, icon = "⚪",
                    message = "AI не нашёл изменений — пропускаем коммит",
                    taskId = taskId
                )))
                send(ExecutorEvent.Repo(RepoLogEvent(
                    type = RepoEventType.INFO, icon = "⚪",
                    message = "${task.filePath.substringAfterLast('/')}: без изменений",
                    taskId = taskId
                )))
                send(ExecutorEvent.Final(TaskExecutionResult.NoChangesNeeded(
                    commitSha = originalSha,
                    tokensUsed = tokensTotal,
                    costEur = editResult.costEUR,
                    originalContent = originalContent,
                    finalContent = originalContent
                )))
                return@channelFlow
            }

            // ── Локальное применение блоков замены ─────────────────────
            val applyResult = aiEditService.applyEdits(originalContent, editResult.blocks)
                .getOrElse { e ->
                    send(ExecutorEvent.Gemini(GeminiLogEvent(
                        type = GeminiEventType.AI_APPLY_FAIL, icon = "❌",
                        message = "Локальное применение упало: ${e.message?.take(80)}",
                        taskId = taskId
                    )))
                    send(ExecutorEvent.Final(TaskExecutionResult.Deferrable(
                        TaskErrorCode.AI_INVALID_RESPONSE,
                        e.message ?: "Apply failed", tokensTotal
                    )))
                    return@channelFlow
                }

            send(ExecutorEvent.Gemini(GeminiLogEvent(
                type = GeminiEventType.AI_BLOCKS_PARSED, icon = "🔧",
                message = "Применение: ${applyResult.totalApplied}/${editResult.blocks.size}" +
                        if (applyResult.totalFailed > 0) ", ${applyResult.totalFailed} not found" else "",
                taskId = taskId
            )))

            if (applyResult.totalApplied == 0 && applyResult.totalFailed > 0) {
                send(ExecutorEvent.Gemini(GeminiLogEvent(
                    type = GeminiEventType.AI_APPLY_FAIL, icon = "⏸",
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
                type = GeminiEventType.AI_APPLY_OK, icon = "✏️",
                message = "Готово к коммиту: ${applyResult.newContent.length / 1024}KB",
                taskId = taskId
            )))

            // ── Сохранение: Offline или Online ─────────────────────────
            if (offlineMode) {
                val writeRes = localRepoManager.writeFile(task.filePath, applyResult.newContent)
                if (writeRes.isFailure) {
                    val e = writeRes.exceptionOrNull()
                    send(ExecutorEvent.Final(TaskExecutionResult.Deferrable(
                        TaskErrorCode.NETWORK_ERROR,
                        "Локальная запись упала: ${e?.message?.take(120)}",
                        tokensTotal
                    )))
                    return@channelFlow
                }
                val durationMs = System.currentTimeMillis() - startTime
                send(ExecutorEvent.Repo(RepoLogEvent(
                    type = RepoEventType.LOCAL_WRITE, icon = "📝",
                    message = "Локально: ${task.filePath.substringAfterLast('/')} (${durationMs}ms)",
                    taskId = taskId
                )))
                send(ExecutorEvent.Final(TaskExecutionResult.Success(
                    commitSha = "pending-batch",
                    resolvedConflict = false,
                    tokensUsed = tokensTotal,
                    costEur = editResult.costEUR,
                    editResult = editResult,
                    originalContent = originalContent,
                    finalContent = applyResult.newContent
                )))
            } else {
                // Атомарный коммит под мьютексом: исключает 409 между параллельными потоками
                val outcome = commitLock.withLock {
                    commit(
                        path = task.filePath,
                        content = applyResult.newContent,
                        initialSha = originalSha,
                        commitMessage = "[Pipeline] ${task.filePath.substringAfterLast('/')}",
                        taskId = taskId
                    ) { event -> send(event) }
                }

                when (outcome) {
                    is CommitOutcome.Ok -> {
                        val durationMs = System.currentTimeMillis() - startTime
                        repoIndexManager.invalidate()
                        send(ExecutorEvent.Repo(RepoLogEvent(
                            type = RepoEventType.FILE_COMMITTED, icon = "💾",
                            message = "Коммит: ${outcome.sha.take(8)} (${durationMs}ms)" +
                                    if (outcome.resolvedConflict) " ⚠️ auto-resolved" else "",
                            taskId = taskId, commitSha = outcome.sha
                        )))
                        send(ExecutorEvent.Final(TaskExecutionResult.Success(
                            commitSha = outcome.sha,
                            resolvedConflict = outcome.resolvedConflict,
                            tokensUsed = tokensTotal,
                            costEur = editResult.costEUR,
                            editResult = editResult,
                            originalContent = originalContent,
                            finalContent = applyResult.newContent
                        )))
                    }
                    is CommitOutcome.FatalErr -> send(ExecutorEvent.Final(
                        TaskExecutionResult.Fatal(TaskErrorCode.UNKNOWN, outcome.message)
                    ))
                    is CommitOutcome.DeferrableErr -> send(ExecutorEvent.Final(
                        TaskExecutionResult.Deferrable(outcome.code, outcome.message, tokensTotal)
                    ))
                }
            }

            delay(INTER_FILE_DELAY_MS)
        } catch (e: CancellationException) {
            Log.d(TAG, "Task ${task.filePath} cancelled")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error in task ${task.filePath}", e)
            send(ExecutorEvent.Final(TaskExecutionResult.Deferrable(
                TaskErrorCode.UNKNOWN, e.message ?: "Unknown error"
            )))
        }
    }

    private suspend fun kotlinx.coroutines.channels.ProducerScope<ExecutorEvent>.executeCreateTask(
        task: FileTask, taskId: String, startTime: Long
    ) {
        val content = task.newFileContent
        if (content.isNullOrBlank()) {
            send(ExecutorEvent.Final(TaskExecutionResult.Fatal(
                TaskErrorCode.INVALID_CREATE_CONTENT,
                "CREATE task '${task.filePath}' has no content"
            )))
            return
        }

        send(ExecutorEvent.Gemini(GeminiLogEvent(
            type = GeminiEventType.INFO, icon = "➕",
            message = "Создание файла: ${task.filePath.substringAfterLast('/')}",
            taskId = taskId
        )))

        val outcome = commitLock.withLock {
            commit(
                path = task.filePath,
                content = content,
                initialSha = null,
                commitMessage = "[Pipeline] CREATE ${task.filePath.substringAfterLast('/')}",
                taskId = taskId
            ) { event -> send(event) }
        }

        when (outcome) {
            is CommitOutcome.Ok -> {
                repoIndexManager.invalidate()
                send(ExecutorEvent.Final(TaskExecutionResult.Success(
                    commitSha = outcome.sha, resolvedConflict = false,
                    tokensUsed = 0, costEur = 0.0, editResult = null,
                    originalContent = "",
                    finalContent = content
                )))
            }
            is CommitOutcome.FatalErr -> send(ExecutorEvent.Final(
                TaskExecutionResult.Fatal(TaskErrorCode.UNKNOWN, outcome.message)
            ))
            is CommitOutcome.DeferrableErr -> send(ExecutorEvent.Final(
                TaskExecutionResult.Deferrable(outcome.code, outcome.message)
            ))
        }
    }

    private suspend fun readFileOffline(path: String): ReadOutcome {
        val result = localRepoManager.readFile(path)
        return result.fold(
            onSuccess = { content -> ReadOutcome.Ok(content, "offline-no-sha") },
            onFailure = { e ->
                if (e is java.io.FileNotFoundException) {
                    ReadOutcome.DeferrableErr(TaskErrorCode.FILE_NOT_FOUND, "Файл не найден в клоне: $path")
                } else {
                    ReadOutcome.DeferrableErr(TaskErrorCode.NETWORK_ERROR, e.message ?: "Read failed: $path")
                }
            }
        )
    }

    private suspend fun kotlinx.coroutines.channels.ProducerScope<ExecutorEvent>.executeCreateTaskOffline(
        task: FileTask, taskId: String, startTime: Long
    ) {
        val content = task.newFileContent
        if (content.isNullOrBlank()) {
            send(ExecutorEvent.Final(TaskExecutionResult.Fatal(
                TaskErrorCode.INVALID_CREATE_CONTENT,
                "CREATE task '${task.filePath}' has no content"
            )))
            return
        }

        val writeRes = localRepoManager.writeFile(task.filePath, content)
        if (writeRes.isFailure) {
            send(ExecutorEvent.Final(TaskExecutionResult.Deferrable(
                TaskErrorCode.NETWORK_ERROR, "Локальное создание упало"
            )))
            return
        }

        val durationMs = System.currentTimeMillis() - startTime
        send(ExecutorEvent.Repo(RepoLogEvent(
            type = RepoEventType.LOCAL_WRITE, icon = "📝",
            message = "Создан локально: ${task.filePath.substringAfterLast('/')} (${durationMs}ms)",
            taskId = taskId
        )))
        send(ExecutorEvent.Final(TaskExecutionResult.Success(
            commitSha = "pending-batch",
            resolvedConflict = false,
            tokensUsed = 0,
            costEur = 0.0,
            editResult = null,
            originalContent = "",
            finalContent = content
        )))
    }

    private suspend fun readFile(path: String): ReadOutcome = withContext(Dispatchers.IO) {
        val branch = try { appSettings.gitHubConfig.first().branch }
        catch (e: Exception) { return@withContext ReadOutcome.FatalErr("GitHub config: ${e.message}") }

        var attempt = 0
        while (attempt <= NETWORK_RETRY_MAX) {
            try {
                val file = gitHubClient.getFileContent(path, branch).getOrThrow()
                val rawContent = file.content ?: ""
                val cleanedB64 = rawContent.filter { !it.isWhitespace() }
                val content = if (cleanedB64.isEmpty()) "" else try {
                    String(Base64.decode(cleanedB64, Base64.NO_WRAP), Charsets.UTF_8)
                } catch (e: IllegalArgumentException) {
                    return@withContext ReadOutcome.DeferrableErr(
                        TaskErrorCode.NETWORK_ERROR, "Не удалось декодировать $path"
                    )
                }
                return@withContext ReadOutcome.Ok(content, file.sha)
            } catch (e: GitHubApiException) {
                if (e.isNotFound) return@withContext ReadOutcome.DeferrableErr(TaskErrorCode.FILE_NOT_FOUND, "Файл не найден: $path")
                if (e.isUnauthorized || e.isForbidden) return@withContext ReadOutcome.FatalErr("GitHub Auth error: ${e.message}")
                delay(1000L * (attempt + 1))
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                delay(1000L * (attempt + 1))
            }
            attempt++
        }
        ReadOutcome.DeferrableErr(TaskErrorCode.NETWORK_ERROR, "Не удалось прочитать $path")
    }

    private suspend fun commit(
        path: String, content: String, initialSha: String?,
        commitMessage: String, taskId: String,
        notify: suspend (ExecutorEvent) -> Unit
    ): CommitOutcome = withContext(Dispatchers.IO) {
        val branch = try { appSettings.gitHubConfig.first().branch }
        catch (e: Exception) { return@withContext CommitOutcome.FatalErr("GitHub config: ${e.message}") }

        var currentSha = initialSha
        // Перед коммитом всегда запрашиваем актуальный SHA файла на ветке
        val fresh = gitHubClient.getFileContent(path, branch).getOrNull()
        if (fresh != null) currentSha = fresh.sha

        for (attempt in 0..CONFLICT_RETRY_MAX) {
            try {
                val result = gitHubClient.createOrUpdateFile(
                    path = path, content = content,
                    message = commitMessage, sha = currentSha, branch = branch
                ).getOrThrow()
                return@withContext CommitOutcome.Ok(result.content.sha, attempt > 0)
            } catch (e: GitHubApiException) {
                if (e.statusCode == 409 && attempt < CONFLICT_RETRY_MAX) {
                    val retryFresh = gitHubClient.getFileContent(path, branch).getOrNull()
                    if (retryFresh != null) {
                        currentSha = retryFresh.sha
                        delay(500L)
                        continue
                    }
                }
                if (e.isUnauthorized || e.isForbidden) return@withContext CommitOutcome.FatalErr("Auth error: ${e.message}")
                return@withContext CommitOutcome.DeferrableErr(TaskErrorCode.HTTP_409_UNRESOLVED, e.message)
            } catch (e: Exception) {
                return@withContext CommitOutcome.DeferrableErr(TaskErrorCode.NETWORK_ERROR, e.message ?: "Commit error")
            }
        }

        CommitOutcome.DeferrableErr(TaskErrorCode.HTTP_409_UNRESOLVED, "Не удалось закоммитить $path")
    }

    private fun classifyAiError(e: Throwable): TaskErrorCode {
        val msg = e.message?.lowercase() ?: ""
        return when {
            "429" in msg || "rate limit" in msg || "лимит" in msg -> TaskErrorCode.HTTP_429_RATE_LIMIT
            "503" in msg || "502" in msg || "500" in msg || "перегружен" in msg -> TaskErrorCode.HTTP_5XX_SERVER
            "timeout" in msg || "таймаут" in msg -> TaskErrorCode.NETWORK_ERROR
            else -> TaskErrorCode.AI_EMPTY_RESPONSE
        }
    }

    private suspend fun kotlinx.coroutines.channels.ProducerScope<ExecutorEvent>.executeDeleteTaskOnline(
        task: FileTask, taskId: String, startTime: Long
    ) {
        val cfg = try { appSettings.gitHubConfig.first() }
        catch (e: Exception) {
            send(ExecutorEvent.Final(TaskExecutionResult.Fatal(TaskErrorCode.UNKNOWN, "GitHub config error")))
            return
        }

        val fileInfo = gitHubClient.getFileContent(task.filePath, cfg.branch).getOrNull()
        if (fileInfo == null) {
            send(ExecutorEvent.Final(TaskExecutionResult.NoChangesNeeded("not-found", 0, 0.0, "", "")))
            return
        }

        commitLock.withLock {
            val result = gitHubClient.deleteFileExt(
                owner = cfg.owner, repo = cfg.repo, token = cfg.token,
                path = task.filePath, message = "[Pipeline] DELETE ${task.filePath.substringAfterLast('/')}",
                sha = fileInfo.sha, branch = cfg.branch
            )

            if (result.isFailure) {
                send(ExecutorEvent.Final(TaskExecutionResult.Deferrable(TaskErrorCode.NETWORK_ERROR, "DELETE failed")))
                return@withLock
            }

            repoIndexManager.invalidate()
            send(ExecutorEvent.Final(TaskExecutionResult.Success(
                commitSha = "delete-${task.id}", resolvedConflict = false, tokensUsed = 0, costEur = 0.0,
                editResult = null, originalContent = "", finalContent = ""
            )))
        }
        delay(INTER_FILE_DELAY_MS)
    }

    private suspend fun kotlinx.coroutines.channels.ProducerScope<ExecutorEvent>.executeDeleteTaskOffline(
        task: FileTask, taskId: String, startTime: Long
    ) {
        if (!localRepoManager.fileExists(task.filePath)) {
            send(ExecutorEvent.Final(TaskExecutionResult.NoChangesNeeded("not-found", 0, 0.0, "", "")))
            return
        }

        val result = localRepoManager.deleteFile(task.filePath)
        if (result.isFailure) {
            send(ExecutorEvent.Final(TaskExecutionResult.Deferrable(TaskErrorCode.NETWORK_ERROR, "Локальное удаление упало")))
            return
        }

        send(ExecutorEvent.Final(TaskExecutionResult.Success(
            commitSha = "pending-batch", resolvedConflict = false, tokensUsed = 0, costEur = 0.0,
            editResult = null, originalContent = "", finalContent = ""
        )))
    }
}