package com.opuside.app.core.ai

import android.util.Log
import com.opuside.app.core.data.AppSettings
import com.opuside.app.core.database.dao.ChatDao
import com.opuside.app.core.database.entity.ChatMessageEntity
import com.opuside.app.core.database.entity.MessageRole
import com.opuside.app.core.network.anthropic.ClaudeApiClient
import com.opuside.app.core.network.anthropic.StreamingResult
import com.opuside.app.core.network.anthropic.model.ClaudeMessage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton
import com.opuside.app.core.network.github.GitHubApiClient

/**
 * 🤖 REPOSITORY ANALYZER v5.0 (3 CRITICAL FIXES)
 *
 * ✅ FIX 1: scanFilesV2() does NOT insert messages into ChatDao
 *    - ViewModel is the SINGLE source of truth for DB writes
 *    - No more message duplication
 *
 * ✅ FIX 2: scanFilesV2() accepts full conversation history
 *    - All prior messages are sent to Claude API
 *    - Claude remembers user's name, previous context, etc.
 *    - Cache works because the system prompt + history prefix stays identical
 *
 * ✅ FIX 3: Streaming events flow directly to ViewModel
 *    - ViewModel stores streaming text in MutableStateFlow
 *    - UI reads it instantly (no Room DB roundtrip)
 */
@Singleton
class RepositoryAnalyzer @Inject constructor(
    private val claudeClient: ClaudeApiClient,
    private val gitHubClient: GitHubApiClient,
    private val chatDao: ChatDao,
    private val appSettings: AppSettings
) {

    companion object {
        private const val TAG = "RepositoryAnalyzer"
        private const val MAX_FILES_PER_SCAN = 50
        private const val MAX_FILE_SIZE_BYTES = 100_000
        private const val SESSION_CLEANUP_THRESHOLD_DAYS = 1L
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SESSION MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════════

    private val sessionManager = ClaudeModelConfig.SessionManager

    init { Log.i(TAG, "RepositoryAnalyzer initialized") }

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

    // ═══════════════════════════════════════════════════════════════════════════
    // REPOSITORY STRUCTURE
    // ═══════════════════════════════════════════════════════════════════════════

    suspend fun getRepositoryStructure(
        path: String = "",
        recursive: Boolean = false
    ): Result<RepositoryStructure> {
        return try {
            val contents = gitHubClient.getContent(path).getOrThrow()
            val structure = RepositoryStructure(
                path = path,
                files = contents.filter { it.type == "file" }.map {
                    FileMetadata(it.name, it.path, it.size, it.name.substringAfterLast('.', ""), it.sha)
                },
                directories = contents.filter { it.type == "dir" }.map {
                    DirectoryMetadata(it.name, it.path)
                }
            )
            Result.success(structure)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get repository structure", e)
            Result.failure(e)
        }
    }

    data class RepositoryStructure(
        val path: String,
        val files: List<FileMetadata>,
        val directories: List<DirectoryMetadata>
    ) {
        val totalFiles: Int get() = files.size
        val totalSize: Int get() = files.sumOf { it.size }
    }

    data class FileMetadata(val name: String, val path: String, val size: Int, val extension: String, val sha: String)
    data class DirectoryMetadata(val name: String, val path: String)

    // ═══════════════════════════════════════════════════════════════════════════
    // SCAN COST ESTIMATION
    // ═══════════════════════════════════════════════════════════════════════════

    suspend fun estimateScanCost(
        filePaths: List<String>,
        model: ClaudeModelConfig.ClaudeModel,
        sessionId: String? = null
    ): Result<ScanEstimate> {
        if (filePaths.isEmpty()) return Result.failure(IllegalArgumentException("No files selected"))
        if (filePaths.size > MAX_FILES_PER_SCAN) return Result.failure(
            IllegalArgumentException("Too many files: ${filePaths.size} > $MAX_FILES_PER_SCAN"))

        return try {
            val files = filePaths.mapNotNull { gitHubClient.getFileContent(it).getOrNull() }
            if (files.isEmpty()) return Result.failure(IllegalStateException("No files could be loaded"))

            val totalSize = files.sumOf { it.size }
            val oversizedFiles = files.filter { it.size > MAX_FILE_SIZE_BYTES }
            val estimatedInputTokens = (totalSize / 4.0).toInt()
            val estimatedOutputTokens = estimatedInputTokens / 2

            val session = sessionId?.let { getSession(it) }
            val currentSessionTokens = session?.totalInputTokens ?: 0
            val projectedTotalTokens = currentSessionTokens + estimatedInputTokens

            val cost = model.calculateCost(
                inputTokens = estimatedInputTokens,
                outputTokens = estimatedOutputTokens
            )

            Result.success(ScanEstimate(
                fileCount = files.size,
                totalSizeBytes = totalSize,
                oversizedFiles = oversizedFiles.map { it.path },
                cost = cost,
                currentSessionTokens = currentSessionTokens,
                projectedTotalTokens = projectedTotalTokens,
                willTriggerLongContext = projectedTotalTokens > model.longContextThreshold,
                isApproachingLongContext = projectedTotalTokens > (model.longContextThreshold * 0.8)
            ))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to estimate scan cost", e)
            Result.failure(e)
        }
    }

    data class ScanEstimate(
        val fileCount: Int,
        val totalSizeBytes: Int,
        val oversizedFiles: List<String>,
        val cost: ClaudeModelConfig.ModelCost,
        val currentSessionTokens: Int,
        val projectedTotalTokens: Int,
        val willTriggerLongContext: Boolean,
        val isApproachingLongContext: Boolean
    ) {
        val canProceed: Boolean get() = oversizedFiles.isEmpty()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ✅ NEW: scanFilesV2 — NO DB WRITES, WITH CONVERSATION HISTORY
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * V2: Does NOT write to ChatDao. ViewModel handles all DB operations.
     * Accepts full conversation history so Claude remembers context.
     *
     * @param conversationHistory All previous messages from the session (USER + ASSISTANT)
     *                            The current userQuery is ALREADY included as the last USER message.
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

            Log.i(TAG, "ScanV2: session=$sessionId, files=${filePaths.size}, " +
                    "model=${model.displayName}, caching=$enableCaching, maxTokens=$maxTokens, " +
                    "history=${conversationHistory.size} msgs")

            val session = getSession(sessionId) ?: createSession(sessionId, model)

            if (session.model != model) {
                emit(AnalysisResult.Error("Model mismatch. Please start a new session."))
                return@flow
            }

            // Load files from GitHub
            val fileContents = mutableMapOf<String, String>()
            if (filePaths.isNotEmpty()) {
                emit(AnalysisResult.Loading("Loading files..."))
                var loadedCount = 0
                for (path in filePaths) {
                    try {
                        val content = gitHubClient.getFileContentDecoded(path).getOrNull()
                        if (content != null) {
                            fileContents[path] = content
                            loadedCount++
                            emit(AnalysisResult.Loading("Loaded $loadedCount/${filePaths.size}..."))
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error loading file: $path", e)
                    }
                }
                if (fileContents.isEmpty() && filePaths.isNotEmpty()) {
                    emit(AnalysisResult.Error("No files could be loaded"))
                    return@flow
                }
            }

            emit(AnalysisResult.Loading("Preparing context..."))
            val context = if (fileContents.isNotEmpty()) buildFileContext(fileContents) else ""
            val systemPrompt = buildSystemPrompt()

            // ✅ FIX 2: Build FULL conversation history for Claude
            // Convert DB messages to Claude API format
            val claudeMessages = mutableListOf<ClaudeMessage>()

            for (msg in conversationHistory) {
                val role = when (msg.role) {
                    MessageRole.USER -> "user"
                    MessageRole.ASSISTANT -> "assistant"
                    else -> continue // Skip SYSTEM messages
                }
                // Skip empty messages
                if (msg.content.isBlank()) continue
                claudeMessages.add(ClaudeMessage(role, msg.content))
            }

            // If there's file context, prepend it to the LAST user message
            if (context.isNotEmpty() && claudeMessages.isNotEmpty()) {
                val lastIdx = claudeMessages.lastIndex
                val lastMsg = claudeMessages[lastIdx]
                if (lastMsg.role == "user") {
                    claudeMessages[lastIdx] = ClaudeMessage(
                        "user",
                        buildUserMessage(context, lastMsg.content)
                    )
                }
            }

            // Ensure messages alternate correctly (Claude API requirement)
            val sanitizedMessages = sanitizeMessageOrder(claudeMessages)

            if (sanitizedMessages.isEmpty()) {
                emit(AnalysisResult.Error("No messages to send"))
                return@flow
            }

            Log.i(TAG, "Sending ${sanitizedMessages.size} messages to Claude (history + current)")

            emit(AnalysisResult.Loading("Analyzing with ${model.displayName}..."))

            var fullResponse = ""
            var inputTokens = 0
            var outputTokens = 0
            var cachedReadTokens = 0
            var cachedWriteTokens = 0
            var streamingStartedEmitted = false

            claudeClient.streamMessage(
                model = model.modelId,
                messages = sanitizedMessages,
                systemPrompt = systemPrompt,
                maxTokens = maxTokens,
                enableCaching = enableCaching
            ).collect { result ->
                when (result) {
                    is StreamingResult.Started -> {
                        if (!streamingStartedEmitted) {
                            streamingStartedEmitted = true
                            emit(AnalysisResult.StreamingStarted)
                        }
                    }

                    is StreamingResult.Delta -> {
                        fullResponse = result.accumulated
                        if (!streamingStartedEmitted) {
                            streamingStartedEmitted = true
                            emit(AnalysisResult.StreamingStarted)
                        }
                        // ✅ FIX 3: Emit every delta immediately
                        emit(AnalysisResult.Streaming(result.accumulated))
                    }

                    is StreamingResult.Completed -> {
                        fullResponse = result.fullText
                        result.usage?.let { usage ->
                            inputTokens = usage.inputTokens
                            outputTokens = usage.outputTokens
                            cachedReadTokens = usage.cacheReadInputTokens ?: 0
                            cachedWriteTokens = usage.cacheCreationInputTokens ?: 0
                        }

                        Log.i(TAG, "Completed: input=$inputTokens, output=$outputTokens, " +
                                "cacheRead=$cachedReadTokens, cacheWrite=$cachedWriteTokens")

                        session.addMessage(inputTokens, outputTokens, cachedReadTokens, cachedWriteTokens)

                        val cost = model.calculateCost(
                            inputTokens = inputTokens,
                            outputTokens = outputTokens,
                            cachedReadTokens = cachedReadTokens,
                            cachedWriteTokens = cachedWriteTokens
                        )

                        // ✅ FIX 1: NO chatDao operations here!
                        emit(AnalysisResult.Completed(
                            text = fullResponse,
                            cost = cost,
                            session = session
                        ))
                    }

                    is StreamingResult.Error -> {
                        Log.e(TAG, "Streaming error", result.exception)
                        // ✅ FIX 1: NO chatDao operations here!
                        emit(AnalysisResult.Error(result.exception.message ?: "Unknown error"))
                    }

                    else -> {}
                }
            }

        } catch (e: IllegalArgumentException) {
            emit(AnalysisResult.Error("Invalid input: ${e.message}"))
        } catch (e: Exception) {
            Log.e(TAG, "Scan failed", e)
            emit(AnalysisResult.Error(e.message ?: "Unknown error"))
        }
    }

    /**
     * Ensure messages alternate user/assistant correctly.
     * Claude API requires: first message is "user", messages alternate.
     * Consecutive same-role messages are merged.
     */
    private fun sanitizeMessageOrder(messages: List<ClaudeMessage>): List<ClaudeMessage> {
        if (messages.isEmpty()) return emptyList()

        val result = mutableListOf<ClaudeMessage>()
        for (msg in messages) {
            if (result.isNotEmpty() && result.last().role == msg.role) {
                // Merge consecutive same-role messages
                val last = result.removeAt(result.lastIndex)
                result.add(ClaudeMessage(msg.role, last.content + "\n\n" + msg.content))
            } else {
                result.add(msg)
            }
        }

        // Ensure first message is from "user"
        if (result.isNotEmpty() && result.first().role != "user") {
            result.add(0, ClaudeMessage("user", "Hello"))
        }

        // Ensure last message is from "user"
        if (result.isNotEmpty() && result.last().role != "user") {
            result.add(ClaudeMessage("user", "Continue"))
        }

        return result
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LEGACY: scanFiles (kept for backward compatibility)
    // ═══════════════════════════════════════════════════════════════════════════

    @Deprecated("Use scanFilesV2 instead — no DB duplication, with conversation history")
    suspend fun scanFiles(
        sessionId: String,
        filePaths: List<String>,
        userQuery: String,
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

            val fileContents = mutableMapOf<String, String>()
            if (filePaths.isNotEmpty()) {
                emit(AnalysisResult.Loading("Loading files..."))
                for (path in filePaths) {
                    try {
                        gitHubClient.getFileContentDecoded(path).getOrNull()?.let {
                            fileContents[path] = it
                        }
                    } catch (e: Exception) { Log.e(TAG, "Error loading file: $path", e) }
                }
            }

            val context = if (fileContents.isNotEmpty()) buildFileContext(fileContents) else ""
            val systemPrompt = buildSystemPrompt()
            val userMessage = if (context.isNotEmpty()) buildUserMessage(context, userQuery) else userQuery

            claudeClient.streamMessage(
                model = model.modelId,
                messages = listOf(ClaudeMessage("user", userMessage)),
                systemPrompt = systemPrompt,
                maxTokens = maxTokens,
                enableCaching = enableCaching
            ).collect { result ->
                when (result) {
                    is StreamingResult.Started -> emit(AnalysisResult.StreamingStarted)
                    is StreamingResult.Delta -> emit(AnalysisResult.Streaming(result.accumulated))
                    is StreamingResult.Completed -> {
                        session.addMessage(
                            result.usage?.inputTokens ?: 0,
                            result.usage?.outputTokens ?: 0,
                            result.usage?.cacheReadInputTokens ?: 0,
                            result.usage?.cacheCreationInputTokens ?: 0
                        )
                        val cost = model.calculateCost(
                            inputTokens = result.usage?.inputTokens ?: 0,
                            outputTokens = result.usage?.outputTokens ?: 0,
                            cachedReadTokens = result.usage?.cacheReadInputTokens ?: 0,
                            cachedWriteTokens = result.usage?.cacheCreationInputTokens ?: 0
                        )
                        emit(AnalysisResult.Completed(result.fullText, cost, session))
                    }
                    is StreamingResult.Error -> emit(AnalysisResult.Error(result.exception.message ?: "Error"))
                    else -> {}
                }
            }
        } catch (e: Exception) {
            emit(AnalysisResult.Error(e.message ?: "Unknown error"))
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════

    private fun buildFileContext(files: Map<String, String>): String = buildString {
        appendLine("# Repository Files Context")
        appendLine()
        files.forEach { (path, content) ->
            appendLine("## File: `$path`")
            appendLine("```")
            appendLine(content)
            appendLine("```")
            appendLine()
        }
    }

    private fun buildSystemPrompt(): String = """
You are an expert Android/Kotlin developer assistant with FULL access to a GitHub repository via API.

## YOUR CAPABILITIES:
- View repository file tree and read files
- Create new files and folders
- Edit existing files
- Delete files
- Commit all changes automatically

## OPERATION FORMAT:
When you need to perform file operations, use these EXACT markers in your response:

### CREATE FILE:
[CREATE_FILE:path/to/file.kt]
file content here
[/CREATE_FILE]

### EDIT FILE (full replacement):
[EDIT_FILE:path/to/existing.kt]
complete new file content
[/EDIT_FILE]

### DELETE FILE:
[DELETE_FILE:path/to/file.kt][/DELETE_FILE]

### CREATE FOLDER (via placeholder file):
[CREATE_FOLDER:path/to/new_folder][/CREATE_FOLDER]

## RULES:
1. Always use operation markers when the user asks to create/edit/delete files
2. You can include multiple operations in one response
3. After each operation marker, explain what you did
4. When asked to show file tree or read files, just respond with the content
5. Be precise with file paths — they are relative to repository root
6. Write complete file content — partial edits are not supported
7. For Kotlin/Java files, always include package declaration and imports
8. Commit messages are auto-generated from your operation type

## LANGUAGE:
- Respond in the same language the user writes in
- Code comments can be in English
    """.trimIndent()

    private fun buildUserMessage(context: String, query: String): String = """
$context

User Query: $query

Please analyze the provided files and respond to the user's query.
    """.trimIndent()

    // ═══════════════════════════════════════════════════════════════════════════
    // ANALYSIS RESULT
    // ═══════════════════════════════════════════════════════════════════════════

    sealed class AnalysisResult {
        data class Loading(val message: String) : AnalysisResult()
        data object StreamingStarted : AnalysisResult()
        data class Streaming(val text: String) : AnalysisResult()
        data class Completed(
            val text: String,
            val cost: ClaudeModelConfig.ModelCost,
            val session: ClaudeModelConfig.ChatSession
        ) : AnalysisResult()
        data class Error(val message: String) : AnalysisResult()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PARSE AND EXECUTE OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════════

    data class ParsedOperation(
        val type: OperationType,
        val path: String,
        val content: String = ""
    )

    enum class OperationType {
        CREATE_FILE, EDIT_FILE, DELETE_FILE, CREATE_FOLDER
    }

    fun parseOperations(response: String): List<ParsedOperation> {
        val operations = mutableListOf<ParsedOperation>()

        val createFileRegex = Regex("""\[CREATE_FILE:(.+?)](.+?)\[/CREATE_FILE]""", RegexOption.DOT_MATCHES_ALL)
        createFileRegex.findAll(response).forEach { match ->
            operations.add(ParsedOperation(OperationType.CREATE_FILE, match.groupValues[1].trim(), match.groupValues[2].trim()))
        }

        val editFileRegex = Regex("""\[EDIT_FILE:(.+?)](.+?)\[/EDIT_FILE]""", RegexOption.DOT_MATCHES_ALL)
        editFileRegex.findAll(response).forEach { match ->
            operations.add(ParsedOperation(OperationType.EDIT_FILE, match.groupValues[1].trim(), match.groupValues[2].trim()))
        }

        val deleteFileRegex = Regex("""\[DELETE_FILE:(.+?)]\[/DELETE_FILE]""")
        deleteFileRegex.findAll(response).forEach { match ->
            operations.add(ParsedOperation(OperationType.DELETE_FILE, match.groupValues[1].trim()))
        }

        val createFolderRegex = Regex("""\[CREATE_FOLDER:(.+?)]\[/CREATE_FOLDER]""")
        createFolderRegex.findAll(response).forEach { match ->
            operations.add(ParsedOperation(OperationType.CREATE_FOLDER, match.groupValues[1].trim()))
        }

        return operations
    }

    suspend fun executeOperations(
        sessionId: String,
        operations: List<ParsedOperation>
    ): List<Result<FileOperationResult>> {
        val results = mutableListOf<Result<FileOperationResult>>()

        for (op in operations) {
            val result = when (op.type) {
                OperationType.CREATE_FILE -> {
                    createFile(sessionId, op.path, op.content, "Create ${op.path} via Claude")
                }
                OperationType.EDIT_FILE -> {
                    try {
                        val currentFile = gitHubClient.getFileContent(op.path).getOrThrow()
                        val oldContent = gitHubClient.getFileContentDecoded(op.path).getOrElse { "" }
                        editFile(sessionId, op.path, oldContent, op.content, currentFile.sha, "Edit ${op.path} via Claude")
                    } catch (e: Exception) {
                        createFile(sessionId, op.path, op.content, "Create ${op.path} via Claude")
                    }
                }
                OperationType.DELETE_FILE -> {
                    try {
                        val currentFile = gitHubClient.getFileContent(op.path).getOrThrow()
                        deleteFile(sessionId, op.path, currentFile.sha, "Delete ${op.path} via Claude")
                    } catch (e: Exception) {
                        Result.failure(e)
                    }
                }
                OperationType.CREATE_FOLDER -> {
                    createFile(sessionId, "${op.path}/.gitkeep", "", "Create folder ${op.path} via Claude")
                }
            }
            results.add(result)
        }

        return results
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // FILE OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════════

    suspend fun createFile(
        sessionId: String, path: String, content: String,
        commitMessage: String = "Create $path via Claude"
    ): Result<FileOperationResult> {
        return try {
            val result = gitHubClient.createOrUpdateFile(path = path, content = content, message = commitMessage).getOrThrow()
            chatDao.insert(ChatMessageEntity(
                sessionId = sessionId, role = MessageRole.SYSTEM,
                content = "✅ Created file: `$path`\n\n```\n${content.take(500)}${if (content.length > 500) "\n..." else ""}\n```"
            ))
            Result.success(FileOperationResult.Created(path, result.content.sha))
        } catch (e: Exception) {
            chatDao.insert(ChatMessageEntity(sessionId = sessionId, role = MessageRole.SYSTEM, content = "❌ Failed to create `$path`: ${e.message}"))
            Result.failure(e)
        }
    }

    suspend fun deleteFile(
        sessionId: String, path: String, sha: String,
        commitMessage: String = "Delete $path via Claude"
    ): Result<FileOperationResult> {
        return try {
            gitHubClient.deleteFile(path = path, message = commitMessage, sha = sha).getOrThrow()
            chatDao.insert(ChatMessageEntity(sessionId = sessionId, role = MessageRole.SYSTEM, content = "🗑️ Deleted file: `$path`"))
            Result.success(FileOperationResult.Deleted(path))
        } catch (e: Exception) {
            chatDao.insert(ChatMessageEntity(sessionId = sessionId, role = MessageRole.SYSTEM, content = "❌ Failed to delete `$path`: ${e.message}"))
            Result.failure(e)
        }
    }

    suspend fun editFile(
        sessionId: String, path: String, oldContent: String, newContent: String,
        sha: String, commitMessage: String = "Edit $path via Claude"
    ): Result<FileOperationResult> {
        return try {
            val result = gitHubClient.createOrUpdateFile(path = path, content = newContent, message = commitMessage, sha = sha).getOrThrow()
            val diff = generateSimpleDiff(oldContent, newContent)
            chatDao.insert(ChatMessageEntity(sessionId = sessionId, role = MessageRole.SYSTEM, content = "✏️ Edited file: `$path`\n\n$diff"))
            Result.success(FileOperationResult.Edited(path, result.content.sha))
        } catch (e: Exception) {
            chatDao.insert(ChatMessageEntity(sessionId = sessionId, role = MessageRole.SYSTEM, content = "❌ Failed to edit `$path`: ${e.message}"))
            Result.failure(e)
        }
    }

    private fun generateSimpleDiff(old: String, new: String): String = buildString {
        appendLine("```diff")
        val oldLines = old.lines()
        val newLines = new.lines()
        val maxLines = maxOf(oldLines.size, newLines.size).coerceAtMost(20)
        for (i in 0 until maxLines) {
            val oldLine = oldLines.getOrNull(i)
            val newLine = newLines.getOrNull(i)
            when {
                oldLine == newLine && oldLine != null -> appendLine("  $oldLine")
                oldLine != null && newLine == null -> appendLine("- $oldLine")
                oldLine == null && newLine != null -> appendLine("+ $newLine")
                oldLine != newLine -> { oldLine?.let { appendLine("- $it") }; newLine?.let { appendLine("+ $it") } }
            }
        }
        if (maxOf(oldLines.size, newLines.size) > 20) appendLine("... (truncated)")
        appendLine("```")
    }

    sealed class FileOperationResult {
        data class Created(val path: String, val sha: String) : FileOperationResult()
        data class Edited(val path: String, val newSha: String) : FileOperationResult()
        data class Deleted(val path: String) : FileOperationResult()
    }
}
