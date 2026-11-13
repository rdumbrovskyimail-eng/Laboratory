package com.opuside.app.feature.pipeline.presentation

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opuside.app.core.ai.RepoIndexManager
import com.opuside.app.core.data.AppSettings
import com.opuside.app.core.network.github.GitHubApiClient
import com.opuside.app.core.security.SecureSettingsDataStore
import com.opuside.app.feature.pipeline.data.*
import com.opuside.app.feature.pipeline.service.PipelineForegroundService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/**
 * 🧠 PIPELINE VIEW MODEL v4.0
 *
 * Модель фиксирована: [PipelineAiConfig.MODEL_ID] (выбор модели убран).
 *
 * Исправлено относительно v3.2:
 *  - offline: клон готовится ДО снимка бэкапа (иначе откат удалял файлы);
 *  - бэкап включён, но не создан → запуск останавливается, а не идёт «без страховки»;
 *  - offline: commit/push выполняются ДО итогового отчёта, ошибка push видна в задачах;
 *  - ошибка одной задачи больше не роняет весь конвейер (Fatal только для auth/config);
 *  - повторный проход получает точную причину прошлой ошибки;
 *  - отмена (Stop) не превращается в «DEFERRED»;
 *  - planning без индекса репозитория больше не зависает в PLANNING.
 */
@HiltViewModel
class PipelineViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val planner: PipelinePlanner,
    private val executor: PipelineExecutor,
    private val summarizer: PipelineSummarizer,
    private val backupManager: PipelineBackupManager,
    private val workflowWatcher: WorkflowWatcher,
    private val repoIndexManager: RepoIndexManager,
    private val appSettings: AppSettings,
    private val secureSettings: SecureSettingsDataStore,
    private val keyRotator: PipelineKeyRotator,
    private val localRepoManager: LocalRepoManager,
    private val gitHubClient: GitHubApiClient
) : ViewModel() {

    private val _state = MutableStateFlow(PipelineState())
    val state: StateFlow<PipelineState> = _state.asStateFlow()

    private val _userPrompt = MutableStateFlow("")
    val userPrompt: StateFlow<String> = _userPrompt.asStateFlow()

    private val _pipelineKeyA = MutableStateFlow("")
    val pipelineKeyA: StateFlow<String> = _pipelineKeyA.asStateFlow()

    private val _pipelineKeyB = MutableStateFlow("")
    val pipelineKeyB: StateFlow<String> = _pipelineKeyB.asStateFlow()

    private val _pipelineActiveKey = MutableStateFlow(0)
    val pipelineActiveKey: StateFlow<Int> = _pipelineActiveKey.asStateFlow()

    val localRepoStatus: StateFlow<LocalRepoManager.RepoStatus> get() = localRepoManager.status
    val localRepoProgress: StateFlow<String?> get() = localRepoManager.progressMessage

    private val _backups = MutableStateFlow<List<PipelineBackupManager.BackupEntry>>(emptyList())
    val backups: StateFlow<List<PipelineBackupManager.BackupEntry>> = _backups.asStateFlow()

    private val _geminiLog = MutableStateFlow<List<GeminiLogEvent>>(emptyList())
    val geminiLog: StateFlow<List<GeminiLogEvent>> = _geminiLog.asStateFlow()

    private val _repoLog = MutableStateFlow<List<RepoLogEvent>>(emptyList())
    val repoLog: StateFlow<List<RepoLogEvent>> = _repoLog.asStateFlow()

    val visibleGeminiLog: StateFlow<List<GeminiLogEvent>> = combine(_geminiLog, _state) { logs, st ->
        val fid = st.logFilterTaskId
        if (fid == null) logs else logs.filter { it.taskId == fid }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val visibleRepoLog: StateFlow<List<RepoLogEvent>> = combine(_repoLog, _state) { logs, st ->
        val fid = st.logFilterTaskId
        if (fid == null) logs else logs.filter { it.taskId == fid }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _repoStats = MutableStateFlow<RepoStats?>(null)
    val repoStats: StateFlow<RepoStats?> = _repoStats.asStateFlow()

    private val _totalCostEur = MutableStateFlow(0.0)
    val totalCostEur: StateFlow<Double> = _totalCostEur.asStateFlow()

    private val _totalTokens = MutableStateFlow(0)
    val totalTokens: StateFlow<Int> = _totalTokens.asStateFlow()

    private val _userError = MutableStateFlow<String?>(null)
    val userError: StateFlow<String?> = _userError.asStateFlow()

    private var pipelineJob: Job? = null
    private val finalModifiedFilesMap = ConcurrentHashMap<String, String>()

    /** Промпт и его code-блоки, зафиксированные в момент планирования. */
    private var runContext: PipelineRunContext = PipelineRunContext.EMPTY

    init {
        viewModelScope.launch {
            appSettings.gitHubConfig.debounce(500).distinctUntilChanged().collectLatest { config ->
                if (config.owner.isNotBlank() && config.repo.isNotBlank() && config.token.isNotBlank()) loadRepoStats()
                else _repoStats.value = null
            }
        }

        viewModelScope.launch {
            _pipelineKeyA.value = try { secureSettings.pipelineKeyA.first() } catch (_: Exception) { "" }
            _pipelineKeyB.value = try { secureSettings.pipelineKeyB.first() } catch (_: Exception) { "" }
            _pipelineActiveKey.value = try { secureSettings.pipelineActiveKeyIndex.first() } catch (_: Exception) { 0 }
            val savedMode = try { secureSettings.pipelineMode.first() } catch (_: Exception) { "online" }
            val mode = if (savedMode == "offline") PipelineMode.OFFLINE else PipelineMode.ONLINE
            _state.update { it.copy(pipelineMode = mode) }
            loadBackups()
        }

        viewModelScope.launch {
            _state.collect { st ->
                if (st.isRunning) {
                    val title = when (st.phase) {
                        PipelinePhase.PLANNING -> "Pipeline: планирование"
                        PipelinePhase.EXECUTING -> "Pipeline: выполнение"
                        PipelinePhase.DEFERRED_PASS -> "Pipeline: повторный проход"
                        PipelinePhase.FINALIZING -> "Pipeline: формирование отчёта"
                        else -> "Pipeline"
                    }
                    val text = "${st.completedTasks}/${st.totalTasks} задач · ✅ ${st.successfulTasks} · ❌ ${st.failedTasks}"
                    PipelineForegroundService.update(
                        context = appContext,
                        title = title,
                        text = text,
                        completed = st.completedTasks,
                        total = st.totalTasks,
                        indeterminate = st.phase == PipelinePhase.PLANNING || st.phase == PipelinePhase.FINALIZING
                    )
                }
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // OPTIONS / BACKUPS
    // ═══════════════════════════════════════════════════════════════════════

    fun toggleDetailedReport() { _state.update { it.copy(isDetailedReportEnabled = !it.isDetailedReportEnabled) } }
    fun toggleBackup() { _state.update { it.copy(isBackupEnabled = !it.isBackupEnabled) } }

    fun loadBackups() {
        viewModelScope.launch { _backups.value = backupManager.getAllBackups() }
    }

    fun rollbackBackup(backupId: String) {
        if (_state.value.isRunning || _state.value.isRollingBack) return
        viewModelScope.launch {
            _state.update { it.copy(isRollingBack = true) }
            appendRepoLog(RepoLogEvent(type = RepoEventType.ROLLBACK_STARTED, icon = "⏪", message = "Откат к #$backupId..."))
            backupManager.rollback(backupId).fold(
                onSuccess = { res ->
                    appendRepoLog(RepoLogEvent(
                        type = RepoEventType.ROLLBACK_COMPLETED, icon = if (res.failures.isEmpty()) "✅" else "🟡",
                        message = "Восстановлено: ${res.restoredFilesCount} файлов" +
                                if (res.failures.isNotEmpty()) " · проблемы: ${res.failures.joinToString("; ").take(200)}" else ""
                    ))
                    if (res.failures.isNotEmpty()) _userError.value = "Откат частичный: ${res.failures.size} файл(ов) не восстановлено"
                    repoIndexManager.invalidate()
                    loadRepoStats()
                    loadBackups()
                },
                onFailure = { e ->
                    appendRepoLog(RepoLogEvent(type = RepoEventType.ERROR, icon = "❌", message = "Ошибка отката: ${e.message}"))
                    _userError.value = e.message
                }
            )
            _state.update { it.copy(isRollingBack = false) }
        }
    }

    fun deleteBackup(backupId: String) {
        viewModelScope.launch { backupManager.deleteBackup(backupId); loadBackups() }
    }

    fun shareFullReport(context: Context, backupId: String? = null) {
        val targetId = backupId ?: _state.value.currentBackupId
        if (targetId == null) {
            _userError.value = "Полный отчёт доступен только при включённом бэкапе"
            return
        }
        viewModelScope.launch {
            backupManager.generateFullReportText(targetId).fold(
                onSuccess = { text -> backupManager.shareReportFile(context, text, "Pipeline_Audit_Report") },
                onFailure = { e -> _userError.value = e.message }
            )
        }
    }

    fun copyReportToClipboard(context: Context) {
        val text = _state.value.detailedReportText ?: _state.value.finalReport ?: return
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Pipeline Report", text))
        _userError.value = "✅ Полный отчёт скопирован в буфер обмена"
    }

    fun onPromptChange(text: String) { _userPrompt.value = text }
    fun dismissError() { _userError.value = null }

    fun setMaxParallel(value: Int) {
        if (_state.value.isRunning) return
        _state.update { it.copy(maxParallelTasks = value.coerceIn(1, 8)) }
    }

    fun setLogFilter(taskId: String?) { _state.update { it.copy(logFilterTaskId = taskId) } }
    fun setPipelineKeyA(v: String) { _pipelineKeyA.value = v; viewModelScope.launch { secureSettings.setPipelineKeyA(v) } }
    fun setPipelineKeyB(v: String) { _pipelineKeyB.value = v; viewModelScope.launch { secureSettings.setPipelineKeyB(v) } }
    fun setPipelineActiveKey(index: Int) { _pipelineActiveKey.value = index; viewModelScope.launch { secureSettings.setPipelineActiveKeyIndex(index) } }
    fun setPipelineMode(mode: PipelineMode) { _state.update { it.copy(pipelineMode = mode) }; viewModelScope.launch { secureSettings.setPipelineMode(mode.name.lowercase()) } }

    fun syncLocalRepo() {
        viewModelScope.launch {
            localRepoManager.ensureCloned().fold(
                onSuccess = { appendRepoLog(RepoLogEvent(type = RepoEventType.CLONE_DONE, icon = "✅", message = "Клон синхронизирован")) },
                onFailure = { appendRepoLog(RepoLogEvent(type = RepoEventType.ERROR, icon = "❌", message = "Ошибка: ${it.message}")) }
            )
        }
    }

    fun deleteLocalClone() { viewModelScope.launch { localRepoManager.deleteClone() } }

    fun loadRepoStats() {
        viewModelScope.launch {
            repoIndexManager.getOrRefresh()?.let { idx ->
                _repoStats.value = RepoStats(idx.owner, idx.repo, idx.branch, idx.totalFiles, idx.totalDirectories, idx.totalSize, idx.totalSizeFormatted, idx.nodes.filter { it.isFile }.groupingBy { it.extension.lowercase() }.eachCount(), idx.maxDepth, idx.truncated, idx.loadedAt)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // PLAN
    // ═══════════════════════════════════════════════════════════════════════

    fun plan() {
        val prompt = _userPrompt.value.trim()
        if (prompt.isBlank()) { _userError.value = "Введите промпт"; return }
        pipelineJob?.cancel()
        pipelineJob = viewModelScope.launch {
            try {
                resetForNewRun()
                PipelineForegroundService.start(appContext, "Pipeline: планирование", "Анализ задач...")
                val ctx = PipelineRunContext.from(prompt)
                runContext = ctx
                _state.update { it.copy(phase = PipelinePhase.PLANNING, promptBlocksCount = ctx.blocks.size, promptPairsCount = ctx.pairs.size) }
                appendGeminiLog(GeminiLogEvent(
                    type = GeminiEventType.PLANNER_START, icon = "🧠",
                    message = "Планирование · ${PipelineAiConfig.DISPLAY_NAME}" +
                            (if (ctx.blocks.isNotEmpty()) " · блоков кода: ${ctx.blocks.size}" else "") +
                            (if (ctx.pairs.isNotEmpty()) " · пар «Найти/Заменить»: ${ctx.pairs.size}" else "")
                ))

                val repoIndex = repoIndexManager.getOrRefresh()
                    ?: throw IllegalStateException("Не удалось загрузить индекс репозитория. Проверьте настройки GitHub.")
                val filePaths = repoIndex.nodes.filter { it.isFile }.map { it.path }

                val plan = planner.plan(ctx, filePaths).getOrThrow()
                _totalCostEur.update { it + plan.costEur }
                _totalTokens.update { it + plan.tokensUsed }

                val tasks = plan.tasks.map {
                    FileTask(
                        operation = it.operation,
                        filePath = it.file,
                        originalPathFromAi = it.file,
                        instructions = it.instructions,
                        codeBlocks = it.codeBlocks,
                        fullContentBlock = it.fullContentBlock,
                        editPairs = it.editPairs,
                        pairsOnly = it.pairsOnly,
                        warning = it.warning
                    )
                }

                appendGeminiLog(GeminiLogEvent(
                    type = GeminiEventType.PLANNER_DONE, icon = "📋",
                    message = "План: ${tasks.size} задач (" +
                            TaskOperation.values().mapNotNull { op ->
                                tasks.count { it.operation == op }.takeIf { it > 0 }?.let { "${op.emoji}$it" }
                            }.joinToString(" ") + ") · €${String.format(java.util.Locale.US, "%.5f", plan.costEur)}",
                    tokens = plan.tokensUsed, costEur = plan.costEur
                ))
                tasks.filter { it.warning != null }.forEach {
                    appendGeminiLog(GeminiLogEvent(type = GeminiEventType.INFO, icon = "⚠️",
                        message = "${it.filePath.substringAfterLast('/')}: ${it.warning}", taskId = it.id))
                }

                _state.update { it.copy(phase = PipelinePhase.REVIEWING, tasks = tasks, planNotes = plan.notes) }
            } catch (e: CancellationException) {
                // Состояние меняет тот, кто отменил (stop/reset/новый plan) — здесь не трогаем
                throw e
            } catch (e: Exception) {
                _userError.value = e.message
                appendGeminiLog(GeminiLogEvent(type = GeminiEventType.ERROR, icon = "❌", message = e.message ?: "Ошибка планирования"))
                _state.update { it.copy(phase = PipelinePhase.IDLE) }
            } finally {
                PipelineForegroundService.stop(appContext)
            }
        }
    }

    fun removeTask(taskId: String) { _state.update { s -> s.copy(tasks = s.tasks.filterNot { it.id == taskId }) } }

    // ═══════════════════════════════════════════════════════════════════════
    // EXECUTE
    // ═══════════════════════════════════════════════════════════════════════

    fun start() {
        if (!_state.value.canStart) return
        pipelineJob?.cancel()
        pipelineJob = viewModelScope.launch {
            PipelineForegroundService.start(appContext, "Pipeline запущен", "${_state.value.tasks.size} задач")
            try {
                finalModifiedFilesMap.clear()
                executor.clearFileLocks()
                val tasks = _state.value.tasks
                val mode = _state.value.pipelineMode
                val isOffline = mode == PipelineMode.OFFLINE
                val ctx = runContext
                var currentBackupId: String? = null

                // 1. Offline: клон должен быть готов ДО снимка (снимок читает оригиналы из клона)
                if (isOffline) {
                    appendRepoLog(RepoLogEvent(type = RepoEventType.CLONE_START, icon = "📦", message = "Подготовка локального клона..."))
                    localRepoManager.ensureCloned().getOrElse {
                        throw IllegalStateException("Локальный клон не готов: ${it.message}")
                    }
                    appendRepoLog(RepoLogEvent(type = RepoEventType.CLONE_DONE, icon = "✅", message = "Клон готов"))
                }

                // 2. Бэкап: если включён — без него не стартуем
                if (_state.value.isBackupEnabled) {
                    currentBackupId = backupManager.createSnapshot(_state.value.pipelineRunId, ctx.userPrompt, mode, tasks)
                        .getOrElse { throw IllegalStateException("${it.message}. Запуск остановлен — отключите бэкап или повторите.") }
                    _state.update { it.copy(currentBackupId = currentBackupId) }
                    appendRepoLog(RepoLogEvent(type = RepoEventType.BACKUP_CREATED, icon = "🛡", message = "Точка отката создана (${tasks.size} файлов)"))
                }

                // 3. Основной проход
                _state.update { it.copy(phase = PipelinePhase.EXECUTING) }
                val fatalRef = AtomicBoolean(false)
                runTasksInParallel(tasks, ctx, _state.value.maxParallelTasks, fatalRef, isRetry = false)
                if (fatalRef.get()) {
                    if (isOffline) localRepoManager.resetHard()
                    _state.update { it.copy(phase = PipelinePhase.FATAL, fatalError = it.fatalError ?: firstFatalMessage()) }
                    return@launch
                }

                // 4. Повторный проход для отложенных (с причиной ошибки и более глубоким thinking)
                val deferred = _state.value.tasks.filter { it.status == TaskStatus.DEFERRED }
                if (deferred.isNotEmpty()) {
                    _state.update { it.copy(phase = PipelinePhase.DEFERRED_PASS) }
                    if (deferred.any { it.errorCode == TaskErrorCode.HTTP_429_RATE_LIMIT || it.errorCode == TaskErrorCode.HTTP_5XX_SERVER }) {
                        appendGeminiLog(GeminiLogEvent(type = GeminiEventType.INFO, icon = "⏳",
                            message = "Лимит/перегрузка Gemini — пауза 30 с перед повторным проходом"))
                        delay(30_000)
                    }
                    appendGeminiLog(GeminiLogEvent(type = GeminiEventType.AI_RETRY, icon = "🔄",
                        message = "Повторный проход: ${deferred.size} задач (thinking=${PipelineAiConfig.EDIT_RETRY_THINKING.apiValue})"))
                    deferred.forEach { updateTask(it.id) { t -> t.copy(status = TaskStatus.PENDING) } }
                    val retryTasks = _state.value.tasks.filter { t -> deferred.any { it.id == t.id } }
                    runTasksInParallel(retryTasks, ctx, _state.value.maxParallelTasks, fatalRef, isRetry = true)
                    _state.value.tasks.filter { it.status == TaskStatus.DEFERRED || it.status == TaskStatus.PENDING }
                        .forEach { updateTask(it.id) { t -> t.copy(status = TaskStatus.FAILED_FINAL) } }
                    if (fatalRef.get()) {
                        if (isOffline) localRepoManager.resetHard()
                        _state.update { it.copy(phase = PipelinePhase.FATAL, fatalError = it.fatalError ?: firstFatalMessage()) }
                        return@launch
                    }
                }

                _state.update { it.copy(phase = PipelinePhase.FINALIZING) }

                // 5. Offline: commit + push ДО отчёта, чтобы отчёт отражал реальность
                if (isOffline) finalizeOfflineRun()
                else repoIndexManager.invalidate()

                // 6. Детальный отчёт
                if (currentBackupId != null) {
                    backupManager.recordFinalFiles(currentBackupId, finalModifiedFilesMap.toMap())
                    loadBackups()
                    if (_state.value.isDetailedReportEnabled) {
                        val fullReport = backupManager.generateFullReportText(currentBackupId).getOrNull()
                        _state.update { it.copy(detailedReportText = fullReport) }
                    }
                }

                // 7. Сводка
                appendGeminiLog(GeminiLogEvent(type = GeminiEventType.SUMMARY_START, icon = "📊", message = "Формирование сводки..."))
                val summary = summarizer.summarize(ctx.userPrompt, _state.value.tasks, _totalCostEur.value, _totalTokens.value)
                _totalCostEur.update { it + summary.costEur }
                _totalTokens.update { it + summary.tokensUsed }
                appendGeminiLog(GeminiLogEvent(type = GeminiEventType.SUMMARY_DONE, icon = "✅", message = "Сводка готова"))

                _state.update { it.copy(phase = PipelinePhase.DONE, finalReport = summary.text) }
                loadRepoStats()
            } catch (e: CancellationException) {
                // После reset() состояние уже IDLE — не перетираем его
                _state.update { if (it.isRunning) it.copy(phase = PipelinePhase.CANCELLED) else it }
                if (_state.value.pipelineMode == PipelineMode.OFFLINE) {
                    // Не оставляем полуизменённый клон
                    withContext(NonCancellable) { localRepoManager.resetHard() }
                }
            } catch (e: Exception) {
                _state.update { it.copy(phase = PipelinePhase.FATAL, fatalError = e.message) }
                appendRepoLog(RepoLogEvent(type = RepoEventType.ERROR, icon = "❌", message = e.message ?: "Ошибка"))
            } finally {
                PipelineForegroundService.stop(appContext)
            }
        }
    }

    private fun firstFatalMessage(): String =
        _state.value.tasks.firstOrNull { it.errorCode == TaskErrorCode.AUTH_ERROR || it.status == TaskStatus.FAILED_FINAL }
            ?.lastError ?: "Критическая ошибка конвейера"

    private suspend fun finalizeOfflineRun() {
        val written = _state.value.tasks.filter { it.status == TaskStatus.SUCCESS && it.commitSha == "pending-batch" }
        if (written.isEmpty()) return

        appendRepoLog(RepoLogEvent(type = RepoEventType.LOCAL_COMMIT, icon = "📦", message = "Локальный коммит ${written.size} файлов..."))
        val commit = localRepoManager.stageAndCommit("[Pipeline] Изменено ${written.size} файлов")
        val sha = commit.getOrElse { e ->
            if (e is LocalRepoManager.NoChangesException) {
                written.forEach { t -> updateTask(t.id) { it.copy(status = TaskStatus.NO_CHANGES_NEEDED, commitSha = null) } }
                return
            }
            failOfflineTasks(written, "Локальный коммит не удался: ${e.message}")
            return
        }

        appendRepoLog(RepoLogEvent(type = RepoEventType.PUSH_START, icon = "⬆️", message = "Push ${sha.take(8)}..."))
        localRepoManager.push().fold(
            onSuccess = {
                _state.update { st -> st.copy(tasks = st.tasks.map { if (it.commitSha == "pending-batch") it.copy(commitSha = sha) else it }) }
                appendRepoLog(RepoLogEvent(type = RepoEventType.PUSH_DONE, icon = "✅", message = "Отправлено: ${sha.take(8)}", commitSha = sha))
                repoIndexManager.invalidate()
                localRepoManager.deleteClone()
            },
            onFailure = { e -> failOfflineTasks(written, "Изменения НЕ отправлены на GitHub: ${e.message}") }
        )
    }

    private suspend fun failOfflineTasks(tasks: List<FileTask>, message: String) {
        appendRepoLog(RepoLogEvent(type = RepoEventType.PUSH_FAILED, icon = "❌", message = message))
        tasks.forEach { t -> updateTask(t.id) { it.copy(status = TaskStatus.FAILED_FINAL, lastError = message, commitSha = null) } }
        tasks.forEach { finalModifiedFilesMap.remove(it.filePath) }
        localRepoManager.resetHard()
    }

    private suspend fun CoroutineScope.runTasksInParallel(
        tasks: List<FileTask>,
        ctx: PipelineRunContext,
        maxParallel: Int,
        fatalRef: AtomicBoolean,
        isRetry: Boolean
    ) {
        val semaphore = Semaphore(maxParallel)
        tasks.map { task ->
            async {
                if (fatalRef.get()) return@async
                semaphore.withPermit {
                    if (fatalRef.get()) return@withPermit
                    _state.update { it.copy(runningTaskIds = it.runningTaskIds + task.id) }
                    try {
                        val isFatal = runOneTask(task, ctx, isRetry)
                        if (isFatal) fatalRef.set(true)
                    } finally {
                        _state.update { it.copy(runningTaskIds = it.runningTaskIds - task.id) }
                    }
                }
            }
        }.awaitAll()
    }

    private suspend fun runOneTask(task: FileTask, ctx: PipelineRunContext, isRetry: Boolean): Boolean {
        updateTask(task.id) { it.copy(status = TaskStatus.PROCESSING, attempts = it.attempts + 1) }
        // Берём актуальную версию задачи (в ней lastError с прошлого прохода)
        val current = _state.value.tasks.firstOrNull { it.id == task.id } ?: task
        val start = System.currentTimeMillis()
        var fatal = false
        var gotFinal = false
        try {
            executor.lockFor(current.filePath).withLock {
                executor.executeTask(current, ctx, isRetry, _state.value.pipelineMode == PipelineMode.OFFLINE).collect { ev ->
                    when (ev) {
                        is PipelineExecutor.ExecutorEvent.Gemini -> appendGeminiLog(ev.log)
                        is PipelineExecutor.ExecutorEvent.Repo -> appendRepoLog(ev.log)
                        is PipelineExecutor.ExecutorEvent.Final -> {
                            gotFinal = true
                            applyTaskResult(current, ev.result, start)
                            if (ev.result is TaskExecutionResult.Fatal) fatal = true
                        }
                    }
                }
            }
            if (!gotFinal) {
                updateTask(task.id) { it.copy(status = TaskStatus.DEFERRED, lastError = "Задача завершилась без результата") }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            updateTask(task.id) { it.copy(status = TaskStatus.DEFERRED, lastError = "Ошибка выполнения: ${e.message}") }
        }
        return fatal
    }

    private fun applyTaskResult(task: FileTask, result: TaskExecutionResult, start: Long) {
        val dur = System.currentTimeMillis() - start
        val taskId = task.id
        when (result) {
            is TaskExecutionResult.Success -> {
                // В отчёт «после» пишем только реально изменённые/созданные файлы (не удалённые)
                if (task.operation != TaskOperation.DELETE && result.finalContent != null) {
                    finalModifiedFilesMap[task.filePath] = result.finalContent
                }
                updateTask(taskId) {
                    it.copy(status = TaskStatus.SUCCESS, commitSha = result.commitSha, durationMs = dur,
                        lastError = null, errorCode = null, resultSummary = result.summary,
                        tokensUsed = it.tokensUsed + result.tokensUsed, costEur = it.costEur + result.costEur)
                }
                addCost(result.costEur, result.tokensUsed)
            }
            is TaskExecutionResult.NoChangesNeeded -> {
                updateTask(taskId) {
                    it.copy(status = TaskStatus.NO_CHANGES_NEEDED, commitSha = result.commitSha, durationMs = dur,
                        lastError = null, errorCode = null, resultSummary = "изменения не требуются",
                        tokensUsed = it.tokensUsed + result.tokensUsed, costEur = it.costEur + result.costEur)
                }
                addCost(result.costEur, result.tokensUsed)
            }
            is TaskExecutionResult.Deferrable -> {
                updateTask(taskId) {
                    it.copy(status = TaskStatus.DEFERRED, lastError = result.message, errorCode = result.errorCode, durationMs = dur,
                        tokensUsed = it.tokensUsed + result.tokensUsed, costEur = it.costEur + result.costEur)
                }
                addCost(result.costEur, result.tokensUsed)
            }
            is TaskExecutionResult.Failed -> {
                updateTask(taskId) {
                    it.copy(status = TaskStatus.FAILED_FINAL, lastError = result.message, errorCode = result.errorCode, durationMs = dur,
                        tokensUsed = it.tokensUsed + result.tokensUsed, costEur = it.costEur + result.costEur)
                }
                addCost(result.costEur, result.tokensUsed)
            }
            is TaskExecutionResult.Fatal -> {
                updateTask(taskId) { it.copy(status = TaskStatus.FAILED_FINAL, lastError = result.message, errorCode = result.errorCode, durationMs = dur) }
                _state.update { it.copy(fatalError = it.fatalError ?: result.message) }
            }
        }
    }

    private fun addCost(cost: Double, tokens: Int) {
        if (cost > 0) _totalCostEur.update { it + cost }
        if (tokens > 0) _totalTokens.update { it + tokens }
    }

    fun stop() {
        pipelineJob?.cancel()
        _state.update { if (it.isRunning) it.copy(phase = PipelinePhase.CANCELLED) else it }
        PipelineForegroundService.stop(appContext)
    }

    fun reset() {
        pipelineJob?.cancel()
        pipelineJob = null
        finalModifiedFilesMap.clear()
        runContext = PipelineRunContext.EMPTY
        _state.value = PipelineState(pipelineMode = _state.value.pipelineMode,
            isBackupEnabled = _state.value.isBackupEnabled, isDetailedReportEnabled = _state.value.isDetailedReportEnabled,
            maxParallelTasks = _state.value.maxParallelTasks)
        _geminiLog.value = emptyList(); _repoLog.value = emptyList()
        _totalCostEur.value = 0.0; _totalTokens.value = 0
        executor.clearFileLocks()
        PipelineForegroundService.stop(appContext)
    }

    private fun resetForNewRun() {
        finalModifiedFilesMap.clear()
        _state.value = PipelineState(pipelineMode = _state.value.pipelineMode,
            isBackupEnabled = _state.value.isBackupEnabled, isDetailedReportEnabled = _state.value.isDetailedReportEnabled,
            maxParallelTasks = _state.value.maxParallelTasks)
        _geminiLog.value = emptyList(); _repoLog.value = emptyList()
        _totalCostEur.value = 0.0; _totalTokens.value = 0
    }

    fun exportRepoToTxt(context: Context) {
        viewModelScope.launch {
            try {
                val index = repoIndexManager.getOrRefresh() ?: return@launch
                val sb = java.lang.StringBuilder()
                val branch = appSettings.gitHubConfig.first().branch
                val isOffline = _state.value.pipelineMode == PipelineMode.OFFLINE

                sb.appendLine("================================================================================")
                sb.appendLine("ПОЛНЫЙ ДАМП РЕПОЗИТОРИЯ: ${index.owner}/${index.repo} ($branch)")
                sb.appendLine("================================================================================\n")

                for (node in index.nodes.filter { it.isFile }) {
                    val content: String = if (isOffline) {
                        localRepoManager.readFile(node.path).getOrNull() ?: ""
                    } else {
                        gitHubClient.getFileContentDecoded(node.path, branch).getOrNull() ?: ""
                    }
                    sb.appendLine("--- FILE: ${node.path} ---")
                    sb.appendLine(content)
                    sb.appendLine()
                }

                backupManager.shareReportFile(context, sb.toString(), "Repository_${index.repo}_Dump")
                appendRepoLog(RepoLogEvent(type = RepoEventType.INFO, icon = "💾", message = "Сформирован полный TXT-дамп"))
            } catch (e: Exception) {
                _userError.value = "Ошибка экспорта: ${e.message}"
            }
        }
    }

    private fun appendGeminiLog(event: GeminiLogEvent) { _geminiLog.update { (it + event).takeLast(2000) } }
    private fun appendRepoLog(event: RepoLogEvent) { _repoLog.update { (it + event).takeLast(2000) } }
    private fun updateTask(taskId: String, transform: (FileTask) -> FileTask) {
        _state.update { s -> s.copy(tasks = s.tasks.map { if (it.id == taskId) transform(it) else it }) }
    }
}