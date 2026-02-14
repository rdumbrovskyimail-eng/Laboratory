package com.opuside.app.core.ai

import android.util.Log
import com.opuside.app.core.data.AppSettings
import com.opuside.app.core.database.dao.ChatDao
import com.opuside.app.core.database.entity.ChatMessageEntity
import com.opuside.app.core.database.entity.MessageRole
import com.opuside.app.core.network.anthropic.ClaudeApiClient
import com.opuside.app.core.network.anthropic.ResilientStreamingClient
import com.opuside.app.core.network.anthropic.StreamingResult
import com.opuside.app.core.network.anthropic.ToolCall
import com.opuside.app.core.network.anthropic.model.ClaudeMessage
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.*
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 🤖 REPOSITORY ANALYZER v14.0 (RESILIENT STREAMING + EXTENDED THINKING)
 *
 * ✅ v14.0 CHANGES:
 * 1. Интеграция ResilientStreamingClient для автоматического retry при обрывах сети
 * 2. Новые AnalysisResult типы: WaitingForNetwork, Retrying
 * 3. Поддержка totalRetries в Completed
 * 4. Все предыдущие фичи v13.0 сохранены
 * 5. ✅ ИСПРАВЛЕНИЕ #1: thinkingBudget снижен с 40000 до 16000 для стабильности стриминга
 */
@Singleton
class RepositoryAnalyzer @Inject constructor(
    private val claudeClient: ClaudeApiClient,
    private val resilientClient: ResilientStreamingClient,  // ★ NEW
    private val repoIndexManager: RepoIndexManager,
    private val toolExecutor: ToolExecutor,
    private val chatDao: ChatDao,
    private val appSettings: AppSettings
) {
    companion object {
        private const val TAG = "RepositoryAnalyzer"
        private const val MAX_TOOL_ITERATIONS = 8
        private const val SESSION_CLEANUP_THRESHOLD_DAYS = 1L
        private val READ_ONLY_TOOLS = setOf("list_files", "read_files", "search_in_files")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SESSION MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════════

    private val sessionManager = ClaudeModelConfig.SessionManager

    init { Log.i(TAG, "RepositoryAnalyzer v14.0 initialized (Resilient Streaming + Extended Thinking)") }

    fun createSession(sessionId: String, model: ClaudeModelConfig.ClaudeModel): ClaudeModelConfig.ChatSession {
        require(sessionId.isNotBlank()) { "Session ID cannot be blank" }
        return sessionManager.createSession(sessionId, model)
    }

    fun getSession(sessionId: String): ClaudeModelConfig.ChatSession? {
        require(sessionId.isNotBlank()) { "Session ID cannot be blank" }
        return sessionManager.getSession(sessionId)
    }

    fun endSession(sessionId: String): ClaudeModelConfig.ChatSession? {
        require(sessionId.isNotBlank()) { "Session ID cannot be blank" }
        return sessionManager.endSession(sessionId)
    }

    fun shouldStartNewSession(sessionId: String): Boolean =
        sessionManager.shouldStartNewSession(sessionId)

    suspend fun cleanupOldSessions(): Int =
        sessionManager.cleanupOldSessions(Duration.ofDays(SESSION_CLEANUP_THRESHOLD_DAYS))

    fun getActiveSessions(): List<ClaudeModelConfig.ChatSession> =
        sessionManager.getAllActiveSessions()
    
    /**
     * ✅ DUMMY: Кеш управляется внутри ClaudeApiClient, не здесь
     */
    fun clearCacheForSession(sessionId: String) {
        Log.i(TAG, "📦 Cache clearing delegated to API client for session: $sessionId")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MAIN ENTRY POINT
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Основная функция анализа файлов репозитория.
     *
     * ✅ ИСПРАВЛЕНИЕ #1: thinkingBudget = 16000 (было 40000)
     * 
     * ПОЧЕМУ 16000 вместо 40000:
     * Из официальной документации Anthropic:
     * "For thinking budgets above 32K, we recommend using batch processing to avoid 
     *  networking issues. Requests pushing the model to think above 32k tokens causes 
     *  long running requests that might run up against system timeouts and open 
     *  connection limits."
     *
     * 40K > 32K = гарантированные таймауты и обрывы соединения
     * 16K < 32K = безопасно для streaming
     */
    suspend fun scanFilesV2(
        sessionId: String,
        filePaths: List<String>,
        userQuery: String,
        conversationHistory: List<ChatMessageEntity>,
        model: ClaudeModelConfig.ClaudeModel,
        enableCaching: Boolean = false,
        maxTokens: Int = 8192,
        enableThinking: Boolean = false,
        thinkingBudget: Int = 16000,               // ✅ ИСПРАВЛЕНИЕ #1: было 40000, стало 16000
        sendTools: Boolean = true,
        sendSystemPrompt: Boolean = true
    ): Flow<AnalysisResult> = flow {
        try {
            require(sessionId.isNotBlank()) { "Session ID cannot be blank" }
            require(userQuery.isNotBlank()) { "User query cannot be blank" }

            val session = getSession(sessionId) ?: createSession(sessionId, model)
            if (session.model != model) {
                emit(AnalysisResult.Error("Model mismatch. Please start a new session."))
                return@flow
            }

            // ═══════════════════════════════════════════════════════════════
            // STEP 1: System prompt + tools (мгновенно)
            // ═══════════════════════════════════════════════════════════════
            val systemPrompt = buildMinimalSystemPrompt()
            val tools = toolExecutor.toolDefinitions

            // ═══════════════════════════════════════════════════════════════
            // STEP 2: Build messages (история добавляется но НЕ влияет на кеш)
            // ═══════════════════════════════════════════════════════════════
            val claudeMessages = mutableListOf<ClaudeMessage>()
            
            // Добавляем историю (если включена) - НЕ кешируется
            for (msg in conversationHistory) {
                val role = when (msg.role) {
                    MessageRole.USER -> "user"
                    MessageRole.ASSISTANT -> "assistant"
                    else -> continue
                }
                if (msg.content.isBlank()) continue
                claudeMessages.add(ClaudeMessage(role, msg.content))
            }

            // Текущий запрос - БУДЕТ кешироваться в Cache Mode
            val enrichedQuery = if (filePaths.isNotEmpty()) {
                "$userQuery\n\n[User has selected these files: ${filePaths.joinToString(", ")}]"
            } else {
                userQuery
            }
            claudeMessages.add(ClaudeMessage("user", enrichedQuery))

            val sanitizedMessages = sanitizeMessageOrder(claudeMessages)
            if (sanitizedMessages.isEmpty()) {
                emit(AnalysisResult.Error("No messages to send"))
                return@flow
            }

            // ═══════════════════════════════════════════════════════════════
            // STEP 3: TOOL LOOP (с ResilientStreamingClient)
            // ═══════════════════════════════════════════════════════════════
            var currentMessages = sanitizedMessages.toMutableList()
            var fullResponseText = ""
            var totalInputTokens = 0
            var totalOutputTokens = 0
            var totalCachedReadTokens = 0
            var totalCachedWriteTokens = 0
            var totalRetries = 0  // ★ NEW
            var iteration = 0
            var streamingStartedEmitted = false

            while (iteration < MAX_TOOL_ITERATIONS) {
                iteration++

                var iterationComplete = false

                // ★ CHANGED: используем resilientClient вместо claudeClient
                resilientClient.streamWithRetry(
                    model = model.modelId,
                    messages = currentMessages,
                    systemPrompt = systemPrompt,
                    maxTokens = maxTokens,
                    enableCaching = enableCaching,
                    tools = tools,
                    enableThinking = enableThinking,
                    thinkingBudget = thinkingBudget,
                    sendTools = sendTools,
                    sendSystemPrompt = sendSystemPrompt
                ).collect { result ->
                    when (result) {
                        is ResilientStreamingClient.ResilientResult.Started -> {
                            if (!streamingStartedEmitted) {
                                streamingStartedEmitted = true
                                emit(AnalysisResult.StreamingStarted)
                            }
                            if (result.isRetry) {
                                Log.i(TAG, "Stream resumed after retry")
                            }
                        }

                        is ResilientStreamingClient.ResilientResult.Delta -> {
                            fullResponseText = result.accumulated
                            if (!streamingStartedEmitted) {
                                streamingStartedEmitted = true
                                emit(AnalysisResult.StreamingStarted)
                            }
                            emit(AnalysisResult.Streaming(result.accumulated))
                        }

                        // ★ NEW: Обработка retry-событий
                        is ResilientStreamingClient.ResilientResult.WaitingForNetwork -> {
                            emit(AnalysisResult.WaitingForNetwork(
                                result.attempt,
                                result.maxAttempts,
                                result.accumulatedText,
                                result.accumulatedTokens
                            ))
                        }

                        is ResilientStreamingClient.ResilientResult.Retrying -> {
                            emit(AnalysisResult.Retrying(
                                result.attempt,
                                result.maxAttempts,
                                result.backoffMs
                            ))
                        }

                        is ResilientStreamingClient.ResilientResult.ToolUse -> {
                            result.usage?.let { usage ->
                                totalInputTokens += usage.inputTokens
                                totalOutputTokens += usage.outputTokens
                                totalCachedReadTokens += usage.cacheReadInputTokens ?: 0
                                totalCachedWriteTokens += usage.cacheCreationInputTokens ?: 0
                            }

                            if (result.textSoFar.isNotBlank()) {
                                fullResponseText = result.textSoFar
                                emit(AnalysisResult.Streaming(result.textSoFar))
                            }

                            for (tc in result.toolCalls) {
                                emit(AnalysisResult.ToolCallStarted(tc.name, tc.input.toString().take(100)))
                            }

                            val toolResults = executeToolsOptimal(result.toolCalls)

                            for (i in toolResults.indices) {
                                val tc = result.toolCalls[i]
                                val tr = toolResults[i]
                                emit(AnalysisResult.ToolCallCompleted(tc.name, tr.isError, tr.operation))
                            }

                            val assistantContent = buildAssistantToolUseContent(result.textSoFar, result.toolCalls)
                            currentMessages.add(ClaudeMessage("assistant", assistantContent, isJsonContent = true))

                            val toolResultContent = buildToolResultContent(toolResults)
                            currentMessages.add(ClaudeMessage("user", toolResultContent, isJsonContent = true))
                        }

                        is ResilientStreamingClient.ResilientResult.Completed -> {
                            fullResponseText = result.fullText
                            totalRetries = result.totalRetries  // ★ NEW
                            
                            result.usage?.let { usage ->
                                totalInputTokens += usage.inputTokens
                                totalOutputTokens += usage.outputTokens
                                totalCachedReadTokens += usage.cacheReadInputTokens ?: 0
                                totalCachedWriteTokens += usage.cacheCreationInputTokens ?: 0
                            }

                            session.addMessage(totalInputTokens, totalOutputTokens,
                                totalCachedReadTokens, totalCachedWriteTokens)

                            val cost = model.calculateCost(
                                inputTokens = totalInputTokens,
                                outputTokens = totalOutputTokens,
                                cachedReadTokens = totalCachedReadTokens,
                                cachedWriteTokens = totalCachedWriteTokens
                            )

                            emit(AnalysisResult.Completed(
                                text = fullResponseText,
                                cost = cost,
                                session = session,
                                toolIterations = iteration,
                                totalRetries = totalRetries  // ★ NEW
                            ))
                            iterationComplete = true
                        }

                        is ResilientStreamingClient.ResilientResult.Error -> {
                            emit(AnalysisResult.Error(result.exception.message ?: "Unknown error"))
                            iterationComplete = true
                        }
                    }
                }

                if (iterationComplete) break
            }

            if (iteration >= MAX_TOOL_ITERATIONS && !fullResponseText.isBlank()) {
                val cost = model.calculateCost(totalInputTokens, totalOutputTokens,
                    totalCachedReadTokens, totalCachedWriteTokens)
                emit(AnalysisResult.Completed(
                    text = fullResponseText + "\n\n⚠️ Tool loop limit reached.",
                    cost = cost, 
                    session = session, 
                    toolIterations = iteration,
                    totalRetries = totalRetries  // ★ NEW
                ))
            } else if (iteration >= MAX_TOOL_ITERATIONS) {
                emit(AnalysisResult.Error("Tool loop limit reached ($MAX_TOOL_ITERATIONS)"))
            }

        } catch (e: IllegalArgumentException) {
            emit(AnalysisResult.Error("Invalid input: ${e.message}"))
        } catch (e: Exception) {
            Log.e(TAG, "Scan failed", e)
            emit(AnalysisResult.Error(e.message ?: "Unknown error"))
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PARALLEL TOOL EXECUTION
    // ═══════════════════════════════════════════════════════════════════════════

    private suspend fun executeToolsOptimal(toolCalls: List<ToolCall>): List<ToolExecutor.ToolResult> {
        val allReadOnly = toolCalls.all { it.name in READ_ONLY_TOOLS }

        return if (allReadOnly && toolCalls.size > 1) {
            coroutineScope {
                toolCalls.map { tc ->
                    async { toolExecutor.execute(tc.name, tc.id, tc.input) }
                }.awaitAll()
            }
        } else {
            toolCalls.map { tc -> toolExecutor.execute(tc.name, tc.id, tc.input) }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // JSON BUILDERS
    // ═══════════════════════════════════════════════════════════════════════════

    private fun buildAssistantToolUseContent(textBefore: String, toolCalls: List<ToolCall>): String {
        val blocks = buildList {
            if (textBefore.isNotBlank()) {
                add(buildJsonObject {
                    put("type", "text")
                    put("text", textBefore)
                })
            }
            for (tc in toolCalls) {
                add(buildJsonObject {
                    put("type", "tool_use")
                    put("id", tc.id)
                    put("name", tc.name)
                    put("input", tc.input)
                })
            }
        }
        return Json.encodeToString(JsonArray(blocks))
    }

    private fun buildToolResultContent(toolResults: List<ToolExecutor.ToolResult>): String {
        val blocks = toolResults.map { result ->
            buildJsonObject {
                put("type", "tool_result")
                put("tool_use_id", result.toolUseId)
                put("content", result.content)
                if (result.isError) put("is_error", true)
            }
        }
        return Json.encodeToString(JsonArray(blocks))
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SYSTEM PROMPT
    // ═══════════════════════════════════════════════════════════════════════════

    private fun buildMinimalSystemPrompt(): String = """
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

    // ═══════════════════════════════════════════════════════════════════════════
    // MESSAGE SANITIZATION
    // ═══════════════════════════════════════════════════════════════════════════

    private fun sanitizeMessageOrder(messages: List<ClaudeMessage>): List<ClaudeMessage> {
        if (messages.isEmpty()) return emptyList()

        val result = mutableListOf<ClaudeMessage>()
        for (msg in messages) {
            if (result.isNotEmpty() && result.last().role == msg.role && !msg.isJsonContent) {
                val last = result.removeAt(result.lastIndex)
                result.add(ClaudeMessage(msg.role, last.content + "\n\n" + msg.content))
            } else {
                result.add(msg)
            }
        }

        while (result.isNotEmpty() && result.first().role != "user") {
            result.removeAt(0)
        }

        while (result.isNotEmpty() && result.last().role != "user") {
            result.removeAt(result.lastIndex)
        }

        return result
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ANALYSIS RESULT
    // ═══════════════════════════════════════════════════════════════════════════

    sealed class AnalysisResult {
        data class Loading(val message: String) : AnalysisResult()
        data object StreamingStarted : AnalysisResult()
        data class Streaming(val text: String) : AnalysisResult()
        data class ToolCallStarted(val toolName: String, val inputPreview: String) : AnalysisResult()
        data class ToolCallCompleted(
            val toolName: String,
            val isError: Boolean,
            val operation: ToolExecutor.FileOperation?
        ) : AnalysisResult()
        
        // ★ NEW: Retry events
        data class WaitingForNetwork(
            val attempt: Int,
            val maxAttempts: Int,
            val accumulatedText: String,
            val accumulatedTokens: Int
        ) : AnalysisResult()

        data class Retrying(
            val attempt: Int,
            val maxAttempts: Int,
            val backoffMs: Long
        ) : AnalysisResult()

        data class Completed(
            val text: String,
            val cost: ClaudeModelConfig.ModelCost,
            val session: ClaudeModelConfig.ChatSession,
            val toolIterations: Int = 1,
            val totalRetries: Int = 0  // ★ NEW
        ) : AnalysisResult()
        
        data class Error(val message: String) : AnalysisResult()
    }
}