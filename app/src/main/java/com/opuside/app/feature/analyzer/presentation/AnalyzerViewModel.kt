package com.opuside.app.feature.analyzer.presentation

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opuside.app.core.ai.ClaudeModelConfig
import com.opuside.app.core.ai.RepositoryAnalyzer
import com.opuside.app.core.database.dao.ChatDao
import com.opuside.app.core.database.entity.ChatMessageEntity
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

/**
 * Analyzer ViewModel v2.1 (UPDATED)
 * 
 * ✅ НОВОЕ:
 * - Выбор модели
 * - Управление сеансами
 * - Детальная статистика
 * - Автоматическая очистка
 */
@HiltViewModel
class AnalyzerViewModel @Inject constructor(
    private val repositoryAnalyzer: RepositoryAnalyzer,
    private val chatDao: ChatDao,
    private val savedStateHandle: SavedStateHandle
) : ViewModel() {
    
    companion object {
        private const val TAG = "AnalyzerViewModel"
        private const val KEY_SESSION_ID = "session_id"
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // SESSION & MODEL
    // ═══════════════════════════════════════════════════════════════════════════
    
    private val sessionId: String = savedStateHandle.get<String>(KEY_SESSION_ID)
        ?: UUID.randomUUID().toString().also {
            savedStateHandle[KEY_SESSION_ID] = it
        }
    
    // ✅ НОВОЕ: Выбранная модель
    private val _selectedModel = MutableStateFlow(ClaudeModelConfig.ClaudeModel.OPUS_4_6)
    val selectedModel: StateFlow<ClaudeModelConfig.ClaudeModel> = _selectedModel.asStateFlow()
    
    // ✅ НОВОЕ: Текущий сеанс
    private val _currentSession = MutableStateFlow<ClaudeModelConfig.ChatSession?>(null)
    val currentSession: StateFlow<ClaudeModelConfig.ChatSession?> = _currentSession.asStateFlow()
    
    // ✅ НОВОЕ: Включить кеширование
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
    
    val messages: Flow<List<ChatMessageEntity>> = chatDao.getMessages(sessionId)
    
    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()
    
    private val _chatError = MutableStateFlow<String?>(null)
    val chatError: StateFlow<String?> = _chatError.asStateFlow()
    
    // ✅ ОБНОВЛЕНО: Статистика токенов теперь из сеанса
    val sessionTokens: StateFlow<ClaudeModelConfig.ModelCost?> = currentSession
        .map { it?.currentCost }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    
    // ✅ НОВОЕ: Предупреждение о длинном контексте
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
        
        // Создаем или восстанавливаем сеанс
        viewModelScope.launch {
            val existingSession = repositoryAnalyzer.getSession(sessionId)
            
            if (existingSession != null) {
                Log.i(TAG, "Restored existing session: $sessionId")
                _currentSession.value = existingSession
                _selectedModel.value = existingSession.model
            } else {
                Log.i(TAG, "Creating new session: $sessionId")
                val newSession = repositoryAnalyzer.createSession(sessionId, _selectedModel.value)
                _currentSession.value = newSession
            }
            
            // Автоматическая очистка старых сеансов (раз в час)
            while (true) {
                delay(3600_000) // 1 час
                val cleaned = repositoryAnalyzer.cleanupOldSessions()
                if (cleaned > 0) {
                    Log.i(TAG, "Auto-cleanup: removed $cleaned old sessions")
                }
            }
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // MODEL SELECTION
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun selectModel(model: ClaudeModelConfig.ClaudeModel) {
        Log.i(TAG, "Changing model to: ${model.displayName}")
        
        _selectedModel.value = model
        
        // При смене модели начинаем новый сеанс
        startNewSession()
    }
    
    fun toggleCaching() {
        _cachingEnabled.value = !_cachingEnabled.value
        Log.d(TAG, "Caching ${if (_cachingEnabled.value) "enabled" else "disabled"}")
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // SESSION MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun startNewSession() {
        viewModelScope.launch {
            Log.i(TAG, "Starting new session")
            
            // Завершаем текущий сеанс
            _currentSession.value?.let { session ->
                repositoryAnalyzer.endSession(session.sessionId)
                Log.i(TAG, "Ended session: ${session.sessionId}, cost: ${session.currentCost.totalCostEUR}€")
            }
            
            // Создаем новый ID
            val newSessionId = UUID.randomUUID().toString()
            savedStateHandle[KEY_SESSION_ID] = newSessionId
            
            // Создаем новый сеанс
            val newSession = repositoryAnalyzer.createSession(newSessionId, _selectedModel.value)
            _currentSession.value = newSession
            
            // Сбрасываем состояние
            _selectedFiles.value = emptySet()
            _scanEstimate.value = null
            _chatError.value = null
            
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
        
        // Автоматически обновляем оценку
        if (files.isNotEmpty()) {
            updateScanEstimate()
        } else {
            _scanEstimate.value = null
        }
    }
    
    fun addFile(filePath: String) {
        _selectedFiles.value = _selectedFiles.value + filePath
        Log.d(TAG, "Added file: $filePath")
        updateScanEstimate()
    }
    
    fun removeFile(filePath: String) {
        _selectedFiles.value = _selectedFiles.value - filePath
        Log.d(TAG, "Removed file: $filePath")
        
        if (_selectedFiles.value.isNotEmpty()) {
            updateScanEstimate()
        } else {
            _scanEstimate.value = null
        }
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
            
            Log.d(TAG, "Updating scan estimate for ${files.size} files")
            
            repositoryAnalyzer.estimateScanCost(
                filePaths = files,
                model = _selectedModel.value,
                sessionId = sessionId
            ).onSuccess { estimate ->
                _scanEstimate.value = estimate
                Log.d(TAG, "Scan estimate: ${estimate.cost.totalCostEUR}€, " +
                        "will trigger long context: ${estimate.willTriggerLongContext}")
            }.onFailure { error ->
                Log.e(TAG, "Failed to estimate scan cost", error)
                _chatError.value = error.message
            }
        }
    }
    
    // ═══════════════════════════════════════════════════════════════════════════
    // CHAT OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════════
    
    fun scanSelectedFiles(userQuery: String) {
        val files = _selectedFiles.value.toList()
        
        if (files.isEmpty()) {
            _chatError.value = "No files selected"
            Log.w(TAG, "Scan attempted with no files selected")
            return
        }
        
        if (userQuery.isBlank()) {
            _chatError.value = "Query cannot be empty"
            Log.w(TAG, "Scan attempted with empty query")
            return
        }
        
        viewModelScope.launch {
            _isStreaming.value = true
            _chatError.value = null
            
            Log.i(TAG, "Starting scan: ${files.size} files, model=${_selectedModel.value.displayName}")
            
            repositoryAnalyzer.scanFiles(
                sessionId = sessionId,
                filePaths = files,
                userQuery = userQuery,
                model = _selectedModel.value,
                enableCaching = _cachingEnabled.value
            ).collect { result ->
                when (result) {
                    is RepositoryAnalyzer.AnalysisResult.Loading -> {
                        Log.d(TAG, "Loading: ${result.message}")
                    }
                    
                    is RepositoryAnalyzer.AnalysisResult.Streaming -> {
                        // Streaming обрабатывается автоматически через chatDao
                    }
                    
                    is RepositoryAnalyzer.AnalysisResult.Completed -> {
                        _isStreaming.value = false
                        _currentSession.value = result.session
                        
                        Log.i(TAG, "Scan completed: cost=${result.cost.totalCostEUR}€, " +
                                "cache savings=${result.cost.savingsPercentage}%")
                        
                        // Очищаем выбранные файлы после успешного сканирования
                        _selectedFiles.value = emptySet()
                        _scanEstimate.value = null
                    }
                    
                    is RepositoryAnalyzer.AnalysisResult.Error -> {
                        _isStreaming.value = false
                        _chatError.value = result.message
                        Log.e(TAG, "Scan error: ${result.message}")
                    }
                }
            }
        }
    }
    
    fun sendMessage(message: String) {
        if (message.isBlank()) {
            _chatError.value = "Message cannot be empty"
            return
        }
        
        // Если файлы выбраны - используем scanSelectedFiles
        if (_selectedFiles.value.isNotEmpty()) {
            scanSelectedFiles(message)
        } else {
            // Просто отправляем сообщение без сканирования
            viewModelScope.launch {
                _isStreaming.value = true
                _chatError.value = null
                
                repositoryAnalyzer.scanFiles(
                    sessionId = sessionId,
                    filePaths = emptyList(),
                    userQuery = message,
                    model = _selectedModel.value,
                    enableCaching = _cachingEnabled.value
                ).collect { result ->
                    when (result) {
                        is RepositoryAnalyzer.AnalysisResult.Completed -> {
                            _isStreaming.value = false
                            _currentSession.value = result.session
                        }
                        is RepositoryAnalyzer.AnalysisResult.Error -> {
                            _isStreaming.value = false
                            _chatError.value = result.message
                        }
                        else -> {}
                    }
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
        
        // Завершаем сеанс при уничтожении ViewModel
        _currentSession.value?.let { session ->
            if (session.isActive) {
                repositoryAnalyzer.endSession(session.sessionId)
                Log.i(TAG, "Session ended on ViewModel cleared: ${session.sessionId}")
            }
        }
        
        Log.i(TAG, "AnalyzerViewModel cleared")
    }
}