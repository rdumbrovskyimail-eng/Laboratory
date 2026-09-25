package com.opuside.app.feature.pipeline.presentation

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opuside.app.core.ai.RepoIndexManager
import com.opuside.app.core.data.AppSettings
import com.opuside.app.core.security.SecureSettingsDataStore
import com.opuside.app.feature.pipeline.data.*
import com.opuside.app.feature.pipeline.service.PipelineForegroundService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlin.random.Random

/**
 * 🧠 PIPELINE VIEW MODEL v3.0
 *
 * Главный координатор конвейера:
 * - Модели: строго 3.5 Flash-Lite (LOW thinking) и 3.1 Flash-Lite (MEDIUM thinking)
 * - Управление снимками и откатом (Backup & Rollback Engine)
 * - Опциональный детальный монолитный TXT-отчет (Промпт + Оригиналы + Итог)
 * - Online (прямой GitHub API) и Offline (локальный git-клон) режимы
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
    private val localRepoManager: LocalRepoManager
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

    val localRepoStatus: StateFlow<LocalRepoManager.RepoStatus>
        get() = localRepoManager.status
    val localRepoProgress: StateFlow<String?>
        get() = localRepoManager.progressMessage

    private val _backups = MutableStateFlow<List<PipelineBackupManager.BackupEntry>>(emptyList())
    val backups: StateFlow<List<PipelineBackupManager.BackupEntry>> = _backups.asStateFlow()

    private val _geminiLog = MutableStateFlow<List<GeminiLogEvent>>(emptyList())
    val geminiLog: StateFlow<List<GeminiLogEvent>> = _geminiLog.asStateFlow()

    private val _repoLog = MutableStateFlow<List<RepoLogEvent>>(emptyList())
    val repoLog: StateFlow<List<RepoLogEvent>> = _repoLog.asStateFlow()

    val visibleGeminiLog: StateFlow<List<GeminiLogEvent>> = combine(
        _geminiLog, _state
    ) { logs, st ->
        val fid = st.logFilterTaskId
        if (fid == null) logs else logs.filter { it.taskId == fid }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList()
    )

    val visibleRepoLog: StateFlow<List<RepoLogEvent>> = combine(
        _repoLog, _state
    ) { logs, st ->
        val fid = st.logFilterTaskId
        if (fid == null) logs else logs.filter { it.taskId == fid }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000),
        initialValue = emptyList()
    )

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

    companion object {
        private const val LOG_CAP = 2500
        private const val LOG_DROP_BATCH = 250
        const val MIN_PARALLEL = 1
        const val MAX_PARALLEL = 8
        private const val JITTER_BASE_MS = 600L
        private const val TASK_TIMEOUT_MS = 5 * 60_000L
        private const val TAG = "PipelineViewModel"
    }

    init {
        // Подгрузка настроек репозитория
        viewModelScope.launch {
            appSettings.gitHubConfig
                .debounce(500)
                .distinctUntilChanged()
                .collectLatest { config ->
                    if (config.owner.isNotBlank() && config.repo.isNotBlank() && config.token.isNotBlank()) {
                        loadRepoStats()
                    } else {
                        _repoStats.value = null
                    }
                }
        }

        // Подгрузка сохраненных ключей и режима модели
        viewModelScope.launch {
            _pipelineKeyA.value = try { secureSettings.pipelineKeyA.first() } catch (_: Exception) { "" }
            _pipelineKeyB.value = try { secureSettings.pipelineKeyB.first() } catch (_: Exception) { "" }
            _pipelineActiveKey.value = try { secureSettings.pipelineActiveKeyIndex.first() } catch (_: Exception) { 0 }

            val savedMode = try { secureSettings.pipelineMode.first() } catch (_: Exception) { "online" }
            val mode = if (savedMode == "offline") PipelineMode.OFFLINE else PipelineMode.ONLINE

            val savedModel = try { secureSettings.pipelineGeminiModel.first() } catch (_: Exception) { "" }
            val cleanModel = if (savedModel.contains("3.1")) "gemini-3.1-flash-lite" else "gemini-3.5-flash-lite"

            _state.update {
                it.copy(
                    pipelineMode = mode,
                    selectedModelApiId = cleanModel
                )
            }
            loadBackups()
        }

        // Фоновый сервис: обновление уведомления
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
                    val currentTask = st.tasks.firstOrNull { it.id in st.runningTaskIds }
                    val text = buildString {
                        append("${st.completedTasks}/${st.totalTasks} задач")
                        if (st.successfulTasks > 0) append(" · ✅ ${st.successfulTasks}")
                        if (st.failedTasks > 0) append(" · ❌ ${st.failedTasks}")
                        if (st.deferredTasks > 0) append(" · 🔄 ${st.deferredTasks}")
                        currentTask?.let {
                            append("\n📄 ${it.filePath.substringAfterLast('/')}")
                        }
                    }
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

    // ═══════════════════════════════════════════════════════════════════════════
    // УПРАВЛЕНИЕ ОПЦИЯМИ БЭКАПА И ОТЧЁТА
    // ═══════════════════════════════════════════════════════════════════════════

    fun toggleDetailedReport() {
        _state.update { it.copy(isDetailedReportEnabled = !it.isDetailedReportEnabled) }
    }

    fun toggleBackup() {
        _state.update { it.copy(isBackupEnabled = !it.isBackupEnabled) }
    }

    fun loadBackups() {
        viewModelScope.launch {
            _backups.value = backupManager.getAllBackups()
        }
    }

    fun rollbackBackup(backupId: String) {
        if (_state.value.isRunning || _state.value.isRollingBack) return
        viewModelScope.launch {
            _state.update { it.copy(isRollingBack = true) }
            appendRepoLog(RepoLogEvent(
                type = RepoEventType.ROLLBACK_STARTED,
                icon = "⏪",
                message = "Запуск отката к точке #$backupId..."
            ))

            val result = backupManager.rollback(backupId)
            result.fold(
                onSuccess = { res ->
                    appendRepoLog(RepoLogEvent(
                        type = RepoEventType.ROLLBACK_COMPLETED,
                        icon = "✅",
                        message = "Откат успешен: восстановлено ${res.restoredFilesCount} файлов" +
                                if (res.commitSha != null) " (коммит ${res.commitSha.take(8)})" else "",
                        commitSha = res.commitSha
                    ))
                    repoIndexManager.invalidate()
                    loadRepoStats()
                    loadBackups()
                },
                onFailure = { e ->
                    appendRepoLog(RepoLogEvent(
                        type = RepoEventType.ERROR,
                        icon = "❌",
                        message = "Ошибка отката: ${e.message?.take(150)}"
                    ))
                    _userError.value = "Ошибка отката: ${e.message}"
                }
            )
            _state.update { it.copy(isRollingBack = false) }
        }
    }

    fun deleteBackup(backupId: String) {
        viewModelScope.launch {
            backupManager.deleteBackup(backupId)
            loadBackups()
        }
    }

    fun shareFullReport(context: Context, backupId: String? = null) {
        val targetId = backupId ?: _state.value.currentBackupId ?: return
        viewModelScope.launch {
            val reportRes = backupManager.generateFullReportText(targetId)
            reportRes.fold(
                onSuccess = { text ->
                    backupManager.shareReportFile(context, text, "Pipeline_Audit_Report")
                },
                onFailure = { e ->
                    _userError.value = "Не удалось сформировать отчёт: ${e.message}"
                }
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // УПРАВЛЕНИЕ ПАРАМЕТРАМИ МОДЕЛИ И РЕЖИМАМИ
    // ═══════════════════════════════════════════════════════════════════════════

    fun onPromptChange(text: String) {
        _userPrompt.value = text
    }

    fun dismissError() {
        _userError.value = null
    }

    fun setMaxParallel(value: Int) {
        if (_state.value.isRunning) return
        val clamped = value.coerceIn(MIN_PARALLEL, MAX_PARALLEL)
        _state.update { it.copy(maxParallelTasks = clamped) }
    }

    fun setLogFilter(taskId: String?) {
        _state.update { it.copy(logFilterTaskId = taskId) }
    }

    fun setPipelineKeyA(v: String) {
        _pipelineKeyA.value = v
        viewModelScope.launch { secureSettings.setPipelineKeyA(v) }
    }

    fun setPipelineKeyB(v: String) {
        _pipelineKeyB.value = v
        viewModelScope.launch { secureSettings.setPipelineKeyB(v) }
    }

    fun setPipelineActiveKey(index: Int) {
        val clamped = index.coerceIn(0, 1)
        _pipelineActiveKey.value = clamped
        viewModelScope.launch { secureSettings.setPipelineActiveKeyIndex(clamped) }
    }

    fun setPipelineMode(mode: PipelineMode) {
        if (_state.value.isRunning) return
        _state.update { it.copy(pipelineMode = mode) }
        viewModelScope.launch {
            secureSettings.setPipelineMode(mode.name.lowercase())
        }
    }

    fun setSelectedModel(apiId: String) {
        if (_state.value.isRunning) return
        // Поддерживаем строго 3.1 Flash-Lite и 3.5 Flash-Lite
        val validId = if (apiId.contains("3.1")) "gemini-3.1-flash-lite" else "gemini-3.5-flash-lite"
        _state.update { it.copy(selectedModelApiId = validId) }
        viewModelScope.launch {
            secureSettings.setPipelineGeminiModel(validId)
        }
    }

    fun syncLocalRepo() {
        if (_state.value.isRunning) return
        viewModelScope.launch {
            appendRepoLog(RepoLogEvent(
                type = RepoEventType.CLONE_START, icon = "📥",
                message = "Синхронизация локального клона..."
            ))
            val result = localRepoManager.ensureCloned()
            result.fold(
                onSuccess = {
                    appendRepoLog(RepoLogEvent(
                        type = RepoEventType.CLONE_DONE, icon = "✅",
                        message = "Клон готов: ${it.name}"
                    ))
                },
                onFailure = { e ->
                    appendRepoLog(RepoLogEvent(
                        type = RepoEventType.ERROR, icon = "❌",
                        message = "Ошибка синхронизации: ${e.message?.take(150)}"
                    ))
                    _userError.value = e.message
                }
            )
        }
    }

    fun deleteLocalClone() {
        if (_state.value.isRunning) return
        viewModelScope.launch {
            localRepoManager.deleteClone()
            appendRepoLog(RepoLogEvent(
                type = RepoEventType.INFO, icon = "🗑",
                message = "Локальный клон удалён"
            ))
        }
    }

    fun loadRepoStats() {
        viewModelScope.launch {
            try {
                val index = repoIndexManager.getOrRefresh() ?: return@launch
                _repoStats.value = RepoStats(
                    owner = index.owner,
                    repo = index.repo,
                    branch = index.branch,
                    totalFiles = index.totalFiles,
                    totalDirectories = index.totalDirectories,
                    totalSizeBytes = index.totalSize,
                    totalSizeFormatted = index.totalSizeFormatted,
                    byExtension = index.nodes.filter { it.isFile }.groupingBy { it.extension.lowercase() }.eachCount(),
                    maxDepth = index.maxDepth,
                    truncated = index.truncated,
                    loadedAtMs = index.loadedAt
                )
            } catch (e: Exception) {
                _userError.value = "Ошибка загрузки структуры репозитория: ${e.message}"
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ПЛАНИРОВАНИЕ
    // ═══════════════════════════════════════════════════════════════════════════

    fun plan() {
        val prompt = _userPrompt.value.trim()
        if (prompt.isBlank()) {
            _userError.value = "Введите промпт с инструкциями"
            return
        }
        if (_state.value.isRunning) {
            _userError.value = "Конвейер уже выполняется"
            return
        }

        pipelineJob?.cancel()
        pipelineJob = viewModelScope.launch {
            try {
                resetForNewRun()
                PipelineForegroundService.start(appContext, "Pipeline: планирование", "Анализ файлов и задач...")
                _state.update { it.copy(phase = PipelinePhase.PLANNING) }

                appendGeminiLog(GeminiLogEvent(
                    type = GeminiEventType.PLANNER_START,
                    icon = "🧠",
                    message = "Планирование [${_state.value.selectedModelApiId}]..."
                ))

                val repoIndex = repoIndexManager.getOrRefresh()
                if (repoIndex == null) {
                    _userError.value = "Не удалось загрузить индекс репозитория. Проверьте настройки GitHub."
                    _state.update { it.copy(phase = PipelinePhase.IDLE) }
                    return@launch
                }

                val filePaths = repoIndex.nodes.filter { it.isFile }.map { it.path }

                val plan = planner.plan(prompt, filePaths, _state.value.selectedModelApiId).getOrElse { e ->
                    appendGeminiLog(GeminiLogEvent(
                        type = GeminiEventType.ERROR,
                        icon = "❌",
                        message = "Ошибка планирования: ${e.message?.take(100)}"
                    ))
                    _userError.value = e.message
                    _state.update { it.copy(phase = PipelinePhase.IDLE) }
                    return@launch
                }

                appendGeminiLog(GeminiLogEvent(
                    type = GeminiEventType.PLANNER_DONE,
                    icon = "✅",
                    message = "План готов: ${plan.tasks.size} задач(и), " +
                            "${plan.tokensUsed} токенов, €${String.format(java.util.Locale.US, "%.5f", plan.costEur)}",
                    tokens = plan.tokensUsed,
                    costEur = plan.costEur
                ))

                _totalCostEur.update { it + plan.costEur }
                _totalTokens.update { it + plan.tokensUsed }

                val tasks = plan.tasks.map { planned ->
                    FileTask(
                        operation = planned.operation,
                        filePath = planned.file,
                        originalPathFromAi = planned.file,
                        instructions = planned.instructions,
                        newFileContent = planned.content,
                        packageName = planned.packageName,
                        status = TaskStatus.PENDING
                    )
                }

                _state.update {
                    it.copy(
                        phase = PipelinePhase.REVIEWING,
                        tasks = tasks,
                        currentTaskIndex = -1
                    )
                }
                PipelineForegroundService.stop(appContext)

            } catch (e: CancellationException) {
                _state.update { it.copy(phase = PipelinePhase.CANCELLED) }
                PipelineForegroundService.stop(appContext)
                throw e
            } catch (e: Exception) {
                _userError.value = "Ошибка планирования: ${e.message}"
                _state.update { it.copy(phase = PipelinePhase.IDLE) }
                PipelineForegroundService.stop(appContext)
            }
        }
    }

    fun removeTask(taskId: String) {
        if (_state.value.phase != PipelinePhase.REVIEWING) return
        _state.update { state ->
            state.copy(tasks = state.tasks.filterNot { it.id == taskId })
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ЗАПУСК ВЫПОЛНЕНИЯ (СНИМОК -> ИСПОЛНЕНИЕ -> ОТЧЁТ)
    // ═══════════════════════════════════════════════════════════════════════════

    fun start() {
        if (!_state.value.canStart || _state.value.tasks.isEmpty()) {
            _userError.value = "Нет задач для выполнения"
            return
        }

        pipelineJob?.cancel()
        pipelineJob = viewModelScope.launch {
            val watcherScope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
            PipelineForegroundService.start(
                appContext,
                "Pipeline запущен",
                "0/${_state.value.tasks.size} задач"
            )

            try {
                finalModifiedFilesMap.clear()
                val tasks = _state.value.tasks
                val isOffline = _state.value.pipelineMode == PipelineMode.OFFLINE
                val maxParallel = _state.value.maxParallelTasks
                val runId = _state.value.pipelineRunId
                val prompt = _userPrompt.value
                var currentBackupId: String? = null

                // ── ШАГ 1: Создание снимка оригиналов (Бэкап) ─────────────
                if (_state.value.isBackupEnabled) {
                    val snapRes = backupManager.createSnapshot(runId, prompt, _state.value.pipelineMode, tasks)
                    if (snapRes.isSuccess) {
                        currentBackupId = snapRes.getOrNull()
                        _state.update { it.copy(currentBackupId = currentBackupId) }
                        appendRepoLog(RepoLogEvent(
                            type = RepoEventType.BACKUP_CREATED,
                            icon = "🛡️",
                            message = "Снимок оригиналов сохранён (бэкап #$currentBackupId)"
                        ))
                    } else {
                        appendRepoLog(RepoLogEvent(
                            type = RepoEventType.ERROR,
                            icon = "⚠️",
                            message = "Ошибка создания снимка: ${snapRes.exceptionOrNull()?.message}"
                        ))
                    }
                }

                // ── ШАГ 2: Подготовка к выполнению (Offline / Online) ─────
                if (isOffline) {
                    appendRepoLog(RepoLogEvent(
                        type = RepoEventType.CLONE_START, icon = "📥",
                        message = "Offline: подготовка локального клона..."
                    ))
                    val cloneRes = localRepoManager.ensureCloned()
                    if (cloneRes.isFailure) {
                        val e = cloneRes.exceptionOrNull()
                        appendRepoLog(RepoLogEvent(
                            type = RepoEventType.ERROR, icon = "❌",
                            message = "Клонирование упало: ${e?.message?.take(150)}"
                        ))
                        _state.update { it.copy(phase = PipelinePhase.FATAL, fatalError = e?.message) }
                        return@launch
                    }
                    appendRepoLog(RepoLogEvent(
                        type = RepoEventType.CLONE_DONE, icon = "✅",
                        message = "Локальный клон готов"
                    ))
                }

                _state.update { it.copy(phase = PipelinePhase.EXECUTING) }
                appendGeminiLog(GeminiLogEvent(
                    type = GeminiEventType.INFO,
                    icon = "▶️",
                    message = "Старт: ${tasks.size} задач, параллелизм=$maxParallel, модель=${_state.value.selectedModelApiId}"
                ))

                val fatalRef = AtomicBoolean(false)

                // ── ШАГ 3: Параллельное выполнение задач ───────────────────
                runTasksInParallel(
                    tasks = tasks,
                    maxParallel = maxParallel,
                    fatalRef = fatalRef,
                    isRetryPass = false,
                    watcherScope = watcherScope
                )

                if (fatalRef.get()) {
                    _state.update { it.copy(phase = PipelinePhase.FATAL) }
                    return@launch
                }

                // ── ШАГ 4: Повторный проход для отложенных (Retry Pass) ────
                val deferredTasks = _state.value.tasks.filter { it.status == TaskStatus.DEFERRED }
                if (deferredTasks.isNotEmpty()) {
                    _state.update { it.copy(phase = PipelinePhase.DEFERRED_PASS) }
                    appendGeminiLog(GeminiLogEvent(
                        type = GeminiEventType.INFO,
                        icon = "🔄",
                        message = "Второй проход: ${deferredTasks.size} отложенных задач"
                    ))

                    for (task in deferredTasks) {
                        updateTask(task.id) { it.copy(status = TaskStatus.PENDING) }
                    }

                    runTasksInParallel(
                        tasks = deferredTasks,
                        maxParallel = maxParallel,
                        fatalRef = fatalRef,
                        isRetryPass = true,
                        watcherScope = watcherScope
                    )

                    if (fatalRef.get()) {
                        _state.update { it.copy(phase = PipelinePhase.FATAL) }
                        return@launch
                    }

                    _state.value.tasks.filter { it.status == TaskStatus.DEFERRED }.forEach { task ->
                        updateTask(task.id) { it.copy(status = TaskStatus.FAILED_FINAL) }
                    }
                }

                // ── ШАГ 5: Финализация и генерация отчетов ────────────────
                _state.update { it.copy(phase = PipelinePhase.FINALIZING) }

                // Запись финальных версий файлов в бэкап
                if (currentBackupId != null && finalModifiedFilesMap.isNotEmpty()) {
                    backupManager.recordFinalFiles(currentBackupId, finalModifiedFilesMap)
                    loadBackups()
                }

                // Опциональный детальный TXT-отчет (Промпт + Оригиналы + Итог)
                if (_state.value.isDetailedReportEnabled && currentBackupId != null) {
                    val reportRes = backupManager.generateFullReportText(currentBackupId)
                    reportRes.onSuccess { reportText ->
                        _state.update { it.copy(detailedReportText = reportText) }
                        backupManager.shareReportFile(appContext, reportText, "Pipeline_Audit_Report_$runId")
                        appendRepoLog(RepoLogEvent(
                            type = RepoEventType.REPORT_GENERATED,
                            icon = "📄",
                            message = "Сформирован подробный TXT-отчёт (Промпт + Оригиналы + Итог)"
                        ))
                    }
                }

                // Генерация сводного отчёта саммарайзером
                appendGeminiLog(GeminiLogEvent(
                    type = GeminiEventType.SUMMARY_START,
                    icon = "📝",
                    message = "Генерируем итоговую сводку..."
                ))

                val finalTasks = _state.value.tasks
                val report = summarizer.summarize(
                    userPrompt = _userPrompt.value,
                    tasks = finalTasks,
                    totalCostEur = _totalCostEur.value,
                    totalTokens = _totalTokens.value,
                    modelApiId = _state.value.selectedModelApiId
                )

                appendGeminiLog(GeminiLogEvent(
                    type = GeminiEventType.SUMMARY_DONE,
                    icon = "✅",
                    message = "Итоговая сводка готова"
                ))

                // Финальный коммит и push для Offline режима
                if (isOffline) {
                    finalizeOfflineRun(finalTasks)
                }

                _state.update {
                    it.copy(
                        phase = PipelinePhase.DONE,
                        finalReport = report
                    )
                }

            } catch (e: CancellationException) {
                appendGeminiLog(GeminiLogEvent(
                    type = GeminiEventType.INFO,
                    icon = "🛑",
                    message = "Конвейер остановлен пользователем"
                ))
                _state.update { it.copy(phase = PipelinePhase.CANCELLED) }
                throw e
            } catch (e: Exception) {
                appendGeminiLog(GeminiLogEvent(
                    type = GeminiEventType.ERROR,
                    icon = "💥",
                    message = "Критическая ошибка: ${e.message?.take(150)}"
                ))
                _state.update {
                    it.copy(phase = PipelinePhase.FATAL, fatalError = e.message)
                }
            } finally {
                watcherScope.cancel()
                PipelineForegroundService.stop(appContext)
            }
        }
    }

    private suspend fun finalizeOfflineRun(tasks: List<FileTask>) {
        val successfulFiles = tasks
            .filter { it.status == TaskStatus.SUCCESS && it.commitSha == "pending-batch" }
            .map { it.filePath.substringAfterLast('/') }

        if (successfulFiles.isNotEmpty()) {
            val commitMsg = "[Pipeline] ${successfulFiles.size} change(s): " +
                    successfulFiles.take(5).joinToString(", ") +
                    if (successfulFiles.size > 5) "..." else ""

            appendRepoLog(RepoLogEvent(
                type = RepoEventType.LOCAL_COMMIT, icon = "📦",
                message = "Коммит ${successfulFiles.size} файлов в локальном клоне..."
            ))

            val commitRes = localRepoManager.stageAndCommit(commitMsg)
            if (commitRes.isSuccess) {
                val sha = commitRes.getOrThrow()
                appendRepoLog(RepoLogEvent(
                    type = RepoEventType.LOCAL_COMMIT, icon = "✏️",
                    message = "Локальный коммит: ${sha.take(8)}",
                    commitSha = sha
                ))
                _state.update { st ->
                    st.copy(tasks = st.tasks.map { t ->
                        if (t.commitSha == "pending-batch") t.copy(commitSha = sha) else t
                    })
                }

                appendRepoLog(RepoLogEvent(
                    type = RepoEventType.PUSH_START, icon = "🚀",
                    message = "Отправка (push) в GitHub..."
                ))

                val pushRes = localRepoManager.push()
                if (pushRes.isSuccess) {
                    appendRepoLog(RepoLogEvent(
                        type = RepoEventType.PUSH_DONE, icon = "✅",
                        message = "Push успешен: ${successfulFiles.size} файлов одним коммитом",
                        commitSha = sha
                    ))
                    viewModelScope.launch {
                        try {
                            workflowWatcher.watch(sha, null).collect { evt ->
                                appendRepoLog(evt)
                            }
                        } catch (_: Exception) {}
                        localRepoManager.deleteClone()
                    }
                } else {
                    val err = pushRes.exceptionOrNull()?.message ?: "Push error"
                    appendRepoLog(RepoLogEvent(
                        type = RepoEventType.PUSH_FAILED, icon = "❌",
                        message = "Push не удался: $err"
                    ))
                    _userError.value = "Push не удался: $err"
                }
            }
        }
    }

    private suspend fun CoroutineScope.runTasksInParallel(
        tasks: List<FileTask>,
        maxParallel: Int,
        fatalRef: AtomicBoolean,
        isRetryPass: Boolean,
        watcherScope: CoroutineScope
    ) {
        if (tasks.isEmpty()) return
        val semaphore = Semaphore(maxParallel.coerceIn(MIN_PARALLEL, MAX_PARALLEL))

        coroutineScope {
            tasks.mapIndexed { localIdx, task ->
                async {
                    if (fatalRef.get()) return@async

                    semaphore.withPermit {
                        if (fatalRef.get()) return@withPermit

                        if (localIdx > 0) {
                            val scale = kotlin.math.sqrt(maxParallel.toDouble()).coerceAtLeast(1.0)
                            val jitterMin = (JITTER_BASE_MS * scale).toLong()
                            val jitterMax = (JITTER_BASE_MS * scale * 2).toLong()
                            delay(Random.nextLong(jitterMin, jitterMax))
                        }

                        _state.update {
                            val realIdx = it.tasks.indexOfFirst { t -> t.id == task.id }
                            it.copy(
                                currentTaskIndex = realIdx,
                                runningTaskIds = it.runningTaskIds + task.id
                            )
                        }

                        try {
                            val realIdx = _state.value.tasks.indexOfFirst { it.id == task.id }
                            if (realIdx < 0) return@withPermit
                            val isFatal = runOneTask(realIdx, task, isRetryPass, watcherScope)
                            if (isFatal) fatalRef.set(true)
                        } finally {
                            _state.update {
                                it.copy(runningTaskIds = it.runningTaskIds - task.id)
                            }
                        }
                    }
                }
            }.awaitAll()
        }
    }

    private suspend fun runOneTask(
        idx: Int,
        task: FileTask,
        isRetryPass: Boolean,
        watcherScope: CoroutineScope
    ): Boolean {
        _state.update { it.copy(currentTaskIndex = idx) }
        updateTask(task.id) {
            it.copy(status = TaskStatus.PROCESSING, attempts = it.attempts + 1)
        }
        val startMs = System.currentTimeMillis()
        var receivedFinal = false
        var receivedFatal = false
        val activeModelId = _state.value.selectedModelApiId
        val isOffline = _state.value.pipelineMode == PipelineMode.OFFLINE

        try {
            kotlinx.coroutines.withTimeout(TASK_TIMEOUT_MS) {
                executor.lockFor(task.filePath).withLock {
                    executor.executeTask(task, isRetryPass, activeModelId, null, isOffline).collect { event ->
                        when (event) {
                            is PipelineExecutor.ExecutorEvent.Gemini -> {
                                appendGeminiLog(event.log)
                            }
                            is PipelineExecutor.ExecutorEvent.Repo -> {
                                appendRepoLog(event.log)
                                val commitSha = event.log.commitSha
                                if (event.log.type == RepoEventType.FILE_COMMITTED && commitSha != null) {
                                    watcherScope.launch {
                                        try {
                                            workflowWatcher.watch(commitSha, task.id).collect { evt ->
                                                appendRepoLog(evt)
                                            }
                                        } catch (_: Exception) {}
                                    }
                                }
                            }
                            is PipelineExecutor.ExecutorEvent.Final -> {
                                receivedFinal = true
                                applyTaskResult(task.id, event.result, startMs)
                                if (event.result is TaskExecutionResult.Fatal) {
                                    receivedFatal = true
                                    _state.update { it.copy(fatalError = event.result.message) }
                                }
                            }
                        }
                    }
                }
            }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            if (!receivedFinal) {
                appendGeminiLog(GeminiLogEvent(
                    type = GeminiEventType.ERROR, icon = "⏰",
                    message = "Задача ${task.filePath.substringAfterLast('/')} превысила лимит времени (5 мин)",
                    taskId = task.id
                ))
                updateTask(task.id) {
                    it.copy(
                        status = TaskStatus.DEFERRED,
                        lastError = "Таймаут выполнения (5 минут)",
                        errorCode = TaskErrorCode.TASK_TIMEOUT
                    )
                }
            }
        } catch (e: CancellationException) {
            if (!receivedFinal) {
                updateTask(task.id) {
                    it.copy(
                        status = TaskStatus.DEFERRED,
                        lastError = "Остановлено пользователем",
                        errorCode = TaskErrorCode.UNKNOWN
                    )
                }
            }
            throw e
        }

        return receivedFatal
    }

    private fun applyTaskResult(taskId: String, result: TaskExecutionResult, startMs: Long) {
        val duration = System.currentTimeMillis() - startMs
        val task = _state.value.tasks.firstOrNull { it.id == taskId }

        when (result) {
            is TaskExecutionResult.Success -> {
                if (task != null && result.finalContent != null) {
                    finalModifiedFilesMap[task.filePath] = result.finalContent
                }
                updateTask(taskId) {
                    it.copy(
                        status = TaskStatus.SUCCESS,
                        commitSha = result.commitSha,
                        resolvedConflict = result.resolvedConflict,
                        tokensUsed = it.tokensUsed + result.tokensUsed,
                        durationMs = duration
                    )
                }
                _totalCostEur.update { it + result.costEur }
                _totalTokens.update { it + result.tokensUsed }
            }
            is TaskExecutionResult.NoChangesNeeded -> {
                if (task != null && result.finalContent != null) {
                    finalModifiedFilesMap[task.filePath] = result.finalContent
                }
                updateTask(taskId) {
                    it.copy(
                        status = TaskStatus.NO_CHANGES_NEEDED,
                        commitSha = result.commitSha,
                        tokensUsed = it.tokensUsed + result.tokensUsed,
                        durationMs = duration
                    )
                }
                _totalCostEur.update { it + result.costEur }
                _totalTokens.update { it + result.tokensUsed }
            }
            is TaskExecutionResult.Deferrable -> {
                updateTask(taskId) {
                    it.copy(
                        status = TaskStatus.DEFERRED,
                        lastError = result.message,
                        errorCode = result.errorCode,
                        tokensUsed = it.tokensUsed + result.tokensUsed,
                        durationMs = duration
                    )
                }
                if (result.tokensUsed > 0) {
                    _totalTokens.update { it + result.tokensUsed }
                }
            }
            is TaskExecutionResult.Fatal -> {
                updateTask(taskId) {
                    it.copy(
                        status = TaskStatus.FAILED_FINAL,
                        lastError = result.message,
                        errorCode = result.errorCode,
                        durationMs = duration
                    )
                }
            }
        }
    }

    fun stop() {
        if (!_state.value.canStop) return
        pipelineJob?.cancel()
        pipelineJob = null
        appendGeminiLog(GeminiLogEvent(
            type = GeminiEventType.INFO,
            icon = "🛑",
            message = "Остановка конвейера по запросу пользователя..."
        ))
        _state.update { it.copy(phase = PipelinePhase.CANCELLED) }
        PipelineForegroundService.stop(appContext)
    }

    fun reset() {
        pipelineJob?.cancel()
        pipelineJob = null
        finalModifiedFilesMap.clear()
        val savedParallel = _state.value.maxParallelTasks
        val savedPrompt = _userPrompt.value
        val savedMode = _state.value.pipelineMode
        val currentModel = _state.value.selectedModelApiId
        val detailedReport = _state.value.isDetailedReportEnabled
        val backup = _state.value.isBackupEnabled

        _state.value = PipelineState(
            maxParallelTasks = savedParallel,
            pipelineMode = savedMode,
            selectedModelApiId = currentModel,
            isDetailedReportEnabled = detailedReport,
            isBackupEnabled = backup
        )
        _userPrompt.value = savedPrompt
        _geminiLog.value = emptyList()
        _repoLog.value = emptyList()
        _totalCostEur.value = 0.0
        _totalTokens.value = 0
        _userError.value = null
        executor.clearFileLocks()
        PipelineForegroundService.stop(appContext)
    }

    private fun resetForNewRun() {
        finalModifiedFilesMap.clear()
        val savedParallel = _state.value.maxParallelTasks
        val savedPrompt = _userPrompt.value
        val savedMode = _state.value.pipelineMode
        val currentModel = _state.value.selectedModelApiId
        val detailedReport = _state.value.isDetailedReportEnabled
        val backup = _state.value.isBackupEnabled

        _state.value = PipelineState(
            maxParallelTasks = savedParallel,
            pipelineMode = savedMode,
            selectedModelApiId = currentModel,
            isDetailedReportEnabled = detailedReport,
            isBackupEnabled = backup
        )
        _userPrompt.value = savedPrompt
        _geminiLog.value = emptyList()
        _repoLog.value = emptyList()
        _totalCostEur.value = 0.0
        _totalTokens.value = 0
        _userError.value = null
    }

    fun exportRepoToTxt(context: Context) {
        viewModelScope.launch {
            try {
                val index = repoIndexManager.getOrRefresh() ?: return@launch
                val sb = java.lang.StringBuilder()
                index.nodes.filter { it.isFile }.forEach { node ->
                    val content = localRepoManager.readFile(node.path).getOrNull() ?: ""
                    sb.append("--- ").append(node.path).append(" ---\n").append(content).append("\n\n")
                }
                val file = java.io.File(context.cacheDir, "repo_export.txt")
                file.writeText(sb.toString())
                appendRepoLog(RepoLogEvent(type = RepoEventType.INFO, icon = "💾", message = "Репозиторий экспортирован"))
            } catch (e: Exception) {
                _userError.value = "Ошибка экспорта: ${e.message}"
            }
        }
    }

    private fun appendGeminiLog(event: GeminiLogEvent) {
        _geminiLog.update { current ->
            if (current.size >= LOG_CAP) (current.drop(LOG_DROP_BATCH) + event)
            else (current + event)
        }
    }

    private fun appendRepoLog(event: RepoLogEvent) {
        _repoLog.update { current ->
            if (current.size >= LOG_CAP) (current.drop(LOG_DROP_BATCH) + event)
            else (current + event)
        }
    }

    private fun updateTask(taskId: String, transform: (FileTask) -> FileTask) {
        _state.update { state ->
            state.copy(tasks = state.tasks.map { t ->
                if (t.id == taskId) transform(t) else t
            })
        }
    }

    override fun onCleared() {
        super.onCleared()
        pipelineJob?.cancel()
        PipelineForegroundService.stop(appContext)
    }
}