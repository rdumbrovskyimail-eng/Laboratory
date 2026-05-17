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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
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
 *
 * Главный цикл:
 *   1. plan(userPrompt) — Gemini-планировщик возвращает список задач
 *   2. REVIEWING — пользователь видит план, может удалить лишнее
 *   3. start() — главный проход по задачам (executeTask × N)
 *   4. После основного прохода — второй проход по DEFERRED задачам с retry
 *   5. summarize() — финальный AI-отчёт
 *   6. DONE
 *
 * Stop handling: cancel главного Job → текущая задача доделывается через
 * structured concurrency, остальные не запускаются.
 *
 * Workflow watchers запускаются параллельно после каждого успешного коммита
 * и эмитят события в repoLog независимо от основного цикла.
 */
@HiltViewModel
class PipelineViewModel @Inject constructor(
    private val planner: PipelinePlanner,
    private val executor: PipelineExecutor,
    private val summarizer: PipelineSummarizer,
    private val workflowWatcher: WorkflowWatcher,
    private val repoIndexManager: RepoIndexManager,
    private val appSettings: AppSettings,
    private val secureSettings: com.opuside.app.core.security.SecureSettingsDataStore
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
     * UI подписывается на это, а не делает remember{ filter() }.
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
        private const val JITTER_BASE_MS = 600L
        /** Tag for logs */
        private const val TAG = "PipelineViewModel"
    }

    // ═══════════════════════════════════════════════════════════════════════
    // INIT
    // ═══════════════════════════════════════════════════════════════════════

    init {
        // Подписываемся на настройки и ждём 500мс, чтобы токен успел расшифроваться.
        // Как только конфигурация готова (есть owner, repo и token) — загружаем статистику.
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

    /**
     * Изменить параллелизм (количество одновременных задач).
     * Можно вызывать только в IDLE или REVIEWING. Во время выполнения игнорируется.
     */
    fun setMaxParallel(value: Int) {
        if (_state.value.isRunning) return
        val clamped = value.coerceIn(MIN_PARALLEL, MAX_PARALLEL)
        _state.update { it.copy(maxParallelTasks = clamped) }
    }

    /**
     * Фильтрует логи по конкретной задаче (null = показать все).
     * UI вызывает при клике на task chip.
     */
    fun setLogFilter(taskId: String?) {
        _state.update { it.copy(logFilterTaskId = taskId) }
    }

    /**
     * Обновить статистику репо (для шапки экрана).
     * Безопасно получает индекс через RepoIndexManager.
     */
    fun loadRepoStats() {
        viewModelScope.launch {
            try {
                // Если индекс уже грузится в Creator'е, этот метод просто подождёт
                // и возьмёт готовый благодаря Mutex внутри RepoIndexManager
                val index = repoIndexManager.getOrRefresh()
                
                if (index == null) {
                    _userError.value = "Не удалось загрузить индекс репозитория. Проверьте токен и доступ."
                    return@launch
                }

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
                _userError.value = "Ошибка загрузки репо: ${e.message}"
            }
        }
    }



    // ═══════════════════════════════════════════════════════════════════════
    // STEP 1: PLAN
    // ═══════════════════════════════════════════════════════════════════════

    /**
     * Запустить планировщик. После успеха — переход в REVIEWING.
     * Можно нажать Plan повторно — переплан перетирает старый.
     */
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

                // Дедупликация: объединяем задачи с одинаковым filePath
                val deduped = _state.value.tasks
                    .groupBy { it.filePath }
                    .map { (_, group) ->
                        if (group.size == 1) group.first()
                        else group.first().copy(
                            instructions = group.joinToString("\n\n═══ Также: ═══\n") { it.instructions },
                            operation = group.first().operation
                        )
                    }
                if (deduped.size < _state.value.tasks.size) {
                    val removed = _state.value.tasks.size - deduped.size
                    appendGeminiLog(GeminiLogEvent(
                        type = GeminiEventType.INFO,
                        icon = "🧹",
                        message = "Дедупликация: объединено $removed дубликат(ов) задач по filePath"
                    ))
                    _state.update { it.copy(tasks = deduped) }
                }

                appendGeminiLog(GeminiLogEvent(
                    type = GeminiEventType.PLANNER_START,
                    icon = "🧠",
                    message = "Планировщик: анализ промпта (${prompt.length}ch)..."
                ))

                // ─── Получаем список путей файлов через встроенный RepoIndexManager ──
                val repoIndex = repoIndexManager.getOrRefresh()
                
                if (repoIndex == null) {
                    _userError.value = "Не удалось загрузить индекс репозитория. Проверь настройки GitHub."
                    _state.update { it.copy(phase = PipelinePhase.IDLE) }
                    return@launch
                }
                
                // Получаем пути только для файлов (исключая папки)
                val filePaths = repoIndex.nodes.filter { it.isFile }.map { it.path }

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

                // Конвертим PlannedTask → FileTask (включая operation и content для CREATE)
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

    /**
     * Удалить задачу из плана (пользователь решил не трогать этот файл).
     */
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
            // SupervisorJob с parent = текущий job (pipelineJob)
            // Watcher'ы будут детьми этого scope, а не async-задач
            // → не блокируют awaitAll, но отменяются при stop pipelineJob
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

                // ═══ ПЕРВЫЙ ПРОХОД (параллельно) ════════════════════════════
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

                // ═══ ВТОРОЙ ПРОХОД (DEFERRED tasks, тоже параллельно) ═══════
                val deferredTasks = _state.value.tasks.filter { it.status == TaskStatus.DEFERRED }
                if (deferredTasks.isNotEmpty()) {
                    _state.update { it.copy(phase = PipelinePhase.DEFERRED_PASS) }
                    appendGeminiLog(GeminiLogEvent(
                        type = GeminiEventType.INFO,
                        icon = "🔄",
                        message = "Второй проход: ${deferredTasks.size} отложенных задач(а)"
                    ))

                    // Сброс статусов перед retry
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

                    // Всё что осталось DEFERRED → FAILED_FINAL
                    val stillDeferred = _state.value.tasks.filter { it.status == TaskStatus.DEFERRED }
                    for (task in stillDeferred) {
                        updateTask(task.id) {
                            it.copy(status = TaskStatus.FAILED_FINAL)
                        }
                    }
                }

                // ═══ ФИНАЛЬНЫЙ ОТЧЁТ ═══════════════════════════════════════
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
                // Отменяем все висящие watcher'ы — мы уже завершились
                watcherScope.cancel()
            }
        }
    }

    /**
     * Параллельный планировщик задач через Semaphore.
     *
     * Запускает все task'и одновременно как async-корутины, но каждая ждёт
     * пермит из Semaphore(maxParallel). Это обеспечивает что в каждый момент
     * времени выполняется не более maxParallel задач.
     *
     * Особенности:
     * - Каждая задача делает jitter delay перед стартом — анти-flake GitHub
     * - При FATAL ставится fatalRef=true, оставшиеся задачи тихо завершаются
     * - currentTaskIndex обновляется на индекс последней начатой задачи (для UI)
     * - runningTaskIds содержит ID всех текущих параллельных задач
     */
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
                    // Если уже был FATAL — пропускаем
                    if (fatalRef.get()) return@async

                    semaphore.withPermit {
                        // Re-check после ожидания пермита
                        if (fatalRef.get()) return@withPermit

                        // Jitter delay — без него GitHub может ответить 409 на быстрые PUT
                        if (localIdx > 0) {
                            val scale = kotlin.math.sqrt(maxParallel.toDouble()).coerceAtLeast(1.0)
                            val jitterMin = (JITTER_BASE_MS * scale).toLong()
                            val jitterMax = (JITTER_BASE_MS * scale * 2).toLong()
                            delay(Random.nextLong(jitterMin, jitterMax))
                        }

                        // Mark as running
                        _state.update {
                            val realIdx = it.tasks.indexOfFirst { t -> t.id == task.id }
                            it.copy(
                                currentTaskIndex = realIdx,
                                runningTaskIds = it.runningTaskIds + task.id
                            )
                        }

                        try {
                            val realIdx = _state.value.tasks.indexOfFirst { it.id == task.id }
                            if (realIdx < 0) return@withPermit  // была удалена
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

    /**
     * Выполнить одну задачу и обновить state.
     * Возвращает true если получили FATAL (надо остановить конвейер).
     *
     * watcherScope — отдельный scope для запуска WorkflowWatcher'ов.
     * Они НЕ должны быть детьми async-задачи, иначе awaitAll будет ждать
     * watcher 3 минуты после завершения задачи.
     */
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
            executor.lockFor(task.filePath).withLock {
                executor.executeTask(task, isRetryPass).collect { event ->
                    when (event) {
                        is PipelineExecutor.ExecutorEvent.Gemini -> {
                        appendGeminiLog(event.log)
                    }
                    is PipelineExecutor.ExecutorEvent.Repo -> {
                        appendRepoLog(event.log)
                        // Если был commit — запускаем watcher как ребёнок watcherScope
                        // (не текущей async-задачи, иначе awaitAll будет ждать watcher 3 мин)
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
            // Если задача была отменена в середине — пометим как DEFERRED
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
                        // Накапливаем токены: если в первом проходе было DEFERRED с токенами,
                        // и затем retry-pass завершился SUCCESS — суммируем оба.
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

    /**
     * Полный сброс конвейера в IDLE. Промпт сохраняется. Сохраняется и параллелизм.
     */
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
            // Ограничиваем размер лога (FIFO) — поднято до 2500 для 50+ задач
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