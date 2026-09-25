package com.opuside.app.feature.settings.presentation

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opuside.app.BuildConfig
import com.opuside.app.core.ai.GeminiModelConfig
import com.opuside.app.core.data.AppSettings
import com.opuside.app.core.network.github.GitHubApiClient
import com.opuside.app.core.network.github.model.GitHubRepository
import com.opuside.app.core.security.GeminiKeyEntry
import com.opuside.app.core.security.SecureSettingsDataStore
import com.opuside.app.core.util.ConfigImporter
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject

sealed class ConnectionStatus {
    data object Unknown : ConnectionStatus()
    data object Testing : ConnectionStatus()
    data object Connected : ConnectionStatus()
    data class Error(val message: String) : ConnectionStatus()
}

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val appSettings: AppSettings,
    private val secureSettings: SecureSettingsDataStore,
    private val gitHubClient: GitHubApiClient
) : ViewModel() {

    // ═════════════════════════════════════════════════════════════════════════
    // STATE — GitHub
    // ═════════════════════════════════════════════════════════════════════════

    private val _githubOwnerInput = MutableStateFlow("")
    val githubOwnerInput: StateFlow<String> = _githubOwnerInput.asStateFlow()

    private val _githubRepoInput = MutableStateFlow("")
    val githubRepoInput: StateFlow<String> = _githubRepoInput.asStateFlow()

    private val _githubTokenInput = MutableStateFlow("")
    val githubTokenInput: StateFlow<String> = _githubTokenInput.asStateFlow()

    private val _githubBranchInput = MutableStateFlow("main")
    val githubBranchInput: StateFlow<String> = _githubBranchInput.asStateFlow()

    private val _githubStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Unknown)
    val githubStatus: StateFlow<ConnectionStatus> = _githubStatus.asStateFlow()

    private val _repoInfo = MutableStateFlow<GitHubRepository?>(null)
    val repoInfo: StateFlow<GitHubRepository?> = _repoInfo.asStateFlow()

    // ═════════════════════════════════════════════════════════════════════════
    // STATE — Gemini (Строго 3.5 Lite и 3.1 Lite)
    // ═════════════════════════════════════════════════════════════════════════

    private val _geminiKeyInput = MutableStateFlow("")
    val geminiKeyInput: StateFlow<String> = _geminiKeyInput.asStateFlow()

    private val _geminiKeys = MutableStateFlow<List<GeminiKeyEntry>>(emptyList())
    val geminiKeys: StateFlow<List<GeminiKeyEntry>> = _geminiKeys.asStateFlow()

    private val _geminiActiveKeyIndex = MutableStateFlow(0)
    val geminiActiveKeyIndex: StateFlow<Int> = _geminiActiveKeyIndex.asStateFlow()

    private val _geminiModelInput = MutableStateFlow(GeminiModelConfig.GeminiModel.getDefault().modelId)
    val geminiModelInput: StateFlow<String> = _geminiModelInput.asStateFlow()

    private val _geminiStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Unknown)
    val geminiStatus: StateFlow<ConnectionStatus> = _geminiStatus.asStateFlow()

    // ═════════════════════════════════════════════════════════════════════════
    // STATE — UI & Безопасность
    // ═════════════════════════════════════════════════════════════════════════

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _biometricAuthRequest = MutableStateFlow(false)
    val biometricAuthRequest: StateFlow<Boolean> = _biometricAuthRequest.asStateFlow()

    private val _isUnlocked = MutableStateFlow(false)
    val isUnlocked: StateFlow<Boolean> = _isUnlocked.asStateFlow()

    private val _unlockExpiration = MutableStateFlow<Long?>(null)
    val unlockExpiration: StateFlow<Long?> = _unlockExpiration.asStateFlow()

    private val _timerTick = MutableStateFlow(0L)
    val timerTick: StateFlow<Long> = _timerTick.asStateFlow()

    private var unlockJob: Job? = null
    private var timerJob: Job? = null

    val gitHubConfig = appSettings.gitHubConfig
    val appVersion = BuildConfig.VERSION_NAME
    val buildType = if (BuildConfig.DEBUG) "Debug" else "Release"

    init {
        loadSettings()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            try {
                // GitHub настройки
                val githubConfig = try {
                    appSettings.gitHubConfig.first()
                } catch (e: Exception) {
                    SecureSettingsDataStore.GitHubConfig("", "", "main", "")
                }

                val githubToken = try {
                    secureSettings.getGitHubToken().first()
                } catch (e: Exception) { "" }

                // Gemini ключи
                val geminiKey = try {
                    secureSettings.getGeminiApiKey().first()
                } catch (e: Exception) { "" }

                val geminiKeysList = try {
                    secureSettings.getGeminiApiKeys().first()
                } catch (e: Exception) { emptyList() }

                val activeIdx = try {
                    secureSettings.getGeminiActiveKeyIndex().first()
                } catch (e: Exception) { 0 }

                _geminiKeys.value = geminiKeysList
                _geminiActiveKeyIndex.value = activeIdx

                if (geminiKeysList.isEmpty() && geminiKey.isNotBlank()) {
                    val migrated = listOf(GeminiKeyEntry("Ключ 1", geminiKey))
                    _geminiKeys.value = migrated
                    viewModelScope.launch { secureSettings.setGeminiApiKeys(migrated) }
                }

                // Модель Gemini (авто-миграция старых ID на 3.5 Flash-Lite)
                val rawModel = try {
                    appSettings.geminiModel.first()
                } catch (e: Exception) { "" }

                val validatedModel = GeminiModelConfig.GeminiModel.fromModelId(rawModel)
                    ?: GeminiModelConfig.GeminiModel.getDefault()

                _githubOwnerInput.value = githubConfig.owner
                _githubRepoInput.value = githubConfig.repo
                _githubBranchInput.value = githubConfig.branch
                _githubTokenInput.value = githubToken
                _geminiKeyInput.value = geminiKey
                _geminiModelInput.value = validatedModel.modelId

            } catch (e: Exception) {
                _message.value = "⚠️ Ошибка загрузки настроек: ${e.message}"
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // БЛОКИРОВКА И БИОМЕТРИЯ
    // ═════════════════════════════════════════════════════════════════════════

    fun unlock() {
        _isUnlocked.value = true
        val expirationTime = System.currentTimeMillis() + UNLOCK_TIMEOUT_MS
        _unlockExpiration.value = expirationTime

        unlockJob?.cancel()
        timerJob?.cancel()

        unlockJob = viewModelScope.launch {
            delay(UNLOCK_TIMEOUT_MS)
            lock()
        }

        timerJob = viewModelScope.launch {
            while (_isUnlocked.value) {
                delay(1000)
                _timerTick.value = System.currentTimeMillis()
                val expiration = _unlockExpiration.value
                if (expiration != null && System.currentTimeMillis() >= expiration) {
                    lock()
                    break
                }
            }
        }
    }

    fun lock() {
        _isUnlocked.value = false
        _unlockExpiration.value = null
        unlockJob?.cancel(); unlockJob = null
        timerJob?.cancel(); timerJob = null
    }

    fun requestUnlock() {
        _biometricAuthRequest.value = true
    }

    fun onBiometricSuccess() {
        unlock()
        clearBiometricRequest()
    }

    fun onBiometricError(error: String) {
        _message.value = "❌ Ошибка авторизации: $error"
        clearBiometricRequest()
    }

    // ═════════════════════════════════════════════════════════════════════════
    // ИМПОРТ / ЭКСПОРТ
    // ═════════════════════════════════════════════════════════════════════════

    fun importConfigFromFile(fileUri: Uri) {
        if (!_isUnlocked.value) {
            _message.value = "🔒 Разблокируйте настройки для импорта"
            return
        }

        viewModelScope.launch {
            _isSaving.value = true
            try {
                val result = ConfigImporter.importConfig(context, fileUri)
                result.onSuccess { config ->
                    config.githubOwner?.let { _githubOwnerInput.value = it }
                    config.githubRepo?.let { _githubRepoInput.value = it }
                    config.githubBranch?.let { _githubBranchInput.value = it }
                    config.githubToken?.let { _githubTokenInput.value = it }
                    _message.value = "✅ Конфигурация загружена!\nНе забудьте нажать «Сохранить»."
                }.onFailure { error ->
                    _message.value = "❌ Ошибка импорта: ${error.message}"
                }
            } catch (e: Exception) {
                _message.value = "❌ Ошибка: ${e.message}"
            } finally {
                _isSaving.value = false
            }
        }
    }

    fun exportCurrentConfig(): String {
        return ConfigImporter.exportConfig(
            githubOwner = _githubOwnerInput.value,
            githubRepo = _githubRepoInput.value,
            githubBranch = _githubBranchInput.value,
            githubToken = _githubTokenInput.value
        )
    }

    // ═════════════════════════════════════════════════════════════════════════
    // ОБНОВЛЕНИЕ ЗНАЧЕНИЙ
    // ═════════════════════════════════════════════════════════════════════════

    private fun checkUnlocked(): Boolean {
        if (!_isUnlocked.value) {
            _message.value = "🔒 Разблокируйте настройки для редактирования"
            return false
        }
        return true
    }

    fun updateGitHubOwner(owner: String) {
        if (!checkUnlocked()) return
        _githubOwnerInput.value = owner
    }

    fun updateGitHubRepo(repo: String) {
        if (!checkUnlocked()) return
        _githubRepoInput.value = repo
    }

    fun updateGitHubToken(token: String) {
        if (!checkUnlocked()) return
        _githubTokenInput.value = token
    }

    fun updateGitHubBranch(branch: String) {
        if (!checkUnlocked()) return
        _githubBranchInput.value = branch
    }

    fun updateGeminiKey(key: String) {
        if (!checkUnlocked()) return
        _geminiKeyInput.value = key
    }

    fun updateGeminiModel(model: String) {
        val validated = GeminiModelConfig.GeminiModel.fromModelId(model) ?: GeminiModelConfig.GeminiModel.getDefault()
        _geminiModelInput.value = validated.modelId
    }

    fun addGeminiKey(label: String, key: String) {
        if (!checkUnlocked()) return
        if (_geminiKeys.value.size >= 10) {
            _message.value = "❌ Максимум 10 ключей"
            return
        }
        if (key.isBlank()) {
            _message.value = "❌ Ключ не может быть пустым"
            return
        }
        val newList = _geminiKeys.value + GeminiKeyEntry(
            label = label.ifBlank { "Ключ ${_geminiKeys.value.size + 1}" },
            key = key
        )
        _geminiKeys.value = newList
        viewModelScope.launch {
            secureSettings.setGeminiApiKeys(newList)
            // Если это первый ключ — делаем его активным
            if (newList.size == 1) {
                setActiveGeminiKey(0)
            }
        }
        _message.value = "✅ Ключ добавлен: $label"
    }

    fun removeGeminiKey(index: Int) {
        if (!checkUnlocked()) return
        val list = _geminiKeys.value.toMutableList()
        if (index !in list.indices) return
        list.removeAt(index)
        _geminiKeys.value = list
        if (_geminiActiveKeyIndex.value >= list.size) {
            _geminiActiveKeyIndex.value = (list.size - 1).coerceAtLeast(0)
        }
        viewModelScope.launch {
            secureSettings.setGeminiApiKeys(list)
            secureSettings.setGeminiActiveKeyIndex(_geminiActiveKeyIndex.value)
            if (list.isNotEmpty()) {
                val activeKey = list[_geminiActiveKeyIndex.value].key
                secureSettings.setGeminiApiKey(activeKey)
                appSettings.setGeminiApiKey(activeKey)
            }
        }
    }

    fun setActiveGeminiKey(index: Int) {
        if (index !in _geminiKeys.value.indices) return
        _geminiActiveKeyIndex.value = index
        val entry = _geminiKeys.value[index]
        viewModelScope.launch {
            secureSettings.setGeminiActiveKeyIndex(index)
            secureSettings.setGeminiApiKey(entry.key)
            appSettings.setGeminiApiKey(entry.key)
            if (secureSettings.pipelineKeyA.first().isBlank()) {
                secureSettings.setPipelineKeyA(entry.key)
            }
        }
        _message.value = "🔑 Активен: ${entry.label}"
    }

    // ═════════════════════════════════════════════════════════════════════════
    // СОХРАНЕНИЕ НАСТРОЕК (СКВОЗНАЯ СИНХРОНИЗАЦИЯ)
    // ═════════════════════════════════════════════════════════════════════════

    fun saveGitHubSettings() {
        if (!checkUnlocked()) return

        viewModelScope.launch {
            _isSaving.value = true
            try {
                if (_githubOwnerInput.value.isBlank() || _githubRepoInput.value.isBlank() || _githubTokenInput.value.isBlank()) {
                    _message.value = "❌ Заполните все поля GitHub"
                    return@launch
                }

                secureSettings.setGitHubToken(_githubTokenInput.value)
                appSettings.setGitHubConfig(
                    owner = _githubOwnerInput.value.trim(),
                    repo = _githubRepoInput.value.trim(),
                    branch = _githubBranchInput.value.trim().ifBlank { "main" }
                )

                _message.value = "✅ Настройки GitHub сохранены"
            } catch (e: Exception) {
                _message.value = "❌ Ошибка сохранения: ${e.message}"
            } finally {
                _isSaving.value = false
            }
        }
    }

    fun saveGeminiSettings() {
        if (!checkUnlocked()) return

        viewModelScope.launch {
            _isSaving.value = true
            try {
                if (_geminiKeys.value.isEmpty()) {
                    _message.value = "❌ Добавьте хотя бы один ключ Gemini"
                    return@launch
                }

                val activeIdx = _geminiActiveKeyIndex.value.coerceIn(0, _geminiKeys.value.lastIndex)
                val activeKey = _geminiKeys.value[activeIdx].key

                // Синхронизируем ключ везде
                secureSettings.setGeminiApiKeys(_geminiKeys.value)
                secureSettings.setGeminiActiveKeyIndex(activeIdx)
                secureSettings.setGeminiApiKey(activeKey)
                appSettings.setGeminiApiKey(activeKey)

                // Если в Pipeline ключ А был пуст — автоматически наполняем
                if (secureSettings.pipelineKeyA.first().isBlank()) {
                    secureSettings.setPipelineKeyA(activeKey)
                }

                // Синхронизируем модель
                appSettings.setGeminiModel(_geminiModelInput.value)
                secureSettings.setPipelineGeminiModel(_geminiModelInput.value)

                _message.value = "✅ Настройки Gemini сохранены (${_geminiKeys.value.size} ключей)"
            } catch (e: Exception) {
                _message.value = "❌ Ошибка сохранения: ${e.message}"
            } finally {
                _isSaving.value = false
            }
        }
    }

    fun saveAllSettings() {
        if (!checkUnlocked()) return

        viewModelScope.launch {
            _isSaving.value = true
            try {
                if (_githubOwnerInput.value.isBlank() || _githubRepoInput.value.isBlank() || _githubTokenInput.value.isBlank()) {
                    _message.value = "❌ Заполните обязательные поля GitHub"
                    return@launch
                }

                saveGitHubSettings()
                if (_geminiKeys.value.isNotEmpty()) {
                    saveGeminiSettings()
                }

                _message.value = "✅ Все настройки успешно сохранены"
            } catch (e: Exception) {
                _message.value = "❌ Ошибка: ${e.message}"
            } finally {
                _isSaving.value = false
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // ПРОВЕРКА СОЕДИНЕНИЯ
    // ═════════════════════════════════════════════════════════════════════════

    fun testGitHubConnection() {
        viewModelScope.launch {
            _githubStatus.value = ConnectionStatus.Testing
            try {
                gitHubClient.getRepository()
                    .onSuccess { repo ->
                        _repoInfo.value = repo
                        _githubStatus.value = ConnectionStatus.Connected
                        _message.value = "✅ GitHub подключён: ${repo.fullName}"
                    }
                    .onFailure { e ->
                        _githubStatus.value = ConnectionStatus.Error(e.message ?: "Ошибка")
                        _message.value = "❌ GitHub тест не прошёл: ${e.message}"
                    }
            } catch (e: Exception) {
                _githubStatus.value = ConnectionStatus.Error(e.message ?: "Ошибка")
            }
        }
    }

    fun testGeminiConnection() {
        val activeKeys = _geminiKeys.value
        val activeIdx = _geminiActiveKeyIndex.value
        val key = if (activeKeys.isNotEmpty() && activeIdx in activeKeys.indices) {
            activeKeys[activeIdx].key.trim()
        } else {
            _geminiKeyInput.value.trim()
        }

        if (key.isBlank()) {
            _message.value = "❌ Сначала добавьте ключ Gemini"
            return
        }

        viewModelScope.launch {
            _geminiStatus.value = ConnectionStatus.Testing
            try {
                val result = withContext(Dispatchers.IO) {
                    testGeminiApi(key, _geminiModelInput.value)
                }
                if (result) {
                    _geminiStatus.value = ConnectionStatus.Connected
                    _message.value = "✅ Gemini API успешно отвечает!"
                } else {
                    _geminiStatus.value = ConnectionStatus.Error("Неверный ответ от API")
                    _message.value = "❌ Gemini: получен неожиданный ответ"
                }
            } catch (e: Exception) {
                val msg = e.message ?: "Ошибка сети"
                _geminiStatus.value = ConnectionStatus.Error(msg)
                _message.value = "❌ Gemini тест не прошёл: $msg"
            }
        }
    }

    private fun testGeminiApi(apiKey: String, modelId: String): Boolean {
        val model = GeminiModelConfig.GeminiModel.fromModelId(modelId) ?: GeminiModelConfig.GeminiModel.getDefault()
        val url = "https://generativelanguage.googleapis.com/v1beta/models/${model.modelId}:generateContent"

        val body = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", "Ping") })
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("maxOutputTokens", 128)
                put("thinkingConfig", JSONObject().apply {
                    put("thinkingLevel", model.forcedThinkingLevel.apiName)
                })
            })
        }

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json; charset=utf-8")
            setRequestProperty("x-goog-api-key", apiKey)
            connectTimeout = 15_000
            readTimeout = 20_000
            doOutput = true
        }

        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

        val code = connection.responseCode
        if (code !in 200..299) {
            val err = connection.errorStream?.let {
                BufferedReader(InputStreamReader(it, Charsets.UTF_8)).use { r -> r.readText() }
            } ?: "HTTP $code"
            throw Exception(parseApiErrorMessage(code, err))
        }

        val response = BufferedReader(InputStreamReader(connection.inputStream, Charsets.UTF_8)).use { it.readText() }
        val json = JSONObject(response)
        return json.has("candidates")
    }

    private fun parseApiErrorMessage(code: Int, body: String): String {
        val msg = try {
            val json = JSONObject(body)
            json.optJSONObject("error")?.optString("message") ?: body.take(200)
        } catch (_: Exception) { body.take(200) }

        return when (code) {
            400 -> "Неверный формат запроса (400): $msg"
            401 -> "Неверный API ключ (401)"
            403 -> "Доступ к модели запрещён (403)"
            429 -> "Лимит запросов исчерпан (429)"
            500, 502, 503 -> "Сервер Gemini временно недоступен"
            else -> "Ошибка API $code: $msg"
        }
    }

    fun clearBiometricRequest() {
        _biometricAuthRequest.value = false
    }

    fun resetToDefaults() {
        _geminiModelInput.value = GeminiModelConfig.GeminiModel.getDefault().modelId
        _message.value = "⚠️ Сброшено к значениям по умолчанию (не сохранено)"
    }

    fun clearMessage() {
        _message.value = null
    }

    companion object {
        private const val UNLOCK_TIMEOUT_MS = 5 * 60 * 1000L
    }
}