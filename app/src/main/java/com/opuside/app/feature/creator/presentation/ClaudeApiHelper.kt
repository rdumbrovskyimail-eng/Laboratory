
package com.opuside.app.feature.creator.presentation

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.lifecycle.HiltViewModel
import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.plugins.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.plugins.logging.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.utils.io.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject

// ═══════════════════════════════════════════════════════════════════════════════
// DATA MODELS - Updated for 2026 with Compaction Support
// ═══════════════════════════════════════════════════════════════════════════════

@Serializable
data class ContentBlock(
    val type: String,
    val text: String? = null,
    val content: String? = null,
    @SerialName("cache_control") val cacheControl: CacheControl? = null
)

@Serializable
data class CacheControl(
    val type: String = "ephemeral"
)

@Serializable
data class ClaudeMessage(
    val role: String,
    val content: List<ContentBlock>
) {
    constructor(role: String, text: String) : this(
        role = role,
        content = listOf(ContentBlock(type = "text", text = text))
    )
}

@Serializable
data class SystemBlock(
    val type: String = "text",
    val text: String
)

@Serializable
data class CompactionTrigger(
    val type: String = "input_tokens",
    val value: Int = 150000
)

@Serializable
data class CompactionEdit(
    val type: String = "compact_20260112",
    val trigger: CompactionTrigger? = null,
    @SerialName("pause_after_compaction") val pauseAfterCompaction: Boolean = false,
    val instructions: String? = null
)

@Serializable
data class ContextManagement(
    val edits: List<CompactionEdit>
)

@Serializable
data class ClaudeApiRequest(
    val model: String = "claude-opus-4-6",
    @SerialName("max_tokens") val maxTokens: Int = 4096,
    val messages: List<ClaudeMessage>,
    val system: List<SystemBlock>? = null,
    @SerialName("context_management") val contextManagement: ContextManagement? = null,
    val stream: Boolean = false
)

@Serializable
data class ClaudeApiResponse(
    val id: String? = null,
    val type: String? = null,
    val role: String? = null,
    val content: List<ContentBlock>? = null,
    val model: String? = null,
    @SerialName("stop_reason") val stopReason: String? = null,
    val usage: ClaudeUsage? = null
)

@Serializable
data class ClaudeUsage(
    @SerialName("input_tokens") val inputTokens: Int? = null,
    @SerialName("output_tokens") val outputTokens: Int? = null,
    val iterations: List<UsageIteration>? = null
)

@Serializable
data class UsageIteration(
    val type: String,
    @SerialName("input_tokens") val inputTokens: Int,
    @SerialName("output_tokens") val outputTokens: Int
)

@Serializable
data class ClaudeStreamEvent(
    val type: String,
    val message: ClaudeApiResponse? = null,
    val delta: ClaudeDelta? = null,
    val usage: ClaudeUsage? = null,
    val error: ClaudeError? = null
)

@Serializable
data class ClaudeDelta(
    val type: String? = null,
    val text: String? = null,
    val content: String? = null
)

@Serializable
data class ClaudeError(
    val type: String? = null,
    val message: String? = null
)

// ═══════════════════════════════════════════════════════════════════════════════
// SECURE STORAGE
// ═══════════════════════════════════════════════════════════════════════════════

class SecureApiKeyStore(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val encryptedPrefs = EncryptedSharedPreferences.create(
        context,
        "claude_secure_prefs",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun saveApiKey(key: String) {
        if (validateApiKey(key)) {
            encryptedPrefs.edit().putString("api_key", key).apply()
        }
    }

    fun getApiKey(): String {
        return encryptedPrefs.getString("api_key", "") ?: ""
    }

    fun clearApiKey() {
        encryptedPrefs.edit().remove("api_key").apply()
    }

    private fun validateApiKey(key: String): Boolean {
        return key.startsWith("sk-ant-") && key.length > 20
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// REPOSITORY - Enhanced with Compaction Support
// ═══════════════════════════════════════════════════════════════════════════════

sealed class ClaudeResult {
    data class Success(
        val response: String, 
        val usage: ClaudeUsage?,
        val hasCompaction: Boolean = false
    ) : ClaudeResult()
    data class Streaming(val chunk: String, val totalResponse: String) : ClaudeResult()
    data class CompactionPaused(val compactionBlock: ContentBlock, val usage: ClaudeUsage?) : ClaudeResult()
    data class Error(val message: String, val code: Int? = null) : ClaudeResult()
    data object Loading : ClaudeResult()
}

class ClaudeRepository(
    private val apiKey: String,
    private val enableCompaction: Boolean = true
) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(this@ClaudeRepository.json)
        }
        install(Logging) {
            level = LogLevel.INFO
            logger = object : Logger {
                override fun log(message: String) {
                    if (!message.contains("sk-ant-")) {
                        println("Ktor: $message")
                    }
                }
            }
        }
        install(HttpTimeout) {
            requestTimeoutMillis = 3_600_000
            connectTimeoutMillis = 180_000
            socketTimeoutMillis = 3_600_000
        }
        install(HttpRequestRetry) {
            retryOnServerErrors(maxRetries = 3)
            exponentialDelay()
        }
        
        engine {
            config {
                retryOnConnectionFailure(true)
            }
        }
    }

    suspend fun sendMessage(
        messages: List<ClaudeMessage>,
        systemPrompt: String? = null,
        triggerThreshold: Int = 150_000,
        pauseAfterCompaction: Boolean = false,
        onProgress: suspend (ClaudeResult) -> Unit
    ) {
        try {
            onProgress(ClaudeResult.Loading)

            val contextManagement = if (enableCompaction) {
                ContextManagement(
                    edits = listOf(
                        CompactionEdit(
                            trigger = CompactionTrigger(value = triggerThreshold),
                            pauseAfterCompaction = pauseAfterCompaction
                        )
                    )
                )
            } else null

            val request = ClaudeApiRequest(
                model = "claude-opus-4-6",
                messages = messages,
                system = systemPrompt?.let { listOf(SystemBlock(text = it)) },
                maxTokens = 4096,
                contextManagement = contextManagement,
                stream = true
            )

            val response: HttpResponse = client.post("https://api.anthropic.com/v1/messages") {
                header("x-api-key", apiKey)
                header("anthropic-version", "2023-06-01")
                if (enableCompaction) {
                    header("anthropic-beta", "compact-2026-01-12")
                }
                contentType(ContentType.Application.Json)
                setBody(request)
            }

            if (response.status.value !in 200..299) {
                handleHttpError(response, onProgress)
                return
            }

            handleStreamingResponse(response, pauseAfterCompaction, onProgress)

        } catch (e: HttpRequestTimeoutException) {
            onProgress(ClaudeResult.Error("⏰ Превышено время ожидания (60 мин). Проверьте соединение"))
        } catch (e: java.io.IOException) {
            onProgress(ClaudeResult.Error("🌐 Проблема с сетью: ${e.message}"))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            onProgress(ClaudeResult.Error("❌ ${e.javaClass.simpleName}: ${e.message}"))
        }
    }

    private suspend fun handleStreamingResponse(
        response: HttpResponse,
        pauseAfterCompaction: Boolean,
        onProgress: suspend (ClaudeResult) -> Unit
    ) {
        val fullResponse = StringBuilder(1_000_000)
        var inputTokens = 0
        var outputTokens = 0
        var hasCompaction = false
        var compactionBlock: ContentBlock? = null
        
        var lastUpdateTime = System.currentTimeMillis()
        val throttleMs = 100L

        val channel: ByteReadChannel = response.bodyAsChannel()
        
        while (!channel.isClosedForRead) {
            val line = channel.readUTF8Line() ?: break
            
            if (!line.startsWith("data: ")) continue
            val data = line.removePrefix("data: ").trim()
            
            if (data.isEmpty() || data == "[DONE]") break

            try {
                val event = json.decodeFromString<ClaudeStreamEvent>(data)

                when (event.type) {
                    "message_start" -> {
                        inputTokens = event.message?.usage?.inputTokens ?: 0
                    }
                    
                    "content_block_start" -> {
                        // Check if this is a compaction block
                        if (event.message?.content?.firstOrNull()?.type == "compaction") {
                            hasCompaction = true
                        }
                    }
                    
                    "content_block_delta" -> {
                        val deltaType = event.delta?.type
                        
                        when (deltaType) {
                            "text_delta" -> {
                                val text = event.delta.text
                                if (text != null) {
                                    fullResponse.append(text)
                                    
                                    val now = System.currentTimeMillis()
                                    if (text.length > 10 && now - lastUpdateTime > throttleMs) {
                                        onProgress(
                                            ClaudeResult.Streaming(
                                                chunk = text,
                                                totalResponse = fullResponse.toString()
                                            )
                                        )
                                        lastUpdateTime = now
                                    }
                                }
                            }
                            "compaction_delta" -> {
                                val content = event.delta.content
                                if (content != null) {
                                    compactionBlock = ContentBlock(
                                        type = "compaction",
                                        content = content
                                    )
                                    hasCompaction = true
                                }
                            }
                        }
                    }
                    
                    "message_delta" -> {
                        outputTokens = event.usage?.outputTokens ?: 0
                    }
                    
                    "message_stop" -> {
                        if (pauseAfterCompaction && hasCompaction && compactionBlock != null) {
                            onProgress(
                                ClaudeResult.CompactionPaused(
                                    compactionBlock = compactionBlock,
                                    usage = ClaudeUsage(inputTokens, outputTokens)
                                )
                            )
                        } else {
                            onProgress(
                                ClaudeResult.Success(
                                    response = fullResponse.toString(),
                                    usage = ClaudeUsage(inputTokens, outputTokens),
                                    hasCompaction = hasCompaction
                                )
                            )
                        }
                    }
                    
                    "error" -> {
                        val errorMsg = event.error?.message ?: "Неизвестная ошибка"
                        onProgress(ClaudeResult.Error("API Error: $errorMsg"))
                        break
                    }
                }
            } catch (e: Exception) {
                println("Parse error: ${e.message}")
                continue
            }
        }
    }

    private suspend fun handleHttpError(
        response: HttpResponse,
        onProgress: suspend (ClaudeResult) -> Unit
    ) {
        val errorBody = try {
            response.bodyAsText()
        } catch (e: Exception) {
            "Не удалось прочитать тело ошибки"
        }

        val errorMessage = when (response.status.value) {
            401 -> "❌ Неверный API ключ"
            429 -> "❌ Превышен лимит запросов. Подождите"
            413 -> "❌ Запрос слишком большой. Уменьшите файл"
            500, 502, 503, 529 -> "❌ Сервер перегружен (${response.status.value})"
            else -> "❌ HTTP ${response.status.value}:\n\n$errorBody"
        }

        onProgress(ClaudeResult.Error(errorMessage, response.status.value))
    }

    fun close() {
        client.close()
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// FILE MANAGER - Optimized for Large Files
// ═══════════════════════════════════════════════════════════════════════════════

sealed class FileResult {
    data class Success(
        val content: String, 
        val sizeBytes: Long, 
        val estimatedTokens: Int,
        val fileName: String
    ) : FileResult()
    data class Error(val message: String) : FileResult()
}

class SecureFileManager(private val context: Context) {

    private val maxFileSizeBytes = 10 * 1024 * 1024L // Increased to 10MB for 700KB files

    suspend fun loadFileFromUri(
        uri: Uri,
        onProgress: (Int) -> Unit = {}
    ): FileResult = withContext(Dispatchers.IO) {
        try {
            val contentResolver = context.contentResolver
            
            val fileName = getFileName(uri) ?: "unknown"
            val mimeType = contentResolver.getType(uri) ?: ""
            
            if (!isAcceptedFileType(mimeType, fileName)) {
                return@withContext FileResult.Error(
                    "❌ Только текстовые файлы (.txt, .kt, .java, .json, .md и т.д.)\nПолучен: $mimeType"
                )
            }
            
            val fileSize = contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                descriptor.statSize
            } ?: 0L

            if (fileSize > maxFileSizeBytes) {
                val sizeMB = fileSize / (1024.0 * 1024.0)
                return@withContext FileResult.Error(
                    "❌ Файл слишком большой (%.2f МБ). Максимум: 10 МБ".format(sizeMB)
                )
            }

            // Pre-allocate with exact size for better memory usage
            val charBuffer = StringBuilder(fileSize.toInt())
            var bytesReadTotal = 0L

            contentResolver.openInputStream(uri)?.use { inputStream ->
                val buffer = ByteArray(16384) // Larger buffer for better performance
                var bytesRead: Int
                
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    bytesReadTotal += bytesRead
                    charBuffer.append(String(buffer, 0, bytesRead, Charsets.UTF_8))
                    
                    val progress = ((bytesReadTotal * 100) / fileSize).toInt().coerceIn(0, 100)
                    onProgress(progress)
                }
            }

            val content = charBuffer.toString()
            val estimatedTokens = estimateTokenCount(content)

            FileResult.Success(
                content = content,
                sizeBytes = fileSize,
                estimatedTokens = estimatedTokens,
                fileName = fileName
            )

        } catch (e: OutOfMemoryError) {
            FileResult.Error("❌ Недостаточно памяти. Закройте другие приложения и повторите")
        } catch (e: Exception) {
            FileResult.Error("❌ Ошибка чтения файла: ${e.message}")
        }
    }

    private fun isAcceptedFileType(mimeType: String, fileName: String): Boolean {
        return mimeType.startsWith("text/") || 
               mimeType == "application/json" || 
               mimeType == "application/x-kotlin" ||
               mimeType.contains("kotlin") ||
               mimeType.contains("java") ||
               fileName.endsWith(".kt") ||
               fileName.endsWith(".java") ||
               fileName.endsWith(".json") ||
               fileName.endsWith(".md") ||
               fileName.endsWith(".txt")
    }

    private fun getFileName(uri: Uri): String? {
        return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            cursor.moveToFirst()
            cursor.getString(nameIndex)
        }
    }

    private fun estimateTokenCount(text: String): Int {
        // More accurate token estimation for code
        val lines = text.lines()
        val codeLines = lines.count { it.trim().isNotEmpty() }
        val avgCharsPerLine = if (lines.isNotEmpty()) text.length / lines.size else 0
        
        // Code typically has higher token density
        val divisor = if (avgCharsPerLine > 50 || codeLines > lines.size * 0.8) 2.8 else 3.5
        return (text.length / divisor).toInt()
    }

    suspend fun saveResponseToFile(
        content: String,
        customFileName: String? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            val downloadsDir = context.getExternalFilesDir(null)
                ?: return@withContext Result.failure(Exception("Директория недоступна"))

            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())
                .format(Date())
            
            val filename = customFileName ?: "claude_opus46_response_$timestamp.txt"
            val file = File(downloadsDir, filename)
            
            file.writeText(buildString {
                appendLine("═══════════════════════════════════════════════════════════════")
                appendLine("Claude Opus 4.6 Response")
                appendLine("Generated: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())}")
                appendLine("═══════════════════════════════════════════════════════════════")
                appendLine()
                append(content)
            }, Charsets.UTF_8)
            
            Result.success(file)

        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// VIEW MODEL - Enhanced with Compaction Management
// ═══════════════════════════════════════════════════════════════════════════════

data class ClaudeUiState(
    val apiKey: String = "",
    val fileUri: Uri? = null,
    val fileContent: String = "",
    val fileName: String = "",
    val fileInfo: String = "",
    val query: String = "",
    val response: String = "",
    val status: String = "Готов к работе",
    val isLoading: Boolean = false,
    val loadingProgress: Int = 0,
    val progress: String = "",
    val useSystemPrompt: Boolean = false,
    val conversationMode: ConversationMode = ConversationMode.SIMPLE,
    val estimatedTokens: Int = 0,
    val estimatedCost: Float = 0f,
    val maxPossibleOutput: Int = 32000,
    val saveStatus: String = "",
    val compactionCount: Int = 0,
    val totalTokensUsed: Int = 0,
    val enableCompaction: Boolean = true,
    val compactionThreshold: Int = 150_000
)

enum class ConversationMode {
    SIMPLE,         // No history, no compaction
    STEP_BY_STEP,  // With system prompt for complex reasoning
    COMPACTION     // With automatic context compaction
}

@HiltViewModel
class ClaudeHelperViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow(ClaudeUiState())
    val uiState: StateFlow<ClaudeUiState> = _uiState.asStateFlow()

    private lateinit var secureStorage: SecureApiKeyStore
    private lateinit var fileManager: SecureFileManager
    
    private var repository: ClaudeRepository? = null
    private var conversationHistory = mutableListOf<ClaudeMessage>()
    private var currentJob: Job? = null
    private var hasActiveCompaction = false

    // Token pricing (approximate)
    private val INPUT_TOKEN_PRICE = 0.015f / 1000  // $15 per million
    private val OUTPUT_TOKEN_PRICE = 0.075f / 1000 // $75 per million

    fun initialize(context: Context) {
        secureStorage = SecureApiKeyStore(context)
        fileManager = SecureFileManager(context)
        
        val savedKey = secureStorage.getApiKey()
        if (savedKey.isNotEmpty()) {
            _uiState.update { it.copy(apiKey = savedKey) }
            initializeRepository(savedKey)
        }
    }

    fun setApiKey(key: String) {
        _uiState.update { it.copy(apiKey = key) }
        
        if (key.startsWith("sk-ant-") && key.length > 20) {
            secureStorage.saveApiKey(key)
            initializeRepository(key)
            _uiState.update { it.copy(status = "✅ API ключ сохранен") }
        }
    }

    fun setQuery(text: String) {
        _uiState.update { it.copy(query = text) }
    }

    fun setConversationMode(mode: ConversationMode) {
        _uiState.update { 
            it.copy(
                conversationMode = mode,
                useSystemPrompt = mode == ConversationMode.STEP_BY_STEP
            )
        }
    }

    fun toggleCompaction() {
        _uiState.update { it.copy(enableCompaction = !it.enableCompaction) }
    }

    fun setCompactionThreshold(threshold: Int) {
        if (threshold >= 50_000) {
            _uiState.update { it.copy(compactionThreshold = threshold) }
        }
    }

    fun loadFileFromUri(uri: Uri) {
        viewModelScope.launch {
            _uiState.update { 
                it.copy(
                    status = "📂 Загрузка файла...", 
                    fileUri = uri,
                    loadingProgress = 0
                ) 
            }

            when (val result = fileManager.loadFileFromUri(uri) { progress ->
                _uiState.update { it.copy(loadingProgress = progress) }
            }) {
                is FileResult.Success -> {
                    val contextLimit = 200_000
                    val systemPromptTokens = if (_uiState.value.useSystemPrompt) 200 else 0
                    val maxPossibleOutput = minOf(
                        32000,
                        contextLimit - result.estimatedTokens - systemPromptTokens - 2_000
                    ).coerceAtLeast(1_000)

                    val estimatedInputCost = result.estimatedTokens * INPUT_TOKEN_PRICE
                    val estimatedOutputCost = maxPossibleOutput * OUTPUT_TOKEN_PRICE
                    val totalCost = estimatedInputCost + estimatedOutputCost
                    
                    // Auto-enable compaction for large files
                    val shouldEnableCompaction = result.estimatedTokens > 100_000
                    
                    _uiState.update {
                        it.copy(
                            fileContent = result.content,
                            fileName = result.fileName,
                            estimatedTokens = result.estimatedTokens,
                            estimatedCost = totalCost,
                            enableCompaction = shouldEnableCompaction || it.enableCompaction,
                            maxPossibleOutput = maxPossibleOutput,
                            fileInfo = buildString {
                                appendLine("📄 ${result.fileName}")
                                appendLine("📊 %.2f МБ | ~%,d токенов".format(
                                    result.sizeBytes / (1024.0 * 1024.0),
                                    result.estimatedTokens
                                ))
                                appendLine("💰 ~$%.3f (вход: $%.3f + выход: $%.3f)".format(
                                    totalCost, estimatedInputCost, estimatedOutputCost
                                ))
                                if (maxPossibleOutput < 32000) {
                                    append("⚠️ Max output: %,d токенов".format(maxPossibleOutput))
                                }
                                if (shouldEnableCompaction) {
                                    append("\n🔄 Compaction: авто-включено")
                                }
                            },
                            status = when {
                                result.estimatedTokens > 150_000 -> "⚠️ Очень большой файл! Compaction рекомендуется"
                                maxPossibleOutput < 10_000 -> "⚠️ Ограниченный output: ${maxPossibleOutput/1000}K токенов"
                                else -> "✅ Файл загружен"
                            },
                            loadingProgress = 100
                        )
                    }
                }
                is FileResult.Error -> {
                    _uiState.update { 
                        it.copy(
                            status = result.message,
                            fileContent = "",
                            fileInfo = "",
                            loadingProgress = 0
                        ) 
                    }
                }
            }
        }
    }

    fun sendQuery() {
        if (repository == null) {
            _uiState.update { it.copy(status = "❌ Сначала введите API ключ") }
            return
        }

        if (_uiState.value.query.isBlank()) {
            _uiState.update { it.copy(status = "❌ Введите запрос") }
            return
        }

        currentJob?.cancel()
        currentJob = viewModelScope.launch {
            val state = _uiState.value
            
            val userContent = buildString {
                append(state.query)
                if (state.fileContent.isNotEmpty()) {
                    append("\n\n```${state.fileName.substringAfterLast('.')}\n")
                    append(state.fileContent)
                    append("\n```")
                }
            }

            val newUserMessage = ClaudeMessage("user", userContent)
            
            // Handle compaction blocks from previous responses
            val messages = mutableListOf<ClaudeMessage>()
            var lastCompactionIndex = -1
            
            conversationHistory.forEachIndexed { index, message ->
                if (message.content.any { it.type == "compaction" }) {
                    lastCompactionIndex = index
                }
            }
            
            // If we have a compaction, start from there
            if (lastCompactionIndex >= 0) {
                messages.addAll(conversationHistory.subList(lastCompactionIndex, conversationHistory.size))
            } else if (state.conversationMode != ConversationMode.SIMPLE) {
                messages.addAll(conversationHistory)
            }
            
            messages.add(newUserMessage)

            val systemPrompt = when (state.conversationMode) {
                ConversationMode.STEP_BY_STEP -> 
                    "Think step-by-step before answering. Break down complex problems into smaller parts. " +
                    "Provide detailed explanations and consider edge cases."
                ConversationMode.COMPACTION ->
                    "You are a helpful assistant. Maintain context across our conversation."
                else -> null
            }

            _uiState.update {
                it.copy(
                    isLoading = true,
                    response = "",
                    saveStatus = "",
                    status = "🚀 Подключение к Claude Opus 4.6...",
                    progress = buildString {
                        if (state.enableCompaction) append("🔄 Compaction: ON")
                        if (state.conversationMode == ConversationMode.STEP_BY_STEP) {
                            if (state.enableCompaction) append(" | ")
                            append("📝 Step-by-step")
                        }
                    }
                )
            }

            repository?.sendMessage(
                messages = messages,
                systemPrompt = systemPrompt,
                triggerThreshold = state.compactionThreshold,
                pauseAfterCompaction = false // For now, we'll handle it automatically
            ) { result ->
                when (result) {
                    is ClaudeResult.Loading -> {
                        _uiState.update { it.copy(status = "⚡ Отправка запроса...") }
                    }
                    
                    is ClaudeResult.Streaming -> {
                        _uiState.update {
                            it.copy(
                                response = result.totalResponse,
                                status = "⚡ Получение ответа...",
                                progress = "%,d символов".format(result.totalResponse.length)
                            )
                        }
                    }
                    
                    is ClaudeResult.Success -> {
                        handleSuccessfulResponse(result, newUserMessage)
                    }
                    
                    is ClaudeResult.CompactionPaused -> {
                        handleCompactionPause(result, messages)
                    }
                    
                    is ClaudeResult.Error -> {
                        _uiState.update {
                            it.copy(
                                response = result.message,
                                isLoading = false,
                                status = "❌ Ошибка"
                            )
                        }
                    }
                }
            }
        }
    }

    private suspend fun handleSuccessfulResponse(
        result: ClaudeResult.Success,
        userMessage: ClaudeMessage
    ) {
        if (_uiState.value.conversationMode != ConversationMode.SIMPLE) {
            if (!hasActiveCompaction) {
                conversationHistory.add(userMessage)
            }
            
            val assistantMessage = ClaudeMessage(
                role = "assistant",
                content = if (result.hasCompaction) {
                    // Find compaction block in response
                    listOf(ContentBlock(type = "text", text = result.response))
                } else {
                    listOf(ContentBlock(type = "text", text = result.response))
                }
            )
            conversationHistory.add(assistantMessage)
        }
        
        val usage = result.usage
        val inputCost = (usage?.inputTokens ?: 0) * INPUT_TOKEN_PRICE
        val outputCost = (usage?.outputTokens ?: 0) * OUTPUT_TOKEN_PRICE
        val totalCost = inputCost + outputCost
        
        _uiState.update { state ->
            state.copy(
                response = result.response,
                isLoading = false,
                status = "✅ In: %,d | Out: %,d | 💰 $%.3f".format(
                    usage?.inputTokens ?: 0,
                    usage?.outputTokens ?: 0,
                    totalCost
                ),
                progress = when {
                    result.hasCompaction -> "🔄 Compacted | Messages: ${conversationHistory.size / 2}"
                    state.conversationMode != ConversationMode.SIMPLE -> "Messages: ${conversationHistory.size / 2}"
                    else -> "Single message mode"
                },
                totalTokensUsed = state.totalTokensUsed + (usage?.inputTokens ?: 0) + (usage?.outputTokens ?: 0),
                compactionCount = if (result.hasCompaction) state.compactionCount + 1 else state.compactionCount
            )
        }

        // Auto-save response
        fileManager.saveResponseToFile(result.response)
            .onSuccess { 
                println("✅ Auto-saved to: ${it.absolutePath}")
            }
            .onFailure { 
                println("❌ Auto-save error: ${it.message}")
            }
    }

    private suspend fun handleCompactionPause(
        result: ClaudeResult.CompactionPaused,
        messages: List<ClaudeMessage>
    ) {
        // Add compaction block to history
        val compactionMessage = ClaudeMessage(
            role = "assistant",
            content = listOf(result.compactionBlock)
        )
        
        // Clear old history and keep only compaction
        conversationHistory.clear()
        conversationHistory.add(compactionMessage)
        hasActiveCompaction = true
        
        _uiState.update { state ->
            state.copy(
                compactionCount = state.compactionCount + 1,
                status = "🔄 Compaction выполнено, продолжаем...",
                progress = "Compaction #${state.compactionCount + 1}"
            )
        }
        
        // Continue conversation with compacted context
        // This would require re-sending with the compacted context
    }

    fun saveResponseToFile() {
        viewModelScope.launch {
            val response = _uiState.value.response
            
            if (response.isEmpty()) {
                _uiState.update { it.copy(saveStatus = "❌ Нет ответа для сохранения") }
                return@launch
            }

            _uiState.update { it.copy(saveStatus = "💾 Сохранение...") }

            val fileName = if (_uiState.value.fileName.isNotEmpty()) {
                "response_${_uiState.value.fileName.substringBeforeLast('.')}_${System.currentTimeMillis()}.txt"
            } else null

            fileManager.saveResponseToFile(response, fileName)
                .onSuccess { file ->
                    _uiState.update { 
                        it.copy(
                            saveStatus = "✅ ${file.name}"
                        ) 
                    }
                    
                    delay(3000)
                    _uiState.update { it.copy(saveStatus = "") }
                }
                .onFailure { error ->
                    _uiState.update { 
                        it.copy(saveStatus = "❌ ${error.message}") 
                    }
                    
                    delay(5000)
                    _uiState.update { it.copy(saveStatus = "") }
                }
        }
    }

    fun cancelRequest() {
        currentJob?.cancel()
        _uiState.update {
            it.copy(
                isLoading = false,
                status = "⛔ Отменено"
            )
        }
    }

    fun clearHistory() {
        conversationHistory.clear()
        hasActiveCompaction = false
        _uiState.update {
            it.copy(
                response = "",
                progress = "",
                status = "🗑️ История очищена",
                saveStatus = "",
                compactionCount = 0,
                totalTokensUsed = 0
            )
        }
    }

    private fun initializeRepository(apiKey: String) {
        repository?.close()
        repository = ClaudeRepository(
            apiKey = apiKey,
            enableCompaction = _uiState.value.enableCompaction
        )
    }

    override fun onCleared() {
        super.onCleared()
        repository?.close()
        currentJob?.cancel()
        conversationHistory.clear()
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// UI COMPONENTS - Optimized for Performance
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
fun OptimizedResponseViewer(
    content: String,
    modifier: Modifier = Modifier
) {
    val lines = remember(content) {
        val allLines = content.lines()
        
        // For very large responses, show last 20K lines
        if (allLines.size > 20_000) {
            listOf("⚠️ Showing last 20,000 lines of ${allLines.size} total") + 
            allLines.takeLast(20_000)
        } else {
            allLines
        }
    }
    
    val listState = rememberLazyListState()
    
    LaunchedEffect(lines.size) {
        if (lines.size in 10..5000) {
            listState.animateScrollToItem(maxOf(0, lines.size - 1))
        }
    }
    
    SelectionContainer {
        LazyColumn(
            state = listState,
            modifier = modifier,
            contentPadding = PaddingValues(vertical = 8.dp)
        ) {
            items(
                items = lines,
                key = { line -> line.hashCode() }
            ) { line ->
                val isCode = line.trimStart().startsWith("```") || 
                           line.startsWith("    ") ||
                           line.contains("{") || line.contains("}")
                
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = if (isCode) FontFamily.Monospace else FontFamily.Default,
                        fontSize = if (isCode) MaterialTheme.typography.bodySmall.fontSize * 0.9f 
                                  else MaterialTheme.typography.bodySmall.fontSize
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (isCode) {
                                Modifier
                                    .background(Color(0xFF2D2D30))
                                    .padding(horizontal = 8.dp, vertical = 2.dp)
                            } else {
                                Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            }
                        ),
                    color = if (isCode) Color(0xFFD4D4D4) else MaterialTheme.colorScheme.onSurface
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// MAIN UI SCREEN
// ═══════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClaudeHelperScreen(
    onBack: () -> Unit,
    viewModel: ClaudeHelperViewModel = viewModel()
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val clipboardManager = LocalClipboardManager.current

    var showApiKey by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.loadFileFromUri(it) }
    }

    LaunchedEffect(Unit) {
        viewModel.initialize(context)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Claude Opus 4.6 🚀")
                        if (uiState.progress.isNotEmpty()) {
                            Text(
                                uiState.progress,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Назад")
                    }
                },
                actions = {
                    if (uiState.totalTokensUsed > 0) {
                        Text(
                            "%,d tokens".format(uiState.totalTokensUsed),
                            style = MaterialTheme.typography.labelSmall,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                    }
                    IconButton(onClick = { showSettings = !showSettings }) {
                        Icon(Icons.Default.Settings, "Настройки")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // API KEY CARD
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (uiState.apiKey.isEmpty()) 
                        MaterialTheme.colorScheme.errorContainer 
                    else MaterialTheme.colorScheme.surface
                )
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("🔑 API Ключ", style = MaterialTheme.typography.titleMedium)
                        IconButton(onClick = { showApiKey = !showApiKey }) {
                            Icon(
                                if (showApiKey) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                "Показать/скрыть"
                            )
                        }
                    }
                    
                    Spacer(Modifier.height(8.dp))
                    
                    OutlinedTextField(
                        value = uiState.apiKey,
                        onValueChange = viewModel::setApiKey,
                        label = { Text("Anthropic API Key") },
                        visualTransformation = if (showApiKey) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("sk-ant-api03-...") },
                        isError = uiState.apiKey.isNotEmpty() && !uiState.apiKey.startsWith("sk-ant-")
                    )
                    
                    if (uiState.apiKey.isEmpty()) {
                        Text(
                            "⚠️ Требуется API ключ от Anthropic",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }

            // SETTINGS CARD (Collapsible)
            AnimatedVisibility(visible = showSettings) {
                Card {
                    Column(Modifier.padding(16.dp)) {
                        Text("⚙️ Настройки", style = MaterialTheme.typography.titleMedium)
                        
                        Spacer(Modifier.height(12.dp))
                        
                        // Conversation Mode Selection
                        Text("Режим работы:", style = MaterialTheme.typography.labelLarge)
                        Column(Modifier.padding(vertical = 8.dp)) {
                            ConversationMode.entries.forEach { mode ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(
                                        selected = uiState.conversationMode == mode,
                                        onClick = { viewModel.setConversationMode(mode) }
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            text = when (mode) {
                                                ConversationMode.SIMPLE -> "Simple - Без истории"
                                                ConversationMode.STEP_BY_STEP -> "Step-by-Step - Пошаговый анализ"
                                                ConversationMode.COMPACTION -> "Compaction - С историей и сжатием"
                                            },
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Text(
                                            text = when (mode) {
                                                ConversationMode.SIMPLE -> "Каждый запрос независимый"
                                                ConversationMode.STEP_BY_STEP -> "Детальный анализ сложных задач"
                                                ConversationMode.COMPACTION -> "Длинные диалоги с автосжатием"
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }
                            }
                        }
                        
                        if (uiState.conversationMode == ConversationMode.COMPACTION) {
                            Divider(Modifier.padding(vertical = 8.dp))
                            
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Checkbox(
                                    checked = uiState.enableCompaction,
                                    onCheckedChange = { viewModel.toggleCompaction() }
                                )
                                Text(
                                    "Включить Compaction",
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            
                            if (uiState.enableCompaction) {
                                Text(
                                    "Порог срабатывания: ${"%,d".format(uiState.compactionThreshold)} токенов",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Slider(
                                    value = uiState.compactionThreshold.toFloat(),
                                    onValueChange = { viewModel.setCompactionThreshold(it.toInt()) },
                                    valueRange = 50_000f..200_000f,
                                    steps = 5,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                        }
                    }
                }
            }

            // FILE CARD
            Card {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("📄 Файл (до 10 МБ)", style = MaterialTheme.typography.titleMedium)
                        Button(
                            onClick = { filePickerLauncher.launch(arrayOf("*/*")) },
                            enabled = !uiState.isLoading
                        ) {
                            Icon(Icons.Default.FileOpen, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Выбрать")
                        }
                    }

                    if (uiState.loadingProgress in 1..99) {
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = { uiState.loadingProgress / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            "Загрузка: ${uiState.loadingProgress}%",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }

                    if (uiState.fileInfo.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = when {
                                    uiState.estimatedTokens > 150_000 -> MaterialTheme.colorScheme.errorContainer
                                    uiState.estimatedTokens > 100_000 -> MaterialTheme.colorScheme.tertiaryContainer
                                    else -> MaterialTheme.colorScheme.primaryContainer
                                }
                            )
                        ) {
                            Text(
                                uiState.fileInfo,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }
                }
            }

            // QUERY CARD
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("💬 Запрос", style = MaterialTheme.typography.titleMedium)
                    
                    if (uiState.conversationMode == ConversationMode.STEP_BY_STEP) {
                        Text(
                            "💡 Режим пошагового анализа активен",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                    
                    Spacer(Modifier.height(8.dp))
                    
                    OutlinedTextField(
                        value = uiState.query,
                        onValueChange = viewModel::setQuery,
                        label = { Text("Что нужно сделать?") },
                        placeholder = { 
                            Text(
                                when (uiState.conversationMode) {
                                    ConversationMode.SIMPLE -> "Проанализируй код"
                                    ConversationMode.STEP_BY_STEP -> "Объясни алгоритм пошагово"
                                    ConversationMode.COMPACTION -> "Давай обсудим архитектуру"
                                }
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        maxLines = 10,
                        enabled = !uiState.isLoading
                    )

                    Spacer(Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (uiState.isLoading) {
                            Button(
                                onClick = viewModel::cancelRequest,
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = MaterialTheme.colorScheme.error
                                ),
                                modifier = Modifier.weight(1f)
                            ) {
                                CircularProgressIndicator(
                                    Modifier.size(18.dp),
                                    strokeWidth = 2.dp,
                                    color = MaterialTheme.colorScheme.onError
                                )
                                Spacer(Modifier.width(8.dp))
                                Text("СТОП")
                            }
                        } else {
                            Button(
                                onClick = viewModel::sendQuery,
                                modifier = Modifier.weight(1f),
                                enabled = uiState.query.isNotEmpty() && 
                                         uiState.apiKey.startsWith("sk-ant-")
                            ) {
                                Icon(Icons.Default.Send, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Отправить")
                            }
                        }

                        if (uiState.conversationMode != ConversationMode.SIMPLE) {
                            OutlinedButton(
                                onClick = viewModel::clearHistory,
                                enabled = !uiState.isLoading && 
                                         (conversationHistory.isNotEmpty() || 
                                          uiState.response.isNotEmpty())
                            ) {
                                Icon(Icons.Default.Delete, null, Modifier.size(18.dp))
                                if (uiState.compactionCount > 0) {
                                    Spacer(Modifier.width(4.dp))
                                    Text("(${uiState.compactionCount})")
                                }
                            }
                        }
                    }
                }
            }

            // RESPONSE CARD
            Card {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("🤖 Ответ Opus 4.6", style = MaterialTheme.typography.titleMedium)
                            if (uiState.compactionCount > 0) {
                                Text(
                                    "Compactions: ${uiState.compactionCount}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }

                        if (uiState.response.isNotEmpty()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                IconButton(
                                    onClick = viewModel::saveResponseToFile,
                                    enabled = !uiState.isLoading
                                ) {
                                    Icon(Icons.Default.Save, "Сохранить")
                                }
                                
                                IconButton(
                                    onClick = {
                                        clipboardManager.setText(AnnotatedString(uiState.response))
                                    }
                                ) {
                                    Icon(Icons.Default.ContentCopy, "Копировать")
                                }
                            }
                        }
                    }

                    if (uiState.saveStatus.isNotEmpty()) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            uiState.saveStatus,
                            style = MaterialTheme.typography.bodySmall,
                            color = when {
                                uiState.saveStatus.startsWith("✅") -> MaterialTheme.colorScheme.primary
                                uiState.saveStatus.startsWith("❌") -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurface
                            }
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    if (uiState.response.isEmpty() && !uiState.isLoading) {
                        Text(
                            "Ответ появится здесь...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                            modifier = Modifier.padding(vertical = 32.dp)
                        )
                    } else {
                        OptimizedResponseViewer(
                            content = uiState.response,
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 600.dp)
                        )
                    }
                }
            }

            // STATUS CARD
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = when {
                        uiState.status.startsWith("✅") -> MaterialTheme.colorScheme.primaryContainer
                        uiState.status.startsWith("❌") || uiState.status.startsWith("⏰") ->
                            MaterialTheme.colorScheme.errorContainer
                        uiState.status.startsWith("⚡") || uiState.status.startsWith("🚀") ->
                            MaterialTheme.colorScheme.tertiaryContainer
                        uiState.status.startsWith("🔄") -> MaterialTheme.colorScheme.secondaryContainer
                        else -> MaterialTheme.colorScheme.surface
                    }
                )
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (uiState.isLoading) {
                        CircularProgressIndicator(
                            Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        uiState.status,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}