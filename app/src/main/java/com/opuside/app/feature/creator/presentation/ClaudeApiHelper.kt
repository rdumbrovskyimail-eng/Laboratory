package com.opuside.app.feature.creator.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.*
import kotlinx.serialization.json.*
import java.io.File
import java.io.BufferedOutputStream
import javax.inject.Inject

// ═══════════════════════════════════════════════════════════════════════════════
// DATA MODELS
// ═══════════════════════════════════════════════════════════════════════════════

@Serializable
data class ClaudeMessage(
    val role: String,
    val content: String
)

@Serializable
data class ClaudeApiRequest(
    val model: String,
    val max_tokens: Int,
    val messages: List<ClaudeMessage>,
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
    val stop_reason: String? = null,
    val usage: ClaudeUsage? = null
)

@Serializable
data class ClaudeUsage(
    val input_tokens: Int? = null,
    val output_tokens: Int? = null
)

// ═══════════════════════════════════════════════════════════════════════════════
// VIEW MODEL
// ═══════════════════════════════════════════════════════════════════════════════

@HiltViewModel
class ClaudeHelperViewModel @Inject constructor() : ViewModel() {

    private val _apiKey = MutableStateFlow("")
    val apiKey: StateFlow<String> = _apiKey

    private val _filePath = MutableStateFlow("")
    val filePath: StateFlow<String> = _filePath

    private val _fileContent = MutableStateFlow("")
    val fileContent: StateFlow<String> = _fileContent

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query

    private val _response = MutableStateFlow("")
    val response: StateFlow<String> = _response

    private val _status = MutableStateFlow("Готов к работе")
    val status: StateFlow<String> = _status

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _progress = MutableStateFlow("")
    val progress: StateFlow<String> = _progress

    private val conversationHistory = mutableListOf<ClaudeMessage>()

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private var currentJob: Job? = null

    fun setApiKey(key: String) {
        _apiKey.value = key
    }

    fun setFilePath(path: String) {
        _filePath.value = path
    }

    fun setQuery(text: String) {
        _query.value = text
    }

    fun loadFile() {
        viewModelScope.launch {
            try {
                val path = _filePath.value.trim()
                if (path.isEmpty()) {
                    _status.value = "❌ Укажите путь к файлу"
                    return@launch
                }

                _status.value = "📂 Загрузка файла..."

                withContext(Dispatchers.IO) {
                    val file = File(path)
                    if (!file.exists()) {
                        _status.value = "❌ Файл не найден: $path"
                        return@withContext
                    }

                    val sizeInKB = file.length() / 1024.0
                    val sizeInMB = sizeInKB / 1024.0

                    // Opus 4.6: 1M token context (beta), ~200K reliable input ≈ 2.8MB
                    if (sizeInMB > 3.0) {
                        _status.value = "❌ Файл слишком большой (%.1f МБ, макс 3 МБ)".format(sizeInMB)
                        return@withContext
                    }

                    val content = file.bufferedReader(Charsets.UTF_8, 131072).use { it.readText() }
                    _fileContent.value = content
                    
                    val estimatedTokens = (content.length / 3.5).toInt()
                    _status.value = "✅ Загружен: ${file.name} (%.2f МБ, ~%,d токенов)".format(
                        sizeInMB,
                        estimatedTokens
                    )
                }

            } catch (e: OutOfMemoryError) {
                _status.value = "❌ Недостаточно памяти"
            } catch (e: Exception) {
                _status.value = "❌ Ошибка: ${e.message}"
            }
        }
    }

    fun cancelRequest() {
        currentJob?.cancel()
        currentJob = null
        _isLoading.value = false
        _status.value = "⛔ Отменено"
    }

    fun sendQuery() {
        currentJob?.cancel()
        currentJob = viewModelScope.launch {
            try {
                val key = _apiKey.value.trim()
                if (key.isEmpty()) {
                    _status.value = "❌ Введите API ключ"
                    return@launch
                }

                val queryText = _query.value.trim()
                if (queryText.isEmpty()) {
                    _status.value = "❌ Введите запрос"
                    return@launch
                }

                _isLoading.value = true
                _response.value = ""
                _progress.value = "Подготовка..."
                _status.value = "🚀 Подключение к Opus 4.6..."

                withContext(Dispatchers.IO) {
                    var userMessage = queryText
                    val fileContent = _fileContent.value.trim()

                    if (fileContent.isNotEmpty()) {
                        userMessage = "$queryText\n\n```kotlin\n$fileContent\n```"
                    }

                    conversationHistory.add(ClaudeMessage("user", userMessage))

                    val inputLength = userMessage.length
                    val estimatedInputTokens = (inputLength / 3.5).toInt()

                    // Opus 4.6: 128K max output tokens (удвоено с 64K)
                    val adaptiveMaxTokens = when {
                        estimatedInputTokens > 700_000 -> 64000   // Близко к лимиту
                        estimatedInputTokens > 500_000 -> 100000
                        estimatedInputTokens > 300_000 -> 128000  // Максимум Opus 4.6
                        estimatedInputTokens > 150_000 -> 128000
                        else -> 128000  // По умолчанию максимум
                    }

                    val request = ClaudeApiRequest(
                        model = "claude-opus-4-6",  // ✅ ПРАВИЛЬНАЯ МОДЕЛЬ!
                        max_tokens = adaptiveMaxTokens,
                        messages = conversationHistory.toList(),
                        stream = true
                    )

                    val requestBody = json.encodeToString(request)
                    val bodyBytes = requestBody.toByteArray(Charsets.UTF_8)

                    _status.value = "📦 Отправка: %.2f МБ (~%,d токенов)".format(
                        bodyBytes.size / (1024.0 * 1024.0),
                        estimatedInputTokens
                    )

                    val url = java.net.URL("https://api.anthropic.com/v1/messages")
                    val connection = url.openConnection() as java.net.HttpURLConnection

                    connection.requestMethod = "POST"
                    connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    connection.setRequestProperty("x-api-key", key)
                    connection.setRequestProperty("anthropic-version", "2023-06-01")
                    connection.setRequestProperty("Accept", "text/event-stream")
                    connection.setRequestProperty("Content-Length", bodyBytes.size.toString())
                    
                    connection.doOutput = true
                    connection.doInput = true
                    connection.useCaches = false
                    connection.connectTimeout = 120_000
                    connection.readTimeout = 1_800_000
                    connection.setChunkedStreamingMode(65536)

                    val startUpload = System.currentTimeMillis()
                    
                    connection.outputStream.use { output ->
                        BufferedOutputStream(output, 131072).use { buffered ->
                            buffered.write(bodyBytes)
                            buffered.flush()
                        }
                    }

                    val uploadTime = (System.currentTimeMillis() - startUpload) / 1000.0
                    _status.value = "✅ Отправлено за %.1f сек".format(uploadTime)

                    val responseCode = connection.responseCode

                    if (responseCode == 200) {
                        _status.value = "⚡ Streaming ответа..."

                        val fullResponse = StringBuilder(300000)
                        var inputTokens = 0
                        var outputTokens = 0
                        var lastUpdateTime = System.currentTimeMillis()
                        var chunkCounter = 0

                        connection.inputStream.bufferedReader(Charsets.UTF_8, 65536).useLines { lines ->
                            for (line in lines) {
                                if (!_isLoading.value) break

                                if (!line.startsWith("data: ")) continue
                                val data = line.removePrefix("data: ").trim()
                                if (data.isEmpty() || data == "[DONE]") continue

                                try {
                                    val event = json.parseToJsonElement(data).jsonObject
                                    val type = event["type"]?.jsonPrimitive?.content

                                    when (type) {
                                        "message_start" -> {
                                            val message = event["message"]?.jsonObject
                                            val usage = message?.get("usage")?.jsonObject
                                            inputTokens = usage?.get("input_tokens")?.jsonPrimitive?.int ?: 0
                                            _status.value = "⚡ Streaming... In: %,d tok".format(inputTokens)
                                            _progress.value = "In: %,d".format(inputTokens)
                                        }

                                        "content_block_delta" -> {
                                            val delta = event["delta"]?.jsonObject
                                            val text = delta?.get("text")?.jsonPrimitive?.content
                                            if (text != null) {
                                                fullResponse.append(text)
                                                chunkCounter++

                                                val now = System.currentTimeMillis()
                                                if (now - lastUpdateTime > 50 || chunkCounter >= 10) {
                                                    _response.value = fullResponse.toString()
                                                    val currentTokens = (fullResponse.length / 3.5).toInt()
                                                    _progress.value = "Out: %,d символов (~%,d tok)".format(
                                                        fullResponse.length,
                                                        currentTokens
                                                    )
                                                    lastUpdateTime = now
                                                    chunkCounter = 0
                                                }
                                            }
                                        }

                                        "message_delta" -> {
                                            val usage = event["usage"]?.jsonObject
                                            outputTokens = usage?.get("output_tokens")?.jsonPrimitive?.int ?: 0
                                        }

                                        "message_stop" -> {
                                            _response.value = fullResponse.toString()
                                        }

                                        "error" -> {
                                            val error = event["error"]?.jsonObject
                                            val errorType = error?.get("type")?.jsonPrimitive?.content
                                            val errorMessage = error?.get("message")?.jsonPrimitive?.content
                                            _response.value = "❌ API Error [$errorType]: $errorMessage"
                                            _status.value = "❌ Ошибка API"
                                        }
                                    }
                                } catch (e: Exception) {
                                    // Пропускаем битые SSE
                                }
                            }
                        }

                        val responseText = fullResponse.toString()
                        _response.value = responseText

                        conversationHistory.add(ClaudeMessage("assistant", responseText))

                        launch(Dispatchers.IO) {
                            try {
                                val downloadsDir = android.os.Environment
                                    .getExternalStoragePublicDirectory(
                                        android.os.Environment.DIRECTORY_DOWNLOADS
                                    )
                                val outputFile = File(
                                    downloadsDir,
                                    "opus46_${System.currentTimeMillis()}.md"
                                )
                                outputFile.writeText(responseText)
                            } catch (e: Exception) {
                                // Ignore
                            }
                        }

                        val sizeKB = responseText.length / 1024.0
                        _status.value = "✅ Готово! In:%,d Out:%,d (%.1f КБ)".format(
                            inputTokens,
                            outputTokens,
                            sizeKB
                        )
                        _progress.value = "In:%,d Out:%,d".format(inputTokens, outputTokens)

                    } else {
                        val errorBody = try {
                            connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "Нет деталей"
                        } catch (e: Exception) {
                            "Ошибка чтения"
                        }

                        _response.value = when (responseCode) {
                            401 -> "❌ Неверный API ключ"
                            429 -> "❌ Rate Limit. Подождите"
                            413 -> "❌ Запрос слишком большой"
                            500, 502, 503, 529 -> "❌ Перегрузка сервера ($responseCode)"
                            else -> "❌ HTTP $responseCode:\n\n$errorBody"
                        }
                        _status.value = "❌ HTTP $responseCode"

                        if (conversationHistory.isNotEmpty()) {
                            conversationHistory.removeAt(conversationHistory.size - 1)
                        }
                    }
                }

            } catch (e: java.net.SocketTimeoutException) {
                _response.value = "⏰ Таймаут"
                _status.value = "⏰ Таймаут"
                if (conversationHistory.isNotEmpty()) {
                    conversationHistory.removeAt(conversationHistory.size - 1)
                }
            } catch (e: OutOfMemoryError) {
                _response.value = "❌ Недостаточно памяти"
                _status.value = "❌ OOM"
                if (conversationHistory.isNotEmpty()) {
                    conversationHistory.removeAt(conversationHistory.size - 1)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                _status.value = "⛔ Отменено"
                if (conversationHistory.isNotEmpty()) {
                    conversationHistory.removeAt(conversationHistory.size - 1)
                }
            } catch (e: Exception) {
                _response.value = "❌ ${e.javaClass.simpleName}: ${e.message}"
                _status.value = "❌ Ошибка"
                if (conversationHistory.isNotEmpty()) {
                    conversationHistory.removeAt(conversationHistory.size - 1)
                }
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun clearHistory() {
        conversationHistory.clear()
        _response.value = ""
        _progress.value = ""
        _status.value = "🗑️ История очищена"
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// UI SCREEN
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
fun ClaudeHelperScreen(
    onBack: () -> Unit,
    viewModel: ClaudeHelperViewModel = viewModel()
) {
    val apiKey by viewModel.apiKey.collectAsState()
    val filePath by viewModel.filePath.collectAsState()
    val fileContent by viewModel.fileContent.collectAsState()
    val query by viewModel.query.collectAsState()
    val response by viewModel.response.collectAsState()
    val status by viewModel.status.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val progress by viewModel.progress.collectAsState()

    val clipboardManager = LocalClipboardManager.current
    val scrollState = rememberScrollState()

    LaunchedEffect(response) {
        if (response.isNotEmpty() && isLoading) {
            scrollState.animateScrollTo(scrollState.maxValue)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Claude Opus 4.6 🚀")
                        if (progress.isNotEmpty()) {
                            Text(
                                progress,
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
                .verticalScroll(scrollState)
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("🔑 API Ключ", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = apiKey,
                        onValueChange = viewModel::setApiKey,
                        label = { Text("Anthropic API Key") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("sk-ant-...") }
                    )
                }
            }

            Card {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("📄 Файл (до 3 МБ)", style = MaterialTheme.typography.titleMedium)
                        Button(
                            onClick = viewModel::loadFile,
                            enabled = !isLoading
                        ) {
                            Icon(Icons.Default.FileOpen, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Загрузить")
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = filePath,
                        onValueChange = viewModel::setFilePath,
                        label = { Text("Путь к файлу") },
                        placeholder = { Text("/storage/emulated/0/Download/code.kt") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    if (fileContent.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        val sizeMB = fileContent.length / (1024.0 * 1024.0)
                        val tokens = (fileContent.length / 3.5).toInt()
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        ) {
                            Text(
                                "✅ %.2f МБ (~%,d токенов)".format(sizeMB, tokens),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }
                }
            }

            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("💬 Запрос", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = query,
                        onValueChange = viewModel::setQuery,
                        label = { Text("Что нужно сделать?") },
                        placeholder = { Text("Проанализируй код") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        maxLines = 10
                    )

                    Spacer(Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        if (isLoading) {
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
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Default.Send, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("Отправить")
                            }
                        }

                        OutlinedButton(
                            onClick = viewModel::clearHistory,
                            enabled = !isLoading
                        ) {
                            Icon(Icons.Default.Delete, null, Modifier.size(18.dp))
                        }
                    }
                }
            }

            Card {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("🤖 Ответ", style = MaterialTheme.typography.titleMedium)

                        if (response.isNotEmpty()) {
                            Row {
                                IconButton(
                                    onClick = {
                                        clipboardManager.setText(AnnotatedString(response))
                                    }
                                ) {
                                    Icon(Icons.Default.ContentCopy, "Копировать")
                                }
                                
                                if (isLoading) {
                                    CircularProgressIndicator(
                                        Modifier.size(24.dp),
                                        strokeWidth = 2.dp
                                    )
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    if (response.isEmpty() && !isLoading) {
                        Text(
                            "Ответ появится здесь...",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                        )
                    } else {
                        SelectionContainer {
                            Text(
                                text = response,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    lineHeight = MaterialTheme.typography.bodySmall.lineHeight * 1.3f
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 150.dp, max = 800.dp)
                                    .verticalScroll(rememberScrollState())
                            )
                        }
                    }
                }
            }

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = when {
                        status.startsWith("✅") -> MaterialTheme.colorScheme.primaryContainer
                        status.startsWith("❌") || status.startsWith("⏰") ->
                            MaterialTheme.colorScheme.errorContainer
                        status.startsWith("⚡") -> MaterialTheme.colorScheme.tertiaryContainer
                        else -> MaterialTheme.colorScheme.secondaryContainer
                    }
                )
            ) {
                Row(
                    Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        status,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}