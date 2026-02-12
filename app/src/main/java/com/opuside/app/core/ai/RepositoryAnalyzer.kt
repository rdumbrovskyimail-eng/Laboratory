package com.opuside.app.core.ai

import android.util.Log
import com.opuside.app.core.data.AppSettings
import com.opuside.app.core.database.dao.ChatDao
import com.opuside.app.core.database.entity.ChatMessageEntity
import com.opuside.app.core.database.entity.MessageRole
import com.opuside.app.core.network.anthropic.ClaudeApiClient
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
 * 🤖 REPOSITORY ANALYZER v10.0 (PROMPT CACHING 100% COMPLIANT)
 *
 * ИСПРАВЛЕНИЯ ПО ОФИЦИАЛЬНОЙ ДОКУМЕНТАЦИИ ANTHROPIC:
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * 1. Cache создается ОДИН РАЗ при первом запросе в session
 * 2. Cache НЕ перезаписывается при последующих запросах
 * 3. System + Tools кешируются (НЕ messages!)
 * 4. Таймер сбрасывается при каждом cache hit (бесплатно)
 * 5. Минимум 1024 токена для кеширования
 */
@Singleton
class RepositoryAnalyzer @Inject constructor(
    private val claudeClient: ClaudeApiClient,
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
    // CACHE STORAGE (хранит system + tools для сессии)
    // ═══════════════════════════════════════════════════════════════════════════
    
    private data class CachedContext(
        val systemPrompt: String,
        val tools: List<JsonObject>,
        val createdAt: Long = System.currentTimeMillis()
    )
    
    private val sessionCacheMap = mutableMapOf<String, CachedContext>()

    // ═══════════════════════════════════════════════════════════════════════════
    // SESSION MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════════

    private val sessionManager = ClaudeModelConfig.SessionManager

    init { Log.i(TAG, "RepositoryAnalyzer v10.0 initialized (Prompt Caching Compliant)") }

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
     * Очищает кеш для сессии (вызывается при startNewSession)
     */
    fun clearCacheForSession(sessionId: String) {
        sessionCacheMap.remove(sessionId)
        Log.i(TAG, "📦 Cache cleared for session: $sessionId")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MAIN ENTRY POINT — PROMPT CACHING 100% COMPLIANT
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * ═══════════════════════════════════════════════════════════════════════
     * ПРАВИЛЬНОЕ КЕШИРОВАНИЕ (по документации Anthropic):
     * ═══════════════════════════════════════════════════════════════════════
     *
     * 1. Первый запрос: создаем cache (system + tools)
     * 2. Последующие запросы: используем СУЩЕСТВУЮЩИЙ cache
     * 3. История НЕ влияет на кеш (кешируются только system + tools)
     * 4. Cache refresh бесплатный при каждом hit
     * 5. Минимум 1024 токена для кеша (проверяется автоматически API)
     */
    suspend fun scanFilesV2(
        sessionId: String,
        filePaths: List<String>,
        userQuery: String,
        conversationHistory: List<ChatMessageEntity>,
        model: ClaudeModelConfig.ClaudeModel,
        enableCaching: Boolean = false,
        maxTokens: Int = 8192
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
            // STEP 1: System prompt + tools (МГНОВЕННО — lazy cached)
            // ═══════════════════════════════════════════════════════════════
            val systemPrompt = buildMinimalSystemPrompt()
            val tools = toolExecutor.toolDefinitions  // lazy — allocated once

            // ═══════════════════════════════════════════════════════════════
            // CACHE LOGIC: создаем ОДИН РАЗ, потом только используем
            // ═══════════════════════════════════════════════════════════════
            if (enableCaching && sessionCacheMap[sessionId] == null) {
                // Первый запрос — создаем кеш
                sessionCacheMap[sessionId] = CachedContext(
                    systemPrompt = systemPrompt,
                    tools = tools
                )
                Log.i(TAG, "📦 Cache CREATED for session: $sessionId")
            }
            
            // Используем кеш (если есть) или обычные данные
            val cachedContext = sessionCacheMap[sessionId]
            val effectiveSystemPrompt = cachedContext?.systemPrompt ?: systemPrompt
            val effectiveTools = cachedContext?.tools ?: tools
            
            if (cachedContext != null) {
                Log.i(TAG, "📦 Cache HIT for session: $sessionId (age: ${(System.currentTimeMillis() - cachedContext.createdAt) / 1000}s)")
            }

            // ═══════════════════════════════════════════════════════════════
            // STEP 2: Build messages
            // ═══════════════════════════════════════════════════════════════
            val claudeMessages = mutableListOf<ClaudeMessage>()
            
            // Добавляем историю (если включена)
            for (msg in conversationHistory) {
                val role = when (msg.role) {
                    MessageRole.USER -> "user"
                    MessageRole.ASSISTANT -> "assistant"
                    else -> continue
                }
                if (msg.content.isBlank()) continue
                claudeMessages.add(ClaudeMessage(role, msg.content))
            }

            // Текущий запрос
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
            // STEP 3: TOOL LOOP
            // ═══════════════════════════════════════════════════════════════
            var currentMessages = sanitizedMessages.toMutableList()
            var fullResponseText = ""
            var totalInputTokens = 0
            var totalOutputTokens = 0
            var totalCachedReadTokens = 0
            var totalCachedWriteTokens = 0
            var iteration = 0
            var streamingStartedEmitted = false

            while (iteration < MAX_TOOL_ITERATIONS) {
                iteration++

                var iterationComplete = false

                claudeClient.streamMessage(
                    model = model.modelId,
                    messages = currentMessages,
                    systemPrompt = effectiveSystemPrompt,
                    maxTokens = maxTokens,
                    enableCaching = enableCaching,
                    tools = effectiveTools
                ).collect { result ->
                    when (result) {
                        is StreamingResult.Started -> {
                            if (!streamingStartedEmitted) {
                                streamingStartedEmitted = true
                                emit(AnalysisResult.StreamingStarted)
                            }
                        }

                        is StreamingResult.Delta -> {
                            fullResponseText = result.accumulated
                            if (!streamingStartedEmitted) {
                                streamingStartedEmitted = true
                                emit(AnalysisResult.StreamingStarted)
                            }
                            emit(AnalysisResult.Streaming(result.accumulated))
                        }

                        is StreamingResult.ToolUse -> {
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

                        is StreamingResult.Completed -> {
                            fullResponseText = result.fullText
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
                                toolIterations = iteration
                            ))
                            iterationComplete = true
                        }

                        is StreamingResult.Error -> {
                            emit(AnalysisResult.Error(result.exception.message ?: "Unknown error"))
                            iterationComplete = true
                        }

                        else -> {}
                    }
                }

                if (iterationComplete) break
            }

            if (iteration >= MAX_TOOL_ITERATIONS && !fullResponseText.isBlank()) {
                val cost = model.calculateCost(totalInputTokens, totalOutputTokens,
                    totalCachedReadTokens, totalCachedWriteTokens)
                emit(AnalysisResult.Completed(
                    text = fullResponseText + "\n\n⚠️ Tool loop limit reached.",
                    cost = cost, session = session, toolIterations = iteration
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
    // PARALLEL TOOL EXECUTION (без изменений)
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
    // JSON BUILDERS (без изменений)
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
    // SYSTEM PROMPT (без изменений)
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
    // MESSAGE SANITIZATION (без изменений)
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
    // ANALYSIS RESULT (без изменений)
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
        data class Completed(
            val text: String,
            val cost: ClaudeModelConfig.ModelCost,
            val session: ClaudeModelConfig.ChatSession,
            val toolIterations: Int = 1
        ) : AnalysisResult()
        data class Error(val message: String) : AnalysisResult()
    }
}