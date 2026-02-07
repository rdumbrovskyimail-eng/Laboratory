package com.opuside.app.feature.analyzer.presentation

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opuside.app.core.data.AppSettings
import com.opuside.app.core.database.dao.ChatDao
import com.opuside.app.core.database.entity.ChatMessageEntity
import com.opuside.app.core.database.entity.MessageRole
import com.opuside.app.core.network.anthropic.ClaudeApiClient
import com.opuside.app.core.network.anthropic.StreamingResult
import com.opuside.app.core.network.anthropic.model.ClaudeMessage
import com.opuside.app.core.network.github.GitHubApiClient
import com.opuside.app.core.network.github.model.Artifact
import com.opuside.app.core.network.github.model.WorkflowJob
import com.opuside.app.core.network.github.model.WorkflowRun
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
 * Работает напрямую с файлами проекта через Cloud API.
 */
@HiltViewModel
class AnalyzerViewModel @Inject constructor(
    private val savedStateHandle: SavedStateHandle,
    private val claudeClient: ClaudeApiClient,
    private val gitHubClient: GitHubApiClient,
    private val chatDao: ChatDao,
    private val appSettings: AppSettings
) : ViewModel() {

    private val sessionId: String by lazy {
        savedStateHandle.get<String>("session_id") 
            ?: UUID.randomUUID().toString().also { newId ->
                savedStateHandle["session_id"] = newId
            }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CHAT STATE
    // ═══════════════════════════════════════════════════════════════════════════

    val chatMessages: StateFlow<List<ChatMessageEntity>> = chatDao.observeSession(sessionId)
        .shareIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            replay = 1
        )
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
    // CHAT WITH CLAUDE (DIRECT REPOSITORY ACCESS)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Отправить сообщение Claude.
     * 
     * Теперь работает напрямую с репозиторием через Cloud API.
     */
    fun sendMessage(userMessage: String) {
        if (userMessage.isBlank() || _isStreaming.value) return

        viewModelScope.launch {
            // Вставляем user message
            val userId = chatDao.insert(ChatMessageEntity(
                sessionId = sessionId,
                role = MessageRole.USER,
                content = userMessage
            ))

            // Вставляем placeholder для assistant streaming
            val assistantId = chatDao.insert(ChatMessageEntity(
                sessionId = sessionId,
                role = MessageRole.ASSISTANT,
                content = "",
                isStreaming = true
            ))

            // Читаем историю ПОСЛЕ вставки
            val messages = buildMessagesForApi(userMessage)

            _isStreaming.value = true
            _currentStreamingText.value = ""
            _chatError.value = null

            streamingJob = launch {
                var fullText = ""
                var tokensUsed = 0

                claudeClient.streamMessage(
                    messages = messages,
                    systemPrompt = getSystemPrompt()
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
                            chatDao.markAsError(assistantId, result.exception.message ?: "Unknown error")
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

    /**
     * Построить список сообщений для API Claude.
     * Читает последние 10 сообщений из БД для контекста.
     */
    private fun buildMessagesForApi(userMessage: String): List<ClaudeMessage> {
        val messages = mutableListOf<ClaudeMessage>()
        
        val history = chatMessages.value
            .filter { it.role != MessageRole.SYSTEM && !it.isStreaming }
            .takeLast(10)
        
        history.forEach { msg ->
            messages.add(ClaudeMessage(
                role = if (msg.role == MessageRole.USER) "user" else "assistant",
                content = msg.content
            ))
        }

        messages.add(ClaudeMessage(role = "user", content = userMessage))

        return messages
    }

    private fun getSystemPrompt(): String = """
You are an expert Android developer assistant in OpusIDE.

Your capabilities:
- Code review and bug detection
- Refactoring suggestions  
- Writing new code based on existing patterns
- Explaining code logic
- Fixing compilation errors
- Suggesting improvements
- Direct repository access through Cloud API

Response format:
- Be concise and specific
- Use code blocks with language tags
- Reference files by their paths
- If suggesting changes, show the exact code
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
            
            gitHubClient.getWorkflowJobs(runId)
                .onSuccess { response ->
                    _runJobs.value = response.jobs
                }
            
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
            
            val config = appSettings.gitHubConfig.firstOrNull()
            val branch = config?.branch?.ifEmpty { "main" } ?: "main"
            
            gitHubClient.triggerWorkflow(workflowId, branch)
                .onSuccess {
                    delay(2000)
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
                delay(10_000)
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
        savedStateHandle["session_id"] = newSessionId
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