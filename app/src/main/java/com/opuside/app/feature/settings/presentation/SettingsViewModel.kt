package com.opuside.app.feature.settings.presentation

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opuside.app.BuildConfig
import com.opuside.app.core.data.AppSettings
import com.opuside.app.core.network.anthropic.ClaudeApiClient
import com.opuside.app.core.network.github.GitHubApiClient
import com.opuside.app.core.network.github.model.GitHubRepository
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
    private val gitHubClient: GitHubApiClient,
    private val claudeClient: ClaudeApiClient
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
    // STATE — Anthropic / Claude
    // ═════════════════════════════════════════════════════════════════════════

    private val _anthropicKeyInput = MutableStateFlow("")
    val anthropicKeyInput: StateFlow<String> = _anthropicKeyInput.asStateFlow()

    private val _claudeModelInput = MutableStateFlow("claude-opus-4-6")
    val claudeModelInput: StateFlow<String> = _claudeModelInput.asStateFlow()

    private val _claudeStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Unknown)
    val claudeStatus: StateFlow<ConnectionStatus> = _claudeStatus.asStateFlow()

    // ═════════════════════════════════════════════════════════════════════════
    // STATE — DeepSeek
    // ═════════════════════════════════════════════════════════════════════════

    private val _deepSeekKeyInput = MutableStateFlow("")
    val deepSeekKeyInput: StateFlow<String> = _deepSeekKeyInput.asStateFlow()

    private val _deepSeekStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Unknown)
    val deepSeekStatus: StateFlow<ConnectionStatus> = _deepSeekStatus.asStateFlow()

    // ═════════════════════════════════════════════════════════════════════════
    // STATE — Gemini
    // ═════════════════════════════════════════════════════════════════════════

    private val _geminiKeyInput = MutableStateFlow("")
    val geminiKeyInput: StateFlow<String> = _geminiKeyInput.asStateFlow()

    private val _geminiKeys = MutableStateFlow<List<SecureSettingsDataStore.GeminiKeyEntry>>(emptyList())
    val geminiKeys: StateFlow<List<SecureSettingsDataStore.GeminiKeyEntry>> = _geminiKeys.asStateFlow()

    private val _geminiActiveKeyIndex = MutableStateFlow(0)
    val geminiActiveKeyIndex: StateFlow<Int> = _geminiActiveKeyIndex.asStateFlow()

    private val _geminiModelInput = MutableStateFlow("gemini-flash-latest")
    val geminiModelInput: StateFlow<String> = _geminiModelInput.asStateFlow()

    private val _geminiStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Unknown)
    val geminiStatus: StateFlow<ConnectionStatus> = _geminiStatus.asStateFlow()

    // ═════════════════════════════════════════════════════════════════════════
    // STATE — UI
    // ═════════════════════════════════════════════════════════════════════════

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _biometricAuthRequest = MutableStateFlow(false)
    val biometricAuthRequest: StateFlow<Boolean> = _biometricAuthRequest.asStateFlow()

    // ═════════════════════════════════════════════════════════════════════════
    // STATE — Biometric Lock
    // ═════════════════════════════════════════════════════════════════════════

    private val _isUnlocked = MutableStateFlow(false)
    val isUnlocked: StateFlow<Boolean> = _isUnlocked.asStateFlow()

    private val _unlockExpiration = MutableStateFlow<Long?>(null)
    val unlockExpiration: StateFlow<Long?> = _unlockExpiration.asStateFlow()

    private val _timerTick = MutableStateFlow(0L)
    val timerTick: StateFlow<Long> = _timerTick.asStateFlow()

    private var unlockJob: Job? = null
    private var timerJob: Job? = null

    // ═════════════════════════════════════════════════════════════════════════
    // PUBLIC PROPERTIES
    // ═════════════════════════════════════════════════════════════════════════

    val gitHubConfig = appSettings.gitHubConfig
    val appVersion = BuildConfig.VERSION_NAME
    val buildType = if (BuildConfig.DEBUG) "Debug" else "Release"

    // ═════════════════════════════════════════════════════════════════════════
    // INITIALIZATION
    // ═════════════════════════════════════════════════════════════════════════

    init {
        android.util.Log.d(TAG, "━".repeat(80))
        android.util.Log.d(TAG, "🚀 SettingsViewModel INITIALIZED")
        android.util.Log.d(TAG, "━".repeat(80))
        loadSettings()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            android.util.Log.d(TAG, "📥 Loading settings from DataStore...")

            try {
                // GitHub config
                val githubConfig = try {
                    appSettings.gitHubConfig.first()
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "  ❌ Failed to load GitHub config", e)
                    SecureSettingsDataStore.GitHubConfig("", "", "main", "")
                }

                android.util.Log.d(TAG, "  ├─ Owner: ${githubConfig.owner.ifEmpty { "[EMPTY]" }}")
                android.util.Log.d(TAG, "  ├─ Repo: ${githubConfig.repo.ifEmpty { "[EMPTY]" }}")
                android.util.Log.d(TAG, "  └─ Branch: ${githubConfig.branch}")

                // GitHub token
                val githubToken = try {
                    secureSettings.getGitHubToken().first().also { token ->
                        android.util.Log.d(TAG, "  ├─ Token: ${if (token.isNotEmpty()) "[${token.take(8)}***]" else "[EMPTY]"}")
                    }
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "  ❌ Failed to decrypt GitHub token", e)
                    ""
                }

                // Anthropic key
                val anthropicKey = try {
                    secureSettings.getAnthropicApiKey().first().also { key ->
                        android.util.Log.d(TAG, "  ├─ Anthropic: ${if (key.isNotEmpty()) "[${key.take(8)}***]" else "[EMPTY]"}")
                    }
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "  ❌ Failed to decrypt Anthropic key", e)
                    ""
                }

                // DeepSeek key
                val deepSeekKey = try {
                    secureSettings.getDeepSeekApiKey().first().also { key ->
                        android.util.Log.d(TAG, "  ├─ DeepSeek: ${if (key.isNotEmpty()) "[${key.take(8)}***]" else "[EMPTY]"}")
                    }
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "  ❌ Failed to decrypt DeepSeek key", e)
                    ""
                }

                // Gemini key
                val geminiKey = try {
                    secureSettings.getGeminiApiKey().first().also { key ->
                        android.util.Log.d(TAG, "  ├─ Gemini: ${if (key.isNotEmpty()) "[${key.take(8)}***]" else "[EMPTY]"}")
                    }
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "  ❌ Failed to decrypt Gemini key", e)
                    ""
                }

                val geminiKeysList = try {
                    secureSettings.getGeminiApiKeys().first()
                } catch (e: Exception) { emptyList() }
                val activeIdx = try {
                    secureSettings.getGeminiActiveKeyIndex().first()
                } catch (e: Exception) { 0 }
                _geminiKeys.value = geminiKeysList
                _geminiActiveKeyIndex.value = activeIdx

                // Миграция: если есть legacy ключ но нет списка — создать первый элемент
                if (geminiKeysList.isEmpty() && geminiKey.isNotBlank()) {
                    val migrated = listOf(SecureSettingsDataStore.GeminiKeyEntry("Key 1", geminiKey))
                    _geminiKeys.value = migrated
                    viewModelScope.launch { secureSettings.setGeminiApiKeys(migrated) }
                }

                // Claude model
                val claudeModel = try {
                    appSettings.claudeModel.first()
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "  ❌ Failed to load Claude model", e)
                    "claude-opus-4-6"
                }

                // Gemini model
                val geminiModel = try {
                    appSettings.geminiModel.first()
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "  ❌ Failed to load Gemini model", e)
                    "gemini-flash-latest"
                }

                // Apply to state — всё в одном месте, никаких промежуточных присваиваний
                _githubOwnerInput.value = githubConfig.owner
                _githubRepoInput.value = githubConfig.repo
                _githubBranchInput.value = githubConfig.branch
                _githubTokenInput.value = githubToken
                _anthropicKeyInput.value = anthropicKey
                _deepSeekKeyInput.value = deepSeekKey
                _geminiKeyInput.value = geminiKey
                _claudeModelInput.value = claudeModel
                _geminiModelInput.value = geminiModel

                android.util.Log.d(TAG, "✅ Settings loaded successfully")

            } catch (e: Exception) {
                android.util.Log.e(TAG, "❌ CRITICAL: Failed to load settings", e)
                _message.value = "⚠️ Failed to load settings: ${e.message}"
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // BIOMETRIC LOCK MANAGEMENT
    // ═════════════════════════════════════════════════════════════════════════

    fun unlock() {
        android.util.Log.d(TAG, "🔓 Settings UNLOCKED")
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
        android.util.Log.d(TAG, "🔒 Settings LOCKED")
        _isUnlocked.value = false
        _unlockExpiration.value = null
        unlockJob?.cancel(); unlockJob = null
        timerJob?.cancel(); timerJob = null
    }

    fun requestUnlock() {
        android.util.Log.d(TAG, "🔐 Unlock requested via biometric")
        _biometricAuthRequest.value = true
    }

    fun onBiometricSuccess() {
        android.util.Log.d(TAG, "✅ Biometric authentication successful")
        unlock()
        clearBiometricRequest()
    }

    fun onBiometricError(error: String) {
        android.util.Log.e(TAG, "❌ Biometric authentication failed: $error")
        _message.value = "❌ Authentication failed: $error"
        clearBiometricRequest()
    }

    // ═════════════════════════════════════════════════════════════════════════
    // CONFIG IMPORT
    // ═════════════════════════════════════════════════════════════════════════

    fun importConfigFromFile(fileUri: Uri) {
        if (!_isUnlocked.value) {
            _message.value = "🔒 Unlock Settings to import configuration"
            return
        }

        viewModelScope.launch {
            _isSaving.value = true
            android.util.Log.d(TAG, "📥 IMPORTING CONFIGURATION")

            try {
                val result = ConfigImporter.importConfig(context, fileUri)

                result.onSuccess { config ->
                    config.githubOwner?.let { _githubOwnerInput.value = it }
                    config.githubRepo?.let { _githubRepoInput.value = it }
                    config.githubBranch?.let { _githubBranchInput.value = it }
                    config.githubToken?.let { _githubTokenInput.value = it }
                    config.claudeApiKey?.let { _anthropicKeyInput.value = it }
                    config.claudeModel?.let { _claudeModelInput.value = it }

                    android.util.Log.d(TAG, "✅ Configuration applied")
                    _message.value = "✅ Configuration imported!\n\n${config.toSummary()}\n\n⚠️ Don't forget to click Save!"

                }.onFailure { error ->
                    android.util.Log.e(TAG, "❌ Import failed", error)
                    _message.value = "❌ Import failed: ${error.message}"
                }

            } catch (e: Exception) {
                android.util.Log.e(TAG, "❌ Import error", e)
                _message.value = "❌ Import error: ${e.message}"
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
            githubToken = _githubTokenInput.value,
            claudeApiKey = _anthropicKeyInput.value,
            claudeModel = _claudeModelInput.value
        )
    }

    // ═════════════════════════════════════════════════════════════════════════
    // UPDATE FUNCTIONS
    // ═════════════════════════════════════════════════════════════════════════

    private fun checkUnlocked(): Boolean {
        if (!_isUnlocked.value) {
            _message.value = "🔒 Unlock Settings to edit"
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

    fun updateAnthropicKey(key: String) {
        if (!checkUnlocked()) return
        _anthropicKeyInput.value = key
        android.util.Log.d(TAG, "🔄 Anthropic Key updated: ${key.take(8)}***")
    }

    fun updateClaudeModel(model: String) {
        _claudeModelInput.value = model
    }

    fun updateDeepSeekKey(key: String) {
        if (!checkUnlocked()) return
        _deepSeekKeyInput.value = key
        android.util.Log.d(TAG, "🔄 DeepSeek Key updated: ${key.take(8)}***")
    }

    fun updateGeminiKey(key: String) {
        if (!checkUnlocked()) return
        _geminiKeyInput.value = key
        android.util.Log.d(TAG, "🔄 Gemini Key updated: ${key.take(8)}***")
    }

    fun updateGeminiModel(model: String) {
        _geminiModelInput.value = model
        android.util.Log.d(TAG, "🔄 Gemini model updated: $model")
    }

    fun addGeminiKey(label: String, key: String) {
        if (!checkUnlocked()) return
        if (_geminiKeys.value.size >= 10) {
            _message.value = "❌ Maximum 10 keys allowed"
            return
        }
        if (key.isBlank()) {
            _message.value = "❌ Key cannot be empty"
            return
        }
        val newList = _geminiKeys.value + SecureSettingsDataStore.GeminiKeyEntry(
            label = label.ifBlank { "Key ${_geminiKeys.value.size + 1}" },
            key = key
        )
        _geminiKeys.value = newList
        viewModelScope.launch { secureSettings.setGeminiApiKeys(newList) }
        _message.value = "✅ Key added: $label"
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
        }
    }

    fun setActiveGeminiKey(index: Int) {
        if (index !in _geminiKeys.value.indices) return
        _geminiActiveKeyIndex.value = index
        viewModelScope.launch { secureSettings.setGeminiActiveKeyIndex(index) }
        val label = _geminiKeys.value[index].label
        _message.value = "🔑 Active: $label"
    }

    fun updateGeminiKeyLabel(index: Int, newLabel: String) {
        if (!checkUnlocked()) return
        val list = _geminiKeys.value.toMutableList()
        if (index !in list.indices) return
        list[index] = list[index].copy(label = newLabel)
        _geminiKeys.value = list
        viewModelScope.launch { secureSettings.setGeminiApiKeys(list) }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // SAVE OPERATIONS
    // ═════════════════════════════════════════════════════════════════════════

    fun saveGitHubSettings() {
        if (!checkUnlocked()) return

        viewModelScope.launch {
            _isSaving.value = true
            android.util.Log.d(TAG, "💾 SAVING GITHUB SETTINGS")

            try {
                if (_githubOwnerInput.value.isBlank()) {
                    _message.value = "❌ Owner cannot be empty"
                    return@launch
                }
                if (_githubRepoInput.value.isBlank()) {
                    _message.value = "❌ Repository cannot be empty"
                    return@launch
                }
                if (_githubTokenInput.value.isBlank()) {
                    _message.value = "❌ Token cannot be empty"
                    return@launch
                }

                secureSettings.setGitHubToken(_githubTokenInput.value)
                secureSettings.setGitHubConfig(
                    owner = _githubOwnerInput.value,
                    repo = _githubRepoInput.value,
                    branch = _githubBranchInput.value
                )

                _message.value = "✅ GitHub settings saved successfully"
                android.util.Log.d(TAG, "✅ GITHUB SETTINGS SAVED")

            } catch (e: Exception) {
                android.util.Log.e(TAG, "❌ SAVE FAILED", e)
                _message.value = "❌ Failed to save: ${e.message}"
            } finally {
                _isSaving.value = false
            }
        }
    }

    fun saveAnthropicSettings() {
        if (!checkUnlocked()) return

        viewModelScope.launch {
            _isSaving.value = true
            android.util.Log.d(TAG, "💾 SAVING ANTHROPIC SETTINGS")

            try {
                if (_anthropicKeyInput.value.isBlank()) {
                    _message.value = "❌ API Key cannot be empty"
                    return@launch
                }

                secureSettings.setAnthropicApiKey(_anthropicKeyInput.value, useBiometric = true)
                appSettings.setClaudeModel(_claudeModelInput.value)

                _message.value = "✅ Claude settings saved successfully"
                android.util.Log.d(TAG, "✅ ANTHROPIC SETTINGS SAVED")

            } catch (e: Exception) {
                android.util.Log.e(TAG, "❌ SAVE FAILED", e)
                _message.value = "❌ Failed to save: ${e.message}"
            } finally {
                _isSaving.value = false
            }
        }
    }

    fun saveDeepSeekSettings() {
        if (!checkUnlocked()) return

        viewModelScope.launch {
            _isSaving.value = true
            android.util.Log.d(TAG, "💾 SAVING DEEPSEEK SETTINGS")

            try {
                if (_deepSeekKeyInput.value.isBlank()) {
                    _message.value = "❌ DeepSeek API Key cannot be empty"
                    return@launch
                }

                secureSettings.setDeepSeekApiKey(_deepSeekKeyInput.value)

                _message.value = "✅ DeepSeek settings saved successfully"
                android.util.Log.d(TAG, "✅ DEEPSEEK SETTINGS SAVED")

            } catch (e: Exception) {
                android.util.Log.e(TAG, "❌ SAVE FAILED", e)
                _message.value = "❌ Failed to save: ${e.message}"
            } finally {
                _isSaving.value = false
            }
        }
    }

    fun saveGeminiSettings() {
        if (!checkUnlocked()) return

        viewModelScope.launch {
            _isSaving.value = true
            android.util.Log.d(TAG, "💾 SAVING GEMINI SETTINGS")

            try {
                if (_geminiKeys.value.isEmpty()) {
                    _message.value = "❌ Add at least one Gemini API key"
                    return@launch
                }

                secureSettings.setGeminiApiKeys(_geminiKeys.value)
                secureSettings.setGeminiActiveKeyIndex(_geminiActiveKeyIndex.value)
                secureSettings.setGeminiApiKey(_geminiKeys.value[_geminiActiveKeyIndex.value].key)
                appSettings.setGeminiModel(_geminiModelInput.value)

                _message.value = "✅ Gemini settings saved (${_geminiKeys.value.size} keys)"
                android.util.Log.d(TAG, "✅ GEMINI SETTINGS SAVED (${_geminiKeys.value.size} keys)")

            } catch (e: Exception) {
                android.util.Log.e(TAG, "❌ SAVE FAILED", e)
                _message.value = "❌ Failed to save: ${e.message}"
            } finally {
                _isSaving.value = false
            }
        }
    }

    fun saveAllSettings() {
        if (!checkUnlocked()) return

        viewModelScope.launch {
            _isSaving.value = true
            android.util.Log.d(TAG, "💾 SAVING ALL SETTINGS")

            try {
                if (_githubOwnerInput.value.isBlank() || _githubRepoInput.value.isBlank() ||
                    _githubTokenInput.value.isBlank() || _anthropicKeyInput.value.isBlank()
                ) {
                    _message.value = "❌ GitHub and Claude fields are required"
                    return@launch
                }

                secureSettings.setGitHubToken(_githubTokenInput.value)
                secureSettings.setGitHubConfig(
                    owner = _githubOwnerInput.value,
                    repo = _githubRepoInput.value,
                    branch = _githubBranchInput.value
                )
                secureSettings.setAnthropicApiKey(_anthropicKeyInput.value, useBiometric = true)
                appSettings.setClaudeModel(_claudeModelInput.value)

                if (_deepSeekKeyInput.value.isNotBlank()) {
                    secureSettings.setDeepSeekApiKey(_deepSeekKeyInput.value)
                }

                if (_geminiKeys.value.isNotEmpty()) {
                    secureSettings.setGeminiApiKeys(_geminiKeys.value)
                    secureSettings.setGeminiActiveKeyIndex(_geminiActiveKeyIndex.value)
                    secureSettings.setGeminiApiKey(_geminiKeys.value[_geminiActiveKeyIndex.value].key)
                    appSettings.setGeminiModel(_geminiModelInput.value)
                }

                _message.value = "✅ All settings saved successfully"
                android.util.Log.d(TAG, "✅ ALL SETTINGS SAVED")

            } catch (e: Exception) {
                android.util.Log.e(TAG, "❌ SAVE FAILED", e)
                _message.value = "❌ Failed to save: ${e.message}"
            } finally {
                _isSaving.value = false
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // TEST CONNECTIONS
    // ═════════════════════════════════════════════════════════════════════════

    fun testGitHubConnection() {
        viewModelScope.launch {
            _githubStatus.value = ConnectionStatus.Testing
            try {
                gitHubClient.getRepository()
                    .onSuccess { repo ->
                        _repoInfo.value = repo
                        _githubStatus.value = ConnectionStatus.Connected
                        _message.value = "✅ GitHub connected: ${repo.fullName}"
                    }
                    .onFailure { e ->
                        _githubStatus.value = ConnectionStatus.Error(e.message ?: "Unknown error")
                        _message.value = "❌ GitHub test failed: ${e.message}"
                    }
            } catch (e: Exception) {
                _githubStatus.value = ConnectionStatus.Error(e.message ?: "Unknown error")
                _message.value = "❌ GitHub test error: ${e.message}"
            }
        }
    }

    fun testClaudeConnection() {
        viewModelScope.launch {
            _claudeStatus.value = ConnectionStatus.Testing
            try {
                claudeClient.testConnection()
                    .onSuccess { message ->
                        _claudeStatus.value = ConnectionStatus.Connected
                        _message.value = "✅ $message"
                    }
                    .onFailure { e ->
                        _claudeStatus.value = ConnectionStatus.Error(e.message ?: "Unknown error")
                        _message.value = "❌ ${e.message}"
                    }
            } catch (e: Exception) {
                _claudeStatus.value = ConnectionStatus.Error(e.message ?: "Unknown error")
                _message.value = "❌ Connection error: ${e.message}"
            }
        }
    }

    fun testDeepSeekConnection() {
        val key = _deepSeekKeyInput.value.trim()
        if (key.isBlank()) {
            _message.value = "❌ Введите DeepSeek API key перед тестом"
            return
        }

        viewModelScope.launch {
            _deepSeekStatus.value = ConnectionStatus.Testing
            android.util.Log.d(TAG, "🧪 Testing DeepSeek connection...")

            try {
                val result = withContext(Dispatchers.IO) {
                    testDeepSeekApi(key)
                }
                if (result) {
                    _deepSeekStatus.value = ConnectionStatus.Connected
                    _message.value = "✅ DeepSeek connected successfully"
                    android.util.Log.d(TAG, "✅ DeepSeek test passed")
                } else {
                    _deepSeekStatus.value = ConnectionStatus.Error("Неверный ответ от API")
                    _message.value = "❌ DeepSeek: неверный ответ от API"
                }
            } catch (e: Exception) {
                val msg = e.message ?: "Unknown error"
                _deepSeekStatus.value = ConnectionStatus.Error(msg)
                _message.value = "❌ DeepSeek test failed: $msg"
                android.util.Log.e(TAG, "❌ DeepSeek test failed", e)
            }
        }
    }

    fun testGeminiConnection() {
        val activeKeys = _geminiKeys.value
        val activeIdx = _geminiActiveKeyIndex.value
        val key = if (activeKeys.isNotEmpty() && activeIdx in activeKeys.indices)
            activeKeys[activeIdx].key.trim()
        else _geminiKeyInput.value.trim()
        if (key.isBlank()) {
            _message.value = "❌ Add a Gemini API key first"
            return
        }

        viewModelScope.launch {
            _geminiStatus.value = ConnectionStatus.Testing
            android.util.Log.d(TAG, "🧪 Testing Gemini connection with model: ${_geminiModelInput.value}")

            try {
                val result = withContext(Dispatchers.IO) {
                    testGeminiApi(key, _geminiModelInput.value)
                }
                if (result) {
                    _geminiStatus.value = ConnectionStatus.Connected
                    _message.value = "✅ Gemini connected successfully"
                    android.util.Log.d(TAG, "✅ Gemini test passed")
                } else {
                    _geminiStatus.value = ConnectionStatus.Error("Неверный ответ от API")
                    _message.value = "❌ Gemini: неверный ответ от API"
                }
            } catch (e: Exception) {
                val msg = e.message ?: "Unknown error"
                _geminiStatus.value = ConnectionStatus.Error(msg)
                _message.value = "❌ Gemini test failed: $msg"
                android.util.Log.e(TAG, "❌ Gemini test failed", e)
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // INTERNAL API TEST HELPERS
    // ═════════════════════════════════════════════════════════════════════════

    private fun testDeepSeekApi(apiKey: String): Boolean {
        val url = "https://api.deepseek.com/v1/chat/completions"
        val body = JSONObject().apply {
            put("model", "deepseek-chat")
            put("max_tokens", 8)
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", "Hi")
                })
            })
        }

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
            connectTimeout = 20_000
            readTimeout = 30_000
            doOutput = true
        }

        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

        val code = connection.responseCode
        if (code !in 200..299) {
            val err = connection.errorStream?.let {
                BufferedReader(InputStreamReader(it)).use { r -> r.readText() }
            } ?: "HTTP $code"
            throw Exception(parseApiErrorMessage(code, err))
        }

        val response = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
        val json = JSONObject(response)
        return json.has("choices")
    }

    private fun testGeminiApi(apiKey: String, modelId: String): Boolean {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$modelId:generateContent"
        val body = JSONObject().apply {
            put("contents", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply { put("text", "Hi") })
                    })
                })
            })
            put("generationConfig", JSONObject().apply {
                put("maxOutputTokens", 8)
            })
        }

        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("x-goog-api-key", apiKey)
            connectTimeout = 20_000
            readTimeout = 30_000
            doOutput = true
        }

        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

        val code = connection.responseCode
        if (code !in 200..299) {
            val err = connection.errorStream?.let {
                BufferedReader(InputStreamReader(it)).use { r -> r.readText() }
            } ?: "HTTP $code"
            throw Exception(parseApiErrorMessage(code, err))
        }

        val response = BufferedReader(InputStreamReader(connection.inputStream)).use { it.readText() }
        val json = JSONObject(response)
        return json.has("candidates")
    }

    private fun parseApiErrorMessage(code: Int, body: String): String {
        val msg = try {
            val json = JSONObject(body)
            json.optJSONObject("error")?.optString("message")
                ?: json.optString("message", "").ifBlank { body.take(200) }
        } catch (_: Exception) { body.take(200) }

        return when (code) {
            400 -> "Неверный запрос: $msg"
            401 -> "Неверный API ключ"
            403 -> "Доступ запрещён: $msg"
            429 -> "Превышен лимит запросов"
            500, 502, 503 -> "Сервер недоступен, попробуйте позже"
            else -> "Ошибка $code: $msg"
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // UTILITIES
    // ═════════════════════════════════════════════════════════════════════════

    fun clearBiometricRequest() {
        _biometricAuthRequest.value = false
    }

    fun resetToDefaults() {
        _claudeModelInput.value = "claude-opus-4-6"
        _geminiModelInput.value = com.opuside.app.core.ai.GeminiModelConfig.GeminiModel.getDefault().modelId
        _message.value = "⚠️ Settings reset to defaults (not saved)"
    }

    fun clearMessage() {
        _message.value = null
    }

    companion object {
        private const val TAG = "SettingsViewModel"
        private const val UNLOCK_TIMEOUT_MS = 5 * 60 * 1000L
    }
}