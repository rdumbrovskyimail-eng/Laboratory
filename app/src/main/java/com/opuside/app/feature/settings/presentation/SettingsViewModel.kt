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

    private val _claudeModelInput = MutableStateFlow("claude-opus-4-5-20250514")
    val claudeModelInput: StateFlow<String> = _claudeModelInput.asStateFlow()

    private val _claudeStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Unknown)
    val claudeStatus: StateFlow<ConnectionStatus> = _claudeStatus.asStateFlow()

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
    // 🔐 STATE - Biometric Lock
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
                    android.util.Log.e(TAG, "  │  ❌ Failed to load GitHub config from Flow", e)
                    SecureSettingsDataStore.GitHubConfig("", "", "main", "")
                }
                
                android.util.Log.d(TAG, "  │  ├─ Owner: ${if (githubConfig.owner.isNotEmpty()) "[${githubConfig.owner}]" else "[EMPTY]"}")
                android.util.Log.d(TAG, "  │  ├─ Repo: ${if (githubConfig.repo.isNotEmpty()) "[${githubConfig.repo}]" else "[EMPTY]"}")
                android.util.Log.d(TAG, "  │  └─ Branch: ${githubConfig.branch}")

                android.util.Log.d(TAG, "  ├─ Loading GitHub token...")
                val githubToken = try {
                    val token = secureSettings.getGitHubToken().first()
                    android.util.Log.d(TAG, "  │  └─ Token: ${if (token.isNotEmpty()) "[${token.take(10)}...]" else "[EMPTY]"}")
                    token
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "  │  └─ ❌ Failed to decrypt GitHub token", e)
                    ""
                }

                android.util.Log.d(TAG, "  ├─ Loading Anthropic API key...")
                val anthropicKey = try {
                    val key = secureSettings.getAnthropicApiKey().first()
                    android.util.Log.d(TAG, "  │  └─ Key: ${if (key.isNotEmpty()) "[${key.take(10)}...]" else "[EMPTY]"}")
                    key
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "  │  └─ ❌ Failed to decrypt Anthropic key", e)
                    ""
                }

                android.util.Log.d(TAG, "  └─ Loading Claude model...")
                val claudeModel = try {
                    appSettings.claudeModel.first()
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "     └─ ❌ Failed to load Claude model", e)
                    "claude-opus-4-5-20250514"
                }
                android.util.Log.d(TAG, "     └─ Model: $claudeModel")

                _githubOwnerInput.value = githubConfig.owner
                _githubRepoInput.value = githubConfig.repo
                _githubBranchInput.value = githubConfig.branch
                _githubTokenInput.value = githubToken
                _anthropicKeyInput.value = anthropicKey
                _claudeModelInput.value = claudeModel
                
                android.util.Log.d(TAG, "")
                android.util.Log.d(TAG, "━".repeat(80))
                android.util.Log.d(TAG, "✅ Settings loaded successfully")
                android.util.Log.d(TAG, "━".repeat(80))
                
            } catch (e: Exception) {
                android.util.Log.e(TAG, "━".repeat(80))
                android.util.Log.e(TAG, "❌ CRITICAL: Failed to load settings", e)
                android.util.Log.e(TAG, "━".repeat(80))
                _message.value = "⚠️ Failed to load settings: ${e.message}"
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 🔐 BIOMETRIC LOCK MANAGEMENT
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
        unlockJob?.cancel()
        unlockJob = null
        timerJob?.cancel()
        timerJob = null
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
            
            android.util.Log.d(TAG, "━".repeat(80))
            android.util.Log.d(TAG, "📥 IMPORTING CONFIGURATION")
            android.util.Log.d(TAG, "━".repeat(80))
            
            try {
                val result = ConfigImporter.importConfig(context, fileUri)
                
                result.onSuccess { config ->
                    android.util.Log.d(TAG, "  ├─ Applying configuration...")
                    
                    config.githubOwner?.let { _githubOwnerInput.value = it }
                    config.githubRepo?.let { _githubRepoInput.value = it }
                    config.githubBranch?.let { _githubBranchInput.value = it }
                    config.githubToken?.let { _githubTokenInput.value = it }
                    config.claudeApiKey?.let { _anthropicKeyInput.value = it }
                    config.claudeModel?.let { _claudeModelInput.value = it }
                    
                    android.util.Log.d(TAG, "  └─ ✅ Configuration applied")
                    android.util.Log.d(TAG, "")
                    android.util.Log.d(TAG, "📊 SUMMARY:")
                    android.util.Log.d(TAG, config.toSummary())
                    android.util.Log.d(TAG, "━".repeat(80))
                    
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
        if (!_isUnlocked.value) {
            _message.value = "🔒 Unlock Settings to edit"
            return
        }
        _githubOwnerInput.value = owner
        android.util.Log.d(TAG, "🔄 GitHub Owner updated: $owner")
    }

    fun updateGitHubRepo(repo: String) {
        if (!_isUnlocked.value) {
            _message.value = "🔒 Unlock Settings to edit"
            return
        }
        _githubRepoInput.value = repo
        android.util.Log.d(TAG, "🔄 GitHub Repo updated: $repo")
    }

    fun updateGitHubToken(token: String) {
        if (!_isUnlocked.value) {
            _message.value = "🔒 Unlock Settings to edit"
            return
        }
        _githubTokenInput.value = token
        android.util.Log.d(TAG, "🔄 GitHub Token updated: ${token.take(10)}...")
    }

    fun updateGitHubBranch(branch: String) {
        if (!_isUnlocked.value) {
            _message.value = "🔒 Unlock Settings to edit"
            return
        }
        _githubBranchInput.value = branch
        android.util.Log.d(TAG, "🔄 GitHub Branch updated: $branch")
    }

    fun updateAnthropicKey(key: String) {
        if (!_isUnlocked.value) {
            _message.value = "🔒 Unlock Settings to edit"
            return
        }
        _anthropicKeyInput.value = key
        android.util.Log.d(TAG, "🔄 Anthropic Key updated: ${key.take(10)}...")
    }

    fun updateClaudeModel(model: String) {
        _claudeModelInput.value = model
        android.util.Log.d(TAG, "🔄 Claude Model updated: $model")
    }

    // ═════════════════════════════════════════════════════════════════════════
    // SAVE OPERATIONS
    // ═════════════════════════════════════════════════════════════════════════

    fun saveGitHubSettings() {
        if (!_isUnlocked.value) {
            _message.value = "🔒 Unlock Settings to save"
            return
        }
        
        viewModelScope.launch {
            _isSaving.value = true
            
            android.util.Log.d(TAG, "━".repeat(80))
            android.util.Log.d(TAG, "💾 SAVING GITHUB SETTINGS")
            android.util.Log.d(TAG, "━".repeat(80))

            try {
                android.util.Log.d(TAG, "  ├─ Validating inputs...")
                
                if (_githubOwnerInput.value.isBlank()) {
                    android.util.Log.w(TAG, "  │  └─ ❌ Owner is blank")
                    _message.value = "❌ Owner cannot be empty"
                    _isSaving.value = false
                    return@launch
                }
                android.util.Log.d(TAG, "  │  ├─ Owner: ${_githubOwnerInput.value}")
                
                if (_githubRepoInput.value.isBlank()) {
                    android.util.Log.w(TAG, "  │  └─ ❌ Repository is blank")
                    _message.value = "❌ Repository cannot be empty"
                    _isSaving.value = false
                    return@launch
                }
                android.util.Log.d(TAG, "  │  ├─ Repo: ${_githubRepoInput.value}")
                
                if (_githubTokenInput.value.isBlank()) {
                    android.util.Log.w(TAG, "  │  └─ ❌ Token is blank")
                    _message.value = "❌ Token cannot be empty"
                    _isSaving.value = false
                    return@launch
                }
                android.util.Log.d(TAG, "  │  ├─ Token: ${_githubTokenInput.value.take(10)}...")
                android.util.Log.d(TAG, "  │  └─ Branch: ${_githubBranchInput.value}")

                android.util.Log.d(TAG, "  ├─ Saving GitHub token...")
                try {
                    secureSettings.setGitHubToken(_githubTokenInput.value)
                    android.util.Log.d(TAG, "  │  └─ ✅ Token saved")
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "  │  └─ ❌ Failed to save token", e)
                    _message.value = "❌ Failed to save token: ${e.message}"
                    _isSaving.value = false
                    return@launch
                }

                android.util.Log.d(TAG, "  ├─ Saving GitHub config...")
                try {
                    secureSettings.setGitHubConfig(
                        owner = _githubOwnerInput.value,
                        repo = _githubRepoInput.value,
                        branch = _githubBranchInput.value
                    )
                    android.util.Log.d(TAG, "  │  └─ ✅ Config saved")
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "  │  └─ ❌ Failed to save config", e)
                    _message.value = "❌ Failed to save config: ${e.message}"
                    _isSaving.value = false
                    return@launch
                }

                _message.value = "✅ GitHub settings saved successfully"
                android.util.Log.d(TAG, "━".repeat(80))
                android.util.Log.d(TAG, "✅ GITHUB SETTINGS SAVED SUCCESSFULLY")
                android.util.Log.d(TAG, "━".repeat(80))
                
            } catch (e: Exception) {
                android.util.Log.e(TAG, "━".repeat(80))
                android.util.Log.e(TAG, "❌ SAVE FAILED", e)
                android.util.Log.e(TAG, "━".repeat(80))
                _message.value = "❌ Failed to save: ${e.message}"
            } finally {
                _isSaving.value = false
            }
        }
    }

    fun saveAnthropicSettings() {
        if (!_isUnlocked.value) {
            _message.value = "🔒 Unlock Settings to save"
            return
        }
        
        viewModelScope.launch {
            _isSaving.value = true
            
            android.util.Log.d(TAG, "━".repeat(80))
            android.util.Log.d(TAG, "💾 SAVING ANTHROPIC SETTINGS")
            android.util.Log.d(TAG, "━".repeat(80))

            try {
                android.util.Log.d(TAG, "  ├─ Validating inputs...")
                
                if (_anthropicKeyInput.value.isBlank()) {
                    android.util.Log.w(TAG, "  │  └─ ❌ API Key is blank")
                    _message.value = "❌ API Key cannot be empty"
                    _isSaving.value = false
                    return@launch
                }
                android.util.Log.d(TAG, "  │  ├─ Key: ${_anthropicKeyInput.value.take(10)}...")
                android.util.Log.d(TAG, "  │  └─ Model: ${_claudeModelInput.value}")

                android.util.Log.d(TAG, "  ├─ Saving Anthropic API key...")
                try {
                    secureSettings.setAnthropicApiKey(_anthropicKeyInput.value, useBiometric = true)
                    android.util.Log.d(TAG, "  │  └─ ✅ Key saved with biometric protection")
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "  │  └─ ❌ Failed to save key", e)
                    _message.value = "❌ Failed to save key: ${e.message}"
                    _isSaving.value = false
                    return@launch
                }

                android.util.Log.d(TAG, "  ├─ Saving Claude model...")
                try {
                    appSettings.setClaudeModel(_claudeModelInput.value)
                    android.util.Log.d(TAG, "  │  └─ ✅ Model saved")
                } catch (e: Exception) {
                    android.util.Log.e(TAG, "  │  └─ ❌ Failed to save model", e)
                    _message.value = "❌ Failed to save model: ${e.message}"
                    _isSaving.value = false
                    return@launch
                }

                _message.value = "✅ Claude settings saved successfully"
                android.util.Log.d(TAG, "━".repeat(80))
                android.util.Log.d(TAG, "✅ ANTHROPIC SETTINGS SAVED SUCCESSFULLY")
                android.util.Log.d(TAG, "━".repeat(80))
                
            } catch (e: Exception) {
                android.util.Log.e(TAG, "━".repeat(80))
                android.util.Log.e(TAG, "❌ SAVE FAILED", e)
                android.util.Log.e(TAG, "━".repeat(80))
                _message.value = "❌ Failed to save: ${e.message}"
            } finally {
                _isSaving.value = false
            }
        }
    }

    fun saveAllSettings() {
        if (!_isUnlocked.value) {
            _message.value = "🔒 Unlock Settings to save"
            return
        }
        
        viewModelScope.launch {
            _isSaving.value = true

            try {
                if (_githubOwnerInput.value.isBlank() || _githubRepoInput.value.isBlank() || 
                    _githubTokenInput.value.isBlank() || _anthropicKeyInput.value.isBlank()) {
                    _message.value = "❌ All fields are required"
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
            android.util.Log.d(TAG, "🔍 Testing GitHub connection...")

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
            android.util.Log.d(TAG, "🔍 Testing Claude connection...")

            try {
                val result = claudeClient.testConnection()

                result.onSuccess { message ->
                    _claudeStatus.value = ConnectionStatus.Connected
                    _message.value = "✅ $message"
                    
                }.onFailure { e ->
                    val errorMessage = e.message ?: "Unknown error"
                    _claudeStatus.value = ConnectionStatus.Error(errorMessage)
                    _message.value = "❌ $errorMessage"
                }
                
            } catch (e: Exception) {
                val errorMessage = e.message ?: "Unknown error"
                _claudeStatus.value = ConnectionStatus.Error(errorMessage)
                _message.value = "❌ Connection error: $errorMessage"
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
        _claudeModelInput.value = "claude-opus-4-5-20250514"
        _message.value = "⚠️ Settings reset to defaults (not saved)"
    }

    fun clearMessage() {
        _message.value = null
    }

    companion object {
        private const val TAG = "SettingsViewModel"
        private const val UNLOCK_TIMEOUT_MS = 5 * 60 * 1000L // 5 минут
    }
}