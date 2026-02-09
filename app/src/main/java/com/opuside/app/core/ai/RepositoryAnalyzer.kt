package com.opuside.app.core.ai

import android.util.Log
import com.opuside.app.core.data.AppSettings
import com.opuside.app.core.database.dao.ChatDao
import com.opuside.app.core.database.entity.ChatMessageEntity
import com.opuside.app.core.database.entity.MessageRole
import com.opuside.app.core.network.anthropic.ClaudeApiClient
import com.opuside.app.core.network.anthropic.StreamingResult
import com.opuside.app.core.network.anthropic.model.ClaudeMessage
import com.opuside.app.core.network.github.GitHubApiClient
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.time.Duration
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 🤖 REPOSITORY ANALYZER v3.0 (OPERATION MARKERS)
 * 
 * ✅ ОБНОВЛЕНО:
 * - Новый системный промпт с маркерами операций
 * - Поддержка чата БЕЗ файлов (чистый диалог)
 * - Парсинг операций из ответа Claude
 * - Выполнение операций (create/edit/delete/folder)
 * 
 * ✅ СОХРАНЕНЫ все фичи:
 * - Выборочное сканирование (MAX_FILES_PER_SCAN)
 * - Оценка стоимости ПЕРЕД сканированием
 * - Защита от больших файлов
 * - Управление сеансами (thread-safe)
 * - Prompt Caching
 * - 4 модели Claude
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
        
        // ✅ СОХРАНЕНО: Лимиты безопасности
        private const val MAX_FILES_PER_SCAN = 50
        private const val MAX_FILE_SIZE_BYTES = 100_000 // 100KB
        
        // ✅ НОВОЕ: Лимиты для сеансов
        private const val SESSION_CLEANUP_THRESHOLD_DAYS = 1L
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ✅ SESSION MANAGEMENT (через SessionManager)
    // ═══════════════════════════════════════════════════════════════════════════

    private val sessionManager = ClaudeModelConfig.SessionManager
    
    init {
        Log.i(TAG, "RepositoryAnalyzer initialized")
    }
    
    fun createSession(
        sessionId: String,
        model: ClaudeModelConfig.ClaudeModel
    ): ClaudeModelConfig.ChatSession {
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
    
    fun shouldStartNewSession(sessionId: String): Boolean {
        return sessionManager.shouldStartNewSession(sessionId)
    }
    
    suspend fun cleanupOldSessions(): Int {
        return sessionManager.cleanupOldSessions(Duration.ofDays(SESSION_CLEANUP_THRESHOLD_DAYS))
    }
    
    fun getActiveSessions(): List<ClaudeModelConfig.ChatSession> {
        return sessionManager.getAllActiveSessions()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ✅ REPOSITORY STRUCTURE
    // ═══════════════════════════════════════════════════════════════════════════

    suspend fun getRepositoryStructure(
        path: String = "",
        recursive: Boolean = false
    ): Result<RepositoryStructure> {
        return try {
            Log.d(TAG, "Getting repository structure: path=$path, recursive=$recursive")
            
            val contents = gitHubClient.getContent(path).getOrThrow()
            
            val structure = RepositoryStructure(
                path = path,
                files = contents.filter { it.type == "file" }.map {
                    FileMetadata(
                        name = it.name,
                        path = it.path,
                        size = it.size,
                        extension = it.name.substringAfterLast('.', ""),
                        sha = it.sha
                    )
                },
                directories = contents.filter { it.type == "dir" }.map {
                    DirectoryMetadata(
                        name = it.name,
                        path = it.path
                    )
                }
            )
            
            Log.d(TAG, "Repository structure loaded: ${structure.totalFiles} files, " +
                    "${structure.directories.size} directories")
            
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
        
        fun toMarkdown(): String = buildString {
            appendLine("## 📂 Repository Structure: `$path`")
            appendLine()
            appendLine("**Files:** $totalFiles")
            appendLine("**Total Size:** ${formatBytes(totalSize)}")
            appendLine()
            
            if (directories.isNotEmpty()) {
                appendLine("### 📁 Directories (${directories.size})")
                directories.forEach { appendLine("- `${it.path}`") }
                appendLine()
            }
            
            if (files.isNotEmpty()) {
                appendLine("### 📄 Files (${files.size})")
                val grouped = files.groupBy { it.extension }
                grouped.forEach { (ext, fileList) ->
                    appendLine("**.$ext** (${fileList.size} files):")
                    fileList.forEach { appendLine("  - `${it.path}` (${formatBytes(it.size)})") }
                    appendLine()
                }
            }
        }
        
        private fun formatBytes(bytes: Int): String = when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        }
    }

    data class FileMetadata(
        val name: String,
        val path: String,
        val size: Int,
        val extension: String,
        val sha: String
    )

    data class DirectoryMetadata(
        val name: String,
        val path: String
    )

    // ═══════════════════════════════════════════════════════════════════════════
    // ✅ SCAN COST ESTIMATION
    // ═══════════════════════════════════════════════════════════════════════════

    suspend fun estimateScanCost(
        filePaths: List<String>,
        model: ClaudeModelConfig.ClaudeModel,
        sessionId: String? = null
    ): Result<ScanEstimate> {
        if (filePaths.isEmpty()) {
            Log.w(TAG, "estimateScanCost called with empty file list")
            return Result.failure(IllegalArgumentException("No files selected"))
        }
        
        if (filePaths.size > MAX_FILES_PER_SCAN) {
            Log.w(TAG, "Too many files selected: ${filePaths.size} > $MAX_FILES_PER_SCAN")
            return Result.failure(IllegalArgumentException(
                "Too many files. Maximum: $MAX_FILES_PER_SCAN, selected: ${filePaths.size}"
            ))
        }
        
        return try {
            Log.d(TAG, "Estimating scan cost for ${filePaths.size} files with ${model.displayName}")
            
            val files = filePaths.mapNotNull { path ->
                gitHubClient.getFileContent(path).getOrNull()
            }
            
            if (files.isEmpty()) {
                Log.w(TAG, "No files could be loaded from the provided paths")
                return Result.failure(IllegalStateException("No files could be loaded"))
            }
            
            val totalSize = files.sumOf { it.size }
            val oversizedFiles = files.filter { it.size > MAX_FILE_SIZE_BYTES }
            
            if (oversizedFiles.isNotEmpty()) {
                Log.w(TAG, "Found ${oversizedFiles.size} oversized files")
            }
            
            val estimatedInputTokens = (totalSize / 4.0).toInt()
            val estimatedOutputTokens = estimatedInputTokens / 2
            
            val session = sessionId?.let { id ->
                getSession(id) ?: run {
                    Log.w(TAG, "Session $id not found for cost estimation")
                    null
                }
            }
            
            val currentSessionTokens = session?.totalInputTokens ?: 0
            val projectedTotalTokens = currentSessionTokens + estimatedInputTokens
            
            val cachedTokens = if (session != null && session.messageCount > 0) {
                (estimatedInputTokens * 0.7).toInt()
            } else {
                0
            }
            
            val cost = model.calculateCost(
                inputTokens = estimatedInputTokens,
                outputTokens = estimatedOutputTokens,
                cachedInputTokens = cachedTokens
            )
            
            Log.d(TAG, "Scan estimate completed: " +
                    "files=${files.size}, " +
                    "tokens=$estimatedInputTokens, " +
                    "cost=${cost.totalCostEUR}€")
            
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
        
        fun toMarkdown(): String = buildString {
            appendLine("## 📊 Scan Estimate")
            appendLine()
            appendLine("**Files to scan:** $fileCount")
            appendLine("**Total size:** ${formatBytes(totalSizeBytes)}")
            appendLine()
            
            if (currentSessionTokens > 0) {
                appendLine("### 📈 Session Progress")
                appendLine("- Current: ${"%,d".format(currentSessionTokens)} tokens")
                appendLine("- After scan: ${"%,d".format(projectedTotalTokens)} tokens")
                appendLine("- Threshold: ${"%,d".format(cost.model.longContextThreshold)} tokens")
                
                val percentage = ((projectedTotalTokens.toDouble() / cost.model.longContextThreshold) * 100).toInt()
                appendLine("- Progress: $percentage%")
                appendLine()
            }
            
            if (willTriggerLongContext) {
                appendLine("### ⚠️ WARNING: WILL TRIGGER LONG CONTEXT!")
                appendLine("- This scan will exceed 200K tokens")
                appendLine("- **ALL prices will DOUBLE for ENTIRE session**")
                appendLine("- Current cost: €${String.format("%.4f", cost.totalCostEUR)}")
                appendLine("- After doubling: €${String.format("%.4f", cost.totalCostEUR * 2)}")
                appendLine("- ✅ **Strong recommendation:** Start NEW session")
                appendLine()
            } else if (isApproachingLongContext) {
                appendLine("### ⚠️ APPROACHING LONG CONTEXT")
                val percentage = ((projectedTotalTokens.toDouble() / cost.model.longContextThreshold) * 100).toInt()
                appendLine("- At $percentage% of threshold")
                appendLine("- Remaining: ${"%,d".format(cost.model.longContextThreshold - projectedTotalTokens)} tokens")
                appendLine("- Consider starting new session soon")
                appendLine()
            }
            
            appendLine("### 💰 Estimated Cost")
            appendLine("**${cost.model.displayName} ${cost.model.emoji}**")
            appendLine()
            
            if (cost.cachedInputTokens > 0) {
                appendLine("- New input: ${"%,d".format(cost.newInputTokens)} × $${cost.model.inputPricePerM}/1M = ${"$%.4f".format(cost.newInputCostUSD)}")
                appendLine("- Cached input: ${"%,d".format(cost.cachedInputTokens)} × $${cost.model.cachedInputPricePerM}/1M = ${"$%.4f".format(cost.cachedInputCostUSD)} ✅")
            } else {
                appendLine("- Input: ${"%,d".format(cost.inputTokens)} × $${cost.model.inputPricePerM}/1M = ${"$%.4f".format(cost.newInputCostUSD)}")
            }
            appendLine("- Output: ${"%,d".format(cost.outputTokens)} × $${cost.model.outputPricePerM}/1M = ${"$%.4f".format(cost.outputCostUSD)}")
            appendLine()
            appendLine("**Total:** ${"$%.4f".format(cost.totalCostUSD)} (€${"%.4f".format(cost.totalCostEUR)})")
            
            if (cost.savingsPercentage > 0) {
                appendLine()
                appendLine("✨ **Cache savings:** ${"%.0f".format(cost.savingsPercentage)}% (€${"%.4f".format(cost.cacheSavingsEUR)})")
                appendLine("💡 Caching is working! Keep this session for more savings.")
            } else if (currentSessionTokens > 0) {
                appendLine()
                appendLine("💡 **Tip:** Send more messages to activate caching (90% savings!)")
            }
            appendLine()
            
            if (oversizedFiles.isNotEmpty()) {
                appendLine("### ⚠️ Files Too Large (>${formatBytes(MAX_FILE_SIZE_BYTES)})")
                oversizedFiles.forEach { appendLine("- `$it`") }
                appendLine()
                appendLine("❌ **Cannot proceed.** Remove large files or split them.")
            } else {
                appendLine("✅ Ready to scan")
            }
        }
        
        private fun formatBytes(bytes: Int): String = when {
            bytes < 1024 -> "$bytes B"
            bytes < 1024 * 1024 -> "${bytes / 1024} KB"
            else -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ✅ НОВОЕ: FILE SCANNING (поддержка чата БЕЗ файлов)
    // ═══════════════════════════════════════════════════════════════════════════

    suspend fun scanFiles(
        sessionId: String,
        filePaths: List<String>,
        userQuery: String,
        model: ClaudeModelConfig.ClaudeModel,
        enableCaching: Boolean = true
    ): Flow<AnalysisResult> = flow {
        try {
            require(sessionId.isNotBlank()) { "Session ID cannot be blank" }
            // ✅ filePaths может быть пустым для чат-режима без файлов
            require(userQuery.isNotBlank()) { "User query cannot be blank" }
            
            Log.i(TAG, "Starting scan: session=$sessionId, files=${filePaths.size}, " +
                    "model=${model.displayName}, caching=$enableCaching")
            
            // Получаем или создаем сеанс
            val session = getSession(sessionId) ?: createSession(sessionId, model).also {
                Log.i(TAG, "Created new session for scan: $sessionId")
            }
            
            // Проверяем, что модель совпадает
            if (session.model != model) {
                Log.w(TAG, "Model mismatch in session: expected ${session.model}, got $model")
                emit(AnalysisResult.Error("Model mismatch. Please start a new session."))
                return@flow
            }
            
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
                            emit(AnalysisResult.Loading("Loaded $loadedCount/${filePaths.size} files..."))
                        } else {
                            Log.w(TAG, "Failed to load file: $path")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Error loading file: $path", e)
                    }
                }
                
                if (fileContents.isEmpty() && filePaths.isNotEmpty()) {
                    Log.e(TAG, "No files could be loaded")
                    emit(AnalysisResult.Error("No files could be loaded"))
                    return@flow
                }
            } // end if (filePaths.isNotEmpty())
            
            emit(AnalysisResult.Loading("Preparing context..."))
            val context = if (fileContents.isNotEmpty()) buildFileContext(fileContents) else ""
            val systemPrompt = buildSystemPrompt()
            val userMessage = if (context.isNotEmpty()) {
                buildUserMessage(context, userQuery)
            } else {
                userQuery
            }
            
            // Сохраняем сообщение пользователя
            chatDao.insert(ChatMessageEntity(
                sessionId = sessionId,
                role = MessageRole.USER,
                content = userQuery
            ))
            
            val assistantMsgId = chatDao.insert(ChatMessageEntity(
                sessionId = sessionId,
                role = MessageRole.ASSISTANT,
                content = "",
                isStreaming = true
            ))
            
            emit(AnalysisResult.Loading("Analyzing with ${model.displayName}..."))
            
            var fullResponse = ""
            var inputTokens = 0
            var outputTokens = 0
            var cachedInputTokens = 0
            
            claudeClient.streamMessage(
                model = model.modelId,
                messages = listOf(ClaudeMessage("user", userMessage)),
                systemPrompt = systemPrompt,
                maxTokens = 8192,
                enableCaching = enableCaching && session.messageCount > 0
            ).collect { result ->
                when (result) {
                    is StreamingResult.Delta -> {
                        fullResponse = result.accumulated
                        emit(AnalysisResult.Streaming(result.accumulated))
                    }
                    
                    is StreamingResult.Completed -> {
                        fullResponse = result.fullText
                        result.usage?.let { usage ->
                            inputTokens = usage.inputTokens
                            outputTokens = usage.outputTokens
                            cachedInputTokens = usage.cacheReadInputTokens ?: 0
                        }
                        
                        Log.i(TAG, "Scan completed: input=$inputTokens, output=$outputTokens, cached=$cachedInputTokens")
                        
                        // Обновляем сеанс
                        session.addMessage(inputTokens, outputTokens, cachedInputTokens)
                        
                        val cost = model.calculateCost(inputTokens, outputTokens, cachedInputTokens)
                        
                        chatDao.finishStreaming(
                            id = assistantMsgId,
                            finalContent = fullResponse,
                            tokensUsed = inputTokens + outputTokens
                        )
                        
                        emit(AnalysisResult.Completed(
                            text = fullResponse,
                            cost = cost,
                            session = session
                        ))
                    }
                    
                    is StreamingResult.Error -> {
                        Log.e(TAG, "Streaming error", result.exception)
                        chatDao.markAsError(assistantMsgId, result.exception.message ?: "Error")
                        emit(AnalysisResult.Error(result.exception.message ?: "Unknown error"))
                    }
                    
                    else -> {}
                }
            }
            
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "Invalid arguments for scan", e)
            emit(AnalysisResult.Error("Invalid input: ${e.message}"))
        } catch (e: Exception) {
            Log.e(TAG, "Scan failed", e)
            emit(AnalysisResult.Error(e.message ?: "Unknown error"))
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

    // ✅ НОВОЕ: Системный промпт с операционными маркерами
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
4. When asked to show file tree or read files, just respond with the content — no markers needed
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

    sealed class AnalysisResult {
        data class Loading(val message: String) : AnalysisResult()
        data class Streaming(val text: String) : AnalysisResult()
        data class Completed(
            val text: String,
            val cost: ClaudeModelConfig.ModelCost,
            val session: ClaudeModelConfig.ChatSession
        ) : AnalysisResult()
        data class Error(val message: String) : AnalysisResult()
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ✅ НОВОЕ: PARSE AND EXECUTE OPERATIONS FROM CLAUDE RESPONSE
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

        // [CREATE_FILE:path]content[/CREATE_FILE]
        val createFileRegex = Regex("""\[CREATE_FILE:(.+?)](.+?)\[/CREATE_FILE]""", RegexOption.DOT_MATCHES_ALL)
        createFileRegex.findAll(response).forEach { match ->
            operations.add(ParsedOperation(
                type = OperationType.CREATE_FILE,
                path = match.groupValues[1].trim(),
                content = match.groupValues[2].trim()
            ))
        }

        // [EDIT_FILE:path]content[/EDIT_FILE]
        val editFileRegex = Regex("""\[EDIT_FILE:(.+?)](.+?)\[/EDIT_FILE]""", RegexOption.DOT_MATCHES_ALL)
        editFileRegex.findAll(response).forEach { match ->
            operations.add(ParsedOperation(
                type = OperationType.EDIT_FILE,
                path = match.groupValues[1].trim(),
                content = match.groupValues[2].trim()
            ))
        }

        // [DELETE_FILE:path][/DELETE_FILE]
        val deleteFileRegex = Regex("""\[DELETE_FILE:(.+?)]\[/DELETE_FILE]""")
        deleteFileRegex.findAll(response).forEach { match ->
            operations.add(ParsedOperation(
                type = OperationType.DELETE_FILE,
                path = match.groupValues[1].trim()
            ))
        }

        // [CREATE_FOLDER:path][/CREATE_FOLDER]
        val createFolderRegex = Regex("""\[CREATE_FOLDER:(.+?)]\[/CREATE_FOLDER]""")
        createFolderRegex.findAll(response).forEach { match ->
            operations.add(ParsedOperation(
                type = OperationType.CREATE_FOLDER,
                path = match.groupValues[1].trim()
            ))
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
                    // Для EDIT нужен SHA текущего файла
                    try {
                        val currentFile = gitHubClient.getFileContent(op.path).getOrThrow()
                        val sha = currentFile.sha
                        val oldContent = gitHubClient.getFileContentDecoded(op.path).getOrElse { "" }
                        editFile(sessionId, op.path, oldContent, op.content, sha, "Edit ${op.path} via Claude")
                    } catch (e: Exception) {
                        // Если файл не существует — создаём
                        Log.w(TAG, "File ${op.path} not found for edit, creating instead")
                        createFile(sessionId, op.path, op.content, "Create ${op.path} via Claude")
                    }
                }
                OperationType.DELETE_FILE -> {
                    try {
                        val currentFile = gitHubClient.getFileContent(op.path).getOrThrow()
                        deleteFile(sessionId, op.path, currentFile.sha, "Delete ${op.path} via Claude")
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to delete ${op.path}: file not found", e)
                        Result.failure(e)
                    }
                }
                OperationType.CREATE_FOLDER -> {
                    // GitHub не поддерживает пустые папки — создаём .gitkeep
                    createFile(sessionId, "${op.path}/.gitkeep", "", "Create folder ${op.path} via Claude")
                }
            }
            results.add(result)
        }

        return results
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ✅ FILE OPERATIONS (без изменений)
    // ═══════════════════════════════════════════════════════════════════════════

    suspend fun createFile(
        sessionId: String,
        path: String,
        content: String,
        commitMessage: String = "Create $path via Claude"
    ): Result<FileOperationResult> {
        return try {
            Log.d(TAG, "Creating file: $path")
            
            val result = gitHubClient.createOrUpdateFile(
                path = path,
                content = content,
                message = commitMessage
            ).getOrThrow()
            
            chatDao.insert(ChatMessageEntity(
                sessionId = sessionId,
                role = MessageRole.SYSTEM,
                content = "✅ Created file: `$path`\n\n```\n${content.take(500)}${if (content.length > 500) "\n..." else ""}\n```"
            ))
            
            Log.i(TAG, "File created successfully: $path")
            Result.success(FileOperationResult.Created(path, result.content.sha))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create file: $path", e)
            chatDao.insert(ChatMessageEntity(
                sessionId = sessionId,
                role = MessageRole.SYSTEM,
                content = "❌ Failed to create `$path`: ${e.message}"
            ))
            Result.failure(e)
        }
    }

    suspend fun deleteFile(
        sessionId: String,
        path: String,
        sha: String,
        commitMessage: String = "Delete $path via Claude"
    ): Result<FileOperationResult> {
        return try {
            Log.d(TAG, "Deleting file: $path")
            
            gitHubClient.deleteFile(path = path, message = commitMessage, sha = sha).getOrThrow()
            
            chatDao.insert(ChatMessageEntity(
                sessionId = sessionId,
                role = MessageRole.SYSTEM,
                content = "🗑️ Deleted file: `$path`"
            ))
            
            Log.i(TAG, "File deleted successfully: $path")
            Result.success(FileOperationResult.Deleted(path))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete file: $path", e)
            chatDao.insert(ChatMessageEntity(
                sessionId = sessionId,
                role = MessageRole.SYSTEM,
                content = "❌ Failed to delete `$path`: ${e.message}"
            ))
            Result.failure(e)
        }
    }

    suspend fun editFile(
        sessionId: String,
        path: String,
        oldContent: String,
        newContent: String,
        sha: String,
        commitMessage: String = "Edit $path via Claude"
    ): Result<FileOperationResult> {
        return try {
            Log.d(TAG, "Editing file: $path")
            
            val result = gitHubClient.createOrUpdateFile(
                path = path,
                content = newContent,
                message = commitMessage,
                sha = sha
            ).getOrThrow()
            
            val diff = generateSimpleDiff(oldContent, newContent)
            
            chatDao.insert(ChatMessageEntity(
                sessionId = sessionId,
                role = MessageRole.SYSTEM,
                content = "✏️ Edited file: `$path`\n\n$diff"
            ))
            
            Log.i(TAG, "File edited successfully: $path")
            Result.success(FileOperationResult.Edited(path, result.content.sha))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to edit file: $path", e)
            chatDao.insert(ChatMessageEntity(
                sessionId = sessionId,
                role = MessageRole.SYSTEM,
                content = "❌ Failed to edit `$path`: ${e.message}"
            ))
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
                oldLine != newLine -> {
                    oldLine?.let { appendLine("- $it") }
                    newLine?.let { appendLine("+ $it") }
                }
            }
        }
        
        if (maxOf(oldLines.size, newLines.size) > 20) {
            appendLine("... (truncated)")
        }
        
        appendLine("```")
    }

    sealed class FileOperationResult {
        data class Created(val path: String, val sha: String) : FileOperationResult()
        data class Edited(val path: String, val newSha: String) : FileOperationResult()
        data class Deleted(val path: String) : FileOperationResult()
    }
}