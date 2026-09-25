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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/**
 * 🧠 PIPELINE VIEW MODEL v3.2 (Speed, Full-Audit & Reliability Optimized)
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
            val savedModel = try { secureSettings.pipelineGeminiModel.first() } catch (_: Exception) { "gemini-3.5-flash-lite" }

            _state.update { it.copy(pipelineMode = mode, selectedModelApiId = savedModel) }
            loadBackups()
        }

        viewModelScope.launch {
            _state.collect { st ->
                if (st.isRunning) {
                    val title = when (st.phase) {
                        PipelinePhase.PLANNING -> "Pipeline: планирование"
                        PipelinePhase.EXECUTING -> "Pipeline: выполнение"
                        PipelinePhase.DEFERRED_PASS -> "Pipeline: retry-проход"
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
                    appendRepoLog(RepoLogEvent(type = RepoEventType.ROLLBACK_COMPLETED, icon = "✅", message = "Восстановлено: ${res.restoredFilesCount} файлов"))
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
        val targetId = backupId ?: _state.value.currentBackupId ?: return
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
    fun setSelectedModel(apiId: String) { _state.update { it.copy(selectedModelApiId = apiId) }; viewModelScope.launch { secureSettings.setPipelineGeminiModel(apiId) } }

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

    fun plan() {
        val prompt = _userPrompt.value.trim()
        if (prompt.isBlank()) { _userError.value = "Введите промпт"; return }
        pipelineJob?.cancel()
        pipelineJob = viewModelScope.launch {
            try {
                resetForNewRun()
                PipelineForegroundService.start(appContext, "Pipeline: планирование", "Анализ задач...")
                _state.update { it.copy(phase = PipelinePhase.PLANNING) }
                appendGeminiLog(GeminiLogEvent(type = GeminiEventType.PLANNER_START, icon = "🧠", message = "Планирование..."))

                val repoIndex = repoIndexManager.getOrRefresh() ?: return@launch
                val filePaths = repoIndex.nodes.filter { it.isFile }.map { it.path }

                val plan = planner.plan(prompt, filePaths, _state.value.selectedModelApiId).getOrThrow()
                _totalCostEur.update { it + plan.costEur }
                _totalTokens.update { it + plan.tokensUsed }

                val tasks = plan.tasks.map {
                    FileTask(operation = it.operation, filePath = it.file, originalPathFromAi = it.file, instructions = it.instructions, newFileContent = it.content, packageName = it.packageName)
                }

                _state.update { it.copy(phase = PipelinePhase.REVIEWING, tasks = tasks) }
                PipelineForegroundService.stop(appContext)
            } catch (e: Exception) {
                _userError.value = e.message
                _state.update { it.copy(phase = PipelinePhase.IDLE) }
                PipelineForegroundService.stop(appContext)
            }
        }
    }

    fun removeTask(taskId: String) { _state.update { s -> s.copy(tasks = s.tasks.filterNot { it.id == taskId }) } }

    fun start() {
        if (!_state.value.canStart || _state.value.tasks.isEmpty()) return
        pipelineJob?.cancel()
        pipelineJob = viewModelScope.launch {
            val watcherScope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
            PipelineForegroundService.start(appContext, "Pipeline запущен", "${_state.value.tasks.size} задач")
            try {
                finalModifiedFilesMap.clear()
                val tasks = _state.value.tasks
                val isOffline = _state.value.pipelineMode == PipelineMode.OFFLINE
                var currentBackupId: String? = null

                if (_state.value.isBackupEnabled) {
                    currentBackupId = backupManager.createSnapshot(_state.value.pipelineRunId, _userPrompt.value, _state.value.pipelineMode, tasks).getOrNull()
                    _state.update { it.copy(currentBackupId = currentBackupId) }
                }

                if (isOffline) localRepoManager.ensureCloned().getOrThrow()

                _state.update { it.copy(phase = PipelinePhase.EXECUTING) }
                val fatalRef = AtomicBoolean(false)

                runTasksInParallel(tasks, _state.value.maxParallelTasks, fatalRef, false, watcherScope)
                if (fatalRef.get()) { _state.update { it.copy(phase = PipelinePhase.FATAL) }; return@launch }

                val deferred = _state.value.tasks.filter { it.status == TaskStatus.DEFERRED }
                if (deferred.isNotEmpty()) {
                    _state.update { it.copy(phase = PipelinePhase.DEFERRED_PASS) }
                    deferred.forEach { updateTask(it.id) { t -> t.copy(status = TaskStatus.PENDING) } }
                    runTasksInParallel(deferred, _state.value.maxParallelTasks, fatalRef, true, watcherScope)
                    _state.value.tasks.filter { it.status == TaskStatus.DEFERRED }.forEach { updateTask(it.id) { t -> t.copy(status = TaskStatus.FAILED_FINAL) } }
                }

                _state.update { it.copy(phase = PipelinePhase.FINALIZING) }
                if (currentBackupId != null) {
                    backupManager.recordFinalFiles(currentBackupId, finalModifiedFilesMap)
                    loadBackups()
                    val fullReport = backupManager.generateFullReportText(currentBackupId).getOrNull()
                    _state.update { it.copy(detailedReportText = fullReport) }
                }

                val finalReport = summarizer.summarize(_userPrompt.value, _state.value.tasks, _totalCostEur.value, _totalTokens.value, _state.value.selectedModelApiId)
                if (isOffline) finalizeOfflineRun(_state.value.tasks)
                _state.update { it.copy(phase = PipelinePhase.DONE, finalReport = finalReport) }
            } catch (e: CancellationException) {
                _state.update { it.copy(phase = PipelinePhase.CANCELLED) }
            } catch (e: Exception) {
                _state.update { it.copy(phase = PipelinePhase.FATAL, fatalError = e.message) }
            } finally {
                watcherScope.cancel()
                PipelineForegroundService.stop(appContext)
            }
        }
    }

    private suspend fun finalizeOfflineRun(tasks: List<FileTask>) {
        val success = tasks.filter { it.status == TaskStatus.SUCCESS && it.commitSha == "pending-batch" }
        if (success.isNotEmpty()) {
            val sha = localRepoManager.stageAndCommit("[Pipeline] Изменено ${success.size} файлов").getOrNull()
            if (sha != null) {
                _state.update { st -> st.copy(tasks = st.tasks.map { if (it.commitSha == "pending-batch") it.copy(commitSha = sha) else it }) }
                localRepoManager.push().onSuccess { localRepoManager.deleteClone() }
            }
        }
    }

    private suspend fun CoroutineScope.runTasksInParallel(tasks: List<FileTask>, maxParallel: Int, fatalRef: AtomicBoolean, isRetry: Boolean, watcherScope: CoroutineScope) {
        val semaphore = Semaphore(maxParallel)
        tasks.map { task ->
            async {
                if (fatalRef.get()) return@async
                semaphore.withPermit {
                    if (fatalRef.get()) return@withPermit
                    _state.update { it.copy(runningTaskIds = it.runningTaskIds + task.id) }
                    try {
                        val isFatal = runOneTask(task, isRetry, watcherScope)
                        if (isFatal) fatalRef.set(true)
                    } finally {
                        _state.update { it.copy(runningTaskIds = it.runningTaskIds - task.id) }
                    }
                }
            }
        }.awaitAll()
    }

    private suspend fun runOneTask(task: FileTask, isRetry: Boolean, watcherScope: CoroutineScope): Boolean {
        updateTask(task.id) { it.copy(status = TaskStatus.PROCESSING, attempts = it.attempts + 1) }
        val start = System.currentTimeMillis()
        var fatal = false
        try {
            executor.lockFor(task.filePath).withLock {
                executor.executeTask(task, isRetry, _state.value.selectedModelApiId, null, _state.value.pipelineMode == PipelineMode.OFFLINE).collect { ev ->
                    when (ev) {
                        is PipelineExecutor.ExecutorEvent.Gemini -> appendGeminiLog(ev.log)
                        is PipelineExecutor.ExecutorEvent.Repo -> appendRepoLog(ev.log)
                        is PipelineExecutor.ExecutorEvent.Final -> {
                            applyTaskResult(task.id, ev.result, start)
                            if (ev.result is TaskExecutionResult.Fatal) fatal = true
                        }
                    }
                }
            }
        } catch (_: Exception) {
            updateTask(task.id) { it.copy(status = TaskStatus.DEFERRED, lastError = "Ошибка выполнения") }
        }
        return fatal
    }

    private fun applyTaskResult(taskId: String, result: TaskExecutionResult, start: Long) {
        val dur = System.currentTimeMillis() - start
        val task = _state.value.tasks.find { it.id == taskId }
        when (result) {
            is TaskExecutionResult.Success -> {
                if (task != null && result.finalContent != null) finalModifiedFilesMap[task.filePath] = result.finalContent
                updateTask(taskId) { it.copy(status = TaskStatus.SUCCESS, commitSha = result.commitSha, durationMs = dur) }
                _totalCostEur.update { it + result.costEur }
                _totalTokens.update { it + result.tokensUsed }
            }
            is TaskExecutionResult.NoChangesNeeded -> updateTask(taskId) { it.copy(status = TaskStatus.NO_CHANGES_NEEDED, commitSha = result.commitSha, durationMs = dur) }
            is TaskExecutionResult.Deferrable -> updateTask(taskId) { it.copy(status = TaskStatus.DEFERRED, lastError = result.message, durationMs = dur) }
            is TaskExecutionResult.Fatal -> updateTask(taskId) { it.copy(status = TaskStatus.FAILED_FINAL, lastError = result.message, durationMs = dur) }
        }
    }

    fun stop() { pipelineJob?.cancel(); _state.update { it.copy(phase = PipelinePhase.CANCELLED) }; PipelineForegroundService.stop(appContext) }

    fun reset() {
        pipelineJob?.cancel()
        finalModifiedFilesMap.clear()
        _state.value = PipelineState(pipelineMode = _state.value.pipelineMode, selectedModelApiId = _state.value.selectedModelApiId)
        _geminiLog.value = emptyList(); _repoLog.value = emptyList()
        _totalCostEur.value = 0.0; _totalTokens.value = 0
        executor.clearFileLocks()
        PipelineForegroundService.stop(appContext)
    }

    private fun resetForNewRun() {
        finalModifiedFilesMap.clear()
        _state.value = PipelineState(pipelineMode = _state.value.pipelineMode, selectedModelApiId = _state.value.selectedModelApiId)
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