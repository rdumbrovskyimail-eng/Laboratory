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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
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
    // STATE - GitHub Settings
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
    // STATE - Anthropic Settings
    // ═════════════════════════════════════════════════════════════════════════

    private val _anthropicKeyInput = MutableStateFlow("")
    val anthropicKeyInput: StateFlow<String> = _anthropicKeyInput.asStateFlow()

    private val _claudeModelInput = MutableStateFlow("claude-opus-4-6")
    val claudeModelInput: StateFlow<String> = _claudeModelInput.asStateFlow()

    private val _claudeStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Unknown)
    val claudeStatus: StateFlow<ConnectionStatus> = _claudeStatus.asStateFlow()

    // ═════════════════════════════════════════════════════════════════════════
    // STATE - DeepSeek Settings
    // ═════════════════════════════════════════════════════════════════════════

    private val _deepSeekKeyInput = MutableStateFlow("")
    val deepSeekKeyInput: StateFlow<String> = _deepSeekKeyInput.asStateFlow()

    // ═════════════════════════════════════════════════════════════════════════
    // STATE - UI
    // ═════════════════════════════════════════════════════════════════════════

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _biometricAuthRequest = MutableStateFlow(false)
    val biometricAuthRequest: StateFlow<Boolean> = _biometricAuthRequest.asStateFlow()

    // ═════════════════════════════════════════════════════════════════════════
    // STATE - Biometric Lock
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
                val githubConfig = try {
                    appSettings.gitHubConfig.first()
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "  │  ❌ Failed to load GitHub config", e)
                    SecureSettingsDataStore.GitHubConfig("", "", "main", "")
                }

                android.util.Log.d(TAG, "  │  ├─ Owner: ${githubConfig.owner.ifEmpty { "[EMPTY]" }}")
                android.util.Log.d(TAG, "  │  ├─ Repo: ${githubConfig.repo.ifEmpty { "[EMPTY]" }}")
                android.util.Log.d(TAG, "  │  └─ Branch: ${githubConfig.branch}")

                val githubToken = try {
                    val token = secureSettings.getGitHubToken().first()
                    android.util.Log.d(TAG, "  │  └─ Token: ${if (token.isNotEmpty()) "[${token.take(8)}***]" else "[EMPTY]"}")
                    token
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "  │  └─ ❌ Failed to decrypt GitHub token", e)
                    ""
                }

                val anthropicKey = try {
                    val key = secureSettings.getAnthropicApiKey().first()
                    android.util.Log.d(TAG, "  │  └─ Anthropic: ${if (key.isNotEmpty()) "[${key.take(8)}***]" else "[EMPTY]"}")
                    key
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "  │  └─ ❌ Failed to decrypt Anthropic key", e)
                    ""
                }

                // ✅ DeepSeek
                val deepSeekKey = try {
                    val key = secureSettings.getDeepSeekApiKey().first()
                    android.util.Log.d(TAG, "  │  └─ DeepSeek: ${if (key.isNotEmpty()) "[${key.take(8)}***]" else "[EMPTY]"}")
                    key
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "  │  └─ ❌ Failed to decrypt DeepSeek key", e)
                    ""
                }

                val claudeModel = try {
                    appSettings.claudeModel.first()
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "     └─ ❌ Failed to load Claude model", e)
                    "claude-opus-4-6"
                }

                _githubOwnerInput.value = githubConfig.owner
                _githubRepoInput.value = githubConfig.repo
                _githubBranchInput.value = githubConfig.branch
                _githubTokenInput.value = githubToken
                _anthropicKeyInput.value = anthropicKey
                _deepSeekKeyInput.value = deepSeekKey
                _claudeModelInput.value = claudeModel

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
    // CONFIG IMPORT/EXPORT
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

    fun updateGitHubOwner(owner: String) {
        if (!_isUnlocked.value) { _message.value = "🔒 Unlock Settings to edit"; return }
        _githubOwnerInput.value = owner
    }

    fun updateGitHubRepo(repo: String) {
        if (!_isUnlocked.value) { _message.value = "🔒 Unlock Settings to edit"; return }
        _githubRepoInput.value = repo
    }

    fun updateGitHubToken(token: String) {
        if (!_isUnlocked.value) { _message.value = "🔒 Unlock Settings to edit"; return }
        _githubTokenInput.value = token
    }

    fun updateGitHubBranch(branch: String) {
        if (!_isUnlocked.value) { _message.value = "🔒 Unlock Settings to edit"; return }
        _githubBranchInput.value = branch
    }

    fun updateAnthropicKey(key: String) {
        if (!_isUnlocked.value) { _message.value = "🔒 Unlock Settings to edit"; return }
        _anthropicKeyInput.value = key
    }

    fun updateClaudeModel(model: String) {
        _claudeModelInput.value = model
    }

    // ✅ DeepSeek
    fun updateDeepSeekKey(key: String) {
        if (!_isUnlocked.value) { _message.value = "🔒 Unlock Settings to edit"; return }
        _deepSeekKeyInput.value = key
        android.util.Log.d(TAG, "🔄 DeepSeek Key updated: ${key.take(8)}***")
    }

    // ═════════════════════════════════════════════════════════════════════════
    // SAVE OPERATIONS
    // ═════════════════════════════════════════════════════════════════════════

    fun saveGitHubSettings() {
        if (!_isUnlocked.value) { _message.value = "🔒 Unlock Settings to save"; return }

        viewModelScope.launch {
            _isSaving.value = true
            android.util.Log.d(TAG, "💾 SAVING GITHUB SETTINGS")

            try {
                if (_githubOwnerInput.value.isBlank()) {
                    _message.value = "❌ Owner cannot be empty"; _isSaving.value = false; return@launch
                }
                if (_githubRepoInput.value.isBlank()) {
                    _message.value = "❌ Repository cannot be empty"; _isSaving.value = false; return@launch
                }
                if (_githubTokenInput.value.isBlank()) {
                    _message.value = "❌ Token cannot be empty"; _isSaving.value = false; return@launch
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
        if (!_isUnlocked.value) { _message.value = "🔒 Unlock Settings to save"; return }

        viewModelScope.launch {
            _isSaving.value = true
            android.util.Log.d(TAG, "💾 SAVING ANTHROPIC SETTINGS")

            try {
                if (_anthropicKeyInput.value.isBlank()) {
                    _message.value = "❌ API Key cannot be empty"; _isSaving.value = false; return@launch
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

    // ✅ DeepSeek
    fun saveDeepSeekSettings() {
        if (!_isUnlocked.value) { _message.value = "🔒 Unlock Settings to save"; return }

        viewModelScope.launch {
            _isSaving.value = true
            android.util.Log.d(TAG, "💾 SAVING DEEPSEEK SETTINGS")

            try {
                if (_deepSeekKeyInput.value.isBlank()) {
                    _message.value = "❌ DeepSeek API Key cannot be empty"
                    _isSaving.value = false
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

    fun saveAllSettings() {
        if (!_isUnlocked.value) { _message.value = "🔒 Unlock Settings to save"; return }

        viewModelScope.launch {
            _isSaving.value = true

            try {
                if (_githubOwnerInput.value.isBlank() || _githubRepoInput.value.isBlank() ||
                    _githubTokenInput.value.isBlank() || _anthropicKeyInput.value.isBlank()
                ) {
                    _message.value = "❌ GitHub and Claude fields are required"
                    _isSaving.value = false
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

                // DeepSeek — сохраняем только если введён
                if (_deepSeekKeyInput.value.isNotBlank()) {
                    secureSettings.setDeepSeekApiKey(_deepSeekKeyInput.value)
                }

                _message.value = "✅ All settings saved successfully"

            } catch (e: Exception) {
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
                val result = gitHubClient.getRepository()
                result.onSuccess { repo ->
                    _repoInfo.value = repo
                    _githubStatus.value = ConnectionStatus.Connected
                    _message.value = "✅ GitHub connected: ${repo.fullName}"
                }.onFailure { e ->
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
                val result = claudeClient.testConnection()
                result.onSuccess { message ->
                    _claudeStatus.value = ConnectionStatus.Connected
                    _message.value = "✅ $message"
                }.onFailure { e ->
                    _claudeStatus.value = ConnectionStatus.Error(e.message ?: "Unknown error")
                    _message.value = "❌ ${e.message}"
                }
            } catch (e: Exception) {
                _claudeStatus.value = ConnectionStatus.Error(e.message ?: "Unknown error")
                _message.value = "❌ Connection error: ${e.message}"
            }
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
