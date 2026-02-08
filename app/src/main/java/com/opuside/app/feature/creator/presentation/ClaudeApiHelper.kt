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

                val file = File(path)
                if (!file.exists()) {
                    _status.value = "❌ Файл не найден: $path"
                    return@launch
                }

                val sizeInKB = file.length() / 1024.0
                val sizeInMB = sizeInKB / 1024.0

                if (sizeInMB > 1.5) {
                    _status.value = "❌ Файл слишком большой (%.1f МБ, макс 1.5 МБ)".format(sizeInMB)
                    return@launch
                }

                withContext(Dispatchers.IO) {
                    val content = file.readText()
                    _fileContent.value = content
                    _status.value = "📄 Загружен: ${file.name} (%.1f КБ, ~%d токенов)".format(
                        sizeInKB,
                        (content.length / 3.5).toInt()
                    )
                }

            } catch (e: Exception) {
                _status.value = "❌ Ошибка чтения: ${e.message}"
            }
        }
    }

    fun cancelRequest() {
        currentJob?.cancel()
        currentJob = null
        _isLoading.value = false
        _status.value = "⛔ Запрос отменён"
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
                _progress.value = "0 токенов"
                _status.value = "🔌 Подключение к Claude Opus 4.6..."

                withContext(Dispatchers.IO) {
                    var userMessage = queryText
                    val fileContent = _fileContent.value.trim()

                    if (fileContent.isNotEmpty()) {
                        userMessage = "$queryText\n\nВОТ КОД:\n\n$fileContent"
                    }

                    conversationHistory.add(ClaudeMessage("user", userMessage))

                    // Адаптивный max_tokens
                    val inputLength = userMessage.length
                    val adaptiveMaxTokens = when {
                        inputLength > 200_000 -> 128000
                        inputLength > 50_000 -> 64000
                        inputLength > 10_000 -> 32000
                        else -> 8192
                    }

                    val request = ClaudeApiRequest(
                        model = "claude-opus-4-6",
                        max_tokens = adaptiveMaxTokens,
                        messages = conversationHistory.toList(),
                        stream = true
                    )

                    val requestBody = json.encodeToString(request)

                    val url = java.net.URL("https://api.anthropic.com/v1/messages")
                    val connection = url.openConnection() as java.net.HttpURLConnection

                    connection.requestMethod = "POST"
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.setRequestProperty("x-api-key", key)
                    connection.setRequestProperty("anthropic-version", "2023-06-01")
                    connection.setRequestProperty("Accept", "text/event-stream")
                    connection.doOutput = true
                    connection.connectTimeout = 60_000
                    connection.readTimeout = 900_000

                    // Отправка
                    _status.value = "📤 Отправка (%.1f КБ)...".format(requestBody.length / 1024.0)

                    connection.outputStream.use { os ->
                        os.write(requestBody.toByteArray(Charsets.UTF_8))
                        os.flush()
                    }

                    val responseCode = connection.responseCode

                    if (responseCode == 200) {
                        _status.value = "⚡ Streaming ответа..."

                        val fullResponse = StringBuilder()
                        var inputTokens = 0
                        var outputTokens = 0
                        var lastUpdateTime = System.currentTimeMillis()

                        connection.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                            for (line in lines) {
                                // Проверка отмены
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
                                            inputTokens = usage?.get("input_tokens")
                                                ?.jsonPrimitive?.int ?: 0
                                            _status.value = "⚡ Streaming... (вход: $inputTokens токенов)"
                                        }

                                        "content_block_delta" -> {
                                            val delta = event["delta"]?.jsonObject
                                            val text = delta?.get("text")?.jsonPrimitive?.content
                                            if (text != null) {
                                                fullResponse.append(text)

                                                // Обновляем UI не чаще чем раз в 100мс
                                                val now = System.currentTimeMillis()
                                                if (now - lastUpdateTime > 100) {
                                                    _response.value = fullResponse.toString()
                                                    _progress.value = "${fullResponse.length} символов"
                                                    lastUpdateTime = now
                                                }
                                            }
                                        }

                                        "message_delta" -> {
                                            val usage = event["usage"]?.jsonObject
                                            outputTokens = usage?.get("output_tokens")
                                                ?.jsonPrimitive?.int ?: 0
                                        }

                                        "message_stop" -> {
                                            // Финальное обновление
                                            _response.value = fullResponse.toString()
                                        }
                                    }
                                } catch (_: Exception) {
                                    // Пропускаем битые SSE события
                                }
                            }
                        }

                        val responseText = fullResponse.toString()
                        _response.value = responseText

                        conversationHistory.add(ClaudeMessage("assistant", responseText))

                        // Сохранение в файл
                        var savedPath = ""
                        try {
                            val downloadsDir = android.os.Environment
                                .getExternalStoragePublicDirectory(
                                    android.os.Environment.DIRECTORY_DOWNLOADS
                                )
                            val outputFile = File(
                                downloadsDir,
                                "claude_opus_${System.currentTimeMillis()}.md"
                            )
                            outputFile.writeText(responseText)
                            savedPath = outputFile.absolutePath
                        } catch (_: Exception) {
                        }

                        val sizeKB = responseText.length / 1024.0
                        _status.value = buildString {
                            append("✅ Готово! ")
                            append("In:$inputTokens Out:$outputTokens ")
                            append("(%.1f КБ)".format(sizeKB))
                            if (savedPath.isNotEmpty()) {
                                append(" | 📁 Сохранено")
                            }
                        }
                        _progress.value = "In:$inputTokens Out:$outputTokens"

                    } else {
                        val errorBody = try {
                            connection.errorStream?.bufferedReader()?.use { it.readText() } ?: "Нет деталей"
                        } catch (_: Exception) {
                            "Не удалось прочитать ошибку"
                        }
                        _response.value = "❌ Ошибка $responseCode:\n\n$errorBody"
                        _status.value = "❌ HTTP $responseCode"
                        // Убираем последнее сообщение из истории
                        if (conversationHistory.isNotEmpty()) {
                            conversationHistory.removeAt(conversationHistory.size - 1)
                        }
                    }
                }

            } catch (e: java.net.SocketTimeoutException) {
                _response.value = "⏰ Таймаут соединения.\nСервер не ответил за 15 минут."
                _status.value = "⏰ Таймаут"
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

    // Автоскролл к ответу при streaming
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
                        Text("Claude Opus 4.6")
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
            // ── API KEY ──
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
                        singleLine = true
                    )
                }
            }

            // ── FILE LOADING ──
            Card {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("📄 Файл с кодом", style = MaterialTheme.typography.titleMedium)
                        Button(onClick = viewModel::loadFile, enabled = !isLoading) {
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
                        placeholder = { Text("/storage/emulated/0/Download/all_code.txt") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    if (fileContent.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Загружено: ${fileContent.length} символов (~${fileContent.length / 4} токенов)",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }

            // ── QUERY ──
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text("💬 Запрос", style = MaterialTheme.typography.titleMedium)

                    Spacer(Modifier.height(8.dp))

                    OutlinedTextField(
                        value = query,
                        onValueChange = viewModel::setQuery,
                        label = { Text("Введите запрос...") },
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
                        // Отправить / Отменить
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
                                Text("Отменить")
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

                        // Очистить
                        OutlinedButton(
                            onClick = viewModel::clearHistory,
                            enabled = !isLoading
                        ) {
                            Icon(Icons.Default.Delete, null, Modifier.size(18.dp))
                        }
                    }
                }
            }

            // ── RESPONSE ──
            Card {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("🤖 Ответ", style = MaterialTheme.typography.titleMedium)

                        if (response.isNotEmpty()) {
                            IconButton(
                                onClick = {
                                    clipboardManager.setText(AnnotatedString(response))
                                }
                            ) {
                                Icon(Icons.Default.ContentCopy, "Копировать")
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
                                    lineHeight = MaterialTheme.typography.bodySmall.lineHeight
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 100.dp, max = 600.dp)
                                    .verticalScroll(rememberScrollState())
                            )
                        }
                    }
                }
            }

            // ── STATUS BAR ──
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

            // Отступ снизу
            Spacer(Modifier.height(32.dp))
        }
    }
}