package com.opuside.app.feature.gemini.presentation

import android.content.Context
import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opuside.app.core.ai.GeminiModelConfig
import com.opuside.app.core.ai.GeminiModelConfig.GeminiModel
import com.opuside.app.core.ai.GeminiModelConfig.GeminiCost
import com.opuside.app.core.ai.GeminiModelConfig.GeminiSession
import com.opuside.app.core.ai.GeminiModelConfig.GenerationConfig
import com.opuside.app.core.ai.GeminiModelConfig.ThinkingLevel
import com.opuside.app.core.ai.ToolExecutor
import com.opuside.app.core.data.AppSettings
import com.opuside.app.core.security.SecureSettingsDataStore
import com.opuside.app.core.database.dao.ChatDao
import com.opuside.app.core.database.entity.ChatMessageEntity
import com.opuside.app.core.database.entity.MessageRole
import com.opuside.app.core.network.gemini.*
import com.opuside.app.core.service.StreamingForegroundService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.*
import java.util.UUID
import javax.inject.Inject

/**
 * 🔷 GEMINI VIEW MODEL v1.0
 *
 * Full Gemini API integration with:
 * - Multi-turn conversation history
 * - Message sanitization (user/model alternation)
 * - Context window overflow protection
 * - Tool loop with timeout (30s per tool batch)
 * - Cancellation-safe (cancels OkHttp Call)
 * - File attachment support
 * - Full GenerationConfig (temperature, topP, topK, thinking, safety, etc.)
 * - ECO/MAX output mode
 * - Foreground service for long streams
 * - API key validation
 * - Provider tracking in messages
 */
@HiltViewModel
class GeminiViewModel @Inject constructor(
    private val geminiClient: GeminiApiClient,
    private val toolExecutor: ToolExecutor,
    private val chatDao: ChatDao,
    private val savedStateHandle: SavedStateHandle,
    private val appSettings: AppSettings,
    private val secureSettings: SecureSettingsDataStore,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    companion object {
        private const val TAG = "GeminiVM"
        private const val KEY_SESSION_ID = "gemini_session_id"
        private const val MAX_OPS_LOG_SIZE = 500
        private const val MAX_TOOL_ITERATIONS = 8
        private const val TOOL_TIMEOUT_MS = 30_000L
        private const val MAX_ATTACHED_FILE_BYTES = 2 * 1024 * 1024L  // 2 MB
        private const val CHARS_PER_TOKEN_ESTIMATE = 4
    }

    // ═══════════════════════════════════════════════════════════════════
    // OPS LOG
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
    // STATE
    // ═══════════════════════════════════════════════════════════════════

    private val _selectedModel = MutableStateFlow(GeminiModel.getDefault())
    val selectedModel: StateFlow<GeminiModel> = _selectedModel.asStateFlow()

    private val _generationConfig = MutableStateFlow(GenerationConfig.ECO)
    val generationConfig: StateFlow<GenerationConfig> = _generationConfig.asStateFlow()

    private val _ecoOutputMode = MutableStateFlow(true)
    val ecoOutputMode: StateFlow<Boolean> = _ecoOutputMode.asStateFlow()

    private val _sendToolsEnabled = MutableStateFlow(true)
    val sendToolsEnabled: StateFlow<Boolean> = _sendToolsEnabled.asStateFlow()

    private val _sendSystemPromptEnabled = MutableStateFlow(true)
    val sendSystemPromptEnabled: StateFlow<Boolean> = _sendSystemPromptEnabled.asStateFlow()

    private val _conversationHistoryEnabled = MutableStateFlow(false)
    val conversationHistoryEnabled: StateFlow<Boolean> = _conversationHistoryEnabled.asStateFlow()

    private val _isStreaming = MutableStateFlow(false)
    val isStreaming: StateFlow<Boolean> = _isStreaming.asStateFlow()

    private val _chatError = MutableStateFlow<String?>(null)
    val chatError: StateFlow<String?> = _chatError.asStateFlow()

    private val _streamingText = MutableStateFlow<String?>(null)
    val streamingText: StateFlow<String?> = _streamingText.asStateFlow()

    // ── File attachment ──
    private val _attachedFileContent = MutableStateFlow<String?>(null)
    private val _attachedFileName = MutableStateFlow<String?>(null)
    val attachedFileName: StateFlow<String?> = _attachedFileName.asStateFlow()
    private val _attachedFileSize = MutableStateFlow(0L)
    val attachedFileSize: StateFlow<Long> = _attachedFileSize.asStateFlow()

    // ── Session ──
    private var _sessionId: String = savedStateHandle.get<String>(KEY_SESSION_ID)
        ?: UUID.randomUUID().toString().also { savedStateHandle[KEY_SESSION_ID] = it }
    private val sessionId: String get() = _sessionId

    private val _currentSession = MutableStateFlow<GeminiSession?>(null)

    val sessionCost: StateFlow<GeminiCost?> = _currentSession
        .map { it?.currentCost }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private val _messagesSessionId = MutableStateFlow(sessionId)
    val messages: Flow<List<ChatMessageEntity>> = _messagesSessionId
        .flatMapLatest { chatDao.getMessages(it) }

    private var sendJob: Job? = null

    // ═══════════════════════════════════════════════════════════════════
    // INIT
    // ═══════════════════════════════════════════════════════════════════

    init {
        viewModelScope.launch {
            val savedModelId = appSettings.geminiModel.first()
            val model = GeminiModel.fromModelId(savedModelId) ?: GeminiModel.getDefault()
            _selectedModel.value = model
            _currentSession.value = GeminiModelConfig.SessionManager
                .createSession(sessionId, model)
        }
        addOperation("🔷", "Gemini ready", OperationLogType.SUCCESS)

        viewModelScope.launch {
            while (true) {
                kotlinx.coroutines.delay(3600_000)
                try { GeminiModelConfig.SessionManager.cleanupOldSessions() } catch (_: Exception) {}
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // TOGGLES
    // ═══════════════════════════════════════════════════════════════════

    fun toggleOutputMode() {
        _ecoOutputMode.value = !_ecoOutputMode.value
        val tok = if (_ecoOutputMode.value) GeminiModelConfig.ECO_OUTPUT_TOKENS
        else _selectedModel.value.maxOutputTokens
        _generationConfig.value = _generationConfig.value.copy(maxOutputTokens = tok)
        addOperation(
            if (_ecoOutputMode.value) "🟢" else "🔴",
            "Output: ${if (_ecoOutputMode.value) "ECO" else "MAX"} (${"%,d".format(tok)} tok)",
            OperationLogType.INFO
        )
    }

    fun toggleSendTools() {
        _sendToolsEnabled.value = !_sendToolsEnabled.value
        addOperation("🔧", "Tools: ${if (_sendToolsEnabled.value) "ON" else "OFF"}",
            OperationLogType.INFO)
    }

    fun toggleSendSystemPrompt() {
        _sendSystemPromptEnabled.value = !_sendSystemPromptEnabled.value
        addOperation("📋", "System: ${if (_sendSystemPromptEnabled.value) "ON" else "OFF"}",
            OperationLogType.INFO)
    }

    fun toggleConversationHistory() {
        _conversationHistoryEnabled.value = !_conversationHistoryEnabled.value
        addOperation("💬",
            "History: ${if (_conversationHistoryEnabled.value) "ON" else "OFF"}",
            OperationLogType.INFO)
    }

    fun updateGenerationConfig(config: GenerationConfig) {
        _generationConfig.value = config
        addOperation("⚙️",
            "Config: T=${config.temperature} P=${config.topP} K=${config.topK}" +
                    if (config.thinkingLevel != ThinkingLevel.NONE)
                        " Think=${config.thinkingLevel.displayName}" else "",
            OperationLogType.INFO)
    }

    fun selectModel(model: GeminiModel) {
        _selectedModel.value = model
        viewModelScope.launch { appSettings.setGeminiModel(model.modelId) }
        startNewSession()
    }

    // ═══════════════════════════════════════════════════════════════════
    // FILE ATTACHMENT
    // ═══════════════════════════════════════════════════════════════════

    fun attachFile(fileName: String, content: String, sizeBytes: Long) {
        if (sizeBytes > MAX_ATTACHED_FILE_BYTES) {
            addOperation("❌", "File too large: ${sizeBytes / 1024}KB > 2048KB",
                OperationLogType.ERROR)
            return
        }
        if (content.isBlank()) {
            addOperation("❌", "File is empty", OperationLogType.ERROR)
            return
        }
        _attachedFileContent.value = content
        _attachedFileName.value = fileName
        _attachedFileSize.value = sizeBytes
        addOperation("📎",
            "Attached: $fileName (${sizeBytes / 1024}KB, ~${content.length / CHARS_PER_TOKEN_ESTIMATE} tok)",
            OperationLogType.SUCCESS)
    }

    fun detachFile() {
        val name = _attachedFileName.value
        _attachedFileContent.value = null
        _attachedFileName.value = null
        _attachedFileSize.value = 0
        if (name != null) addOperation("📎", "Detached: $name", OperationLogType.INFO)
    }

    // ═══════════════════════════════════════════════════════════════════
    // SESSION
    // ═══════════════════════════════════════════════════════════════════

    fun startNewSession() {
        sendJob?.cancel()
        geminiClient.cancelCurrentRequest()
        viewModelScope.launch {
            _currentSession.value?.let {
                GeminiModelConfig.SessionManager.endSession(it.sessionId)
            }
            val newId = UUID.randomUUID().toString()
            savedStateHandle[KEY_SESSION_ID] = newId
            _sessionId = newId
            _messagesSessionId.value = newId
            _currentSession.value = GeminiModelConfig.SessionManager
                .createSession(newId, _selectedModel.value)
            _chatError.value = null
            _isStreaming.value = false
            _streamingText.value = null
            addOperation("🔄", "New session: ${_selectedModel.value.displayName}",
                OperationLogType.SUCCESS)
        }
    }

    fun getSessionStats(): String? = _currentSession.value?.getDetailedStats()

    suspend fun getChatAsText(): String {
        val all = chatDao.getSession(sessionId)
            .filter { !it.isStreaming && it.content.isNotBlank() }
        if (all.isEmpty()) return ""
        return buildString {
            appendLine("═══ Gemini Chat ═══")
            appendLine("Model: ${_selectedModel.value.displayName}")
            appendLine("═".repeat(30))
            appendLine()
            all.forEach { msg ->
                val role = when (msg.role) {
                    MessageRole.USER -> "👤 You"
                    MessageRole.ASSISTANT -> "🔷 Gemini"
                    MessageRole.SYSTEM -> "⚙️ System"
                }
                appendLine("── $role ──")
                appendLine(msg.content)
                appendLine()
            }
        }.trimEnd()
    }

    // ═══════════════════════════════════════════════════════════════════
    // SEND MESSAGE
    // ═══════════════════════════════════════════════════════════════════

    fun sendMessage(message: String) {
        if (message.isBlank() || _isStreaming.value) return
        sendJob?.cancel()

        sendJob = viewModelScope.launch {
            _isStreaming.value = true
            _chatError.value = null
            _streamingText.value = null

            // ── Validate API key ────────────────────────────────────
            val apiKey = secureSettings.getActiveGeminiApiKey().first()
            if (apiKey.isBlank()) {
                _chatError.value = "Gemini API key not set. Open Settings (Tune icon) to add it."
                _isStreaming.value = false
                return@launch
            }

            val model = _selectedModel.value
            val config = _generationConfig.value

            // ── Build message with attachment ────────────────────────
            val attachedContent = _attachedFileContent.value
            val attachedName = _attachedFileName.value
            val fullMessage = if (attachedContent != null && attachedName != null) {
                "$message\n\n<attached_file name=\"$attachedName\">\n$attachedContent\n</attached_file>"
            } else message

            addOperation("📤", "${model.displayName}: ${message.take(50)}...",
                OperationLogType.PROGRESS)

            // ── Save user message to DB ─────────────────────────────
            chatDao.insert(ChatMessageEntity(
                sessionId = sessionId,
                role = MessageRole.USER,
                content = if (attachedName != null) "$message\n📎 $attachedName (${_attachedFileSize.value / 1024}KB)"
                else message,
                provider = "gemini"
            ))

            // ── Build Gemini messages (with history) ────────────────
            val geminiMessages = mutableListOf<GeminiMessage>()

            if (_conversationHistoryEnabled.value) {
                val history = chatDao.getSession(sessionId)
                    .filter { !it.isStreaming && it.content.isNotBlank() && it.role != MessageRole.SYSTEM }
                for (msg in history) {
                    when (msg.role) {
                        MessageRole.USER -> geminiMessages.add(GeminiMessage.user(msg.content))
                        MessageRole.ASSISTANT -> geminiMessages.add(GeminiMessage.model(msg.content))
                        else -> { /* skip */ }
                    }
                }
            }

            // Current message
            geminiMessages.add(GeminiMessage.user(fullMessage))

            // ── Sanitize messages ───────────────────────────────────
            val sanitizedMessages = sanitizeGeminiMessages(geminiMessages)
            if (sanitizedMessages.isEmpty()) {
                _chatError.value = "No valid messages to send"
                _isStreaming.value = false
                return@launch
            }

            // ── Context overflow protection ─────────────────────────
            val systemPrompt = if (_sendSystemPromptEnabled.value) buildGeminiSystemPrompt() else null
            val tools = if (_sendToolsEnabled.value) toolExecutor.toolDefinitions else null
            val contextProtected = protectContextOverflow(
                sanitizedMessages.toMutableList(), model, config, systemPrompt, tools
            )

            // ── Foreground service ──────────────────────────────────
            StreamingForegroundService.start(appContext, "Gemini ${model.displayName}")

            val startTime = System.currentTimeMillis()
            var fullResponse = ""
            var totalInputTokens = 0
            var totalOutputTokens = 0
            var totalThinkingTokens = 0
            var iteration = 0
            var currentMessages = contextProtected.toMutableList()

            try {
                while (iteration < MAX_TOOL_ITERATIONS) {
                    iteration++
                    var iterationComplete = false

                    geminiClient.streamGenerate(
                        model = model,
                        messages = currentMessages,
                        systemPrompt = systemPrompt,
                        config = config,
                        tools = tools,
                        sendTools = _sendToolsEnabled.value,
                        sendSystemPrompt = _sendSystemPromptEnabled.value,
                        apiKey = apiKey
                    ).collect { result ->
                        when (result) {
                            is GeminiStreamResult.Started -> {
                                _streamingText.value = ""
                            }

                            is GeminiStreamResult.Delta -> {
                                fullResponse = result.accumulated
                                _streamingText.value = fullResponse

                                // Update foreground service
                                val elapsed = ((System.currentTimeMillis() - startTime) / 1000).toInt()
                                val tokens = fullResponse.length / CHARS_PER_TOKEN_ESTIMATE
                                StreamingForegroundService.updateProgress(
                                    appContext,
                                    progressText = "Generating: ~${"%,d".format(tokens)} tokens",
                                    tokens = tokens,
                                    elapsedSec = elapsed
                                )
                            }

                            is GeminiStreamResult.ToolUse -> {
                                result.usage?.let {
                                    totalInputTokens += it.inputTokens
                                    totalOutputTokens += it.outputTokens
                                    totalThinkingTokens += it.thinkingTokens
                                }

                                if (result.textSoFar.isNotBlank()) {
                                    fullResponse = result.textSoFar
                                    _streamingText.value = fullResponse
                                }

                                // Log tool calls
                                for (tc in result.toolCalls) {
                                    addOperation("🔧", "Tool: ${tc.name}",
                                        OperationLogType.PROGRESS)
                                }

                                // Execute tools with timeout
                                val toolResults = withTimeoutOrNull(TOOL_TIMEOUT_MS) {
                                    result.toolCalls.map { tc ->
                                        toolExecutor.execute(
                                            tc.name,
                                            UUID.randomUUID().toString(),
                                            tc.args
                                        )
                                    }
                                } ?: run {
                                    addOperation("⏰", "Tool execution timeout (${TOOL_TIMEOUT_MS / 1000}s)",
                                        OperationLogType.ERROR)
                                    result.toolCalls.map { tc ->
                                        ToolExecutor.ToolResult(
                                            toolUseId = UUID.randomUUID().toString(),
                                            content = "Error: Tool execution timed out after ${TOOL_TIMEOUT_MS / 1000} seconds",
                                            isError = true
                                        )
                                    }
                                }

                                // Log tool results
                                for (i in toolResults.indices) {
                                    val tc = result.toolCalls[i]
                                    val tr = toolResults[i]
                                    val icon = if (tr.isError) "❌" else "✅"
                                    val opInfo = tr.operation?.let {
                                        when (it) {
                                            is ToolExecutor.FileOperation.Created -> "Created: ${it.path}"
                                            is ToolExecutor.FileOperation.Edited -> "Edited: ${it.path}"
                                            is ToolExecutor.FileOperation.Deleted -> "Deleted: ${it.path}"
                                            is ToolExecutor.FileOperation.DirectoryCreated -> "Dir: ${it.path}"
                                        }
                                    } ?: tc.name
                                    addOperation(icon, opInfo,
                                        if (tr.isError) OperationLogType.ERROR else OperationLogType.SUCCESS)
                                }

                                // Build assistant message with function calls
                                val assistantParts = mutableListOf<GeminiPart>()
                                if (result.textSoFar.isNotBlank()) {
                                    assistantParts.add(GeminiPart.Text(result.textSoFar))
                                }
                                result.toolCalls.forEach { tc ->
                                    assistantParts.add(GeminiPart.FunctionCall(tc.name, tc.args, tc.thoughtSignature))
                                }
                                currentMessages.add(GeminiMessage("model", assistantParts))

                                // Build function responses
                                val responseParts = toolResults.mapIndexed { i, tr ->
                                    GeminiPart.FunctionResponse(
                                        name = result.toolCalls[i].name,
                                        response = buildJsonObject {
                                            put("result", JsonPrimitive(tr.content))
                                            if (tr.isError) put("error", JsonPrimitive(true))
                                        }
                                    )
                                }
                                currentMessages.add(GeminiMessage("user", responseParts))
                            }

                            is GeminiStreamResult.Completed -> {
                                fullResponse = result.fullText
                                result.usage?.let {
                                    totalInputTokens += it.inputTokens
                                    totalOutputTokens += it.outputTokens
                                    totalThinkingTokens += it.thinkingTokens
                                }

                                _currentSession.value?.addMessage(
                                    totalInputTokens, totalOutputTokens, totalThinkingTokens
                                )

                                chatDao.insert(ChatMessageEntity(
                                    sessionId = sessionId,
                                    role = MessageRole.ASSISTANT,
                                    content = fullResponse,
                                    isStreaming = false,
                                    provider = "gemini"
                                ))

                                _streamingText.value = null
                                _isStreaming.value = false

                                val cost = model.calculateCost(
                                    totalInputTokens, totalOutputTokens, totalThinkingTokens
                                )
                                val toolInfo = if (iteration > 1) " (${iteration} iter)" else ""
                                addOperation("✅",
                                    "${"%,d".format(cost.totalTokens)} tok, €${String.format("%.4f", cost.totalCostEUR)}$toolInfo",
                                    OperationLogType.SUCCESS)

                                if (_attachedFileContent.value != null) detachFile()
                                iterationComplete = true
                            }

                            is GeminiStreamResult.Error -> {
                                _isStreaming.value = false
                                _streamingText.value = null
                                _chatError.value = result.exception.message
                                addOperation("❌", result.exception.message ?: "Error",
                                    OperationLogType.ERROR)
                                iterationComplete = true
                            }
                        }
                    }
                    if (iterationComplete) break
                }

                // Tool loop limit reached
                if (iteration >= MAX_TOOL_ITERATIONS && fullResponse.isNotBlank()) {
                    val cost = model.calculateCost(totalInputTokens, totalOutputTokens, totalThinkingTokens)
                    chatDao.insert(ChatMessageEntity(
                        sessionId = sessionId, role = MessageRole.ASSISTANT,
                        content = fullResponse + "\n\n⚠️ Tool loop limit reached.",
                        isStreaming = false, provider = "gemini"
                    ))
                    _currentSession.value?.addMessage(totalInputTokens, totalOutputTokens, totalThinkingTokens)
                    _streamingText.value = null
                    _isStreaming.value = false
                    addOperation("⚠️", "Tool loop limit (${MAX_TOOL_ITERATIONS})", OperationLogType.ERROR)
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
                StreamingForegroundService.stop(appContext)
            }
        }
    }

    fun cancelStreaming() {
        sendJob?.cancel()
        sendJob = null
        geminiClient.cancelCurrentRequest()
        _isStreaming.value = false
        _streamingText.value = null
        StreamingForegroundService.stop(appContext)
        addOperation("🛑", "Streaming cancelled", OperationLogType.INFO)
    }

    fun dismissError() { _chatError.value = null }

    // ═══════════════════════════════════════════════════════════════════
    // MESSAGE SANITIZATION
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Gemini requires strict user/model alternation.
     * Merges consecutive same-role messages, ensures first=user, last=user.
     */
    private fun sanitizeGeminiMessages(
        messages: List<GeminiMessage>
    ): List<GeminiMessage> {
        if (messages.isEmpty()) return emptyList()

        val result = mutableListOf<GeminiMessage>()
        for (msg in messages) {
            if (result.isNotEmpty() && result.last().role == msg.role) {
                // Merge consecutive same-role messages
                val last = result.removeAt(result.lastIndex)
                val mergedParts = last.parts + msg.parts
                result.add(GeminiMessage(msg.role, mergedParts))
            } else {
                result.add(msg)
            }
        }

        // First message must be "user"
        while (result.isNotEmpty() && result.first().role != "user") {
            result.removeAt(0)
        }

        // Last message must be "user" (for generation)
        while (result.isNotEmpty() && result.last().role != "user") {
            result.removeAt(result.lastIndex)
        }

        return result
    }

    // ═══════════════════════════════════════════════════════════════════
    // CONTEXT OVERFLOW PROTECTION
    // ═══════════════════════════════════════════════════════════════════

    /**
     * Rough estimation: 1 token ≈ 4 characters.
     * Trims oldest message pairs if context exceeds model limit.
     */
    private fun protectContextOverflow(
        messages: MutableList<GeminiMessage>,
        model: GeminiModel,
        config: GenerationConfig,
        systemPrompt: String?,
        tools: List<JsonObject>?
    ): List<GeminiMessage> {
        val maxInput = model.contextWindow - config.maxOutputTokens
        val overheadTokens = (systemPrompt?.length?.div(CHARS_PER_TOKEN_ESTIMATE) ?: 0) +
                (if (tools != null) 2000 else 0) // rough tools overhead

        var estimatedTokens = overheadTokens + messages.sumOf { msg ->
            msg.parts.sumOf { part ->
                when (part) {
                    is GeminiPart.Text -> part.text.length / CHARS_PER_TOKEN_ESTIMATE
                    is GeminiPart.InlineData -> 258
                    is GeminiPart.FunctionCall -> 200
                    is GeminiPart.FunctionResponse -> part.response.toString().length / CHARS_PER_TOKEN_ESTIMATE
                }
            }
        }

        if (estimatedTokens > maxInput && messages.size > 2) {
            addOperation("⚠️",
                "Context overflow (~${"%,d".format(estimatedTokens)} > ${"%,d".format(maxInput)}). Trimming history.",
                OperationLogType.PROGRESS)

            // Remove oldest messages in pairs (user+model), keep at least last user message
            while (messages.size > 2 && estimatedTokens > (maxInput * 0.85).toInt()) {
                val removed = messages.removeAt(0)
                estimatedTokens -= removed.parts.sumOf { part ->
                    when (part) {
                        is GeminiPart.Text -> part.text.length / CHARS_PER_TOKEN_ESTIMATE
                        else -> 200
                    }
                }
            }

            // Re-sanitize after trimming
            return sanitizeGeminiMessages(messages)
        }

        return messages
    }

    // ═══════════════════════════════════════════════════════════════════
    // OPS LOG
    // ═══════════════════════════════════════════════════════════════════

    fun addOperation(
        icon: String,
        message: String,
        type: OperationLogType = OperationLogType.INFO
    ) {
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
    // SYSTEM PROMPT
    // ═══════════════════════════════════════════════════════════════════

    private fun buildGeminiSystemPrompt(): String = """
You are an expert Android/Kotlin developer assistant connected to a GitHub repository.

You have tools to interact with the repository. Use them when needed:
- list_files: See project structure (instant, from local index)
- read_files: Read file contents
- search_in_files: Find files by name
- create_file: Create new files with commit
- edit_file: Replace file content with commit
- delete_file: Remove files with commit
- create_directory: Create folders

RULES:
1. For simple questions/chat — just respond, NO tools needed
2. When user asks about project structure — use list_files
3. When you need to see code — use read_files (list_files first to verify paths)
4. For code changes — ALWAYS read the file first, then edit
5. Respond in the same language as the user
6. Write complete file content when creating/editing (no partial edits)
    """.trimIndent()

    // ═══════════════════════════════════════════════════════════════════
    // LIFECYCLE
    // ═══════════════════════════════════════════════════════════════════

    override fun onCleared() {
        super.onCleared()
        sendJob?.cancel()
        geminiClient.cancelCurrentRequest()
        _currentSession.value?.let {
            if (it.isActive) GeminiModelConfig.SessionManager.endSession(it.sessionId)
        }
        StreamingForegroundService.stop(appContext)
    }
}
