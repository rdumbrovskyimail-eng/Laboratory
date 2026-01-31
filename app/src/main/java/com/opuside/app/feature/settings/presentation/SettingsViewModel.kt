package com.opuside.app.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opuside.app.BuildConfig
import com.opuside.app.core.data.AppSettings
import com.opuside.app.core.network.anthropic.ClaudeApiClient
import com.opuside.app.core.network.anthropic.model.ClaudeMessage
import com.opuside.app.core.network.github.GitHubApiClient
import com.opuside.app.core.network.github.model.GitHubRepository
import com.opuside.app.core.security.SecureSettingsDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
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
    private val appSettings: AppSettings,
    private val secureSettings: SecureSettingsDataStore,
    private val gitHubClient: GitHubApiClient,
    private val claudeClient: ClaudeApiClient
) : ViewModel() {

    // GitHub Settings
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

    // Anthropic Settings
    private val _anthropicKeyInput = MutableStateFlow("")
    val anthropicKeyInput: StateFlow<String> = _anthropicKeyInput.asStateFlow()

    private val _claudeModelInput = MutableStateFlow("claude-opus-4-5-20251101")
    val claudeModelInput: StateFlow<String> = _claudeModelInput.asStateFlow()

    private val _claudeStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Unknown)
    val claudeStatus: StateFlow<ConnectionStatus> = _claudeStatus.asStateFlow()

    // Cache Settings
    private val _cacheTimeoutInput = MutableStateFlow(5)
    val cacheTimeoutInput: StateFlow<Int> = _cacheTimeoutInput.asStateFlow()

    private val _maxCacheFilesInput = MutableStateFlow(20)
    val maxCacheFilesInput: StateFlow<Int> = _maxCacheFilesInput.asStateFlow()

    private val _autoClearCacheInput = MutableStateFlow(true)
    val autoClearCacheInput: StateFlow<Boolean> = _autoClearCacheInput.asStateFlow()

    // UI State
    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    // Biometric Event
    private val _biometricAuthRequest = MutableStateFlow(false)
    val biometricAuthRequest: StateFlow<Boolean> = _biometricAuthRequest.asStateFlow()

    // GitHub Config
    val gitHubConfig = appSettings.gitHubConfig

    // App Info
    val appVersion = BuildConfig.VERSION_NAME
    val buildType = if (BuildConfig.DEBUG) "Debug" else "Release"

    init {
        loadSettings()
    }

    private fun loadSettings() {
        viewModelScope.launch {
            // Load GitHub config
            gitHubConfig.collect { config ->
                _githubOwnerInput.value = config.owner
                _githubRepoInput.value = config.repo
                _githubBranchInput.value = config.branch
                _githubTokenInput.value = config.token
            }
        }

        viewModelScope.launch {
            // Load Anthropic key
            appSettings.anthropicApiKey.collect { key ->
                _anthropicKeyInput.value = key
            }
        }

        viewModelScope.launch {
            // Load Claude model
            appSettings.claudeModel.collect { model ->
                _claudeModelInput.value = model
            }
        }

        viewModelScope.launch {
            // Load cache settings
            appSettings.cacheConfig.collect { config ->
                _cacheTimeoutInput.value = config.timeoutMinutes
                _maxCacheFilesInput.value = config.maxFiles
                _autoClearCacheInput.value = config.autoClear
            }
        }
    }

    // GitHub Updates
    fun updateGitHubOwner(owner: String) {
        _githubOwnerInput.value = owner
    }

    fun updateGitHubRepo(repo: String) {
        _githubRepoInput.value = repo
    }

    fun updateGitHubToken(token: String) {
        _githubTokenInput.value = token
    }

    fun updateGitHubBranch(branch: String) {
        _githubBranchInput.value = branch
    }

    // Anthropic Updates
    fun updateAnthropicKey(key: String) {
        _anthropicKeyInput.value = key
    }

    fun updateClaudeModel(model: String) {
        _claudeModelInput.value = model
    }

    // Cache Updates
    fun updateCacheTimeout(minutes: Int) {
        _cacheTimeoutInput.value = minutes
    }

    fun updateMaxCacheFiles(count: Int) {
        _maxCacheFilesInput.value = count
    }

    fun updateAutoClearCache(enabled: Boolean) {
        _autoClearCacheInput.value = enabled
    }

    // Save Operations
    fun saveGitHubSettings() {
        viewModelScope.launch {
            _isSaving.value = true

            try {
                secureSettings.setGitHubToken(_githubTokenInput.value)
                appSettings.setGitHubConfig(
                    owner = _githubOwnerInput.value,
                    repo = _githubRepoInput.value,
                    branch = _githubBranchInput.value
                )
                _message.value = "✅ GitHub settings saved"
            } catch (e: Exception) {
                _message.value = "❌ Failed to save: ${e.message}"
            }

            _isSaving.value = false
        }
    }

    fun saveAnthropicSettings(useBiometric: Boolean) {
        viewModelScope.launch {
            _isSaving.value = true

            try {
                secureSettings.setAnthropicApiKey(_anthropicKeyInput.value, useBiometric)
                appSettings.setClaudeModel(_claudeModelInput.value)
                _message.value = "✅ Claude settings saved"
            } catch (e: Exception) {
                _message.value = "❌ Failed to save: ${e.message}"
            }

            _isSaving.value = false
        }
    }

    fun saveCacheSettings() {
        viewModelScope.launch {
            _isSaving.value = true

            try {
                appSettings.setCacheSettings(
                    timeoutMinutes = _cacheTimeoutInput.value,
                    maxFiles = _maxCacheFilesInput.value,
                    autoClear = _autoClearCacheInput.value
                )
                _message.value = "✅ Cache settings saved"
            } catch (e: Exception) {
                _message.value = "❌ Failed to save: ${e.message}"
            }

            _isSaving.value = false
        }
    }

    fun saveAllSettings() {
        viewModelScope.launch {
            _isSaving.value = true

            try {
                saveGitHubSettings()
                saveAnthropicSettings(false)
                saveCacheSettings()
                _message.value = "✅ All settings saved"
            } catch (e: Exception) {
                _message.value = "❌ Failed to save: ${e.message}"
            }

            _isSaving.value = false
        }
    }

    // Test Connections
    fun testGitHubConnection() {
        viewModelScope.launch {
            _githubStatus.value = ConnectionStatus.Testing

            try {
                val result = gitHubClient.getRepository(
                    owner = _githubOwnerInput.value,
                    repo = _githubRepoInput.value
                )

                result.onSuccess { repo ->
                    _repoInfo.value = repo
                    _githubStatus.value = ConnectionStatus.Connected
                }.onFailure { e ->
                    _githubStatus.value = ConnectionStatus.Error(e.message ?: "Unknown error")
                }
            } catch (e: Exception) {
                _githubStatus.value = ConnectionStatus.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun testClaudeConnection() {
        viewModelScope.launch {
            _claudeStatus.value = ConnectionStatus.Testing

            try {
                val result = claudeClient.sendMessage(
                    messages = listOf(
                        ClaudeMessage(role = "user", content = "Hello")
                    ),
                    maxTokens = 10
                )

                result.onSuccess {
                    _claudeStatus.value = ConnectionStatus.Connected
                }.onFailure { e ->
                    _claudeStatus.value = ConnectionStatus.Error(e.message ?: "Unknown error")
                }
            } catch (e: Exception) {
                _claudeStatus.value = ConnectionStatus.Error(e.message ?: "Unknown error")
            }
        }
    }

    // Biometric
    fun requestBiometricAuth() {
        _biometricAuthRequest.value = true
    }

    fun clearBiometricRequest() {
        _biometricAuthRequest.value = false
    }

    // Reset
    fun resetToDefaults() {
        viewModelScope.launch {
            _cacheTimeoutInput.value = 5
            _maxCacheFilesInput.value = 20
            _autoClearCacheInput.value = true
            _claudeModelInput.value = "claude-opus-4-5-20251101"
            _message.value = "⚠️ Settings reset to defaults (not saved)"
        }
    }

    fun clearMessage() {
        _message.value = null
    }
}