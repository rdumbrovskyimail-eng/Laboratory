package com.opuside.app.feature.analyzer.presentation

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opuside.app.core.ai.ClaudeModelConfig
import com.opuside.app.core.ai.RepositoryAnalyzer
import com.opuside.app.core.ai.ToolExecutor
import com.opuside.app.core.data.AppSettings
import com.opuside.app.core.database.dao.ChatDao
import com.opuside.app.core.database.entity.ChatMessageEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * Analyzer ViewModel v8.0 (ZERO-LATENCY STREAMING)
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * FIXES:
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * 1. ZERO-LATENCY: sendMessage → HTTP POST мгновенно
 *    - DB insert и history read выполняются ПЕРЕД отправкой
 *    - НЕТ repo tree loading, НЕТ file pre-loading
 *    - Первый emit = StreamingStarted → UI сразу показывает streaming bubble
 *
 * 2. CANCELLATION GUARD: sendJob?.cancel() предотвращает двойную отправку
 *    - Быстрые тапы на Send не создают параллельных запросов
 *
 * 3. BOUNDED OPS LOG: максимум MAX_OPS_LOG_SIZE записей
 *    - Нет memory leak на длинных сессиях
 *
 * 4. TOOL CALL UI: отображает tool calls в операционном логе
 *
 * 5. FIXED CACHE TIMER: стартует на StreamingStarted, не на Completed
 */
@HiltViewModel
class AnalyzerViewModel @Inject constructor(
    private val repositoryAnalyzer: RepositoryAnalyzer,
    private val chatDao: ChatDao,
    private val savedStateHandle: SavedStateHandle,
    private val appSettings: AppSettings
) : ViewModel() {

    companion object {
        private const val TAG = "AnalyzerVM"
        private const val KEY_SESSION_ID = "session_id"
        private const val MAX_OPS_LOG_SIZE = 500
    }

    // ═══════════════════════════════════════════════════════════════════
    // OPERATIONS LOG — bounded
    // ═══════════════════════════════════════════════════════════════════

    data class OperationLogItem(
        val icon: String,
        val message: String,
        val timestamp: Long = System.currentTimeMillis(),
        val type: OperationLogType = OperationLogType.INFO
    )

    enum class OperationLogType { INFO, SUCCESS, ERROR, PROGRESS }

    private val _operationsLog = MutableStateFlow<List<OperationLogItem>>(emptyList())
    val operationsLog: StateFlow<List<OperationLogItem>> = _operationsLog.asStateFlow()

    // ═══════════════════════════════════════════════════════════════════
    // ECO / MAX OUTPUT MODE
    // ═══════════════════════════════════════════════════════════════════

    private val _ecoOutputMode = MutableStateFlow(true)
    val ecoOutputMode: StateFlow<Boolean> = _ecoOutputMode.asStateFlow()

    // ═══════════════════════════════════════════════════════════════════
    // DEDICATED CACHE MODE
    // ═══════════════════════════════════════════════════════════════════

    private val _cacheModeEnabled = MutableStateFlow(false)
    val cacheModeEnabled: StateFlow<Boolean> = _cacheModeEnabled.asStateFlow()

    private val _cacheTimerMs = MutableStateFlow(0L)
    val cacheTimerMs: StateFlow<Long> = _cacheTimerMs.asStateFlow()

    private val _cacheIsWarmed = MutableStateFlow(false)
    val cacheIsWarmed: StateFlow<Boolean> = _cacheIsWarmed.asStateFlow()

    private val _cacheTotalReadTokens = MutableStateFlow(0)
    val cacheTotalReadTokens: StateFlow<Int> = _cacheTotalReadTokens.asStateFlow()

    private val _cacheTotalWriteTokens = MutableStateFlow(0)
    val cacheTotalWriteTokens: StateFlow<Int> = _cacheTotalWriteTokens.asStateFlow()

    private val _cacheTotalSavingsEUR = MutableStateFlow(0.0)
    val cacheTotalSavingsEUR: StateFlow<Double> = _cacheTotalSavingsEUR.asStateFlow()

    private val _cacheHitCount = MutableStateFlow(0)
    val cacheHitCount: StateFlow<Int> = _cacheHitCount.asStateFlow()

    private var cacheTimerJob: Job? = null
    private var cacheExpiresAt: Long = 0L

    // ═══════════════════════════════════════════════════════════════════
    // SESSION & MODEL
    // ═══════════════════════════════════════════════════════════════════

    private var _sessionId: String = savedStateHandle.get<String>(KEY_SESSION_ID)
        ?: UUID.randomUUID().toString().also { savedStateHandle[KEY_SESSION_ID] = it }
    private val sessionId: String get() = _sessionId

    private val _selectedModel = MutableStateFlow(ClaudeModelConfig.ClaudeModel.getDefault())
    val selectedModel: StateFlow<ClaudeModelConfig.ClaudeModel> = _selectedModel.asStateFlow()

    private val _currentSession = MutableStateFlow<ClaudeModelConfig.ChatSession?>(null)
    val currentSession: StateFlow<ClaudeModelConfig.ChatSession?> = _currentSession.asStateFlow()

    // ═══════════════════════════════════════════════════════════════════
    // FILE SELECTION
    // ═══════════════════════════════════════════════════════════════════

    private val _selectedFiles = MutableStateFlow<Set<String>>(emptySet())
    val selectedFiles: StateFlow<Set<String>> = _selectedFiles.asStateFlow()

    // ═══════════════════════════════════════════════════════════════════
    // CHAT
    // ═══════════════════════════════════════════════════════════════════

    private val _messagesSessionId = MutableStateFlow(sessionId)
    val messages: Flow<List<ChatMessageEntity>> = _messagesSessionId
        .flatMapLatest { id -> chatDao.getMessages(id) }

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _chatError = MutableStateFlow<String?>(null)
    val chatError: StateFlow<String?> = _chatError.asStateFlow()

    private val _streamingText = MutableStateFlow<String?>(null)
    val streamingText: StateFlow<String?> = _streamingText.asStateFlow()

    /** Cancellation guard: prevents concurrent sends */
    private var sendJob: Job? = null

    val sessionTokens: StateFlow<ClaudeModelConfig.ModelCost?> = currentSession
        .map { it?.currentCost }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val isApproachingLongContext: StateFlow<Boolean> = currentSession
        .map { it?.isApproachingLongContext ?: false }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // ═══════════════════════════════════════════════════════════════════
    // INIT
    // ═══════════════════════════════════════════════════════════════════

    init {
        viewModelScope.launch {
            val savedModelId = appSettings.claudeModel.first()
            val model = ClaudeModelConfig.ClaudeModel.fromModelId(savedModelId) ?: ClaudeModelConfig.ClaudeModel.getDefault()
            _selectedModel.value = model

            val existing = repositoryAnalyzer.getSession(sessionId)
            if (existing != null && existing.model == model) {
                _currentSession.value = existing
            } else {
                existing?.let { repositoryAnalyzer.endSession(sessionId) }
                _currentSession.value = repositoryAnalyzer.createSession(sessionId, model)
            }
        }

        // Auto-cleanup every hour
        viewModelScope.launch {
            while (true) { delay(3600_000); try { repositoryAnalyzer.cleanupOldSessions() } catch (_: Exception) {} }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // ECO / MAX TOGGLE
    // ═══════════════════════════════════════════════════════════════════

    fun toggleOutputMode() {
        if (_cacheModeEnabled.value) {
            addOperation("🔒", "ECO заблокирован в Cache Mode", OperationLogType.INFO)
            return
        }
        _ecoOutputMode.value = !_ecoOutputMode.value
        val tok = getEffectiveMaxTokens()
        addOperation(
            if (_ecoOutputMode.value) "🟢" else "🔴",
            "Output: ${if (_ecoOutputMode.value) "ECO" else "MAX"} (${"%,d".format(tok)} tok)",
            OperationLogType.INFO
        )
    }

    fun getEffectiveMaxTokens(): Int = getEffectiveMaxTokens(_selectedModel.value)

    fun getEffectiveMaxTokens(model: ClaudeModelConfig.ClaudeModel): Int {
        return if (_cacheModeEnabled.value) model.maxOutputTokens
        else model.getEffectiveOutputTokens(_ecoOutputMode.value)
    }

    // ═══════════════════════════════════════════════════════════════════
    // CACHE MODE
    // ═══════════════════════════════════════════════════════════════════

    fun toggleCacheMode() {
        if (_ecoOutputMode.value && !_cacheModeEnabled.value) {
            addOperation("🔒", "Cache заблокирован: сначала переключите на MAX", OperationLogType.ERROR)
            return
        }

        val newState = !_cacheModeEnabled.value
        _cacheModeEnabled.value = newState

        if (newState) {
            _ecoOutputMode.value = false
            addOperation("📦", "CACHE MODE ON — output MAX: ${"%,d".format(_selectedModel.value.maxOutputTokens)} tok", OperationLogType.SUCCESS)
        } else {
            stopCacheTimer()
            _cacheIsWarmed.value = false
            _cacheTotalReadTokens.value = 0
            _cacheTotalWriteTokens.value = 0
            _cacheTotalSavingsEUR.value = 0.0
            _cacheHitCount.value = 0
            cacheExpiresAt = 0L
            addOperation("📦", "CACHE MODE OFF", OperationLogType.INFO)
        }
    }

    private fun startCacheTimerIfNeeded() {
        if (!_cacheModeEnabled.value) return
        if (_cacheIsWarmed.value && cacheTimerJob?.isActive == true) return

        cacheTimerJob?.cancel()
        cacheExpiresAt = System.currentTimeMillis() + ClaudeModelConfig.CACHE_TTL_MS
        _cacheTimerMs.value = ClaudeModelConfig.CACHE_TTL_MS
        _cacheIsWarmed.value = true

        cacheTimerJob = viewModelScope.launch {
            while (true) {
                val remaining = cacheExpiresAt - System.currentTimeMillis()
                if (remaining <= 0) {
                    _cacheTimerMs.value = 0
                    _cacheIsWarmed.value = false
                    addOperation("⏰", "Cache TTL истёк", OperationLogType.ERROR)
                    break
                }
                _cacheTimerMs.value = remaining
                delay(1000)
            }
        }
    }

    private fun resetCacheTimer() {
        if (!_cacheIsWarmed.value) { startCacheTimerIfNeeded(); return }
        cacheExpiresAt = System.currentTimeMillis() + ClaudeModelConfig.CACHE_TTL_MS
        _cacheTimerMs.value = ClaudeModelConfig.CACHE_TTL_MS
    }

    private fun stopCacheTimer() {
        cacheTimerJob?.cancel()
        cacheTimerJob = null
        _cacheTimerMs.value = 0
        cacheExpiresAt = 0L
    }

    private fun handleCacheResult(cachedReadTokens: Int, cachedWriteTokens: Int, savingsEUR: Double) {
        if (cachedWriteTokens > 0) {
            _cacheTotalWriteTokens.value += cachedWriteTokens
            addOperation("📝", "Cache WRITE: ${"%,d".format(cachedWriteTokens)} tok", OperationLogType.SUCCESS)
        }
        if (cachedReadTokens > 0) {
            _cacheTotalReadTokens.value += cachedReadTokens
            _cacheHitCount.value += 1
            _cacheTotalSavingsEUR.value += savingsEUR
            resetCacheTimer()
            addOperation("⚡", "Cache HIT: ${"%,d".format(cachedReadTokens)} tok (€${String.format("%.4f", savingsEUR)} saved)", OperationLogType.SUCCESS)
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // OPS LOG — bounded to MAX_OPS_LOG_SIZE
    // ═══════════════════════════════════════════════════════════════════

    private fun addOperation(icon: String, message: String, type: OperationLogType = OperationLogType.INFO) {
        _operationsLog.update { current ->
            val newItem = OperationLogItem(icon, message, type = type)
            if (current.size >= MAX_OPS_LOG_SIZE) {
                current.drop(current.size - MAX_OPS_LOG_SIZE + 1) + newItem
            } else {
                current + newItem
            }
        }
    }

    fun clearOperationsLog() { _operationsLog.value = emptyList() }

    // ═══════════════════════════════════════════════════════════════════
    // MODEL SELECTION
    // ═══════════════════════════════════════════════════════════════════

    fun selectModel(model: ClaudeModelConfig.ClaudeModel) {
        _selectedModel.value = model
        viewModelScope.launch { appSettings.setClaudeModel(model.modelId) }
        startNewSession()
    }

    // ═══════════════════════════════════════════════════════════════════
    // SESSION
    // ═══════════════════════════════════════════════════════════════════

    fun startNewSession() {
        sendJob?.cancel()
        viewModelScope.launch {
            _currentSession.value?.let { repositoryAnalyzer.endSession(it.sessionId) }

            val newSessionId = UUID.randomUUID().toString()
            savedStateHandle[KEY_SESSION_ID] = newSessionId
            _sessionId = newSessionId
            _messagesSessionId.value = newSessionId

            _currentSession.value = repositoryAnalyzer.createSession(newSessionId, _selectedModel.value)
            _selectedFiles.value = emptySet()
            _chatError.value = null
            _isStreaming.value = false
            _streamingText.value = null

            if (_cacheModeEnabled.value) {
                stopCacheTimer()
                _cacheIsWarmed.value = false
                _cacheTotalReadTokens.value = 0
                _cacheTotalWriteTokens.value = 0
                _cacheTotalSavingsEUR.value = 0.0
                _cacheHitCount.value = 0
            }

            addOperation("🔄", "Новый сеанс: ${_selectedModel.value.displayName}", OperationLogType.SUCCESS)
        }
    }

    fun getSessionStats(): String? = _currentSession.value?.getDetailedStats()

    // ═══════════════════════════════════════════════════════════════════
    // FILE SELECTION
    // ═══════════════════════════════════════════════════════════════════

    fun selectFiles(files: Set<String>) { _selectedFiles.value = files }
    fun addFile(filePath: String) { _selectedFiles.value = _selectedFiles.value + filePath }
    fun removeFile(filePath: String) { _selectedFiles.value = _selectedFiles.value - filePath }
    fun clearSelectedFiles() { _selectedFiles.value = emptySet() }

    // ═══════════════════════════════════════════════════════════════════
    // SEND MESSAGE — ZERO LATENCY + CANCELLATION GUARD
    // ═══════════════════════════════════════════════════════════════════

    fun sendMessage(message: String) {
        if (message.isBlank()) { _chatError.value = "Message cannot be empty"; return }
        if (_isStreaming.value) return  // Already streaming — ignore tap

        // Cancel any previous send (race condition guard)
        sendJob?.cancel()

        sendJob = viewModelScope.launch {
            _isStreaming.value = true
            _chatError.value = null
            _streamingText.value = null

            val useModel = _selectedModel.value
            val isCacheMode = _cacheModeEnabled.value
            val maxTokens = getEffectiveMaxTokens(useModel)
            val modeName = if (isCacheMode) "CACHE" else if (_ecoOutputMode.value) "ECO" else "MAX"

            addOperation("📤", "$modeName ${"%,d".format(maxTokens)}: ${message.take(50)}...", OperationLogType.PROGRESS)

            // DB write — fast (~5ms)
            chatDao.insert(ChatMessageEntity(
                sessionId = sessionId,
                role = com.opuside.app.core.database.entity.MessageRole.USER,
                content = message
            ))

            // DB read history — fast (~10ms)
            val historyMessages = chatDao.getSession(sessionId)
                .filter { it.role != com.opuside.app.core.database.entity.MessageRole.SYSTEM }
                .filter { !it.isStreaming && it.content.isNotBlank() }

            // ═══════════════════════════════════════════════════════════
            // МГНОВЕННО уходит в HTTP POST — нет предзагрузки файлов/дерева
            // ═══════════════════════════════════════════════════════════
            var fullResponse = ""

            try {
                repositoryAnalyzer.scanFilesV2(
                    sessionId = sessionId,
                    filePaths = _selectedFiles.value.toList(),
                    userQuery = message,
                    conversationHistory = historyMessages,
                    model = useModel,
                    maxTokens = maxTokens,
                    enableCaching = isCacheMode
                ).collect { result ->
                    when (result) {
                        is RepositoryAnalyzer.AnalysisResult.Loading -> {
                            addOperation("⏳", result.message, OperationLogType.PROGRESS)
                        }

                        is RepositoryAnalyzer.AnalysisResult.StreamingStarted -> {
                            _streamingText.value = ""
                            if (isCacheMode) startCacheTimerIfNeeded()
                        }

                        is RepositoryAnalyzer.AnalysisResult.Streaming -> {
                            fullResponse = result.text
                            _streamingText.value = fullResponse
                        }

                        is RepositoryAnalyzer.AnalysisResult.ToolCallStarted -> {
                            addOperation("🔧", "Tool: ${result.toolName}", OperationLogType.PROGRESS)
                        }

                        is RepositoryAnalyzer.AnalysisResult.ToolCallCompleted -> {
                            val icon = if (result.isError) "❌" else "✅"
                            val opInfo = result.operation?.let {
                                when (it) {
                                    is ToolExecutor.FileOperation.Created -> "Created: ${it.path}"
                                    is ToolExecutor.FileOperation.Edited -> "Edited: ${it.path}"
                                    is ToolExecutor.FileOperation.Deleted -> "Deleted: ${it.path}"
                                    is ToolExecutor.FileOperation.DirectoryCreated -> "Dir: ${it.path}"
                                }
                            } ?: result.toolName
                            addOperation(icon, opInfo, if (result.isError) OperationLogType.ERROR else OperationLogType.SUCCESS)
                        }

                        is RepositoryAnalyzer.AnalysisResult.Completed -> {
                            fullResponse = result.text
                            _isStreaming.value = false

                            chatDao.insert(ChatMessageEntity(
                                sessionId = sessionId,
                                role = com.opuside.app.core.database.entity.MessageRole.ASSISTANT,
                                content = fullResponse,
                                isStreaming = false
                            ))

                            _streamingText.value = null
                            _currentSession.value = result.session

                            result.cost.let { cost ->
                                val toolInfo = if (result.toolIterations > 1) " (${result.toolIterations} iterations)" else ""
                                addOperation("✅",
                                    "${"%,d".format(cost.totalTokens)} tok, €${String.format("%.4f", cost.totalCostEUR)}$toolInfo",
                                    OperationLogType.SUCCESS
                                )

                                if (isCacheMode) {
                                    handleCacheResult(cost.cachedReadTokens, cost.cachedWriteTokens, cost.cacheSavingsEUR)
                                }
                            }

                            _selectedFiles.value = emptySet()
                        }

                        is RepositoryAnalyzer.AnalysisResult.Error -> {
                            _isStreaming.value = false
                            _streamingText.value = null
                            _chatError.value = result.message
                            addOperation("❌", result.message, OperationLogType.ERROR)
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Job was cancelled (new session, new send) — clean up silently
                _isStreaming.value = false
                _streamingText.value = null
                throw e  // Re-throw for proper coroutine cancellation
            } catch (e: Exception) {
                _isStreaming.value = false
                _streamingText.value = null
                _chatError.value = e.message
                addOperation("❌", "Error: ${e.message}", OperationLogType.ERROR)
            }
        }
    }

    fun clearChat() { viewModelScope.launch { chatDao.clearSession(sessionId) } }
    fun dismissError() { _chatError.value = null }

    fun getCacheTimerFormatted(ms: Long): String {
        if (ms <= 0) return "0:00"
        val sec = (ms / 1000).toInt()
        return "${sec / 60}:${String.format("%02d", sec % 60)}"
    }

    override fun onCleared() {
        super.onCleared()
        sendJob?.cancel()
        cacheTimerJob?.cancel()
        _currentSession.value?.let { if (it.isActive) repositoryAnalyzer.endSession(it.sessionId) }
    }
}
