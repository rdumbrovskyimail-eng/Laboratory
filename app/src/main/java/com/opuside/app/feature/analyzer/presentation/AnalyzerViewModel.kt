package com.opuside.app.feature.analyzer.presentation

import android.content.Context
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
import com.opuside.app.core.service.StreamingForegroundService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * Analyzer ViewModel v12.0 (RESILIENT STREAMING + FOREGROUND SERVICE)
 *
 * ═══════════════════════════════════════════════════════════════════════════
 * v12.0 НОВЫЕ ВОЗМОЖНОСТИ:
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * 1. Foreground Service: защита от Doze при ЛЮБОМ стриминге
 * 2. Resilient Streaming: автоматический retry при обрывах сети
 * 3. Network Monitoring: уведомления о потере/восстановлении сети
 * 4. Все фичи v11.0 сохранены (Thinking, Long Context, File Attachment)
 */
@HiltViewModel
class AnalyzerViewModel @Inject constructor(
    private val repositoryAnalyzer: RepositoryAnalyzer,
    private val chatDao: ChatDao,
    private val savedStateHandle: SavedStateHandle,
    private val appSettings: AppSettings,
    @ApplicationContext private val appContext: Context  // ★ NEW
) : ViewModel() {

    companion object {
        private const val TAG = "AnalyzerVM"
        private const val KEY_SESSION_ID = "session_id"
        private const val MAX_OPS_LOG_SIZE = 500
        private const val MAX_ATTACHED_FILE_BYTES = 2 * 1024 * 1024L  // 2 MB
    }

    // ═══════════════════════════════════════════════════════════════════
    // OPERATIONS LOG
    // ═══════════════════════════════════════════════════════════════════

    data class OperationLogItem(
        val id: String = UUID.randomUUID().toString(),
        val icon: String,
        val message: String,
        val timestamp: Long = System.currentTimeMillis(),
        val type: OperationLogType = OperationLogType.INFO
    )

    enum class OperationLogType { INFO, SUCCESS, ERROR, PROGRESS }

    private val _operationsLog = MutableStateFlow<List<OperationLogItem>>(emptyList())
    val operationsLog: StateFlow<List<OperationLogItem>> = _operationsLog.asStateFlow()

    // ═══════════════════════════════════════════════════════════════════
    // CONVERSATION HISTORY MODE
    // ═══════════════════════════════════════════════════════════════════

    private val _conversationHistoryEnabled = MutableStateFlow(false)
    val conversationHistoryEnabled: StateFlow<Boolean> = _conversationHistoryEnabled.asStateFlow()

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
    // EXTENDED THINKING
    // ═══════════════════════════════════════════════════════════════════

    private val _thinkingEnabled = MutableStateFlow(false)
    val thinkingEnabled: StateFlow<Boolean> = _thinkingEnabled.asStateFlow()

    private val _thinkingBudget = MutableStateFlow(40_000)
    val thinkingBudget: StateFlow<Int> = _thinkingBudget.asStateFlow()

    // ═══════════════════════════════════════════════════════════════════
    // SEND TOOLS & SYSTEM PROMPT TOGGLES
    // ═══════════════════════════════════════════════════════════════════

    private val _sendToolsEnabled = MutableStateFlow(true)
    val sendToolsEnabled: StateFlow<Boolean> = _sendToolsEnabled.asStateFlow()

    private val _sendSystemPromptEnabled = MutableStateFlow(true)
    val sendSystemPromptEnabled: StateFlow<Boolean> = _sendSystemPromptEnabled.asStateFlow()

    // ═══════════════════════════════════════════════════════════════════
    // LONG CONTEXT MODE (1M)
    // ═══════════════════════════════════════════════════════════════════

    private val _longContextEnabled = MutableStateFlow(false)
    val longContextEnabled: StateFlow<Boolean> = _longContextEnabled.asStateFlow()

    // ═══════════════════════════════════════════════════════════════════
    // ATTACHED FILE (до 2MB .txt)
    // ═══════════════════════════════════════════════════════════════════

    private val _attachedFileContent = MutableStateFlow<String?>(null)
    val attachedFileContent: StateFlow<String?> = _attachedFileContent.asStateFlow()

    private val _attachedFileName = MutableStateFlow<String?>(null)
    val attachedFileName: StateFlow<String?> = _attachedFileName.asStateFlow()

    private val _attachedFileSize = MutableStateFlow(0L)
    val attachedFileSize: StateFlow<Long> = _attachedFileSize.asStateFlow()

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

        viewModelScope.launch {
            while (true) { delay(3600_000); try { repositoryAnalyzer.cleanupOldSessions() } catch (_: Exception) {} }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // COPY CHAT
    // ═══════════════════════════════════════════════════════════════════

    suspend fun getChatAsText(): String {
        val allMessages = chatDao.getSession(sessionId)
            .filter { !it.isStreaming && it.content.isNotBlank() }

        if (allMessages.isEmpty()) return ""

        val sb = StringBuilder()
        sb.appendLine("═══ Analyzer Chat ═══")
        sb.appendLine("Model: ${_selectedModel.value.displayName}")
        sb.appendLine("═".repeat(30))
        sb.appendLine()

        allMessages.forEach { msg ->
            val role = when (msg.role) {
                MessageRole.USER -> "👤 You"
                MessageRole.ASSISTANT -> "🤖 Claude"
                MessageRole.SYSTEM -> "⚙️ System"
            }
            sb.appendLine("── $role ──")
            sb.appendLine(msg.content)
            sb.appendLine()
        }

        return sb.toString().trimEnd()
    }

    // ═══════════════════════════════════════════════════════════════════
    // CONVERSATION HISTORY TOGGLE
    // ═══════════════════════════════════════════════════════════════════

    fun toggleConversationHistory() {
        // Блокируем если включен Cache Mode
        if (_cacheModeEnabled.value) {
            addOperation("🔒", "История заблокирована в Cache Mode", OperationLogType.ERROR)
            return
        }

        _conversationHistoryEnabled.value = !_conversationHistoryEnabled.value
        val status = if (_conversationHistoryEnabled.value) "ON" else "OFF"
        addOperation("💬", "Conversation History: $status", OperationLogType.INFO)
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
        if (_cacheModeEnabled.value) return model.maxOutputTokens

        val baseOutput = model.getEffectiveOutputTokens(_ecoOutputMode.value)

        // В Long Context режиме — разрешаем до 128K output для Opus 4.6
        return if (_longContextEnabled.value && model.supportsLongContext1M) {
            model.maxOutputTokens // 128,000 для Opus 4.6
        } else {
            baseOutput
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // EXTENDED THINKING TOGGLES
    // ═══════════════════════════════════════════════════════════════════

    fun toggleThinking() {
        _thinkingEnabled.value = !_thinkingEnabled.value
        val status = if (_thinkingEnabled.value) "ON (${"%,d".format(_thinkingBudget.value)} tok)" else "OFF"
        addOperation("🧠", "Thinking: $status", OperationLogType.INFO)
    }

    fun setThinkingBudget(budget: Int) {
        _thinkingBudget.value = budget.coerceIn(1000, 100_000)
        addOperation("🧠", "Thinking budget: ${"%,d".format(_thinkingBudget.value)} tok", OperationLogType.INFO)
    }

    fun toggleSendTools() {
        _sendToolsEnabled.value = !_sendToolsEnabled.value
        val status = if (_sendToolsEnabled.value) "ON" else "OFF"
        addOperation("🔧", "Tools: $status", OperationLogType.INFO)
    }

    fun toggleSendSystemPrompt() {
        _sendSystemPromptEnabled.value = !_sendSystemPromptEnabled.value
        val status = if (_sendSystemPromptEnabled.value) "ON" else "OFF"
        addOperation("📋", "System Prompt: $status", OperationLogType.INFO)
    }

    // ═══════════════════════════════════════════════════════════════════
    // LONG CONTEXT TOGGLE
    // ═══════════════════════════════════════════════════════════════════

    fun toggleLongContext() {
        val model = _selectedModel.value
        if (!model.supportsLongContext1M) {
            addOperation("❌", "${model.displayName} не поддерживает Long Context", OperationLogType.ERROR)
            return
        }
        _longContextEnabled.value = !_longContextEnabled.value
        if (_longContextEnabled.value) {
            addOperation("🔓", "Long Context ON (1M) — цена input ×2!", OperationLogType.SUCCESS)
        } else {
            addOperation("🔒", "Long Context OFF (200K)", OperationLogType.INFO)
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // FILE ATTACHMENT
    // ═══════════════════════════════════════════════════════════════════

    fun attachFile(fileName: String, content: String, sizeBytes: Long) {
        if (sizeBytes > MAX_ATTACHED_FILE_BYTES) {
            addOperation("❌", "Файл слишком большой: ${sizeBytes / 1024}KB > 2048KB", OperationLogType.ERROR)
            return
        }
        if (content.isBlank()) {
            addOperation("❌", "Файл пустой", OperationLogType.ERROR)
            return
        }
        _attachedFileContent.value = content
        _attachedFileName.value = fileName
        _attachedFileSize.value = sizeBytes
        addOperation("📎", "Прикреплён: $fileName (${sizeBytes / 1024}KB, ~${content.length / 4} tok)", OperationLogType.SUCCESS)
    }

    fun detachFile() {
        val name = _attachedFileName.value
        _attachedFileContent.value = null
        _attachedFileName.value = null
        _attachedFileSize.value = 0
        if (name != null) {
            addOperation("📎", "Откреплён: $name", OperationLogType.INFO)
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // CACHE MODE — 100% ПО ДОКУМЕНТАЦИИ
    // ═══════════════════════════════════════════════════════════════════

    /**
     * ═══════════════════════════════════════════════════════════════════
     * ПРАВИЛЬНОЕ КЕШИРОВАНИЕ (по документации Anthropic):
     * ═══════════════════════════════════════════════════════════════════
     *
     * 1. Включаем Cache Mode → output MAX, таймер НЕ запускается
     * 2. Первый запрос → создается кеш (system + tools), запускается таймер
     * 3. Последующие запросы → cache hit, таймер СБРАСЫВАЕТСЯ (бесплатно)
     * 4. Выключаем Cache Mode → очищаем кеш сессии, сбрасываем таймер
     */
    fun toggleCacheMode() {
        if (_ecoOutputMode.value && !_cacheModeEnabled.value) {
            addOperation("🔒", "Cache заблокирован: сначала переключите на MAX", OperationLogType.ERROR)
            return
        }

        val newState = !_cacheModeEnabled.value
        _cacheModeEnabled.value = newState

        if (newState) {
            // Включаем Cache Mode
            _ecoOutputMode.value = false
            _conversationHistoryEnabled.value = false
            addOperation("📦", "CACHE MODE ON — первое сообщение будет кешировано", OperationLogType.SUCCESS)
            addOperation("🔒", "История автоматически выключена", OperationLogType.INFO)
        } else {
            // Выключаем Cache Mode
            stopCacheTimer()
            _cacheIsWarmed.value = false
            _cacheTotalReadTokens.value = 0
            _cacheTotalWriteTokens.value = 0
            _cacheTotalSavingsEUR.value = 0.0
            _cacheHitCount.value = 0
            cacheExpiresAt = 0L
            
            // Очищаем кеш сессии
            repositoryAnalyzer.clearCacheForSession(_sessionId)
            
            addOperation("📦", "CACHE MODE OFF — кеш очищен", OperationLogType.SUCCESS)
        }
    }

    /**
     * Запускает таймер при первом запросе (когда кеш создается)
     */
    private fun startCacheTimerIfNeeded() {
        if (!_cacheModeEnabled.value) return
        if (_cacheIsWarmed.value && cacheTimerJob?.isActive == true) {
            // Таймер уже работает — сбрасываем его (cache hit)
            resetCacheTimer()
            return
        }

        // Первый запрос — запускаем таймер
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
     * Сбрасывает таймер при cache hit (бесплатно)
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
     * Обрабатывает результаты кеширования из API
     */
    private fun handleCacheResult(cachedReadTokens: Int, cachedWriteTokens: Int, savingsEUR: Double) {
        if (cachedWriteTokens > 0) {
            // Первый запрос — кеш создан
            _cacheTotalWriteTokens.value += cachedWriteTokens
            startCacheTimerIfNeeded()  // Запускаем таймер
            addOperation("📝", "Cache WRITE: ${"%,d".format(cachedWriteTokens)} tok", OperationLogType.SUCCESS)
        }
        if (cachedReadTokens > 0) {
            // Cache hit — сбрасываем таймер (бесплатно)
            _cacheTotalReadTokens.value += cachedReadTokens
            _cacheHitCount.value += 1
            _cacheTotalSavingsEUR.value += savingsEUR
            resetCacheTimer()  // Сбрасываем таймер
            addOperation("⚡", "Cache HIT: ${"%,d".format(cachedReadTokens)} tok (€${String.format("%.4f", savingsEUR)} saved)", OperationLogType.SUCCESS)
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // OPS LOG
    // ═══════════════════════════════════════════════════════════════════

    fun addOperation(icon: String, message: String, type: OperationLogType = OperationLogType.INFO) {
        _operationsLog.update { current ->
            val newItem = OperationLogItem(icon = icon, message = message, type = type)
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
        
        // Очищаем кеш для старой сессии
        repositoryAnalyzer.clearCacheForSession(_sessionId)
        
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
    // SEND MESSAGE (★ MODIFIED WITH FOREGROUND SERVICE + RETRY HANDLING)
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

            // Добавляем прикреплённый файл к сообщению
            val attachedContent = _attachedFileContent.value
            val attachedName = _attachedFileName.value
            val fullMessage = if (attachedContent != null && attachedName != null) {
                "$message\n\n<attached_file name=\"$attachedName\">\n$attachedContent\n</attached_file>"
            } else {
                message
            }

            val thinkingTag = if (_thinkingEnabled.value) "+🧠" else ""
            val modeName = if (isCacheMode) "CACHE" else if (_ecoOutputMode.value) "ECO" else "MAX"
            val fullModeName = "$modeName$thinkingTag"

            addOperation("📤", "$fullModeName ${"%,d".format(maxTokens)}: ${message.take(50)}...", OperationLogType.PROGRESS)

            // В чат пишем только текст сообщения
            chatDao.insert(ChatMessageEntity(
                sessionId = sessionId,
                role = MessageRole.USER,
                content = if (attachedName != null) "$message\n📎 $attachedName (${_attachedFileSize.value / 1024}KB)" else message,
                provider = "claude"
            ))

            // Загружаем историю
            val historyMessages = if (_conversationHistoryEnabled.value && !isCacheMode) {
                chatDao.getSession(sessionId)
                    .filter { it.role != MessageRole.SYSTEM }
                    .filter { !it.isStreaming && it.content.isNotBlank() }
            } else {
                emptyList()
            }

            // ★ NEW: Запускаем Foreground Service для ВСЕХ стримов
            StreamingForegroundService.start(appContext, useModel.displayName)

            val startTime = System.currentTimeMillis()
            var fullResponse = ""

            try {
                repositoryAnalyzer.scanFilesV2(
                    sessionId = sessionId,
                    filePaths = _selectedFiles.value.toList(),
                    userQuery = fullMessage,
                    conversationHistory = historyMessages,
                    model = useModel,
                    maxTokens = maxTokens,
                    enableCaching = isCacheMode,
                    enableThinking = _thinkingEnabled.value,
                    thinkingBudget = _thinkingBudget.value,
                    sendTools = _sendToolsEnabled.value,
                    sendSystemPrompt = _sendSystemPromptEnabled.value
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

                            // ★ NEW: Обновляем уведомление Foreground Service
                            val elapsed = ((System.currentTimeMillis() - startTime) / 1000).toInt()
                            val tokens = fullResponse.length / 4 // грубая оценка
                            StreamingForegroundService.updateProgress(
                                appContext,
                                progressText = "Generating: ~${"%,d".format(tokens)} tokens",
                                tokens = tokens,
                                elapsedSec = elapsed
                            )
                        }

                        // ★ NEW: Обработка retry-событий от ResilientStreamingClient
                        is RepositoryAnalyzer.AnalysisResult.WaitingForNetwork -> {
                            addOperation("📡", "Сеть потеряна. Ожидание... (попытка ${result.attempt}/${result.maxAttempts})", OperationLogType.ERROR)
                            StreamingForegroundService.updateProgress(
                                appContext,
                                progressText = "⚠️ Waiting for network (attempt ${result.attempt})",
                                tokens = result.accumulatedTokens,
                                elapsedSec = ((System.currentTimeMillis() - startTime) / 1000).toInt()
                            )
                        }

                        is RepositoryAnalyzer.AnalysisResult.Retrying -> {
                            addOperation("🔄", "Переподключение #${result.attempt} (backoff ${result.backoffMs / 1000}s)", OperationLogType.PROGRESS)
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
                                isStreaming = false,
                                provider = "claude"
                            ))

                            _streamingText.value = null
                            _currentSession.value = result.session

                            result.cost.let { cost ->
                                val toolInfo = if (result.toolIterations > 1) " (${result.toolIterations} iterations)" else ""
                                val retryInfo = if (result.totalRetries > 0) " [${result.totalRetries} retries]" else ""
                                addOperation("✅",
                                    "${"%,d".format(cost.totalTokens)} tok, €${String.format("%.4f", cost.totalCostEUR)}$toolInfo$retryInfo",
                                    OperationLogType.SUCCESS
                                )

                                if (isCacheMode) {
                                    handleCacheResult(cost.cachedReadTokens, cost.cachedWriteTokens, cost.cacheSavingsEUR)
                                }
                            }

                            _selectedFiles.value = emptySet()
                            if (_attachedFileContent.value != null) {
                                detachFile()
                            }
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
            } finally {
                // ★ NEW: Останавливаем Foreground Service
                StreamingForegroundService.stop(appContext)
            }
        }
    }

    fun cancelStreaming() {
        sendJob?.cancel()
        sendJob = null
        _isStreaming.value = false
        _streamingText.value = null
        StreamingForegroundService.stop(appContext)
        addOperation("🛑", "Streaming cancelled", OperationLogType.INFO)
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
        // ★ NEW: Гарантируем остановку сервиса при очистке ViewModel
        StreamingForegroundService.stop(appContext)
    }
}