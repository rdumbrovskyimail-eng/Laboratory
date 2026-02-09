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
import javax.inject.Inject

// ═══════════════════════════════════════════════════════════════════════════════
// DATA MODELS (FIXED FOR OPUS 4.6)
// ═══════════════════════════════════════════════════════════════════════════════

@Serializable
data class ClaudeMessage(
    val role: String,
    val content: String
)

@Serializable
data class ClaudeApiRequest(
    val model: String = "claude-opus-4-20250514",
    @SerialName("max_tokens") val maxTokens: Int = 32000, // FIXED: API limit is 32K, not 128K
    val messages: List<ClaudeMessage>,
    val system: String? = null, // FIXED: String, not List<SystemBlock>
    val stream: Boolean = false
)

@Serializable
data class ClaudeContentBlock(
    val type: String,
    val text: String? = null
)

@Serializable
data class ClaudeApiResponse(
    val id: String? = null,
    val type: String? = null,
    val role: String? = null,
    val content: List<ClaudeContentBlock>? = null,
    val model: String? = null,
    @SerialName("stop_reason") val stopReason: String? = null,
    val usage: ClaudeUsage? = null
)

@Serializable
data class ClaudeUsage(
    @SerialName("input_tokens") val inputTokens: Int? = null,
    @SerialName("output_tokens") val outputTokens: Int? = null
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
    val text: String? = null
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
// REPOSITORY (FIXED)
// ═══════════════════════════════════════════════════════════════════════════════

sealed class ClaudeResult {
    data class Success(val response: String, val usage: ClaudeUsage?) : ClaudeResult()
    data class Streaming(val chunk: String, val totalResponse: String) : ClaudeResult()
    data class Error(val message: String, val code: Int? = null) : ClaudeResult()
    data object Loading : ClaudeResult()
}

class ClaudeRepository(private val apiKey: String) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
    }

    private val client = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(json)
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
        onProgress: suspend (ClaudeResult) -> Unit
    ) {
        try {
            onProgress(ClaudeResult.Loading)

            val request = ClaudeApiRequest(
                messages = messages,
                system = systemPrompt, // FIXED: Now just a String
                maxTokens = 32000, // FIXED: Use constant 32K
                stream = true
            )

            val response: HttpResponse = client.post("https://api.anthropic.com/v1/messages") {
                header("x-api-key", apiKey)
                header("anthropic-version", "2023-06-01")
                contentType(ContentType.Application.Json)
                setBody(request)
            }

            if (response.status.value !in 200..299) {
                handleHttpError(response, onProgress)
                return
            }

            handleStreamingResponse(response, onProgress)

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
        onProgress: suspend (ClaudeResult) -> Unit
    ) {
        val fullResponse = StringBuilder(500_000)
        var inputTokens = 0
        var outputTokens = 0
        
        var lastUpdateTime = System.currentTimeMillis()
        val throttleMs = 200L

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
                    
                    "content_block_delta" -> {
                        val text = event.delta?.text
                        
                        if (text != null) {
                            fullResponse.append(text)
                        }
                        
                        val now = System.currentTimeMillis()
                        if (text != null && text.length > 20 && now - lastUpdateTime > throttleMs) {
                            onProgress(
                                ClaudeResult.Streaming(
                                    chunk = text,
                                    totalResponse = fullResponse.toString()
                                )
                            )
                            lastUpdateTime = now
                        }
                    }
                    
                    "message_delta" -> {
                        outputTokens = event.usage?.outputTokens ?: 0
                    }
                    
                    "message_stop" -> {
                        onProgress(
                            ClaudeResult.Success(
                                response = fullResponse.toString(),
                                usage = ClaudeUsage(inputTokens, outputTokens)
                            )
                        )
                    }
                    
                    "error" -> {
                        val errorMsg = event.error?.message ?: "Неизвестная ошибка"
                        onProgress(ClaudeResult.Error("API Error: $errorMsg"))
                        break
                    }
                }
            } catch (e: Exception) {
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
// FILE MANAGER
// ═══════════════════════════════════════════════════════════════════════════════

sealed class FileResult {
    data class Success(val content: String, val sizeBytes: Long, val estimatedTokens: Int) : FileResult()
    data class Error(val message: String) : FileResult()
}

class SecureFileManager(private val context: Context) {

    private val maxFileSizeBytes = 5 * 1024 * 1024L

    suspend fun loadFileFromUri(
        uri: Uri,
        onProgress: (Int) -> Unit = {}
    ): FileResult = withContext(Dispatchers.IO) {
        try {
            val contentResolver = context.contentResolver
            
            val mimeType = contentResolver.getType(uri) ?: ""
            if (!mimeType.startsWith("text/") && 
                mimeType != "application/json" && 
                mimeType != "application/x-kotlin" &&
                !mimeType.contains("kotlin") &&
                !mimeType.contains("java")) {
                return@withContext FileResult.Error(
                    "❌ Только текстовые файлы (.txt, .kt, .json, .md и т.д.)\nПолучен: $mimeType"
                )
            }
            
            val fileSize = contentResolver.openFileDescriptor(uri, "r")?.use { descriptor ->
                descriptor.statSize
            } ?: 0L

            if (fileSize > maxFileSizeBytes) {
                val sizeMB = fileSize / (1024.0 * 1024.0)
                return@withContext FileResult.Error(
                    "❌ Файл слишком большой (%.2f МБ). Максимум: 5 МБ".format(sizeMB)
                )
            }

            val charBuffer = StringBuilder((fileSize / 2).toInt())
            var bytesReadTotal = 0L

            contentResolver.openInputStream(uri)?.use { inputStream ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                
                while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                    bytesReadTotal += bytesRead
                    charBuffer.append(String(buffer, 0, bytesRead, Charsets.UTF_8))
                    
                    val progress = ((bytesReadTotal * 100) / fileSize).toInt().coerceIn(0, 100)
                    onProgress(progress)
                }
            }

            val estimatedTokens = (charBuffer.length / 3.2).toInt()

            FileResult.Success(
                content = charBuffer.toString(),
                sizeBytes = fileSize,
                estimatedTokens = estimatedTokens
            )

        } catch (e: OutOfMemoryError) {
            FileResult.Error("❌ Недостаточно памяти. Закройте другие приложения и повторите")
        } catch (e: Exception) {
            FileResult.Error("❌ Ошибка чтения файла: ${e.message}")
        }
    }

    suspend fun saveResponseToTxt(content: String): Result<File> = withContext(Dispatchers.IO) {
        try {
            val downloadsDir = context.getExternalFilesDir(null)
                ?: return@withContext Result.failure(Exception("Директория недоступна"))

            val timestamp = java.text.SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", java.util.Locale.getDefault())
                .format(java.util.Date())
            
            val filename = "claude_opus46_$timestamp.txt"
            val file = File(downloadsDir, filename)
            
            file.writeText(content, Charsets.UTF_8)
            Result.success(file)

        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    suspend fun saveResponse(content: String): Result<File> = withContext(Dispatchers.IO) {
        try {
            val downloadsDir = context.getExternalFilesDir(null)
                ?: return@withContext Result.failure(Exception("Директория недоступна"))

            val filename = "opus46_${System.currentTimeMillis()}.md"
            val file = File(downloadsDir, filename)
            
            file.writeText(content, Charsets.UTF_8)
            Result.success(file)

        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// VIEW MODEL (FIXED)
// ═══════════════════════════════════════════════════════════════════════════════

data class ClaudeUiState(
    val apiKey: String = "",
    val fileUri: Uri? = null,
    val fileContent: String = "",
    val fileInfo: String = "",
    val query: String = "",
    val response: String = "",
    val status: String = "Готов к работе",
    val isLoading: Boolean = false,
    val loadingProgress: Int = 0,
    val progress: String = "",
    val useSystemPrompt: Boolean = false,
    val conversationCount: Int = 0,
    val isLargeFileMode: Boolean = false,
    val estimatedTokens: Int = 0,
    val needsBetaMode: Boolean = false,
    val maxPossibleOutput: Int = 32000, // FIXED: API limit is 32K
    val saveStatus: String = ""
)

@HiltViewModel
class ClaudeHelperViewModel @Inject constructor() : ViewModel() {

    private val _uiState = MutableStateFlow(ClaudeUiState())
    val uiState: StateFlow<ClaudeUiState> = _uiState.asStateFlow()

    private lateinit var secureStorage: SecureApiKeyStore
    private lateinit var fileManager: SecureFileManager
    
    private var repository: ClaudeRepository? = null
    private var conversationHistory = mutableListOf<ClaudeMessage>()
    private var currentJob: Job? = null

    private val LARGE_FILE_THRESHOLD = 100_000 // ~30K tokens
    private val CRITICAL_THRESHOLD = 160_000 // ~50K tokens

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

    fun toggleSystemPrompt() {
        _uiState.update { it.copy(useSystemPrompt = !it.useSystemPrompt) }
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
                    val isLarge = result.content.length > LARGE_FILE_THRESHOLD
                    val isCritical = result.content.length > CRITICAL_THRESHOLD
                    
                    val contextLimit = 200_000 // Claude context window
                    val maxPossibleOutput = minOf(
                        32000, // API limit
                        contextLimit - result.estimatedTokens - 2_000
                    ).coerceAtLeast(1_000)
                    
                    _uiState.update {
                        it.copy(
                            fileContent = result.content,
                            estimatedTokens = result.estimatedTokens,
                            isLargeFileMode = isLarge,
                            needsBetaMode = isCritical,
                            maxPossibleOutput = maxPossibleOutput,
                            fileInfo = buildString {
                                append("✅ %.2f МБ (~%,d токенов)".format(
                                    result.sizeBytes / (1024.0 * 1024.0),
                                    result.estimatedTokens
                                ))
                                if (isLarge) append(" - LARGE MODE")
                                append("\n")
                                if (maxPossibleOutput < 32000) {
                                    append("⚠️ Max output: ~%,d токенов".format(maxPossibleOutput))
                                } else {
                                    append("✅ Max output: 32K токенов")
                                }
                            },
                            status = when {
                                isCritical -> "⚠️ КРИТИЧЕСКИЙ размер! Output ограничен ${maxPossibleOutput/1000}K токенов"
                                isLarge -> "⚠️ Большой файл! История диалога отключена"
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
            
            var userMessage = state.query
            if (state.fileContent.isNotEmpty()) {
                userMessage = "${state.query}\n\n```kotlin\n${state.fileContent}\n```"
            }

            val messages = if (state.isLargeFileMode) {
                listOf(ClaudeMessage("user", userMessage))
            } else {
                conversationHistory.add(ClaudeMessage("user", userMessage))
                conversationHistory.toList()
            }

            _uiState.update {
                it.copy(
                    isLoading = true,
                    response = "",
                    saveStatus = "",
                    status = when {
                        state.needsBetaMode -> "🚀 Критический размер: output до ${state.maxPossibleOutput/1000}K"
                        state.isLargeFileMode -> "🚀 Large File Mode: без истории"
                        else -> "🚀 Подключение к Opus 4.6..."
                    },
                    progress = if (state.useSystemPrompt) "📝 System Prompt" else ""
                )
            }

            repository?.sendMessage(
                messages = messages,
                systemPrompt = if (state.useSystemPrompt) {
                    "Think step-by-step before answering. Break down complex problems into smaller parts."
                } else null
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
                                progress = "${result.totalResponse.length} символов"
                            )
                        }
                    }
                    is ClaudeResult.Success -> {
                        if (!state.isLargeFileMode) {
                            conversationHistory.add(ClaudeMessage("assistant", result.response))
                        }
                        
                        val usage = result.usage
                        _uiState.update {
                            it.copy(
                                response = result.response,
                                isLoading = false,
                                status = "✅ Готово! In: %,d | Out: %,d токенов".format(
                                    usage?.inputTokens ?: 0,
                                    usage?.outputTokens ?: 0
                                ),
                                progress = if (state.isLargeFileMode) {
                                    "Large Mode"
                                } else {
                                    "Сообщений: ${conversationHistory.size / 2}"
                                },
                                conversationCount = if (state.isLargeFileMode) 0 else conversationHistory.size / 2
                            )
                        }

                        fileManager.saveResponse(result.response)
                            .onSuccess { 
                                println("✅ Auto-saved MD to: ${it.absolutePath}")
                            }
                            .onFailure { 
                                println("❌ Auto-save error: ${it.message}")
                            }
                    }
                    is ClaudeResult.Error -> {
                        if (!state.isLargeFileMode && conversationHistory.isNotEmpty()) {
                            conversationHistory.removeAt(conversationHistory.size - 1)
                        }
                        
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

    fun saveResponseToTxt() {
        viewModelScope.launch {
            val response = _uiState.value.response
            
            if (response.isEmpty()) {
                _uiState.update { it.copy(saveStatus = "❌ Нет ответа для сохранения") }
                return@launch
            }

            _uiState.update { it.copy(saveStatus = "💾 Сохранение...") }

            fileManager.saveResponseToTxt(response)
                .onSuccess { file ->
                    _uiState.update { 
                        it.copy(
                            saveStatus = "✅ Сохранено: ${file.name}"
                        ) 
                    }
                    println("✅ Saved TXT to: ${file.absolutePath}")
                    
                    delay(3000)
                    _uiState.update { it.copy(saveStatus = "") }
                }
                .onFailure { error ->
                    _uiState.update { 
                        it.copy(saveStatus = "❌ Ошибка: ${error.message}") 
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
        _uiState.update {
            it.copy(
                response = "",
                progress = "",
                status = "🗑️ История очищена",
                conversationCount = 0,
                saveStatus = ""
            )
        }
    }

    private fun initializeRepository(apiKey: String) {
        repository?.close()
        repository = ClaudeRepository(apiKey)
    }

    override fun onCleared() {
        super.onCleared()
        repository?.close()
        currentJob?.cancel()
        conversationHistory.clear()
        _uiState.update { it.copy(fileContent = "", response = "") }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// UI COMPONENTS
// ═══════════════════════════════════════════════════════════════════════════════

data class CodeLine(
    val index: Int,
    val text: String,
    val isCode: Boolean
)

@Composable
fun OptimizedResponseViewer(
    content: String,
    modifier: Modifier = Modifier
) {
    val lines = remember(content) {
        val allLines = content.lines()
        
        val displayLines = if (allLines.size > 15_000) {
            allLines.takeLast(15_000)
        } else {
            allLines
        }
        
        var inCodeBlock = false
        displayLines.mapIndexed { index, line ->
            if (line.trimStart().startsWith("```")) {
                inCodeBlock = !inCodeBlock
            }
            CodeLine(index, line, inCodeBlock)
        }
    }
    
    val listState = rememberLazyListState()
    
    LaunchedEffect(lines.size) {
        if (lines.size in 10..1000) {
            listState.animateScrollToItem(maxOf(0, lines.size - 1))
        }
    }
    
    SelectionContainer {
        LazyColumn(
            state = listState,
            modifier = modifier
        ) {
            if (lines.size > 8000) {
                item {
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Text(
                            text = "⚠️ Очень большой ответ (${lines.size} строк). " +
                                   "Возможны небольшие задержки при быстром скролле.",
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
            
            items(
                items = lines,
                key = { it.index }
            ) { line ->
                Text(
                    text = line.text,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = if (line.isCode) FontFamily.Monospace else FontFamily.Default,
                        lineHeight = MaterialTheme.typography.bodySmall.lineHeight * 1.25f
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 0.5.dp)
                        .then(
                            if (line.isCode) {
                                Modifier
                                    .background(Color(0xFF1E1E1E))
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            } else Modifier
                        ),
                    color = if (line.isCode) Color(0xFFD4D4D4) else Color.Unspecified
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// UI SCREEN
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
                                style = MaterialTheme.typography.bodySmall,
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
            Card {
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
                        placeholder = { Text("sk-ant-...") },
                        isError = uiState.apiKey.isNotEmpty() && !uiState.apiKey.startsWith("sk-ant-")
                    )
                    
                    if (uiState.apiKey.isNotEmpty() && !uiState.apiKey.startsWith("sk-ant-")) {
                        Text(
                            "⚠️ API ключ должен начинаться с sk-ant-",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(top = 4.dp)
                        )
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
                        Text("📄 Файл (до 5 МБ)", style = MaterialTheme.typography.titleMedium)
                        Button(
                            onClick = {
                                filePickerLauncher.launch(arrayOf("*/*"))
                            },
                            enabled = !uiState.isLoading
                        ) {
                            Icon(Icons.Default.FileOpen, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Выбрать")
                        }
                    }

                    if (uiState.loadingProgress > 0 && uiState.loadingProgress < 100) {
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
                                containerColor = if (uiState.isLargeFileMode) {
                                    MaterialTheme.colorScheme.errorContainer
                                } else {
                                    MaterialTheme.colorScheme.primaryContainer
                                }
                            )
                        ) {
                            Text(
                                uiState.fileInfo,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                }
            }

            // WARNING CARDS
            if (uiState.needsBetaMode) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "🚨 КРИТИЧЕСКИЙ размер файла",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "~${"%,d".format(uiState.estimatedTokens)} токенов input",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Text(
                            "Output ограничен ~${uiState.maxPossibleOutput/1000}K токенов (API max: 32K)",
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            "Context: Input + Output ≤ 200K",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            } else if (uiState.isLargeFileMode) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.Warning,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(
                                "⚠️ Режим больших файлов",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                            Text(
                                "~${"%,d".format(uiState.estimatedTokens)} токенов. История отключена.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                "Ответ может занять 3-5 минут. Используйте Wi-Fi.",
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            // QUERY CARD
            Card {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("💬 Запрос", style = MaterialTheme.typography.titleMedium)
                        
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = uiState.useSystemPrompt,
                                onCheckedChange = { viewModel.toggleSystemPrompt() },
                                enabled = !uiState.isLoading
                            )
                            Text(
                                "📝 Step-by-step",
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(start = 4.dp)
                            )
                        }
                    }
                    
                    if (uiState.useSystemPrompt) {
                        Text(
                            "💡 Пошаговое рассуждение для сложных задач",
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
                        placeholder = { Text("Проанализируй код и предложи улучшения") },
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
                                enabled = uiState.query.isNotEmpty() && uiState.apiKey.startsWith("sk-ant-")
                            ) {
                                Icon(Icons.Default.Send, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Отправить")
                            }
                        }

                        OutlinedButton(
                            onClick = viewModel::clearHistory,
                            enabled = !uiState.isLoading && (uiState.conversationCount > 0 || uiState.response.isNotEmpty())
                        ) {
                            Icon(Icons.Default.Delete, null, Modifier.size(18.dp))
                            if (uiState.conversationCount > 0) {
                                Spacer(Modifier.width(4.dp))
                                Text("(${uiState.conversationCount})")
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
                        Text("🤖 Ответ Opus 4.6", style = MaterialTheme.typography.titleMedium)

                        if (uiState.response.isNotEmpty()) {
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                IconButton(
                                    onClick = viewModel::saveResponseToTxt,
                                    enabled = !uiState.isLoading
                                ) {
                                    Icon(Icons.Default.Save, "Сохранить в TXT")
                                }
                                
                                IconButton(
                                    onClick = {
                                        clipboardManager.setText(AnnotatedString(uiState.response))
                                    }
                                ) {
                                    Icon(Icons.Default.ContentCopy, "Копировать")
                                }
                                
                                if (uiState.isLoading) {
                                    CircularProgressIndicator(
                                        Modifier.size(24.dp),
                                        strokeWidth = 2.dp
                                    )
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
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
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
                        else -> MaterialTheme.colorScheme.secondaryContainer
                    }
                )
            ) {
                Row(
                    Modifier.padding(12.dp),
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