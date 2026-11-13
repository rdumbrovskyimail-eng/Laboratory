package com.opuside.app.feature.pipeline.data

import android.util.Base64
import android.util.Log
import com.opuside.app.core.ai.RepoIndexManager
import com.opuside.app.core.data.AppSettings
import com.opuside.app.core.network.github.GitHubApiClient
import com.opuside.app.core.network.github.GitHubApiException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * ⚡ PIPELINE EXECUTOR v4.0
 *
 * Пофайловое выполнение плана. Модель — только [PipelineAiConfig.MODEL_ID].
 *
 * MODIFY:
 *   1. если в промпте есть блок с ПОЛНЫМ содержимым файла и он прошёл проверку
 *      [PipelineContentGuard.fullReplacementProblem] → файл заменяется блоком дословно
 *      (без модели, $0);
 *   2. иначе — якорные правки [PipelineEditEngine] → применение all-or-nothing →
 *      проверка плейсхолдеров / структуры / усадки → запись.
 * CREATE:
 *   - сначала проверяем, что файла НЕТ (если есть — это MODIFY, оригинал не затирается);
 *   - содержимое берётся из блока промпта дословно или генерируется отдельным вызовом.
 * DELETE: как раньше, но без падения всего конвейера.
 *
 * Online: запись через Contents API строго с SHA прочитанной версии. Если файл
 * изменился извне — задача откладывается (раньше чужие изменения молча затирались).
 */
@Singleton
class PipelineExecutor @Inject constructor(
    private val gitHubClient: GitHubApiClient,
    private val editEngine: PipelineEditEngine,
    private val repoIndexManager: RepoIndexManager,
    private val appSettings: AppSettings,
    private val localRepoManager: LocalRepoManager
) {

    companion object {
        private const val TAG = "PipelineExecutor"
        private const val NETWORK_RETRY_MAX = 2
        private const val INTER_FILE_DELAY_MS = 150L
    }

    private val fileLocks = ConcurrentHashMap<String, Mutex>()
    fun lockFor(path: String): Mutex = fileLocks.computeIfAbsent(path) { Mutex() }
    fun clearFileLocks() { fileLocks.clear() }

    /** Сериализация записей в ветку: параллельная генерация, последовательные коммиты. */
    private val commitLock = Mutex()

    sealed class ExecutorEvent {
        data class Gemini(val log: GeminiLogEvent) : ExecutorEvent()
        data class Repo(val log: RepoLogEvent) : ExecutorEvent()
        data class Final(val result: TaskExecutionResult) : ExecutorEvent()
    }

    // ═══════════════════════════════════════════════════════════════════════
    // STORAGE ABSTRACTION (online / offline)
    // ═══════════════════════════════════════════════════════════════════════

    private sealed class ReadOutcome {
        data class Ok(val content: String, val sha: String?) : ReadOutcome()
        object NotFound : ReadOutcome()
        data class Err(val code: TaskErrorCode, val message: String, val fatal: Boolean) : ReadOutcome()
    }

    private sealed class WriteOutcome {
        data class Ok(val sha: String) : WriteOutcome()
        object AlreadyExists : WriteOutcome()
        data class Conflict(val message: String) : WriteOutcome()
        data class Err(val code: TaskErrorCode, val message: String, val fatal: Boolean) : WriteOutcome()
    }

    private interface FileStore {
        val offline: Boolean
        suspend fun read(path: String): ReadOutcome
        /** expectedSha == null → создание (файла быть не должно). */
        suspend fun write(path: String, content: String, expectedSha: String?, message: String): WriteOutcome
        suspend fun delete(path: String, sha: String?, message: String): WriteOutcome
    }

    private inner class OnlineStore : FileStore {
        override val offline = false

        override suspend fun read(path: String): ReadOutcome = withContext(Dispatchers.IO) {
            val branch = try { appSettings.gitHubConfig.first().branch }
            catch (e: Exception) { return@withContext ReadOutcome.Err(TaskErrorCode.UNKNOWN, "GitHub config: ${e.message}", true) }

            var attempt = 0
            var lastMessage = "Не удалось прочитать $path"
            while (attempt <= NETWORK_RETRY_MAX) {
                try {
                    val file = gitHubClient.getFileContent(path, branch).getOrThrow()
                    val cleaned = (file.content ?: "").filter { !it.isWhitespace() }
                    val content = if (cleaned.isEmpty()) "" else try {
                        String(Base64.decode(cleaned, Base64.NO_WRAP), Charsets.UTF_8)
                    } catch (e: IllegalArgumentException) {
                        return@withContext ReadOutcome.Err(TaskErrorCode.NETWORK_ERROR, "Не удалось декодировать $path", false)
                    }
                    return@withContext ReadOutcome.Ok(content, file.sha)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: GitHubApiException) {
                    if (e.isNotFound) return@withContext ReadOutcome.NotFound
                    if (e.isUnauthorized || e.isForbidden) {
                        return@withContext ReadOutcome.Err(TaskErrorCode.AUTH_ERROR, "GitHub: доступ запрещён (${e.message})", true)
                    }
                    lastMessage = e.message ?: lastMessage
                } catch (e: Exception) {
                    lastMessage = e.message ?: lastMessage
                }
                attempt++
                if (attempt <= NETWORK_RETRY_MAX) delay(1000L * attempt)
            }
            ReadOutcome.Err(TaskErrorCode.NETWORK_ERROR, lastMessage, false)
        }

        override suspend fun write(path: String, content: String, expectedSha: String?, message: String): WriteOutcome =
            withContext(Dispatchers.IO) {
                val branch = try { appSettings.gitHubConfig.first().branch }
                catch (e: Exception) { return@withContext WriteOutcome.Err(TaskErrorCode.UNKNOWN, "GitHub config: ${e.message}", true) }

                commitLock.withLock {
                    for (attempt in 0..1) {
                        try {
                            val res = gitHubClient.createOrUpdateFile(
                                path = path, content = content, message = message,
                                sha = expectedSha, branch = branch
                            ).getOrThrow()
                            return@withLock WriteOutcome.Ok(res.content.sha)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: GitHubApiException) {
                            if (e.isUnauthorized || e.isForbidden) {
                                return@withLock WriteOutcome.Err(TaskErrorCode.AUTH_ERROR, "GitHub: нет прав на запись (${e.message})", true)
                            }
                            if (expectedSha == null && (e.statusCode == 422 || e.statusCode == 409)) {
                                // Создание файла, который уже существует → НЕ перезаписываем.
                                return@withLock WriteOutcome.AlreadyExists
                            }
                            if (e.statusCode == 409 && expectedSha != null) {
                                // Либо гонка обновления ветки (файл тот же — можно повторить),
                                // либо файл изменили извне (тогда правка устарела).
                                val fresh = gitHubClient.getFileContent(path, branch).getOrNull()
                                if (fresh?.sha == expectedSha && attempt == 0) {
                                    delay(700L)
                                    continue
                                }
                                return@withLock WriteOutcome.Conflict("Файл изменился в репозитории во время работы конвейера")
                            }
                            return@withLock WriteOutcome.Err(TaskErrorCode.NETWORK_ERROR, e.message ?: "GitHub error", false)
                        } catch (e: Exception) {
                            return@withLock WriteOutcome.Err(TaskErrorCode.NETWORK_ERROR, e.message ?: "Commit error", false)
                        }
                    }
                    WriteOutcome.Conflict("Не удалось закоммитить $path")
                }
            }

        override suspend fun delete(path: String, sha: String?, message: String): WriteOutcome =
            withContext(Dispatchers.IO) {
                val cfg = try { appSettings.gitHubConfig.first() }
                catch (e: Exception) { return@withContext WriteOutcome.Err(TaskErrorCode.UNKNOWN, "GitHub config: ${e.message}", true) }
                if (sha == null) return@withContext WriteOutcome.Err(TaskErrorCode.UNKNOWN, "Нет SHA для удаления", false)
                commitLock.withLock {
                    val r = gitHubClient.deleteFileExt(
                        owner = cfg.owner, repo = cfg.repo, token = cfg.token,
                        path = path, message = message, sha = sha, branch = cfg.branch
                    )
                    if (r.isSuccess) WriteOutcome.Ok("delete")
                    else {
                        val msg = r.exceptionOrNull()?.message ?: "DELETE failed"
                        when {
                            msg.contains(" 409") -> WriteOutcome.Conflict("Файл изменился в репозитории")
                            msg.contains(" 401") || msg.contains(" 403") ->
                                WriteOutcome.Err(TaskErrorCode.AUTH_ERROR, msg, true)
                            else -> WriteOutcome.Err(TaskErrorCode.NETWORK_ERROR, msg, false)
                        }
                    }
                }
            }
    }

    private inner class OfflineStore : FileStore {
        override val offline = true

        override suspend fun read(path: String): ReadOutcome {
            if (!localRepoManager.fileExists(path)) return ReadOutcome.NotFound
            return localRepoManager.readFile(path).fold(
                onSuccess = { ReadOutcome.Ok(it, null) },
                onFailure = { e ->
                    if (e is java.io.FileNotFoundException) ReadOutcome.NotFound
                    else ReadOutcome.Err(TaskErrorCode.UNKNOWN, e.message ?: "Read failed: $path", false)
                }
            )
        }

        override suspend fun write(path: String, content: String, expectedSha: String?, message: String): WriteOutcome {
            if (expectedSha == null && localRepoManager.fileExists(path)) return WriteOutcome.AlreadyExists
            return localRepoManager.writeFile(path, content).fold(
                onSuccess = { WriteOutcome.Ok("pending-batch") },
                onFailure = { WriteOutcome.Err(TaskErrorCode.UNKNOWN, "Локальная запись: ${it.message}", false) }
            )
        }

        override suspend fun delete(path: String, sha: String?, message: String): WriteOutcome =
            localRepoManager.deleteFile(path).fold(
                onSuccess = { WriteOutcome.Ok("pending-batch") },
                onFailure = { WriteOutcome.Err(TaskErrorCode.UNKNOWN, "Локальное удаление: ${it.message}", false) }
            )
    }

    private val onlineStore = OnlineStore()
    private val offlineStore = OfflineStore()

    // ═══════════════════════════════════════════════════════════════════════
    // ENTRY POINT
    // ═══════════════════════════════════════════════════════════════════════

    fun executeTask(
        task: FileTask,
        ctx: PipelineRunContext,
        isRetryPass: Boolean,
        offlineMode: Boolean
    ): Flow<ExecutorEvent> = channelFlow {
        val store: FileStore = if (offlineMode) offlineStore else onlineStore
        val run = TaskRun(task, ctx, isRetryPass, store, System.currentTimeMillis())

        gemini(GeminiEventType.TASK_START, task.operation.emoji,
            "${task.operation.displayName}: ${task.filePath.substringAfterLast('/')}" +
                    if (isRetryPass) " (повтор: ${task.lastError?.take(60) ?: "—"})" else "", task.id)

        try {
            when (task.operation) {
                TaskOperation.MODIFY -> executeModify(run)
                TaskOperation.CREATE -> executeCreate(run)
                TaskOperation.DELETE -> executeDelete(run)
            }
            delay(INTER_FILE_DELAY_MS)
        } catch (e: CancellationException) {
            Log.d(TAG, "Task ${task.filePath} cancelled")
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error in task ${task.filePath}", e)
            finish(TaskExecutionResult.Deferrable(TaskErrorCode.UNKNOWN, e.message ?: "Unknown error",
                run.tokens, run.cost))
        }
    }

    /** Состояние одной задачи: накапливает токены/стоимость всех вызовов модели. */
    private class TaskRun(
        val task: FileTask,
        val ctx: PipelineRunContext,
        val isRetry: Boolean,
        val store: FileStore,
        val startTime: Long
    ) {
        var tokens = 0
        var cost = 0.0
        val name: String get() = task.filePath.substringAfterLast('/')
        fun add(r: PipelineGeminiClient.Response) { tokens += r.totalTokens; cost += r.costEur }
        fun add(e: Throwable) {
            if (e is PipelineGeminiClient.GeminiCallException) {
                tokens += e.spentInputTokens + e.spentOutputTokens
                cost += e.spentCostEur
            }
        }
        fun elapsed() = System.currentTimeMillis() - startTime
    }

    // ═══════════════════════════════════════════════════════════════════════
    // MODIFY
    // ═══════════════════════════════════════════════════════════════════════

    private suspend fun ProducerScope<ExecutorEvent>.executeModify(run: TaskRun) {
        val task = run.task
        when (val read = run.store.read(task.filePath)) {
            is ReadOutcome.Err -> {
                finish(if (read.fatal) TaskExecutionResult.Fatal(read.code, read.message)
                else TaskExecutionResult.Deferrable(read.code, read.message))
            }
            ReadOutcome.NotFound -> {
                if (task.fullContentBlock != null) {
                    gemini(GeminiEventType.INFO, "➕", "Файла нет — создаю из блока #${task.fullContentBlock}", task.id)
                    createWithContent(run)
                } else {
                    finish(TaskExecutionResult.Failed(TaskErrorCode.FILE_NOT_FOUND, "Файл не найден: ${task.filePath}"))
                }
            }
            is ReadOutcome.Ok -> {
                repo(RepoEventType.FILE_READ, "📄", "Прочитан: ${run.name} (${read.content.lines().size} строк)", task.id)
                modifyExisting(run, read.content, read.sha)
            }
        }
    }

    private suspend fun ProducerScope<ExecutorEvent>.modifyExisting(run: TaskRun, original: String, sha: String?) {
        val task = run.task

        // ── 1. Полная замена блоком из промпта (без модели) ────────────────
        run.ctx.block(task.fullContentBlock)?.let { block ->
            val candidate = adaptLineEndings(block.content, original)
            val problem = PipelineContentGuard.fullReplacementProblem(task.filePath, original, candidate)
            if (problem == null) {
                if (candidate == original) {
                    finish(TaskExecutionResult.NoChangesNeeded(sha ?: "offline", 0, 0.0, original, original))
                    return
                }
                gemini(GeminiEventType.INFO, "📎", "Замена содержимым блока #${block.index} (дословно, без модели)", task.id)
                writeAndFinish(run, original, candidate, sha, "блок #${block.index} целиком")
                return
            }
            gemini(GeminiEventType.GUARD_REJECT, "🛡", "Блок #${block.index} не принят как целый файл: $problem → точечная правка", task.id)
        }

        // ── 2. Пары «Найти / Заменить на» — кодом, дословно, без модели ────
        val pairs = task.editPairs.mapNotNull { run.ctx.pair(it) }
        var working = original
        val appliedPairs = mutableListOf<FindReplacePair>()
        val alreadyPairs = mutableListOf<FindReplacePair>()
        val unresolved = mutableListOf<Pair<FindReplacePair, String>>()
        for (pair in pairs) {
            when (val r = FindReplaceApplier.apply(working, pair)) {
                is FindReplaceApplier.Result.Applied -> {
                    working = r.newContent
                    appliedPairs += pair
                    gemini(GeminiEventType.AI_APPLY_OK, "🔁",
                        "Пара #${pair.index} (${pair.kind.label}) применена дословно" +
                                (if (r.occurrences > 1) " · вхождений: ${r.occurrences}" else "") +
                                (r.note?.let { " · $it" } ?: ""), task.id)
                }
                FindReplaceApplier.Result.AlreadyApplied -> {
                    alreadyPairs += pair
                    gemini(GeminiEventType.INFO, "⚪", "Пара #${pair.index}: изменение уже есть в файле — пропуск", task.id)
                }
                is FindReplaceApplier.Result.Unresolved -> {
                    unresolved += pair to r.reason
                    gemini(GeminiEventType.GUARD_REJECT, "🔎", "Пара #${pair.index}: ${r.reason} → место ищет модель", task.id)
                }
            }
        }
        val pairsSummary = if (pairs.isEmpty()) null else buildString {
            append("замены: ${appliedPairs.size} дословно")
            if (alreadyPairs.isNotEmpty()) append(", ${alreadyPairs.size} уже были")
            if (unresolved.isNotEmpty()) append(", ${unresolved.size} через модель")
        }
        // Строки, которые пользователь сам написал в «Заменить на» — плейсхолдерами не считаются
        val userLines = pairs.flatMap { it.replacement.lines() }.map { it.trim() }.toSet()

        val needModel = pairs.isEmpty() || !task.pairsOnly || unresolved.isNotEmpty()
        if (!needModel) {
            if (working == original) {
                finish(TaskExecutionResult.NoChangesNeeded(sha ?: "offline", 0, 0.0, original, original))
                return
            }
            PipelineContentGuard.structureProblem(task.filePath, original, working)?.let {
                reject(run, TaskErrorCode.VALIDATION_FAILED, "После замен $it — файл не записан. Проверьте границы фрагментов «Найти».")
                return
            }
            gemini(GeminiEventType.AI_APPLY_OK, "✏️",
                "Готово к записи без модели: ${original.lines().size} → ${working.lines().size} строк", task.id)
            writeAndFinish(run, original, working, sha, pairsSummary)
            return
        }

        // ── 3. Якорные правки моделью (словесные правки и/или ненайденные пары) ──
        val refBlocks = task.codeBlocks.mapNotNull { run.ctx.block(it) }
        gemini(GeminiEventType.AI_REQUEST, "📤",
            "→ ${PipelineAiConfig.DISPLAY_NAME} · правки · thinking=${if (run.isRetry) PipelineAiConfig.EDIT_RETRY_THINKING.apiValue else PipelineAiConfig.EDIT_THINKING.apiValue}" +
                    (if (refBlocks.isNotEmpty()) " · блоки ${refBlocks.joinToString { "#${it.index}" }}" else "") +
                    (if (unresolved.isNotEmpty()) " · пары ${unresolved.joinToString { "#${it.first.index}" }}" else ""), task.id)

        val onlyPairsLeft = task.pairsOnly && unresolved.isNotEmpty()
        val plan = editEngine.requestEdits(PipelineEditEngine.EditRequest(
            path = task.filePath,
            content = working,
            instructions = if (onlyPairsLeft)
                "Apply ONLY the user's find/replace pairs listed below that code could not match exactly. Change nothing else."
            else task.instructions,
            referenceBlocks = refBlocks,
            promptContext = if (onlyPairsLeft) null else promptContextFor(run),
            isRetry = run.isRetry,
            retryReason = task.lastError,
            appliedPairs = appliedPairs + alreadyPairs,
            unresolvedPairs = unresolved
        )).getOrElse { e ->
            run.add(e)
            finish(mapAiFailure(e, run))
            return
        }
        run.add(plan.response)
        aiResponseLog(run, plan.response, "${plan.edits.size} правок" + if (plan.summary.isNotBlank()) " · ${plan.summary.take(80)}" else "")

        val afterModel: String = if (plan.noChanges) {
            gemini(GeminiEventType.INFO, "⚪", "Модель: дополнительных изменений не требуется", task.id)
            working
        } else {
            when (val outcome = AnchoredEditApplier.apply(working, plan.edits)) {
                is ApplyOutcome.Failed -> {
                    val reason = outcome.reasons.joinToString("; ")
                    gemini(GeminiEventType.AI_APPLY_FAIL, "❌", "Правки не применены: ${reason.take(160)}", task.id)
                    finish(TaskExecutionResult.Deferrable(TaskErrorCode.NOT_FOUND_BLOCKS, reason, run.tokens, run.cost))
                    return
                }
                is ApplyOutcome.Applied -> {
                    outcome.notes.forEach { gemini(GeminiEventType.INFO, "🔎", it, task.id) }
                    gemini(GeminiEventType.AI_BLOCKS_PARSED, "🔧",
                        "Применено ${outcome.appliedCount}/${plan.edits.size}" +
                                (if (outcome.noopCount > 0) " · ${outcome.noopCount} без эффекта" else "") +
                                (if (outcome.fuzzyCount > 0) " · ${outcome.fuzzyCount} с выравниванием отступов" else ""), task.id)
                    outcome.newContent
                }
            }
        }

        // Ненайденные пары модель тоже не применила → файл не трогаем (all-or-nothing)
        if (unresolved.isNotEmpty() && afterModel == working) {
            val msg = "Не найдено место для " + unresolved.joinToString("; ") { (p, r) ->
                "пары #${p.index} («${p.findPreview.take(50)}»): $r"
            } + ". Файл не изменён — проверьте текст «Найти»."
            gemini(GeminiEventType.AI_APPLY_FAIL, "❌", msg.take(200), task.id)
            finish(TaskExecutionResult.Deferrable(TaskErrorCode.NOT_FOUND_BLOCKS, msg, run.tokens, run.cost))
            return
        }

        if (afterModel == original) {
            finish(TaskExecutionResult.NoChangesNeeded(sha ?: "offline", run.tokens, run.cost, original, original))
            return
        }

        // ── 4. Защита целостности ──────────────────────────────────────────
        val newContent = afterModel
        PipelineContentGuard.findNewPlaceholders(original, newContent)
            .filterNot { it.trim() in userLines }
            .takeIf { it.isNotEmpty() }?.let { lazy ->
                reject(run, TaskErrorCode.LAZY_PLACEHOLDER,
                    "Модель вставила плейсхолдер вместо кода: «${lazy.first().take(80)}». Нужно писать код полностью.")
                return
            }
        PipelineContentGuard.structureProblem(task.filePath, original, newContent)?.let {
            reject(run, TaskErrorCode.VALIDATION_FAILED, it)
            return
        }
        // Усадку проверяем только для части, сделанной моделью (замены пользователя — его решение)
        PipelineContentGuard.shrinkProblem(working, newContent, task.instructions)?.let {
            reject(run, TaskErrorCode.VALIDATION_FAILED, it)
            return
        }

        gemini(GeminiEventType.AI_APPLY_OK, "✏️",
            "Готово к записи: ${original.lines().size} → ${newContent.lines().size} строк", task.id)
        val summary = listOfNotNull(pairsSummary, plan.summary.ifBlank { null }).joinToString(" · ").ifBlank { null }
        writeAndFinish(run, original, newContent, sha, summary)
    }

    // ═══════════════════════════════════════════════════════════════════════
    // CREATE
    // ═══════════════════════════════════════════════════════════════════════

    private suspend fun ProducerScope<ExecutorEvent>.executeCreate(run: TaskRun) {
        val task = run.task
        // Никогда не создаём поверх существующего файла.
        when (val read = run.store.read(task.filePath)) {
            is ReadOutcome.Err -> {
                finish(if (read.fatal) TaskExecutionResult.Fatal(read.code, read.message)
                else TaskExecutionResult.Deferrable(read.code, read.message))
                return
            }
            is ReadOutcome.Ok -> {
                gemini(GeminiEventType.GUARD_REJECT, "🛡",
                    "${run.name} уже существует — выполняю как правку, оригинал не затирается", task.id)
                modifyExisting(run, read.content, read.sha)
                return
            }
            ReadOutcome.NotFound -> createWithContent(run)
        }
    }

    private suspend fun ProducerScope<ExecutorEvent>.createWithContent(run: TaskRun) {
        val task = run.task
        var content: String? = null
        var summary: String? = null

        run.ctx.block(task.fullContentBlock)?.let { block ->
            val candidate = block.content.trimEnd() + "\n"
            val problem = PipelineContentGuard.newFileProblem(task.filePath, candidate, fromUserBlock = true)
            if (problem == null) {
                content = candidate
                summary = "создан из блока #${block.index} дословно"
                gemini(GeminiEventType.INFO, "📎", "Содержимое из блока #${block.index} (дословно, без модели)", task.id)
            } else {
                gemini(GeminiEventType.GUARD_REJECT, "🛡", "Блок #${block.index} не принят: $problem → генерация", task.id)
            }
        }

        if (content == null) {
            val refBlocks = task.codeBlocks.mapNotNull { run.ctx.block(it) }
            gemini(GeminiEventType.AI_REQUEST, "📤", "→ ${PipelineAiConfig.DISPLAY_NAME} · генерация файла", task.id)
            val gen = editEngine.generateNewFile(PipelineEditEngine.CreateRequest(
                path = task.filePath,
                instructions = task.instructions,
                referenceBlocks = refBlocks,
                promptContext = promptContextFor(run),
                isRetry = run.isRetry,
                retryReason = task.lastError
            )).getOrElse { e ->
                run.add(e)
                finish(mapAiFailure(e, run))
                return
            }
            run.add(gen.response)
            aiResponseLog(run, gen.response, "${gen.content.lines().size} строк")

            val problem = PipelineContentGuard.newFileProblem(task.filePath, gen.content, fromUserBlock = false)
            if (problem != null) {
                val code = if (problem.startsWith("плейсхолдер")) TaskErrorCode.LAZY_PLACEHOLDER else TaskErrorCode.VALIDATION_FAILED
                reject(run, code, problem)
                return
            }
            content = gen.content
            summary = "сгенерирован моделью"
        }

        val finalContent: String = content

        when (val w = run.store.write(task.filePath, finalContent, null, "[Pipeline] CREATE ${run.name}")) {
            is WriteOutcome.Ok -> {
                afterWrite(run, w.sha, "Создан")
                finish(TaskExecutionResult.Success(
                    commitSha = w.sha, resolvedConflict = false,
                    tokensUsed = run.tokens, costEur = run.cost, summary = summary,
                    originalContent = "", finalContent = finalContent
                ))
            }
            WriteOutcome.AlreadyExists -> finish(TaskExecutionResult.Deferrable(
                TaskErrorCode.FILE_ALREADY_EXISTS,
                "Файл появился в репозитории во время работы — повтор выполнит его как правку",
                run.tokens, run.cost
            ))
            is WriteOutcome.Conflict -> finish(TaskExecutionResult.Deferrable(TaskErrorCode.HTTP_409_UNRESOLVED, w.message, run.tokens, run.cost))
            is WriteOutcome.Err -> finish(writeErr(w, run))
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // DELETE
    // ═══════════════════════════════════════════════════════════════════════

    private suspend fun ProducerScope<ExecutorEvent>.executeDelete(run: TaskRun) {
        val task = run.task
        when (val read = run.store.read(task.filePath)) {
            is ReadOutcome.Err -> finish(if (read.fatal) TaskExecutionResult.Fatal(read.code, read.message)
                else TaskExecutionResult.Deferrable(read.code, read.message))
            ReadOutcome.NotFound -> {
                gemini(GeminiEventType.INFO, "⚪", "Файла уже нет — удалять нечего", task.id)
                finish(TaskExecutionResult.NoChangesNeeded("not-found", 0, 0.0, null, null))
            }
            is ReadOutcome.Ok -> {
                when (val w = run.store.delete(task.filePath, read.sha, "[Pipeline] DELETE ${run.name}")) {
                    is WriteOutcome.Ok -> {
                        afterWrite(run, w.sha, "Удалён")
                        finish(TaskExecutionResult.Success(
                            commitSha = if (run.store.offline) "pending-batch" else "delete-${task.id}",
                            resolvedConflict = false, tokensUsed = 0, costEur = 0.0,
                            summary = "файл удалён", originalContent = read.content, finalContent = null
                        ))
                    }
                    is WriteOutcome.Conflict -> finish(TaskExecutionResult.Deferrable(TaskErrorCode.HTTP_409_UNRESOLVED, w.message))
                    is WriteOutcome.Err -> finish(writeErr(w, run))
                    WriteOutcome.AlreadyExists -> finish(TaskExecutionResult.Deferrable(TaskErrorCode.UNKNOWN, "Неожиданный ответ при удалении"))
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // SHARED
    // ═══════════════════════════════════════════════════════════════════════

    private suspend fun ProducerScope<ExecutorEvent>.writeAndFinish(
        run: TaskRun, original: String, newContent: String, sha: String?, summary: String?
    ) {
        val task = run.task
        // Offline-хранилищу SHA не нужен, но null означал бы «создание» → передаём маркер.
        val expected = sha ?: "offline-existing"
        when (val w = run.store.write(task.filePath, newContent, expected, "[Pipeline] ${run.name}")) {
            is WriteOutcome.Ok -> {
                afterWrite(run, w.sha, "Записан")
                finish(TaskExecutionResult.Success(
                    commitSha = w.sha, resolvedConflict = false,
                    tokensUsed = run.tokens, costEur = run.cost, summary = summary,
                    originalContent = original, finalContent = newContent
                ))
            }
            is WriteOutcome.Conflict -> {
                repo(RepoEventType.COMMIT_CONFLICT, "⚠️", "${run.name}: ${w.message} → повтор с новой версией", task.id)
                finish(TaskExecutionResult.Deferrable(TaskErrorCode.HTTP_409_UNRESOLVED, w.message, run.tokens, run.cost))
            }
            WriteOutcome.AlreadyExists -> finish(TaskExecutionResult.Deferrable(
                TaskErrorCode.FILE_ALREADY_EXISTS, "Неожиданный конфликт создания", run.tokens, run.cost))
            is WriteOutcome.Err -> finish(writeErr(w, run))
        }
    }

    private suspend fun ProducerScope<ExecutorEvent>.afterWrite(run: TaskRun, sha: String, verb: String) {
        if (run.store.offline) {
            repo(RepoEventType.LOCAL_WRITE, "📝", "$verb локально: ${run.name} (${run.elapsed()}ms)", run.task.id)
        } else {
            repoIndexManager.invalidate()
            repo(RepoEventType.FILE_COMMITTED, "💾", "$verb: ${run.name} · ${sha.take(8)} (${run.elapsed()}ms)",
                run.task.id, commitSha = sha)
        }
    }

    private fun writeErr(w: WriteOutcome.Err, run: TaskRun): TaskExecutionResult =
        if (w.fatal) TaskExecutionResult.Fatal(w.code, w.message)
        else TaskExecutionResult.Deferrable(w.code, w.message, run.tokens, run.cost)

    private suspend fun ProducerScope<ExecutorEvent>.reject(run: TaskRun, code: TaskErrorCode, message: String) {
        gemini(GeminiEventType.GUARD_REJECT, "🛡", "Отклонено защитой: ${message.take(160)}", run.task.id)
        finish(TaskExecutionResult.Deferrable(code, message, run.tokens, run.cost))
    }

    private fun mapAiFailure(e: Throwable, run: TaskRun): TaskExecutionResult {
        val msg = e.message ?: "AI call failed"
        val kind = (e as? PipelineGeminiClient.GeminiCallException)?.kind
            ?: return TaskExecutionResult.Deferrable(TaskErrorCode.UNKNOWN, msg, run.tokens, run.cost)
        return when (kind) {
            PipelineGeminiClient.FailureKind.NO_KEY,
            PipelineGeminiClient.FailureKind.AUTH -> TaskExecutionResult.Fatal(TaskErrorCode.AUTH_ERROR, msg)
            PipelineGeminiClient.FailureKind.RATE_LIMIT ->
                TaskExecutionResult.Deferrable(TaskErrorCode.HTTP_429_RATE_LIMIT, msg, run.tokens, run.cost)
            PipelineGeminiClient.FailureKind.SERVER ->
                TaskExecutionResult.Deferrable(TaskErrorCode.HTTP_5XX_SERVER, msg, run.tokens, run.cost)
            PipelineGeminiClient.FailureKind.NETWORK ->
                TaskExecutionResult.Deferrable(TaskErrorCode.NETWORK_ERROR, msg, run.tokens, run.cost)
            PipelineGeminiClient.FailureKind.TRUNCATED ->
                TaskExecutionResult.Deferrable(TaskErrorCode.OUTPUT_TRUNCATED, msg, run.tokens, run.cost)
            PipelineGeminiClient.FailureKind.EMPTY,
            PipelineGeminiClient.FailureKind.PARSE ->
                TaskExecutionResult.Deferrable(TaskErrorCode.AI_INVALID_RESPONSE, msg, run.tokens, run.cost)
            PipelineGeminiClient.FailureKind.BLOCKED ->
                TaskExecutionResult.Failed(TaskErrorCode.AI_BLOCKED, msg, run.tokens, run.cost)
            PipelineGeminiClient.FailureKind.BAD_REQUEST ->
                if ((e as PipelineGeminiClient.GeminiCallException).httpCode == 404)
                    TaskExecutionResult.Fatal(TaskErrorCode.UNKNOWN, msg)
                else TaskExecutionResult.Failed(TaskErrorCode.AI_INVALID_RESPONSE, msg, run.tokens, run.cost)
        }
    }

    private fun promptContextFor(run: TaskRun): String? {
        val rendered = PromptBlockExtractor.renderForExecutor(run.ctx, run.task.codeBlocks.toSet())
        return rendered.takeIf { it.isNotBlank() && it.length <= PipelineAiConfig.FULL_PROMPT_CONTEXT_LIMIT_CHARS }
    }

    /** Блок из промпта приводим к окончаниям строк и финальному переводу строки оригинала. */
    private fun adaptLineEndings(block: String, original: String): String {
        var text = block.replace("\r\n", "\n").trimEnd('\n')
        val origNormalized = original.replace("\r\n", "\n")
        if (origNormalized.endsWith("\n") || origNormalized.isEmpty()) text += "\n"
        if (original.contains("\r\n")) text = text.replace("\n", "\r\n")
        return text
    }

    private suspend fun ProducerScope<ExecutorEvent>.aiResponseLog(
        run: TaskRun, r: PipelineGeminiClient.Response, details: String
    ) {
        send(ExecutorEvent.Gemini(GeminiLogEvent(
            type = GeminiEventType.AI_RESPONSE, icon = "📥",
            message = "${PipelineAiConfig.BADGE}: $details · ${r.inputTokens}in+${r.billedOutputTokens}out" +
                    (if (r.thinkingTokens > 0) " (думал ${r.thinkingTokens})" else "") +
                    " · €${String.format(Locale.US, "%.5f", r.costEur)}",
            taskId = run.task.id, tokens = r.totalTokens, costEur = r.costEur
        )))
    }

    private suspend fun ProducerScope<ExecutorEvent>.gemini(type: GeminiEventType, icon: String, message: String, taskId: String) =
        send(ExecutorEvent.Gemini(GeminiLogEvent(type = type, icon = icon, message = message, taskId = taskId)))

    private suspend fun ProducerScope<ExecutorEvent>.repo(
        type: RepoEventType, icon: String, message: String, taskId: String, commitSha: String? = null
    ) = send(ExecutorEvent.Repo(RepoLogEvent(type = type, icon = icon, message = message, taskId = taskId, commitSha = commitSha)))

    private suspend fun ProducerScope<ExecutorEvent>.finish(result: TaskExecutionResult) =
        send(ExecutorEvent.Final(result))
}