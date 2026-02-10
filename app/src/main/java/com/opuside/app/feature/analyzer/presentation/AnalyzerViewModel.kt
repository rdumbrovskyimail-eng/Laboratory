package com.opuside.app.feature.analyzer.presentation

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opuside.app.core.ai.ClaudeModelConfig
import com.opuside.app.core.ai.RepositoryAnalyzer
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
 * Analyzer ViewModel v5.2 (FIXED CACHE TIMER + REAL STREAMING)
 *
 * ✅ ИСПРАВЛЕНО (2026-02-10):
 * 1. ТАЙМЕР КЕША:
 *    - Запускается СРАЗУ при StreamingStarted (когда Claude начинает отвечать)
 *    - НЕ ждёт окончания ответа!
 *    - Обновляется при cache read (добавляет +5 минут к текущему времени)
 * 
 * 2. РЕАЛЬНЫЙ STREAMING:
 *    - Streaming event теперь обновляет БД в реальном времени
 *    - UI видит каждое слово по мере генерации
 *    - Оптимизация для Samsung S23 Ultra: debounce 16ms (60 FPS)
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
        private const val STREAMING_DEBOUNCE_MS = 16L // 60 FPS для S23 Ultra
    }

    // ═══════════════════════════════════════════════════════════════════
    // OPERATIONS LOG
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
    
    // ✅ НОВОЕ: Храним время истечения кеша
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
    // REPOSITORY
    // ═══════════════════════════════════════════════════════════════════

    private val _repositoryStructure = MutableStateFlow<RepositoryAnalyzer.RepositoryStructure?>(null)
    val repositoryStructure: StateFlow<RepositoryAnalyzer.RepositoryStructure?> = _repositoryStructure.asStateFlow()

    private val _selectedFiles = MutableStateFlow<Set<String>>(emptySet())
    val selectedFiles: StateFlow<Set<String>> = _selectedFiles.asStateFlow()

    private val _scanEstimate = MutableStateFlow<RepositoryAnalyzer.ScanEstimate?>(null)
    val scanEstimate: StateFlow<RepositoryAnalyzer.ScanEstimate?> = _scanEstimate.asStateFlow()

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

    val sessionTokens: StateFlow<ClaudeModelConfig.ModelCost?> = currentSession
        .map { it?.currentCost }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val isApproachingLongContext: StateFlow<Boolean> = currentSession
        .map { it?.isApproachingLongContext ?: false }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    val isLongContext: StateFlow<Boolean> = currentSession
        .map { it?.isLongContext ?: false }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    // ═══════════════════════════════════════════════════════════════════
    // INIT
    // ═══════════════════════════════════════════════════════════════════

    init {
        Log.i(TAG, "Init: session=$sessionId")

        viewModelScope.launch {
            val savedModelId = appSettings.claudeModel.first()
            val model = ClaudeModelConfig.ClaudeModel.fromModelId(savedModelId)
                ?: ClaudeModelConfig.ClaudeModel.getDefault()
            _selectedModel.value = model
            Log.i(TAG, "Model: ${model.displayName}")

            val existing = repositoryAnalyzer.getSession(sessionId)
            if (existing != null && existing.model == model) {
                _currentSession.value = existing
            } else {
                existing?.let { repositoryAnalyzer.endSession(sessionId) }
                _currentSession.value = repositoryAnalyzer.createSession(sessionId, model)
            }
        }

        // Auto-cleanup
        viewModelScope.launch {
            while (true) {
                delay(3600_000)
                try { repositoryAnalyzer.cleanupOldSessions() } catch (_: Exception) {}
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // ECO / MAX TOGGLE
    // ═══════════════════════════════════════════════════════════════════

    fun toggleOutputMode() {
        if (_cacheModeEnabled.value) {
            addOperation("🔒", "ECO заблокирован: в Cache Mode всегда MAX output", OperationLogType.INFO)
            return
        }
        _ecoOutputMode.value = !_ecoOutputMode.value
        val model = _selectedModel.value
        val effectiveTokens = getEffectiveMaxTokens(model)
        val modeName = if (_ecoOutputMode.value) "ECO 🟢" else "MAX 🔴"
        addOperation(
            if (_ecoOutputMode.value) "🟢" else "🔴",
            "Output: $modeName (${"%,d".format(effectiveTokens)} tok)",
            OperationLogType.INFO
        )
    }

    fun getEffectiveMaxTokens(): Int = getEffectiveMaxTokens(_selectedModel.value)

    fun getEffectiveMaxTokens(model: ClaudeModelConfig.ClaudeModel): Int {
        return if (_cacheModeEnabled.value) {
            model.maxOutputTokens
        } else {
            model.getEffectiveOutputTokens(_ecoOutputMode.value)
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // CACHE MODE CONTROLS (FIXED TIMER)
    // ═══════════════════════════════════════════════════════════════════

    fun toggleCacheMode() {
        if (_ecoOutputMode.value && !_cacheModeEnabled.value) {
            addOperation("🔒", "Cache заблокирован: сначала выключите ECO (переключите на MAX)", OperationLogType.ERROR)
            return
        }

        val newState = !_cacheModeEnabled.value
        _cacheModeEnabled.value = newState

        if (newState) {
            _ecoOutputMode.value = false
            val model = _selectedModel.value
            addOperation("📦", "CACHE MODE ON — output MAX: ${"%,d".format(model.maxOutputTokens)} tok", OperationLogType.SUCCESS)
            Log.i(TAG, "Cache Mode ON, forced MAX output: ${model.maxOutputTokens}")
        } else {
            stopCacheTimer()
            _cacheIsWarmed.value = false
            _cacheTotalReadTokens.value = 0
            _cacheTotalWriteTokens.value = 0
            _cacheTotalSavingsEUR.value = 0.0
            _cacheHitCount.value = 0
            cacheExpiresAt = 0L
            addOperation("📦", "CACHE MODE OFF", OperationLogType.INFO)
            Log.i(TAG, "Cache Mode OFF")
        }
    }

    /**
     * ✅ ИСПРАВЛЕНО: Запускает таймер на ПОЛНЫЕ 5 минут
     * Вызывается когда Claude НАЧИНАЕТ отвечать (StreamingStarted)
     */
    private fun startCacheTimer() {
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
                    addOperation("⏰", "Cache TTL истёк! Кеш очищен.", OperationLogType.ERROR)
                    Log.w(TAG, "Cache TTL expired")
                    break
                }
                _cacheTimerMs.value = remaining
                delay(1000)
            }
        }
        
        Log.i(TAG, "Cache timer started: ${getCacheTimerFormatted(ClaudeModelConfig.CACHE_TTL_MS)}")
    }

    /**
     * ✅ ИСПРАВЛЕНО: Добавляет +5 минут к ТЕКУЩЕМУ времени истечения
     * Вызывается при cache read (hit)
     */
    private fun refreshCacheTimer() {
        if (!_cacheIsWarmed.value) {
            // Если кеш не прогрет — запускаем новый таймер
            startCacheTimer()
            return
        }
        
        // Добавляем 5 минут к текущему времени
        val oldExpires = cacheExpiresAt
        cacheExpiresAt = System.currentTimeMillis() + ClaudeModelConfig.CACHE_TTL_MS
        val addedMinutes = ((cacheExpiresAt - oldExpires) / 60000.0)
        
        Log.i(TAG, "Cache timer refreshed: +${String.format("%.1f", addedMinutes)} min, new TTL: ${getCacheTimerFormatted(cacheExpiresAt - System.currentTimeMillis())}")
        addOperation("🔄", "TTL обновлён: +5:00 мин", OperationLogType.SUCCESS)
    }

    private fun stopCacheTimer() {
        cacheTimerJob?.cancel()
        cacheTimerJob = null
        _cacheTimerMs.value = 0
        cacheExpiresAt = 0L
    }

    /**
     * ✅ ИСПРАВЛЕНО: Вызывается ПОСЛЕ получения usage statistics
     * Обновляет метрики, но НЕ запускает таймер (уже запущен в StreamingStarted!)
     */
    private fun handleCacheResult(cachedReadTokens: Int, cachedWriteTokens: Int, savingsEUR: Double) {
        if (cachedWriteTokens > 0) {
            _cacheTotalWriteTokens.value += cachedWriteTokens
            addOperation("📝", "Cache WRITE: ${"%,d".format(cachedWriteTokens)} tok", OperationLogType.SUCCESS)
        }
        if (cachedReadTokens > 0) {
            _cacheTotalReadTokens.value += cachedReadTokens
            _cacheHitCount.value += 1
            _cacheTotalSavingsEUR.value += savingsEUR
            
            // ✅ Обновляем таймер при cache hit (добавляем время!)
            refreshCacheTimer()
            
            addOperation("⚡", "Cache HIT: ${"%,d".format(cachedReadTokens)} tok (€${String.format("%.4f", savingsEUR)} saved)", OperationLogType.SUCCESS)
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // OPERATIONS LOG
    // ═══════════════════════════════════════════════════════════════════

    private fun addOperation(icon: String, message: String, type: OperationLogType = OperationLogType.INFO) {
        _operationsLog.value = _operationsLog.value + OperationLogItem(icon, message, type = type)
    }

    fun clearOperationsLog() { _operationsLog.value = emptyList() }

    // ═══════════════════════════════════════════════════════════════════
    // MODEL SELECTION
    // ═══════════════════════════════════════════════════════════════════

    fun selectModel(model: ClaudeModelConfig.ClaudeModel) {
        Log.i(TAG, "Model → ${model.displayName}")
        _selectedModel.value = model
        viewModelScope.launch { appSettings.setClaudeModel(model.modelId) }
        startNewSession()
    }

    // ═══════════════════════════════════════════════════════════════════
    // SESSION MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════

    fun startNewSession() {
        viewModelScope.launch {
            _currentSession.value?.let { repositoryAnalyzer.endSession(it.sessionId) }

            val newSessionId = UUID.randomUUID().toString()
            savedStateHandle[KEY_SESSION_ID] = newSessionId
            _sessionId = newSessionId
            _messagesSessionId.value = newSessionId

            val newSession = repositoryAnalyzer.createSession(newSessionId, _selectedModel.value)
            _currentSession.value = newSession
            _selectedFiles.value = emptySet()
            _scanEstimate.value = null
            _chatError.value = null

            // Reset cache stats
            if (_cacheModeEnabled.value) {
                stopCacheTimer()
                _cacheIsWarmed.value = false
                _cacheTotalReadTokens.value = 0
                _cacheTotalWriteTokens.value = 0
                _cacheTotalSavingsEUR.value = 0.0
                _cacheHitCount.value = 0
                cacheExpiresAt = 0L
            }

            addOperation("🔄", "Новый сеанс: ${_selectedModel.value.displayName}", OperationLogType.SUCCESS)
        }
    }

    fun getSessionStats(): String? = _currentSession.value?.getDetailedStats()

    // ═══════════════════════════════════════════════════════════════════
    // REPOSITORY OPERATIONS
    // ═══════════════════════════════════════════════════════════════════

    fun loadRepositoryStructure(path: String = "") {
        viewModelScope.launch {
            repositoryAnalyzer.getRepositoryStructure(path).onSuccess {
                _repositoryStructure.value = it
            }.onFailure {
                _chatError.value = "Failed to load repository: ${it.message}"
            }
        }
    }

    fun selectFiles(files: Set<String>) {
        _selectedFiles.value = files
        if (files.isNotEmpty()) updateScanEstimate() else _scanEstimate.value = null
    }

    fun addFile(filePath: String) {
        _selectedFiles.value = _selectedFiles.value + filePath
        updateScanEstimate()
    }

    fun removeFile(filePath: String) {
        _selectedFiles.value = _selectedFiles.value - filePath
        if (_selectedFiles.value.isNotEmpty()) updateScanEstimate() else _scanEstimate.value = null
    }

    fun clearSelectedFiles() {
        _selectedFiles.value = emptySet()
        _scanEstimate.value = null
    }

    private fun updateScanEstimate() {
        viewModelScope.launch {
            val files = _selectedFiles.value.toList()
            if (files.isEmpty()) { _scanEstimate.value = null; return@launch }
            repositoryAnalyzer.estimateScanCost(files, _selectedModel.value, sessionId)
                .onSuccess { _scanEstimate.value = it }
                .onFailure { _chatError.value = it.message }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // CHAT (with REAL STREAMING)
    // ═══════════════════════════════════════════════════════════════════

    fun sendMessage(message: String) {
        if (message.isBlank()) { _chatError.value = "Message cannot be empty"; return }

        viewModelScope.launch {
            _isStreaming.value = true
            _chatError.value = null

            val useModel = _selectedModel.value
            val isCacheMode = _cacheModeEnabled.value
            val maxTokens = getEffectiveMaxTokens(useModel)
            val modeName = if (isCacheMode) "CACHE MAX" else if (_ecoOutputMode.value) "ECO" else "MAX"

            addOperation("📤", "$modeName ${"%,d".format(maxTokens)} tok: ${message.take(40)}...", OperationLogType.PROGRESS)

            // Save user message
            chatDao.insert(ChatMessageEntity(
                sessionId = sessionId,
                role = com.opuside.app.core.database.entity.MessageRole.USER,
                content = message
            ))

            // Create assistant message placeholder
            val assistantMsgId = chatDao.insert(ChatMessageEntity(
                sessionId = sessionId,
                role = com.opuside.app.core.database.entity.MessageRole.ASSISTANT,
                content = "",
                isStreaming = true
            ))

            var fullResponse = ""
            var inputTokens = 0
            var outputTokens = 0
            var cachedReadTokens = 0
            var cachedWriteTokens = 0
            var streamingStartedEmitted = false
            var lastUpdateTime = 0L

            repositoryAnalyzer.scanFiles(
                sessionId = sessionId,
                filePaths = _selectedFiles.value.toList(),
                userQuery = message,
                model = useModel,
                maxTokens = maxTokens,
                enableCaching = isCacheMode
            ).collect { result ->
                when (result) {
                    is RepositoryAnalyzer.AnalysisResult.Loading -> {
                        addOperation("⏳", result.message, OperationLogType.PROGRESS)
                    }

                    is RepositoryAnalyzer.AnalysisResult.StreamingStarted -> {
                        if (!streamingStartedEmitted) {
                            streamingStartedEmitted = true
                            
                            // ✅ ИСПРАВЛЕНО: Запускаем таймер СРАЗУ когда Claude начал отвечать!
                            if (isCacheMode) {
                                startCacheTimer()
                                addOperation("⏱️", "Кеш активен — таймер 5:00", OperationLogType.INFO)
                            }
                        }
                    }

                    is RepositoryAnalyzer.AnalysisResult.Streaming -> {
                        // ✅ ИСПРАВЛЕНО: РЕАЛЬНЫЙ STREAMING
                        // Обновляем БД в реальном времени с debounce для производительности
                        fullResponse = result.text
                        
                        val now = System.currentTimeMillis()
                        if (now - lastUpdateTime >= STREAMING_DEBOUNCE_MS) {
                            chatDao.updateStreamingContent(assistantMsgId, fullResponse)
                            lastUpdateTime = now
                        }
                    }

                    is RepositoryAnalyzer.AnalysisResult.Completed -> {
                        _isStreaming.value = false
                        _currentSession.value = result.session

                        fullResponse = result.text
                        result.cost.let { cost ->
                            inputTokens = cost.inputTokens
                            outputTokens = cost.outputTokens
                            cachedReadTokens = cost.cachedReadTokens
                            cachedWriteTokens = cost.cachedWriteTokens
                        }

                        Log.i(TAG, "Completed: input=$inputTokens, output=$outputTokens, " +
                                "cacheRead=$cachedReadTokens, cacheWrite=$cachedWriteTokens")

                        _currentSession.value?.addMessage(inputTokens, outputTokens, cachedReadTokens, cachedWriteTokens)

                        addOperation("✅",
                            "${"%,d".format(result.cost.totalTokens)} tok, €${String.format("%.4f", result.cost.totalCostEUR)}",
                            OperationLogType.SUCCESS
                        )

                        // ✅ Обновляем метрики кеша (НЕ запускаем таймер - уже работает!)
                        if (isCacheMode) {
                            handleCacheResult(
                                result.cost.cachedReadTokens,
                                result.cost.cachedWriteTokens,
                                result.cost.cacheSavingsEUR
                            )
                        }

                        // Финализируем сообщение в БД
                        chatDao.finishStreaming(
                            id = assistantMsgId,
                            finalContent = fullResponse,
                            tokensUsed = inputTokens + outputTokens
                        )

                        val operations = repositoryAnalyzer.parseOperations(fullResponse)
                        if (operations.isNotEmpty()) {
                            addOperation("🔧", "${operations.size} операций", OperationLogType.INFO)
                            executeClaudeOperations(operations)
                        }

                        _selectedFiles.value = emptySet()
                        _scanEstimate.value = null
                    }

                    is RepositoryAnalyzer.AnalysisResult.Error -> {
                        _isStreaming.value = false
                        _chatError.value = result.message
                        chatDao.markAsError(assistantMsgId, result.message)
                        addOperation("❌", result.message, OperationLogType.ERROR)
                    }
                }
            }
        }
    }

    private fun executeClaudeOperations(operations: List<RepositoryAnalyzer.ParsedOperation>) {
        viewModelScope.launch {
            for (op in operations) {
                val name = when (op.type) {
                    RepositoryAnalyzer.OperationType.CREATE_FILE -> "📝 Create: ${op.path}"
                    RepositoryAnalyzer.OperationType.EDIT_FILE -> "✏️ Edit: ${op.path}"
                    RepositoryAnalyzer.OperationType.DELETE_FILE -> "🗑️ Delete: ${op.path}"
                    RepositoryAnalyzer.OperationType.CREATE_FOLDER -> "📁 Folder: ${op.path}"
                }
                addOperation("⚙️", name, OperationLogType.PROGRESS)
            }
            val results = repositoryAnalyzer.executeOperations(sessionId, operations)
            results.forEachIndexed { i, res ->
                val op = operations[i]
                res.onSuccess { addOperation("✅", "Done: ${op.path}", OperationLogType.SUCCESS) }
                   .onFailure { addOperation("❌", "${op.path}: ${it.message}", OperationLogType.ERROR) }
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

    // ═══════════════════════════════════════════════════════════════════
    // CLEANUP
    // ═══════════════════════════════════════════════════════════════════

    override fun onCleared() {
        super.onCleared()
        cacheTimerJob?.cancel()
        _currentSession.value?.let { if (it.isActive) repositoryAnalyzer.endSession(it.sessionId) }
        Log.i(TAG, "Cleared")
    }
}
