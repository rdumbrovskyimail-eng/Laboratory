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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * Analyzer ViewModel v4.0 (ECO/MAX OUTPUT MODE)
 * 
 * ✅ ОБНОВЛЕНО:
 * - ECO/MAX toggle для output токенов
 * - ECO (🟢): 8192 output — экономия rate limits
 * - MAX (🔴): maxOutputTokens модели — полная мощность
 * - getEffectiveMaxTokens() для передачи в API
 */
@HiltViewModel
class AnalyzerViewModel @Inject constructor(
    private val repositoryAnalyzer: RepositoryAnalyzer,
    private val chatDao: ChatDao,
    private val savedStateHandle: SavedStateHandle,
    private val appSettings: AppSettings
) : ViewModel() {
    
    companion object {
        private const val TAG = "AnalyzerViewModel"
        private const val KEY_SESSION_ID = "session_id"
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // OPERATIONS LOG
    // ═══════════════════════════════════════════════════════════════════════════
    
    data class OperationLogItem(
        val icon: String,
        val message: String,
        val timestamp: Long = System.currentTimeMillis(),
        val type: OperationLogType = OperationLogType.INFO
    )
    
    enum class OperationLogType { INFO, SUCCESS, ERROR, PROGRESS }
    
    private val _operationsLog = MutableStateFlow<List<OperationLogItem>>(emptyList())
    val operationsLog: StateFlow<List<OperationLogItem>> = _operationsLog.asStateFlow()
    
    private val _autoHaikuEnabled = MutableStateFlow(true)
    val autoHaikuEnabled: StateFlow<Boolean> = _autoHaikuEnabled.asStateFlow()
    
    // ═══════════════════════════════════════════════════════════════════════════
    // ✅ НОВОЕ: ECO / MAX OUTPUT MODE
    // ═══════════════════════════════════════════════════════════════════════════
    
    /** true = ECO (🟢 8K output), false = MAX (🔴 модельный максимум) */
    private val _ecoOutputMode = MutableStateFlow(true)
    val ecoOutputMode: StateFlow<Boolean> = _ecoOutputMode.asStateFlow()
    
    // ═══════════════════════════════════════════════════════════════════════════
    // SESSION & MODEL
    // ═══════════════════════════════════════════════════════════════════════════
    
    private var _sessionId: String = savedStateHandle.get<String>(KEY_SESSION_ID)
        ?: UUID.randomUUID().toString().also {
            savedStateHandle[KEY_SESSION_ID] = it
        }
    
    private val sessionId: String get() = _sessionId
    
    private val _selectedModel = MutableStateFlow(ClaudeModelConfig.ClaudeModel.getDefault())
    val selectedModel: StateFlow<ClaudeModelConfig.ClaudeModel> = _selectedModel.asStateFlow()
    
    private val _currentSession = MutableStateFlow<ClaudeModelConfig.ChatSession?>(null)
    val currentSession: StateFlow<ClaudeModelConfig.ChatSession?> = _currentSession.asStateFlow()
    
    private val _cachingEnabled = MutableStateFlow(true)
    val cachingEnabled: StateFlow<Boolean> = _cachingEnabled.asStateFlow()
    
    // ═══════════════════════════════════════════════════════════════════════════
    // REPOSITORY
    // ═══════════════════════════════════════════════════════════════════════════
    
    private val _repositoryStructure = MutableStateFlow<RepositoryAnalyzer.RepositoryStructure?>(null)
    val repositoryStructure: StateFlow<RepositoryAnalyzer.RepositoryStructure?> = 
        _repositoryStructure.asStateFlow()
    
    private val _selectedFiles = MutableStateFlow<Set<String>>(emptySet())
    val selectedFiles: StateFlow<Set<String>> = _selectedFiles.asStateFlow()
    
    private val _scanEstimate = MutableStateFlow<RepositoryAnalyzer.ScanEstimate?>(null)
    val scanEstimate: StateFlow<RepositoryAnalyzer.ScanEstimate?> = _scanEstimate.asStateFlow()
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CHAT
    // ═══════════════════════════════════════════════════════════════════════════
    
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
    
    // ═══════════════════════════════════════════════════════════════════════════
    // INITIALIZATION
    // ═══════════════════════════════════════════════════════════════════════════
    
    init {
        Log.i(TAG, "AnalyzerViewModel initialized with sessionId: $sessionId")
        
        viewModelScope.launch {
            val savedModelId = appSettings.claudeModel.first()
            Log.d(TAG, "Loading model from Settings: $savedModelId")
            
            val model = ClaudeModelConfig.ClaudeModel.fromModelId(savedModelId)
                ?: ClaudeModelConfig.ClaudeModel.getDefault().also {
                    Log.w(TAG, "Model not found, using default: ${it.displayName}")
                }
            
            _selectedModel.value = model
            Log.i(TAG, "✅ Model loaded: ${model.displayName} (${model.modelId})")
            
            val existingSession = repositoryAnalyzer.getSession(sessionId)
            
            if (existingSession != null) {
                Log.i(TAG, "Restored existing session: $sessionId")
                _currentSession.value = existingSession
                
                if (existingSession.model != model) {
                    Log.w(TAG, "Session model mismatch! Starting new session...")
                    startNewSession()
                }
            } else {
                Log.i(TAG, "Creating new session: $sessionId")
                val newSession = repositoryAnalyzer.createSession(sessionId, model)
                _currentSession.value = newSession
            }
        }
        
        viewModelScope.launch {
            while (true) {
                delay(3600_000)
                try {
                    val cleaned = repositoryAnalyzer.cleanupOldSessions()
                    if (cleaned > 0) Log.i(TAG, "Auto-cleanup: removed $cleaned old sessions")
                } catch (e: Exception) {
                    Log.e(TAG, "Cleanup failed", e)
                }
            }
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // ECO / MAX OUTPUT MODE
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun toggleOutputMode() {
        _ecoOutputMode.value = !_ecoOutputMode.value
        val model = _selectedModel.value
        val effectiveTokens = model.getEffectiveOutputTokens(_ecoOutputMode.value)
        val modeName = if (_ecoOutputMode.value) "ECO 🟢" else "MAX 🔴"
        addOperation(
            if (_ecoOutputMode.value) "🟢" else "🔴",
            "Output: $modeName (${"%,d".format(effectiveTokens)} tok, ${model.displayName})",
            OperationLogType.INFO
        )
        Log.d(TAG, "Output mode: $modeName, effective tokens: $effectiveTokens for ${model.displayName}")
    }
    
    /**
     * Получить текущий эффективный лимит output для выбранной модели
     */
    fun getEffectiveMaxTokens(): Int {
        return _selectedModel.value.getEffectiveOutputTokens(_ecoOutputMode.value)
    }
    
    /**
     * Получить эффективный лимит output для конкретной модели (для Auto-Haiku)
     */
    fun getEffectiveMaxTokens(model: ClaudeModelConfig.ClaudeModel): Int {
        return model.getEffectiveOutputTokens(_ecoOutputMode.value)
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // AUTO-HAIKU & OPERATIONS LOG HELPERS
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun toggleAutoHaiku() {
        _autoHaikuEnabled.value = !_autoHaikuEnabled.value
        addOperation("💨", "Auto-Haiku ${if (_autoHaikuEnabled.value) "включён" else "выключен"}")
        Log.d(TAG, "Auto-Haiku ${if (_autoHaikuEnabled.value) "enabled" else "disabled"}")
    }
    
    private fun addOperation(icon: String, message: String, type: OperationLogType = OperationLogType.INFO) {
        _operationsLog.value = _operationsLog.value + OperationLogItem(icon, message, type = type)
    }
    
    fun clearOperationsLog() {
        _operationsLog.value = emptyList()
    }
    
    private fun isSimpleOperation(query: String): Boolean {
        val lower = query.lowercase()
        val simplePatterns = listOf(
            "покажи дерево", "дерево файлов", "file tree", "show tree",
            "список файлов", "list files", "ls ", "dir ",
            "прочти файл", "прочитай", "read file", "cat ",
            "покажи структуру", "show structure", "show files",
            "что в папке", "содержимое папки", "what's in",
            "покажи файл", "show file", "open file"
        )
        return simplePatterns.any { lower.contains(it) }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // MODEL SELECTION
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun selectModel(model: ClaudeModelConfig.ClaudeModel) {
        Log.i(TAG, "Changing model to: ${model.displayName}")
        
        _selectedModel.value = model
        
        viewModelScope.launch {
            appSettings.setClaudeModel(model.modelId)
            Log.d(TAG, "✅ Model saved to Settings: ${model.modelId}")
        }
        
        startNewSession()
    }
    
    fun toggleCaching() {
        _cachingEnabled.value = !_cachingEnabled.value
        addOperation(
            "📦", 
            "Cache ${if (_cachingEnabled.value) "включён" else "выключен"}",
            OperationLogType.INFO
        )
        Log.d(TAG, "Caching ${if (_cachingEnabled.value) "enabled" else "disabled"}")
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // SESSION MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun startNewSession() {
        viewModelScope.launch {
            Log.i(TAG, "Starting new session")
            
            _currentSession.value?.let { session ->
                repositoryAnalyzer.endSession(session.sessionId)
                Log.i(TAG, "Ended session: ${session.sessionId}, cost: ${session.currentCost.totalCostEUR}€")
            }
            
            val newSessionId = UUID.randomUUID().toString()
            savedStateHandle[KEY_SESSION_ID] = newSessionId
            _sessionId = newSessionId
            _messagesSessionId.value = newSessionId
            
            val newSession = repositoryAnalyzer.createSession(newSessionId, _selectedModel.value)
            _currentSession.value = newSession
            
            _selectedFiles.value = emptySet()
            _scanEstimate.value = null
            _chatError.value = null
            
            addOperation("🔄", "Новый сеанс: ${_selectedModel.value.displayName}", OperationLogType.SUCCESS)
            
            Log.i(TAG, "New session created: $newSessionId with ${_selectedModel.value.displayName}")
        }
    }
    
    fun getSessionStats(): String? {
        return _currentSession.value?.getDetailedStats()
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // REPOSITORY OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun loadRepositoryStructure(path: String = "") {
        viewModelScope.launch {
            Log.d(TAG, "Loading repository structure: $path")
            
            repositoryAnalyzer.getRepositoryStructure(path).onSuccess { structure ->
                _repositoryStructure.value = structure
                Log.d(TAG, "Repository structure loaded: ${structure.totalFiles} files")
            }.onFailure { error ->
                Log.e(TAG, "Failed to load repository structure", error)
                _chatError.value = "Failed to load repository: ${error.message}"
            }
        }
    }
    
    fun selectFiles(files: Set<String>) {
        _selectedFiles.value = files
        Log.d(TAG, "Selected ${files.size} files")
        if (files.isNotEmpty()) updateScanEstimate() else _scanEstimate.value = null
    }
    
    fun addFile(filePath: String) {
        _selectedFiles.value = _selectedFiles.value + filePath
        Log.d(TAG, "Added file: $filePath")
        updateScanEstimate()
    }
    
    fun removeFile(filePath: String) {
        _selectedFiles.value = _selectedFiles.value - filePath
        Log.d(TAG, "Removed file: $filePath")
        if (_selectedFiles.value.isNotEmpty()) updateScanEstimate() else _scanEstimate.value = null
    }
    
    fun clearSelectedFiles() {
        _selectedFiles.value = emptySet()
        _scanEstimate.value = null
        Log.d(TAG, "Cleared selected files")
    }
    
    private fun updateScanEstimate() {
        viewModelScope.launch {
            val files = _selectedFiles.value.toList()
            if (files.isEmpty()) {
                _scanEstimate.value = null
                return@launch
            }
            
            repositoryAnalyzer.estimateScanCost(
                filePaths = files,
                model = _selectedModel.value,
                sessionId = sessionId
            ).onSuccess { estimate ->
                _scanEstimate.value = estimate
            }.onFailure { error ->
                Log.e(TAG, "Failed to estimate scan cost", error)
                _chatError.value = error.message
            }
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CHAT OPERATIONS (Auto-Haiku + ECO/MAX output)
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun sendMessage(message: String) {
        if (message.isBlank()) {
            _chatError.value = "Message cannot be empty"
            return
        }
        
        viewModelScope.launch {
            _isStreaming.value = true
            _chatError.value = null
            
            // Auto-Haiku: определяем простые операции
            val useModel = if (_autoHaikuEnabled.value && isSimpleOperation(message)) {
                addOperation("💨", "Auto-Haiku: простая операция", OperationLogType.INFO)
                ClaudeModelConfig.ClaudeModel.HAIKU_4_5
            } else {
                _selectedModel.value
            }
            
            // ✅ НОВОЕ: ECO/MAX output tokens
            val maxTokens = getEffectiveMaxTokens(useModel)
            val modeName = if (_ecoOutputMode.value) "ECO" else "MAX"
            
            addOperation("📤", "Отправка ($modeName ${"%,d".format(maxTokens)} tok): ${message.take(40)}...", OperationLogType.PROGRESS)
            
            repositoryAnalyzer.scanFiles(
                sessionId = sessionId,
                filePaths = _selectedFiles.value.toList(),
                userQuery = message,
                model = useModel,
                maxTokens = maxTokens, // ✅ передаём ECO или MAX лимит
                enableCaching = _cachingEnabled.value
            ).collect { result ->
                when (result) {
                    is RepositoryAnalyzer.AnalysisResult.Loading -> {
                        addOperation("⏳", result.message, OperationLogType.PROGRESS)
                    }
                    
                    is RepositoryAnalyzer.AnalysisResult.Streaming -> {
                        // Стриминг обрабатывается через chatDao
                    }
                    
                    is RepositoryAnalyzer.AnalysisResult.Completed -> {
                        _isStreaming.value = false
                        _currentSession.value = result.session
                        
                        addOperation(
                            "✅", 
                            "Ответ (${result.cost.totalTokens} tok, €${String.format("%.4f", result.cost.totalCostEUR)})", 
                            OperationLogType.SUCCESS
                        )
                        
                        val operations = repositoryAnalyzer.parseOperations(result.text)
                        if (operations.isNotEmpty()) {
                            addOperation("🔧", "Обнаружено ${operations.size} операций", OperationLogType.INFO)
                            executeClaudeOperations(operations)
                        }
                        
                        _selectedFiles.value = emptySet()
                        _scanEstimate.value = null
                    }
                    
                    is RepositoryAnalyzer.AnalysisResult.Error -> {
                        _isStreaming.value = false
                        _chatError.value = result.message
                        addOperation("❌", "Ошибка: ${result.message}", OperationLogType.ERROR)
                    }
                }
            }
        }
    }
    
    private fun executeClaudeOperations(operations: List<RepositoryAnalyzer.ParsedOperation>) {
        viewModelScope.launch {
            for (op in operations) {
                val opName = when (op.type) {
                    RepositoryAnalyzer.OperationType.CREATE_FILE -> "📝 Создаю файл: ${op.path}"
                    RepositoryAnalyzer.OperationType.EDIT_FILE -> "✏️ Редактирую: ${op.path}"
                    RepositoryAnalyzer.OperationType.DELETE_FILE -> "🗑️ Удаляю: ${op.path}"
                    RepositoryAnalyzer.OperationType.CREATE_FOLDER -> "📁 Создаю папку: ${op.path}"
                }
                addOperation("⚙️", opName, OperationLogType.PROGRESS)
            }
            
            val results = repositoryAnalyzer.executeOperations(
                sessionId = sessionId,
                operations = operations
            )
            
            results.forEachIndexed { index, result ->
                val op = operations[index]
                result.onSuccess {
                    addOperation("✅", "Готово: ${op.path}", OperationLogType.SUCCESS)
                }.onFailure { e ->
                    addOperation("❌", "Ошибка ${op.path}: ${e.message}", OperationLogType.ERROR)
                }
            }
        }
    }
    
    fun clearChat() {
        viewModelScope.launch {
            chatDao.clearSession(sessionId)
            Log.i(TAG, "Chat cleared for session: $sessionId")
        }
    }
    
    fun dismissError() {
        _chatError.value = null
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CLEANUP
    // ═══════════════════════════════════════════════════════════════════════════
    
    override fun onCleared() {
        super.onCleared()
        _currentSession.value?.let { session ->
            if (session.isActive) {
                repositoryAnalyzer.endSession(session.sessionId)
                Log.i(TAG, "Session ended on ViewModel cleared: ${session.sessionId}")
            }
        }
        Log.i(TAG, "AnalyzerViewModel cleared")
    }
}