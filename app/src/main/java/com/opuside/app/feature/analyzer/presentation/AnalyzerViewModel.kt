package com.opuside.app.feature.analyzer.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opuside.app.core.data.AppSettings
import com.opuside.app.core.database.dao.ChatDao
import com.opuside.app.core.database.entity.CachedFileEntity
import com.opuside.app.core.database.entity.ChatMessageEntity
import com.opuside.app.core.database.entity.MessageRole
import com.opuside.app.core.network.anthropic.ClaudeApiClient
import com.opuside.app.core.network.anthropic.StreamingResult
import com.opuside.app.core.network.anthropic.model.ClaudeMessage
import com.opuside.app.core.network.github.GitHubApiClient
import com.opuside.app.core.network.github.model.Artifact
import com.opuside.app.core.network.github.model.WorkflowJob
import com.opuside.app.core.network.github.model.WorkflowRun
import com.opuside.app.core.util.CacheContext
import com.opuside.app.core.util.CacheManager
import com.opuside.app.core.util.TimerState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * ViewModel для Analyzer (Окно 2).
 * 
 * КЛЮЧЕВАЯ ЛОГИКА КЕША:
 * 1. Файлы выбираются в Creator и добавляются в кеш
 * 2. При добавлении запускается 5-минутный таймер
 * 3. Все запросы к Claude используют ТОЛЬКО файлы из кеша как контекст
 * 4. Claude НЕ сканирует весь проект — только кеш!
 * 5. Таймер истёк = кеш очищен = нужно заново выбрать файлы
 */
@HiltViewModel
class AnalyzerViewModel @Inject constructor(
    private val cacheManager: CacheManager,
    private val claudeClient: ClaudeApiClient,
    private val gitHubClient: GitHubApiClient,
    private val chatDao: ChatDao,
    private val appSettings: AppSettings
) : ViewModel() {

    private val sessionId = UUID.randomUUID().toString()

    // ═══════════════════════════════════════════════════════════════════════════
    // CACHE STATE
    // ═══════════════════════════════════════════════════════════════════════════

    val cachedFiles: StateFlow<List<CachedFileEntity>> = cacheManager.cachedFiles
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val fileCount: StateFlow<Int> = cacheManager.fileCount

    val timerSeconds: StateFlow<Int> = cacheManager.remainingSeconds

    val formattedTimer: StateFlow<String> = cacheManager.formattedTime

    val timerProgress: StateFlow<Float> = cacheManager.timerProgress

    val timerState: StateFlow<TimerState> = cacheManager.timerState

    val isTimerCritical: StateFlow<Boolean> = cacheManager.isTimerCritical

    val isCacheActive: StateFlow<Boolean> = cacheManager.isCacheActive

    // ═══════════════════════════════════════════════════════════════════════════
    // CHAT STATE
    // ═══════════════════════════════════════════════════════════════════════════

    val chatMessages: StateFlow<List<ChatMessageEntity>> = chatDao.observeSession(sessionId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _currentStreamingText = MutableStateFlow("")
    val currentStreamingText: StateFlow<String> = _currentStreamingText.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _chatError = MutableStateFlow<String?>(null)
    val chatError: StateFlow<String?> = _chatError.asStateFlow()

    private val _tokensUsedInSession = MutableStateFlow(0)
    val tokensUsedInSession: StateFlow<Int> = _tokensUsedInSession.asStateFlow()

    private var streamingJob: Job? = null

    // ═══════════════════════════════════════════════════════════════════════════
    // GITHUB ACTIONS STATE
    // ═══════════════════════════════════════════════════════════════════════════

    private val _workflowRuns = MutableStateFlow<List<WorkflowRun>>(emptyList())
    val workflowRuns: StateFlow<List<WorkflowRun>> = _workflowRuns.asStateFlow()

    private val _selectedRun = MutableStateFlow<WorkflowRun?>(null)
    val selectedRun: StateFlow<WorkflowRun?> = _selectedRun.asStateFlow()

    private val _runJobs = MutableStateFlow<List<WorkflowJob>>(emptyList())
    val runJobs: StateFlow<List<WorkflowJob>> = _runJobs.asStateFlow()

    private val _jobLogs = MutableStateFlow<String?>(null)
    val jobLogs: StateFlow<String?> = _jobLogs.asStateFlow()

    private val _artifacts = MutableStateFlow<List<Artifact>>(emptyList())
    val artifacts: StateFlow<List<Artifact>> = _artifacts.asStateFlow()

    private val _actionsLoading = MutableStateFlow(false)
    val actionsLoading: StateFlow<Boolean> = _actionsLoading.asStateFlow()

    private val _actionsError = MutableStateFlow<String?>(null)
    val actionsError: StateFlow<String?> = _actionsError.asStateFlow()

    private var pollingJob: Job? = null

    // ═══════════════════════════════════════════════════════════════════════════
    // CACHE OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════════

    fun removeFromCache(filePath: String) {
        viewModelScope.launch {
            cacheManager.removeFile(filePath)
        }
    }

    fun clearCache() {
        viewModelScope.launch {
            cacheManager.clearCache()
            // Добавляем системное сообщение в чат
            chatDao.insert(ChatMessageEntity(
                sessionId = sessionId,
                role = MessageRole.SYSTEM,
                content = "⚠️ Cache cleared. Add files from Creator to continue analysis."
            ))
        }
    }

    fun pauseTimer() = cacheManager.pauseTimer()
    fun resumeTimer() = cacheManager.resumeTimer()

    // ═══════════════════════════════════════════════════════════════════════════
    // CHAT WITH CLAUDE (USING CACHE CONTEXT!)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Отправить сообщение Claude.
     * 
     * ВАЖНО: Контекст берётся ТОЛЬКО из кеша!
     * Если кеш пуст или таймер истёк — предупреждаем пользователя.
     */
    fun sendMessage(userMessage: String) {
        if (userMessage.isBlank() || _isStreaming.value) return

        viewModelScope.launch {
            // Получаем контекст из кеша
            val cacheContext = cacheManager.getContextForClaude()
            
            // Проверяем состояние кеша
            if (cacheContext.isEmpty) {
                _chatError.value = "⚠️ Cache is empty! Add files from Creator tab first."
                return@launch
            }
            
            if (!cacheContext.isActive) {
                _chatError.value = "⏱️ Cache timer expired! Add files again to refresh."
                return@launch
            }

            // 1. Сохраняем сообщение пользователя
            val userEntity = ChatMessageEntity(
                sessionId = sessionId,
                role = MessageRole.USER,
                content = userMessage,
                cachedFilesContext = cacheContext.filePaths
            )
            chatDao.insert(userEntity)

            // 2. Создаём placeholder для ответа
            val assistantEntity = ChatMessageEntity(
                sessionId = sessionId,
                role = MessageRole.ASSISTANT,
                content = "",
                isStreaming = true,
                cachedFilesContext = cacheContext.filePaths
            )
            val assistantId = chatDao.insert(assistantEntity)

            // 3. Формируем сообщения для API
            val messages = buildMessagesForApi(userMessage, cacheContext)

            // 4. Запускаем streaming
            _isStreaming.value = true
            _currentStreamingText.value = ""
            _chatError.value = null

            streamingJob = launch {
                var fullText = ""
                var tokensUsed = 0

                claudeClient.streamMessage(
                    messages = messages,
                    systemPrompt = getSystemPrompt(cacheContext)
                ).collect { result ->
                    when (result) {
                        is StreamingResult.Started -> { }
                        
                        is StreamingResult.Delta -> {
                            fullText = result.accumulated
                            _currentStreamingText.value = fullText
                        }
                        
                        is StreamingResult.StopReason -> { }
                        
                        is StreamingResult.Completed -> {
                            fullText = result.fullText
                            tokensUsed = result.usage?.let { it.inputTokens + it.outputTokens } ?: 0
                            _tokensUsedInSession.value += tokensUsed
                            chatDao.finishStreaming(assistantId, fullText, tokensUsed)
                        }
                        
                        is StreamingResult.Error -> {
                            _chatError.value = result.exception.message
                            chatDao.markAsError(assistantId, result.exception.message)
                        }
                    }
                }

                _isStreaming.value = false
                _currentStreamingText.value = ""
            }
        }
    }

    fun cancelStreaming() {
        streamingJob?.cancel()
        _isStreaming.value = false
        _currentStreamingText.value = ""
    }

    private fun buildMessagesForApi(
        userMessage: String,
        cacheContext: CacheContext
    ): List<ClaudeMessage> {
        val messages = mutableListOf<ClaudeMessage>()
        
        // Добавляем историю (последние 10 сообщений)
        val history = chatMessages.value
            .filter { it.role != MessageRole.SYSTEM && !it.isStreaming }
            .takeLast(10)
        
        history.forEach { msg ->
            messages.add(ClaudeMessage(
                role = if (msg.role == MessageRole.USER) "user" else "assistant",
                content = msg.content
            ))
        }

        // Текущее сообщение С КОНТЕКСТОМ ИЗ КЕША
        val fullMessage = """
${cacheContext.formattedContext}

━━━ USER REQUEST ━━━
$userMessage
        """.trimIndent()
        
        messages.add(ClaudeMessage(role = "user", content = fullMessage))

        return messages
    }

    private fun getSystemPrompt(cacheContext: CacheContext): String = """
You are an expert Android developer assistant in OpusIDE.

IMPORTANT CONTEXT RULES:
1. You have access ONLY to the ${cacheContext.fileCount} files shown in the cached context above
2. Do NOT assume or hallucinate about other files in the project
3. If asked about files not in cache, inform the user to add them first
4. Always reference specific line numbers and file paths from the cache

Your capabilities:
- Code review and bug detection
- Refactoring suggestions  
- Writing new code based on existing patterns
- Explaining code logic
- Fixing compilation errors
- Suggesting improvements

Response format:
- Be concise and specific
- Use code blocks with language tags
- Reference files by their paths from cache
- If suggesting changes, show the exact code

Current cache: ${cacheContext.fileCount} files, ~${cacheContext.totalTokensEstimate} tokens
Cache active: ${cacheContext.isActive}
    """.trimIndent()

    // ═══════════════════════════════════════════════════════════════════════════
    // GITHUB ACTIONS
    // ═══════════════════════════════════════════════════════════════════════════

    fun loadWorkflowRuns() {
        viewModelScope.launch {
            _actionsLoading.value = true
            _actionsError.value = null
            
            gitHubClient.getWorkflowRuns(perPage = 10)
                .onSuccess { response ->
                    _workflowRuns.value = response.workflowRuns
                }
                .onFailure { e ->
                    _actionsError.value = e.message
                }
            
            _actionsLoading.value = false
        }
    }

    fun selectWorkflowRun(run: WorkflowRun) {
        _selectedRun.value = run
        loadRunDetails(run.id)
    }

    fun clearSelectedRun() {
        _selectedRun.value = null
        _runJobs.value = emptyList()
        _jobLogs.value = null
        _artifacts.value = emptyList()
    }

    private fun loadRunDetails(runId: Long) {
        viewModelScope.launch {
            _actionsLoading.value = true
            
            // Load jobs
            gitHubClient.getWorkflowJobs(runId)
                .onSuccess { response ->
                    _runJobs.value = response.jobs
                }
            
            // Load artifacts
            gitHubClient.getRunArtifacts(runId)
                .onSuccess { response ->
                    _artifacts.value = response.artifacts
                }
            
            _actionsLoading.value = false
        }
    }

    fun loadJobLogs(jobId: Long) {
        viewModelScope.launch {
            _actionsLoading.value = true
            _jobLogs.value = null
            
            gitHubClient.getJobLogs(jobId)
                .onSuccess { logs ->
                    _jobLogs.value = logs
                }
                .onFailure { e ->
                    _actionsError.value = "Failed to load logs: ${e.message}"
                }
            
            _actionsLoading.value = false
        }
    }

    fun triggerWorkflow(workflowId: Long) {
        viewModelScope.launch {
            _actionsLoading.value = true
            
            val branch = appSettings.githubBranch.first().ifEmpty { "main" }
            
            gitHubClient.triggerWorkflow(workflowId, branch)
                .onSuccess {
                    delay(2000) // Ждём запуска
                    loadWorkflowRuns()
                }
                .onFailure { e ->
                    _actionsError.value = "Failed to trigger: ${e.message}"
                }
            
            _actionsLoading.value = false
        }
    }

    fun rerunWorkflow(runId: Long) {
        viewModelScope.launch {
            gitHubClient.rerunWorkflow(runId)
                .onSuccess {
                    delay(2000)
                    loadWorkflowRuns()
                }
                .onFailure { e ->
                    _actionsError.value = "Failed to rerun: ${e.message}"
                }
        }
    }

    fun cancelWorkflow(runId: Long) {
        viewModelScope.launch {
            gitHubClient.cancelWorkflow(runId)
                .onSuccess {
                    delay(1000)
                    loadWorkflowRuns()
                }
                .onFailure { e ->
                    _actionsError.value = "Failed to cancel: ${e.message}"
                }
        }
    }

    fun startPolling() {
        pollingJob?.cancel()
        pollingJob = viewModelScope.launch {
            while (true) {
                loadWorkflowRuns()
                delay(10_000) // 10 секунд
            }
        }
    }

    fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
    }

    fun clearActionsError() {
        _actionsError.value = null
    }

    fun clearChatError() {
        _chatError.value = null
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // NEW CHAT SESSION
    // ═══════════════════════════════════════════════════════════════════════════

    fun startNewSession(): String {
        val newSessionId = UUID.randomUUID().toString()
        // В реальности нужно обновить sessionId, но это требует рефакторинга
        // Пока просто очищаем UI
        return newSessionId
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═══════════════════════════════════════════════════════════════════════════

    override fun onCleared() {
        super.onCleared()
        streamingJob?.cancel()
        pollingJob?.cancel()
    }
}
