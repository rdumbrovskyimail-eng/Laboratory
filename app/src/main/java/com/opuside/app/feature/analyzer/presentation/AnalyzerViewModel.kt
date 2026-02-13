package com.opuside.app.ui.analyzer

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
import com.opuside.app.core.database.entity.MessageRole
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.*
import javax.inject.Inject

/**
 * 🤖 ANALYZER VIEWMODEL v12.0 (CACHE + FIRST MESSAGE CACHING + HISTORY LOCK)
 *
 * ✅ ИЗМЕНЕНИЯ:
 * 1. История ЗАБЛОКИРОВАНА в Cache Mode
 * 2. Первое user сообщение кешируется
 * 3. Cache работает: system + tools + первое сообщение
 * 4. Правильный таймер и статистика
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
    // STATE
    // ═══════════════════════════════════════════════════════════════════

    private val _sessionId = savedStateHandle.get<String>(KEY_SESSION_ID) 
        ?: UUID.randomUUID().toString().also { savedStateHandle[KEY_SESSION_ID] = it }
    
    val sessionId: String get() = _sessionId

    private val _selectedModel = MutableStateFlow(ClaudeModelConfig.ClaudeModel.getDefault())
    val selectedModel: StateFlow<ClaudeModelConfig.ClaudeModel> = _selectedModel.asStateFlow()

    private val _ecoOutputMode = MutableStateFlow(false)
    val ecoOutputMode: StateFlow<Boolean> = _ecoOutputMode.asStateFlow()

    private val _conversationHistoryEnabled = MutableStateFlow(false)
    val conversationHistoryEnabled: StateFlow<Boolean> = _conversationHistoryEnabled.asStateFlow()

    private val _selectedFiles = MutableStateFlow<Set<String>>(emptySet())
    val selectedFiles: StateFlow<Set<String>> = _selectedFiles.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _streamingText = MutableStateFlow<String?>(null)
    val streamingText: StateFlow<String?> = _streamingText.asStateFlow()

    private val _chatError = MutableStateFlow<String?>(null)
    val chatError: StateFlow<String?> = _chatError.asStateFlow()

    private val _currentSession = MutableStateFlow<ClaudeModelConfig.ChatSession?>(null)
    val currentSession: StateFlow<ClaudeModelConfig.ChatSession?> = _currentSession.asStateFlow()

    // ═══════════════════════════════════════════════════════════════════
    // CACHE STATE
    // ═══════════════════════════════════════════════════════════════════

    private val _cacheModeEnabled = MutableStateFlow(false)
    val cacheModeEnabled: StateFlow<Boolean> = _cacheModeEnabled.asStateFlow()

    private val _cacheIsWarmed = MutableStateFlow(false)
    val cacheIsWarmed: StateFlow<Boolean> = _cacheIsWarmed.asStateFlow()

    private val _cacheTimerMs = MutableStateFlow(0L)
    val cacheTimerMs: StateFlow<Long> = _cacheTimerMs.asStateFlow()

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
    // OPERATIONS LOG
    // ═══════════════════════════════════════════════════════════════════

    private val _operationsLog = MutableStateFlow<List<OperationLogEntry>>(emptyList())
    val operationsLog: StateFlow<List<OperationLogEntry>> = _operationsLog.asStateFlow()

    data class OperationLogEntry(
        val id: String = UUID.randomUUID().toString(),
        val timestamp: Long = System.currentTimeMillis(),
        val icon: String,
        val message: String,
        val type: OperationLogType
    )

    enum class OperationLogType {
        INFO, SUCCESS, ERROR, PROGRESS
    }

    private fun addOperation(icon: String, message: String, type: OperationLogType) {
        val entry = OperationLogEntry(icon = icon, message = message, type = type)
        _operationsLog.value = (_operationsLog.value + entry).takeLast(MAX_OPS_LOG_SIZE)
    }

    fun clearOperationsLog() {
        _operationsLog.value = emptyList()
    }

    // ═══════════════════════════════════════════════════════════════════
    // JOBS
    // ═══════════════════════════════════════════════════════════════════

    private var sendJob: Job? = null

    init {
        Log.i(TAG, "AnalyzerViewModel v12.0 initialized (Cache + First Message Caching)")
        loadSession()
    }

    private fun loadSession() {
        viewModelScope.launch {
            val session = repositoryAnalyzer.getSession(_sessionId)
                ?: repositoryAnalyzer.createSession(_sessionId, _selectedModel.value)
            _currentSession.value = session
            addOperation("📊", "Session loaded: ${session.model.displayName}", OperationLogType.INFO)
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // MODEL SELECTION
    // ═══════════════════════════════════════════════════════════════════

    fun selectModel(model: ClaudeModelConfig.ClaudeModel) {
        if (_isStreaming.value) {
            _chatError.value = "Cannot change model during streaming"
            return
        }

        val previousModel = _selectedModel.value
        _selectedModel.value = model

        if (previousModel != model) {
            viewModelScope.launch {
                repositoryAnalyzer.endSession(_sessionId)
                val newSession = repositoryAnalyzer.createSession(_sessionId, model)
                _currentSession.value = newSession
                addOperation("🔄", "Switched to ${model.displayName}", OperationLogType.INFO)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // ECO MODE
    // ═══════════════════════════════════════════════════════════════════

    fun toggleEcoOutputMode() {
        if (_cacheModeEnabled.value) {
            addOperation("🔒", "ECO заблокирован в Cache Mode", OperationLogType.ERROR)
            return
        }

        _ecoOutputMode.value = !_ecoOutputMode.value
        val maxTokens = getEffectiveMaxTokens(_selectedModel.value)
        val status = if (_ecoOutputMode.value) "ON (${"%,d".format(maxTokens)} tok)" else "OFF"
        addOperation("💰", "ECO Mode: $status", OperationLogType.INFO)
    }

    private fun getEffectiveMaxTokens(model: ClaudeModelConfig.ClaudeModel): Int {
        return if (_cacheModeEnabled.value) {
            model.maxOutputTokens
        } else {
            model.getEffectiveOutputTokens(_ecoOutputMode.value)
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // CONVERSATION HISTORY
    // ═══════════════════════════════════════════════════════════════════

    fun toggleConversationHistory() {
        // ✅ НОВОЕ: Блокируем если включен Cache Mode
        if (_cacheModeEnabled.value) {
            addOperation("🔒", "История заблокирована в Cache Mode", OperationLogType.ERROR)
            return
        }
        
        _conversationHistoryEnabled.value = !_conversationHistoryEnabled.value
        val status = if (_conversationHistoryEnabled.value) "ON" else "OFF"
        addOperation("💬", "Conversation History: $status", OperationLogType.INFO)
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
            // ✅ НОВОЕ: Выключаем историю при включении Cache
            _conversationHistoryEnabled.value = false
            _ecoOutputMode.value = false
            
            stopCacheTimer()
            _cacheIsWarmed.value = false
            _cacheTotalReadTokens.value = 0
            _cacheTotalWriteTokens.value = 0
            _cacheTotalSavingsEUR.value = 0.0
            _cacheHitCount.value = 0
            cacheExpiresAt = 0L
            
            addOperation("📦", "CACHE MODE ON — первое сообщение будет кешировано", OperationLogType.SUCCESS)
            addOperation("🔒", "История автоматически выключена", OperationLogType.INFO)
        } else {
            stopCacheTimer()
            _cacheIsWarmed.value = false
            _cacheTotalReadTokens.value = 0
            _cacheTotalWriteTokens.value = 0
            _cacheTotalSavingsEUR.value = 0.0
            _cacheHitCount.value = 0
            cacheExpiresAt = 0L
            repositoryAnalyzer.clearCacheForSession(_sessionId)
            
            addOperation("📦", "CACHE MODE OFF — кеш очищен", OperationLogType.SUCCESS)
        }
    }

    /**
     * ✅ ИСПРАВЛЕНО: Запускается при первом cache write
     */
    private fun startCacheTimerIfNeeded() {
        if (!_cacheModeEnabled.value) return
        
        if (_cacheIsWarmed.value && cacheTimerJob?.isActive == true) {
            // Кеш уже прогрет и таймер работает — это cache hit, обновляем таймер
            resetCacheTimer()
            return
        }

        // Первый запрос — создаем кеш и запускаем таймер
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
        
        addOperation("⏰", "Cache timer started (5 min)", OperationLogType.SUCCESS)
    }

    /**
     * ✅ ИСПРАВЛЕНО: Сбрасывает таймер при каждом cache hit
     */
    private fun resetCacheTimer() {
        if (!_cacheIsWarmed.value) { 
            startCacheTimerIfNeeded()
            return 
        }
        cacheExpiresAt = System.currentTimeMillis() + ClaudeModelConfig.CACHE_TTL_MS
        _cacheTimerMs.value = ClaudeModelConfig.CACHE_TTL_MS
        addOperation("⏰", "Cache timer refreshed (free)", OperationLogType.SUCCESS)
    }

    private fun stopCacheTimer() {
        cacheTimerJob?.cancel()
        cacheTimerJob = null
        _cacheTimerMs.value = 0
        cacheExpiresAt = 0L
    }

    /**
     * ✅ ИСПРАВЛЕНО: Корректно обрабатывает cache write и cache read
     */
    private fun handleCacheResult(cachedReadTokens: Int, cachedWriteTokens: Int, savingsEUR: Double) {
        if (cachedWriteTokens > 0) {
            // Первый запрос — кеш создан
            _cacheTotalWriteTokens.value += cachedWriteTokens
            startCacheTimerIfNeeded()
            addOperation("📝", "Cache WRITE: ${"%,d".format(cachedWriteTokens)} tok", OperationLogType.SUCCESS)
        }
        if (cachedReadTokens > 0) {
            // Cache hit — обновляем таймер
            _cacheTotalReadTokens.value += cachedReadTokens
            _cacheHitCount.value += 1
            _cacheTotalSavingsEUR.value += savingsEUR
            resetCacheTimer()
            addOperation("⚡", "Cache HIT: ${"%,d".format(cachedReadTokens)} tok (€${String.format("%.4f", savingsEUR)} saved)", OperationLogType.SUCCESS)
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // FILE SELECTION
    // ═══════════════════════════════════════════════════════════════════

    fun toggleFileSelection(filePath: String) {
        _selectedFiles.value = if (_selectedFiles.value.contains(filePath)) {
            _selectedFiles.value - filePath
        } else {
            _selectedFiles.value + filePath
        }
    }

    fun clearFileSelection() {
        _selectedFiles.value = emptySet()
    }

    fun selectAllFiles(files: List<String>) {
        _selectedFiles.value = files.toSet()
    }

    // ═══════════════════════════════════════════════════════════════════
    // ERROR HANDLING
    // ═══════════════════════════════════════════════════════════════════

    /**
     * ✅ ДОБАВЛЕНО: Метод для очистки ошибки
     */
    fun clearError() {
        _chatError.value = null
    }

    // ═══════════════════════════════════════════════════════════════════
    // SEND MESSAGE
    // ═══════════════════════════════════════════════════════════════════

    fun sendMessage(message: String) {
        if (message.isBlank()) { _chatError.value = "Message cannot be empty"; return }
        if (_isStreaming.value) return

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

            chatDao.insert(ChatMessageEntity(
                sessionId = sessionId,
                role = MessageRole.USER,
                content = message
            ))

            // ✅ ИСПРАВЛЕНО: В Cache Mode история ВСЕГДА пустая
            val historyMessages = if (_conversationHistoryEnabled.value && !isCacheMode) {
                chatDao.getSession(sessionId)
                    .filter { it.role != MessageRole.SYSTEM }
                    .filter { !it.isStreaming && it.content.isNotBlank() }
            } else {
                emptyList()
            }

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
                                role = MessageRole.ASSISTANT,
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
                _isStreaming.value = false
                _streamingText.value = null
                throw e
            } catch (e: Exception) {
                _isStreaming.value = false
                _streamingText.value = null
                _chatError.value = e.message
                addOperation("❌", "Error: ${e.message}", OperationLogType.ERROR)
            }
        }
    }

    fun stopStreaming() {
        sendJob?.cancel()
        _isStreaming.value = false
        _streamingText.value = null
        addOperation("⏹️", "Streaming stopped", OperationLogType.INFO)
    }

    // ═══════════════════════════════════════════════════════════════════
    // CLEANUP
    // ═══════════════════════════════════════════════════════════════════

    override fun onCleared() {
        super.onCleared()
        stopCacheTimer()
        sendJob?.cancel()
        Log.i(TAG, "ViewModel cleared")
    }
}