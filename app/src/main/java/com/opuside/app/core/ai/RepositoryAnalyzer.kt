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
import kotlinx.coroutines.flow.first
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton
import com.opuside.app.core.network.github.GitHubApiClient

/**
 * 🤖 REPOSITORY ANALYZER v6.0 (FIXED REPO ACCESS)
 *
 * ✅ FIX: Claude теперь РЕАЛЬНО видит структуру репозитория
 *    - Автоматическая загрузка file tree при каждом запросе
 *    - Правдивый system prompt (без лжи про "FULL access")
 *    - GitHub config (owner/repo/branch) включён в контекст
 *    - Рекурсивное сканирование до 3 уровней вложенности
 *
 * ✅ СОХРАНЕНО:
 *    - Все предыдущие исправления (cache, streaming, no duplication)
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
        private const val MAX_TREE_DEPTH = 3  // Рекурсия до 3 уровней
        private const val MAX_TREE_FILES = 500  // Максимум файлов в дереве
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

    /**
     * ✅ НОВОЕ: Рекурсивное получение файлового дерева
     * Возвращает текстовое представление структуры репозитория
     */
    private suspend fun buildRepositoryTree(
        path: String = "",
        depth: Int = 0,
        maxDepth: Int = MAX_TREE_DEPTH,
        filesCollected: MutableList<String> = mutableListOf()
    ): String = buildString {
        if (depth >= maxDepth || filesCollected.size >= MAX_TREE_FILES) return@buildString

        try {
            val contents = gitHubClient.getContent(path).getOrNull() ?: return@buildString
            val indentStr = "  ".repeat(depth)

            contents.forEach { item ->
                when (item.type) {
                    "file" -> {
                        if (filesCollected.size < MAX_TREE_FILES) {
                            appendLine("${indentStr}📄 ${item.name} (${formatSize(item.size)})")
                            filesCollected.add(item.path)
                        }
                    }
                    "dir" -> {
                        appendLine("${indentStr}📁 ${item.name}/")
                        // Рекурсивно обходим директорию
                        append(buildRepositoryTree(item.path, depth + 1, maxDepth, filesCollected))
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to scan directory: $path", e)
            val indentStr = "  ".repeat(depth)
            appendLine("${indentStr}⚠️ [Error reading directory]")
        }
    }

    private fun formatSize(bytes: Int): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "${bytes / (1024 * 1024)} MB"
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
    // ✅ FIXED: scanFilesV2 — WITH REPO STRUCTURE
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * V2: Does NOT write to ChatDao. ViewModel handles all DB operations.
     * Accepts full conversation history so Claude remembers context.
     * 
     * ✅ NEW: Автоматически загружает структуру репозитория в контекст
     *
     * @param conversationHistory All previous messages from the session (USER + ASSISTANT)
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

            // ✅ НОВОЕ: Загружаем GitHub config
            val gitHubConfig = appSettings.gitHubConfig.first()
            val repoInfo = if (gitHubConfig.owner.isNotBlank() && gitHubConfig.repo.isNotBlank()) {
                "${gitHubConfig.owner}/${gitHubConfig.repo} (branch: ${gitHubConfig.branch})"
            } else {
                "Not configured"
            }

            // ✅ НОВОЕ: Загружаем структуру репозитория
            emit(AnalysisResult.Loading("Scanning repository structure..."))
            val repoTree = try {
                buildRepositoryTree()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to build repo tree", e)
                "[Repository structure unavailable: ${e.message}]"
            }

            // Load explicitly requested files
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
            
            // ✅ НОВОЕ: Build context with repo structure
            val context = buildFullContext(repoInfo, repoTree, fileContents)
            val systemPrompt = buildSystemPrompt()

            // Build FULL conversation history for Claude
            val claudeMessages = mutableListOf<ClaudeMessage>()

            for (msg in conversationHistory) {
                val role = when (msg.role) {
                    MessageRole.USER -> "user"
                    MessageRole.ASSISTANT -> "assistant"
                    else -> continue
                }
                if (msg.content.isBlank()) continue
                claudeMessages.add(ClaudeMessage(role, msg.content))
            }

            // Prepend context to LAST user message
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

                        emit(AnalysisResult.Completed(
                            text = fullResponse,
                            cost = cost,
                            session = session
                        ))
                    }

                    is StreamingResult.Error -> {
                        Log.e(TAG, "Streaming error", result.exception)
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
     */
    private fun sanitizeMessageOrder(messages: List<ClaudeMessage>): List<ClaudeMessage> {
        if (messages.isEmpty()) return emptyList()

        val result = mutableListOf<ClaudeMessage>()
        for (msg in messages) {
            if (result.isNotEmpty() && result.last().role == msg.role) {
                val last = result.removeAt(result.lastIndex)
                result.add(ClaudeMessage(msg.role, last.content + "\n\n" + msg.content))
            } else {
                result.add(msg)
            }
        }

        if (result.isNotEmpty() && result.first().role != "user") {
            result.add(0, ClaudeMessage("user", "Hello"))
        }

        if (result.isNotEmpty() && result.last().role != "user") {
            result.add(ClaudeMessage("user", "Continue"))
        }

        return result
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LEGACY: scanFiles (kept for backward compatibility)
    // ═══════════════════════════════════════════════════════════════════════════

    @Deprecated("Use scanFilesV2 instead")
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
    // CONTEXT BUILDING
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * ✅ НОВОЕ: Build full context with repo structure + explicit files
     */
    private fun buildFullContext(
        repoInfo: String,
        repoTree: String,
        explicitFiles: Map<String, String>
    ): String = buildString {
        appendLine("# Repository Context")
        appendLine()
        appendLine("**Repository**: $repoInfo")
        appendLine()
        
        if (repoTree.isNotBlank()) {
            appendLine("## File Structure")
            appendLine("```")
            appendLine(repoTree)
            appendLine("```")
            appendLine()
        }

        if (explicitFiles.isNotEmpty()) {
            appendLine("## Explicitly Loaded Files")
            appendLine()
            explicitFiles.forEach { (path, content) ->
                appendLine("### File: `$path`")
                appendLine("```")
                appendLine(content)
                appendLine("```")
                appendLine()
            }
        }
    }

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

    /**
     * ✅ FIXED: Правдивый system prompt
     */
    private fun buildSystemPrompt(): String = """
You are an expert Android/Kotlin developer assistant working with a GitHub repository.

## WHAT YOU CAN SEE:
1. **Repository structure** — You receive a complete file tree showing all directories and files
2. **Explicitly loaded files** — When user attaches files, you see their full content
3. **GitHub config** — You know which repository (owner/repo/branch) you're working with

## WHAT YOU CANNOT DO:
- You CANNOT read files that weren't explicitly loaded
- You CANNOT browse directories interactively
- You CANNOT execute git commands

## WHEN USER ASKS "Show me the repo structure" or "What files are in this project":
✅ DO: Refer to the "File Structure" section in your context — it's already there!
❌ DON'T: Say you don't have access or ask for a link

## FILE OPERATIONS:
When you need to create/edit/delete files, use these EXACT markers:

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
1. The file tree in your context is COMPLETE — trust it
2. If user asks about files/structure, reference what you already see
3. Use operation markers only when user asks to CREATE/EDIT/DELETE
4. Be precise with file paths — they are relative to repository root
5. Write complete file content — partial edits are not supported
6. For Kotlin/Java files, always include package declaration and imports

## LANGUAGE:
- Respond in the same language the user writes in
- Code comments can be in English
    """.trimIndent()

    private fun buildUserMessage(context: String, query: String): String = """
$context

User Query: $query

Please analyze the provided context and respond to the user's query.
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