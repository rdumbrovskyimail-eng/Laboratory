package com.opuside.app.feature.creator.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
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
    val messages: List<ClaudeMessage>
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
    
    private val conversationHistory = mutableListOf<ClaudeMessage>()
    
    private val json = Json { 
        ignoreUnknownKeys = true
        isLenient = true
    }
    
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
                    _status.value = "Ошибка: укажите путь к файлу"
                    return@launch
                }
                
                val file = File(path)
                if (!file.exists()) {
                    _status.value = "Ошибка: файл не найден"
                    return@launch
                }
                
                val sizeInMB = file.length() / (1024.0 * 1024.0)
                if (sizeInMB > 1.0) {
                    _status.value = "Ошибка: файл слишком большой (%.2f МБ, макс 1 МБ)".format(sizeInMB)
                    return@launch
                }
                
                withContext(Dispatchers.IO) {
                    val content = file.readText()
                    _fileContent.value = content
                    _status.value = "Загружен: ${file.name} (%.2f МБ)".format(sizeInMB)
                }
                
            } catch (e: Exception) {
                _status.value = "Ошибка чтения: ${e.message}"
            }
        }
    }
    
    fun sendQuery() {
        viewModelScope.launch {
            try {
                val key = _apiKey.value.trim()
                if (key.isEmpty()) {
                    _status.value = "Ошибка: введите API ключ"
                    return@launch
                }
                
                val queryText = _query.value.trim()
                if (queryText.isEmpty()) {
                    _status.value = "Ошибка: введите запрос"
                    return@launch
                }
                
                _isLoading.value = true
                _status.value = "Отправка запроса..."
                _response.value = "Ожидание ответа..."
                
                withContext(Dispatchers.IO) {
                    var userMessage = queryText
                    val fileContent = _fileContent.value.trim()
                    
                    if (fileContent.isNotEmpty()) {
                        userMessage = "Вот код из файла:\n\n```\n$fileContent\n```\n\n$queryText"
                    }
                    
                    conversationHistory.add(ClaudeMessage("user", userMessage))
                    
                    val request = ClaudeApiRequest(
                        model = "claude-opus-4-6",
                        max_tokens = 128000,
                        messages = conversationHistory.toList()
                    )
                    
                    val requestBody = json.encodeToString(request)
                    
                    // Используем простой HTTP запрос через Java
                    val url = java.net.URL("https://api.anthropic.com/v1/messages")
                    val connection = url.openConnection() as java.net.HttpURLConnection
                    
                    connection.requestMethod = "POST"
                    connection.setRequestProperty("Content-Type", "application/json")
                    connection.setRequestProperty("x-api-key", key)
                    connection.setRequestProperty("anthropic-version", "2023-06-01")
                    connection.doOutput = true
                    
                    connection.outputStream.use { os ->
                        os.write(requestBody.toByteArray())
                    }
                    
                    val responseCode = connection.responseCode
                    val responseBody = if (responseCode == 200) {
                        connection.inputStream.bufferedReader().use { it.readText() }
                    } else {
                        connection.errorStream.bufferedReader().use { it.readText() }
                    }
                    
                    withContext(Dispatchers.Main) {
                        if (responseCode == 200) {
                            val claudeResponse = json.decodeFromString<ClaudeApiResponse>(responseBody)
                            val responseText = claudeResponse.content?.firstOrNull()?.text ?: "Нет ответа"
                            
                            conversationHistory.add(ClaudeMessage("assistant", responseText))
                            
                            _response.value = responseText
                            
                            val tokens = claudeResponse.usage
                            _status.value = "Успешно! Входных: ${tokens?.input_tokens}, Выходных: ${tokens?.output_tokens}"
                        } else {
                            _response.value = "Ошибка $responseCode:\n$responseBody"
                            _status.value = "Ошибка запроса"
                        }
                    }
                }
                
            } catch (e: Exception) {
                _response.value = "Исключение: ${e.message}\n${e.stackTraceToString()}"
                _status.value = "Ошибка: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    fun clearHistory() {
        conversationHistory.clear()
        _response.value = "История диалога очищена"
        _status.value = "История очищена"
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
    
    val clipboardManager = LocalClipboardManager.current
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Claude API Helper") },
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
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // API KEY
            Card {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        "API Ключ",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
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
            
            // FILE LOADING
            Card {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Файл с кодом (до 1 МБ)",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Button(onClick = viewModel::loadFile) {
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
                        placeholder = { Text("/storage/emulated/0/code.txt") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    
                    if (fileContent.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = fileContent,
                            onValueChange = {},
                            readOnly = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp),
                            maxLines = 5
                        )
                    }
                }
            }
            
            // QUERY
            Card {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Ваш запрос",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(
                                onClick = viewModel::clearHistory,
                                colors = ButtonDefaults.outlinedButtonColors()
                            ) {
                                Icon(Icons.Default.Delete, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Очистить")
                            }
                            Button(
                                onClick = viewModel::sendQuery,
                                enabled = !isLoading
                            ) {
                                if (isLoading) {
                                    CircularProgressIndicator(
                                        Modifier.size(18.dp),
                                        strokeWidth = 2.dp,
                                        color = MaterialTheme.colorScheme.onPrimary
                                    )
                                } else {
                                    Icon(Icons.Default.Send, null, Modifier.size(18.dp))
                                }
                                Spacer(Modifier.width(4.dp))
                                Text("Отправить")
                            }
                        }
                    }
                    
                    Spacer(Modifier.height(8.dp))
                    
                    OutlinedTextField(
                        value = query,
                        onValueChange = viewModel::setQuery,
                        label = { Text("Введите запрос...") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        maxLines = 5
                    )
                }
            }
            
            // RESPONSE
            Card {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "Ответ Claude",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Button(
                            onClick = {
                                clipboardManager.setText(AnnotatedString(response))
                            },
                            enabled = response.isNotEmpty()
                        ) {
                            Icon(Icons.Default.ContentCopy, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Копировать")
                        }
                    }
                    
                    Spacer(Modifier.height(8.dp))
                    
                    OutlinedTextField(
                        value = response,
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp),
                        maxLines = 15
                    )
                }
            }
            
            // STATUS
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Row(
                    Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Info,
                        null,
                        Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        status,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }
    }
}