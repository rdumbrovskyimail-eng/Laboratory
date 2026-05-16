package com.opuside.app.feature.pipeline.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opuside.app.core.ai.RepoIndexManager
import com.opuside.app.core.data.AppSettings
import com.opuside.app.feature.pipeline.data.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlin.random.Random

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * PIPELINE VIEW MODEL v1.0
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * Оркестратор всего G-конвейера. Управляет state machine, агрегирует
 * события из двух источников (Gemini + Repo), управляет жизненным циклом
 * watcher'ов workflows.
 */
@HiltViewModel
class PipelineViewModel @Inject constructor(
    private val planner: PipelinePlanner,
    private val executor: PipelineExecutor,
    private val summarizer: PipelineSummarizer,
    private val workflowWatcher: WorkflowWatcher,
    private val repoIndexManager: RepoIndexManager,
    private val appSettings: AppSettings
) : ViewModel() {

    // ═══════════════════════════════════════════════════════════════════════
    // STATE
    // ═══════════════════════════════════════════════════════════════════════

    private val _state = MutableStateFlow(PipelineState())
    val state: StateFlow<PipelineState> = _state.asStateFlow()

    private val _userPrompt = MutableStateFlow("")
    val userPrompt: StateFlow<String> = _userPrompt.asStateFlow()

    private val _geminiLog = MutableStateFlow<List<GeminiLogEvent>>(emptyList())
    val geminiLog: StateFlow<List<GeminiLogEvent>> = _geminiLog.asStateFlow()

    private val _repoLog = MutableStateFlow<List<RepoLogEvent>>(emptyList())
    val repoLog: StateFlow<List<RepoLogEvent>> = _repoLog.asStateFlow()

    /**
     * Логи, отфильтрованные по logFilterTaskId. Фильтрация выполняется
     * в фоновом виде (не на Main Thread) через combine + stateIn.
     */
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

    /** Главный job выполнения (для cancel) */
    private var pipelineJob: Job? = null

    /** Кэш путей файлов для fallback'а, если getOrRefresh вернёт null */
    private var cachedFilePaths: List<String> = emptyList()

    companion object {
        /** Максимум событий в каждом логе (FIFO) */
        private const val LOG_CAP = 2500
        /** Сколько событий сбрасывать когда достигнут CAP */
        private const val LOG_DROP_BATCH = 250
        /** Default параллелизм. Можно поменять через setMaxParallel(). */
        private const val DEFAULT_MAX_PARALLEL = 3
        /** Минимальная и максимальная допустимая параллельность */
        const val MIN_PARALLEL = 1
        const val MAX_PARALLEL = 8
        /** Jitter delay между задачами (анти-flake GitHub) */
        private const val JITTER_MIN_MS = 400L
        private const val JITTER_MAX_MS = 1200L
    }

    // ═══════════════════════════════════════════════════════════════════════
    // INIT
    // ═══════════════════════════════════════════════════════════════════════

    init {
        loadRepoStats()
    }

    // ═══════════════════════════════════════════════════════════════════════
    // INPUT HANDLERS
    // ═══════════════════════════════════════════════════════════════════════

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

    /**
     * Обновить статистику репо (для шапки экрана).
     */
    fun loadRepoStats() {
        viewModelScope.launch {
            try {
                val cfg = appSettings.gitHubConfig.first()
                val index = repoIndexManager.getOrRefresh()
                if (index == null) {
                    _userError.value = "Не удалось загрузить индекс репозитория. Проверь настройки GitHub."
                    return@launch
                }
                val paths = extractFilePaths(index)
                if (paths.isEmpty()) {
                    _userError.value = "Индекс репозитория пуст или не удалось извлечь пути. " +
                            "Проверь функцию extractFilePaths() в PipelineViewModel.kt."
                    return@launch
                }
                
                // Сохраняем в кэш для использования в plan()
                cachedFilePaths = paths

                // Подсчёт по расширениям из путей
                val byExt: Map<String, Int> = paths
                    .mapNotNull { p ->
                        val dot = p.lastIndexOf('.')
                        if (dot < 0 || dot == p.length - 1) null
                        else p.substring(dot + 1).lowercase()
                    }
                    .groupingBy { it }
                    .eachCount()

                _repoStats.value = RepoStats(
                    owner = cfg.owner,
                    repo = cfg.repo,
                    branch = cfg.branch,
                    totalFiles = paths.size,
                    totalDirectories = 0,
                    totalSizeBytes = 0L,
                    totalSizeFormatted = "—",
                    byExtension = byExt,
                    maxDepth = paths.maxOfOrNull { it.count { c -> c == '/' } } ?: 0,
                    truncated = false,
                    loadedAtMs = System.currentTimeMillis()
                )
            } catch (e: Exception) {
                _userError.value = "Ошибка загрузки репо: ${e.message}"
            }
        }
    }

    private fun extractFilePaths(index: Any): List<String> {
        val collectionGetters = listOf(
            "getNodes", "getFiles", "getPaths", "getEntries",
            "getAllPaths", "getFileList", "getAllFiles"
        )
        val pathGetters = listOf("getPath", "getFilePath", "getName", "getFullPath")
        val isFileGetters = listOf("isFile", "getFile", "isRegularFile")

        val cls = index::class.java
        for (getterName in collectionGetters) {
            try {
                val method = cls.methods.firstOrNull { it.name == getterName } ?: continue
                val result = method.invoke(index) ?: continue
                val list = (result as? List<*>) ?: continue
                if (list.isEmpty()) continue

                val first = list.first() ?: continue
                if (first is String) {
                    @Suppress("UNCHECKED_CAST")
                    return (list as List<String>).filter { it.isNotBlank() }
                }
                val elemCls = first::class.java
                val pathMethod = pathGetters
                    .firstNotNullOfOrNull { name -> elemCls.methods.firstOrNull { it.name == name } }
                    ?: continue
                val isFileMethod = isFileGetters
                    .firstNotNullOfOrNull { name -> elemCls.methods.firstOrNull { it.name == name } }

                val paths = list.mapNotNull { node ->
                    if (node == null) return@mapNotNull null
                    if (isFileMethod != null) {
                        val isFile = isFileMethod.invoke(node) as? Boolean
                        if (isFile == false) return@mapNotNull null
                    }
                    (pathMethod.invoke(node) as? String)?.takeIf { it.isNotBlank() }
                }
                if (paths.isNotEmpty()) return paths
            } catch (e: Exception) {
                android.util.Log.w("PipelineViewModel",
                    "extractFilePaths via $getterName failed: ${e.message}")
            }
        }

        android.util.Log.e("PipelineViewModel",
            "Could not extract file paths from RepoIndex via reflection. " +
                    "Open PipelineViewModel.kt and replace extractFilePaths() body " +
                    "with a direct call to your RepoIndex API.")
        return emptyList()
    }

    // ═══════════════════════════════════════════════════════════════════════
    // STEP 1: PLAN
    // ═══════════════════════════════════════════════════════════════════════

    fun plan() {
        val prompt = _userPrompt.value.trim()
        if (prompt.isBlank()) {
            _userError.value = "Введите промпт"
            return
        }
        if (_state.value.isRunning) {
            _userError.value = "Конвейер уже выполняется. Остановите его сначала."
            return
        }

        pipelineJob?.cancel()
        pipelineJob = viewModelScope.launch {
            try {
                resetForNewRun()
                _state.update { it.copy(phase = PipelinePhase.PLANNING) }

                appendGeminiLog(GeminiLogEvent(
                    type = GeminiEventType.PLANNER_START,
                    icon = "🧠",
                    message = "Планировщик: анализ промпта (${prompt.length}ch)..."
                ))

                // ─── Загружаем индекс репозитория и извлекаем список путей ──
                val index = try {
                    repoIndexManager.getOrRefresh()
                } catch (e: Exception) {
                    null
                }
                
                var filePaths = if (index != null) extractFilePaths(index) else emptyList()
                
                // Если удалось получить свежие пути — обновляем кэш.
                // Иначе — берём из кэша (fallback).
                if (filePaths.isNotEmpty()) {
                    cachedFilePaths = filePaths
                } else {
                    filePaths = cachedFilePaths
                    if (filePaths.isNotEmpty()) {
                        appendGeminiLog(GeminiLogEvent(
                            type = GeminiEventType.INFO,
                            icon = "🗂",
                            message = "Используется кэшированный список файлов (${filePaths.size})"
                        ))
                    }
                }

                if (filePaths.isEmpty()) {
                    _userError.value = "Не удалось загрузить индекс репозитория. Проверь настройки GitHub."
                    _state.update { it.copy(phase = PipelinePhase.IDLE) }
                    return@launch
                }

                val plan = planner.plan(prompt, filePaths).getOrElse { e ->
                    appendGeminiLog(GeminiLogEvent(
                        type = GeminiEventType.ERROR,
                        icon = "❌",
                        message = "Планировщик упал: ${e.message?.take(100)}"
                    ))
                    _userError.value = e.message
                    _state.update { it.copy(phase = PipelinePhase.IDLE) }
                    return@launch
                }

                appendGeminiLog(GeminiLogEvent(
                    type = GeminiEventType.PLANNER_DONE,
                    icon = "✅",
                    message = "План готов: ${plan.tasks.size} задач(а), " +
                            "${plan.tokensUsed} токенов, €${String.format(java.util.Locale.US, "%.5f", plan.costEur)}",
                    tokens = plan.tokensUsed,
                    costEur = plan.costEur
                ))

                _totalCostEur.update { it + plan.costEur }
                _totalTokens.update { it + plan.tokensUsed }

                val tasks = plan.tasks.mapIndexed { idx, planned ->
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

            } catch (e: CancellationException) {
                _state.update { it.copy(phase = PipelinePhase.CANCELLED) }
                throw e
            } catch (e: Exception) {
                _userError.value = "Ошибка планирования: ${e.message}"
                _state.update { it.copy(phase = PipelinePhase.IDLE) }
            }
        }
    }

    fun removeTask(taskId: String) {
        if (_state.value.phase != PipelinePhase.REVIEWING) return
        _state.update { state ->
            state.copy(tasks = state.tasks.filterNot { it.id == taskId })
        }
    }

    // ═══════════════════════════════════════════════════════════════════════
    // STEP 2: START EXECUTION
    // ═══════════════════════════════════════════════════════════════════════

    fun start() {
        if (!_state.value.canStart) {
            _userError.value = "Нечего запускать"
            return
        }
        if (_state.value.tasks.isEmpty()) {
            _userError.value = "План пуст"
            return
        }

        pipelineJob?.cancel()
        pipelineJob = viewModelScope.launch {
            val watcherScope = CoroutineScope(coroutineContext + SupervisorJob(coroutineContext[Job]))
            try {
                val maxParallel = _state.value.maxParallelTasks
                _state.update { it.copy(phase = PipelinePhase.EXECUTING) }

                appendGeminiLog(GeminiLogEvent(
                    type = GeminiEventType.INFO,
                    icon = "▶️",
                    message = "Старт конвейера: ${_state.value.tasks.size} задач(а), " +
                            "параллелизм=$maxParallel"
                ))

                val initialTasks = _state.value.tasks.toList()
                val fatalRef = AtomicBoolean(false)

                runTasksInParallel(
                    tasks = initialTasks,
                    maxParallel = maxParallel,
                    fatalRef = fatalRef,
                    isRetryPass = false,
                    watcherScope = watcherScope
                )

                if (fatalRef.get()) {
                    _state.update { it.copy(phase = PipelinePhase.FATAL) }
                    return@launch
                }

                val deferredTasks = _state.value.tasks.filter { it.status == TaskStatus.DEFERRED }
                if (deferredTasks.isNotEmpty()) {
                    _state.update { it.copy(phase = PipelinePhase.DEFERRED_PASS) }
                    appendGeminiLog(GeminiLogEvent(
                        type = GeminiEventType.INFO,
                        icon = "🔄",
                        message = "Второй проход: ${deferredTasks.size} отложенных задач(а)"
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

                    val stillDeferred = _state.value.tasks.filter { it.status == TaskStatus.DEFERRED }
                    for (task in stillDeferred) {
                        updateTask(task.id) {
                            it.copy(status = TaskStatus.FAILED_FINAL)
                        }
                    }
                }

                _state.update { it.copy(phase = PipelinePhase.FINALIZING) }
                appendGeminiLog(GeminiLogEvent(
                    type = GeminiEventType.SUMMARY_START,
                    icon = "📝",
                    message = "Генерируем финальный отчёт..."
                ))

                val finalTasks = _state.value.tasks
                val report = summarizer.summarize(
                    userPrompt = _userPrompt.value,
                    tasks = finalTasks,
                    totalCostEur = _totalCostEur.value,
                    totalTokens = _totalTokens.value
                )

                appendGeminiLog(GeminiLogEvent(
                    type = GeminiEventType.SUMMARY_DONE,
                    icon = "✅",
                    message = "Отчёт готов"
                ))

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
                    it.copy(
                        phase = PipelinePhase.FATAL,
                        fatalError = e.message
                    )
                }
            } finally {
                watcherScope.cancel()
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
                            val jitter = Random.nextLong(JITTER_MIN_MS, JITTER_MAX_MS)
                            delay(jitter)
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
            it.copy(
                status = TaskStatus.PROCESSING,
                attempts = it.attempts + 1
            )
        }
        val startMs = System.currentTimeMillis()

        var receivedFinal = false
        var receivedFatal = false

        try {
            executor.executeTask(task, isRetryPass).collect { event ->
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
                                } catch (e: CancellationException) {
                                    // нормально, остановка
                                } catch (e: Exception) {
                                    appendRepoLog(RepoLogEvent(
                                        type = RepoEventType.INFO,
                                        icon = "ℹ️",
                                        message = "Watcher ${commitSha.take(8)}: ${e.message?.take(80)}",
                                        taskId = task.id,
                                        commitSha = commitSha
                                    ))
                                }
                            }
                        }
                    }
                    is PipelineExecutor.ExecutorEvent.Final -> {
                        receivedFinal = true
                        applyTaskResult(task.id, event.result, startMs)
                        if (event.result is TaskExecutionResult.Fatal) {
                            receivedFatal = true
                            _state.update {
                                it.copy(fatalError = event.result.message)
                            }
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            if (!receivedFinal) {
                updateTask(task.id) {
                    it.copy(
                        status = TaskStatus.DEFERRED,
                        lastError = "Cancelled mid-execution",
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

        when (result) {
            is TaskExecutionResult.Success -> {
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

    // ═══════════════════════════════════════════════════════════════════════
    // STOP / RESET
    // ═══════════════════════════════════════════════════════════════════════

    fun stop() {
        if (!_state.value.canStop) return
        pipelineJob?.cancel()
        pipelineJob = null
        appendGeminiLog(GeminiLogEvent(
            type = GeminiEventType.INFO,
            icon = "🛑",
            message = "Остановка по запросу пользователя..."
        ))
        _state.update { it.copy(phase = PipelinePhase.CANCELLED) }
    }

    fun reset() {
        pipelineJob?.cancel()
        pipelineJob = null
        val savedParallel = _state.value.maxParallelTasks
        _state.value = PipelineState(maxParallelTasks = savedParallel)
        _geminiLog.value = emptyList()
        _repoLog.value = emptyList()
        _totalCostEur.value = 0.0
        _totalTokens.value = 0
        _userError.value = null
    }

    private fun resetForNewRun() {
        val savedParallel = _state.value.maxParallelTasks
        _state.value = PipelineState(maxParallelTasks = savedParallel)
        _geminiLog.value = emptyList()
        _repoLog.value = emptyList()
        _totalCostEur.value = 0.0
        _totalTokens.value = 0
        _userError.value = null
    }

    // ═══════════════════════════════════════════════════════════════════════
    // HELPERS
    // ═══════════════════════════════════════════════════════════════════════

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
    }
}