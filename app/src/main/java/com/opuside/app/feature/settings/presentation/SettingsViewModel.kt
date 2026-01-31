package com.opuside.app.feature.settings.presentation

import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opuside.app.BuildConfig
import com.opuside.app.core.data.AppSettings
import com.opuside.app.core.security.SecureSettingsDataStore
import com.opuside.app.core.network.anthropic.ClaudeApiClient
import com.opuside.app.core.network.anthropic.model.ClaudeMessage
import com.opuside.app.core.network.github.GitHubApiClient
import com.opuside.app.core.network.github.model.GitHubRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * ✅ ИСПРАВЛЕНО: Проблема №8 - Убран FragmentActivity из ViewModel
 * Используется Event pattern для биометрической аутентификации
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val appSettings: AppSettings,
    private val secureSettings: SecureSettingsDataStore,
    private val claudeClient: ClaudeApiClient,
    private val gitHubClient: GitHubApiClient
) : ViewModel() {

    // ═══════════════════════════════════════════════════════════════════════════
    // GITHUB SETTINGS
    // ═══════════════════════════════════════════════════════════════════════════

    val gitHubConfig = appSettings.gitHubConfig
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 
            AppSettings.GitHubConfig("", "", "main", ""))

    private val _githubStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Unknown)
    val githubStatus: StateFlow<ConnectionStatus> = _githubStatus.asStateFlow()

    private val _repoInfo = MutableStateFlow<GitHubRepository?>(null)
    val repoInfo: StateFlow<GitHubRepository?> = _repoInfo.asStateFlow()

    // Input fields
    private val _githubOwnerInput = MutableStateFlow("")
    val githubOwnerInput: StateFlow<String> = _githubOwnerInput.asStateFlow()

    private val _githubRepoInput = MutableStateFlow("")
    val githubRepoInput: StateFlow<String> = _githubRepoInput.asStateFlow()

    private val _githubTokenInput = MutableStateFlow("")
    val githubTokenInput: StateFlow<String> = _githubTokenInput.asStateFlow()

    private val _githubBranchInput = MutableStateFlow("main")
    val githubBranchInput: StateFlow<String> = _githubBranchInput.asStateFlow()

    // ═══════════════════════════════════════════════════════════════════════════
    // ANTHROPIC SETTINGS
    // ═══════════════════════════════════════════════════════════════════════════

    val anthropicApiKey = appSettings.anthropicApiKey
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

    val claudeModel = appSettings.claudeModel
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "claude-opus-4-5-20251101")

    private val _claudeStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Unknown)
    val claudeStatus: StateFlow<ConnectionStatus> = _claudeStatus.asStateFlow()

    private val _anthropicKeyInput = MutableStateFlow("")
    val anthropicKeyInput: StateFlow<String> = _anthropicKeyInput.asStateFlow()

    private val _claudeModelInput = MutableStateFlow("claude-opus-4-5-20251101")
    val claudeModelInput: StateFlow<String> = _claudeModelInput.asStateFlow()

    // ═══════════════════════════════════════════════════════════════════════════
    // ✅ ДОБАВЛЕНО: Проблема №8 - Event pattern для биометрии
    // ═══════════════════════════════════════════════════════════════════════════

    private val _biometricAuthRequest = MutableStateFlow(false)
    val biometricAuthRequest: StateFlow<Boolean> = _biometricAuthRequest.asStateFlow()

    // ═══════════════════════════════════════════════════════════════════════════
    // CACHE SETTINGS
    // ═══════════════════════════════════════════════════════════════════════════

    val cacheConfig = appSettings.cacheConfig
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000),
            AppSettings.CacheConfig(5, 20, true))

    private val _cacheTimeoutInput = MutableStateFlow(5)
    val cacheTimeoutInput: StateFlow<Int> = _cacheTimeoutInput.asStateFlow()

    private val _maxCacheFilesInput = MutableStateFlow(20)
    val maxCacheFilesInput: StateFlow<Int> = _maxCacheFilesInput.asStateFlow()

    private val _autoClearCacheInput = MutableStateFlow(true)
    val autoClearCacheInput: StateFlow<Boolean> = _autoClearCacheInput.asStateFlow()

    // ═══════════════════════════════════════════════════════════════════════════
    // UI STATE
    // ═══════════════════════════════════════════════════════════════════════════

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    // ═══════════════════════════════════════════════════════════════════════════
    // APP INFO
    // ═══════════════════════════════════════════════════════════════════════════

    val appVersion: String = BuildConfig.VERSION_NAME
    val buildType: String = BuildConfig.BUILD_TYPE

    // ═══════════════════════════════════════════════════════════════════════════
    // INITIALIZATION
    // ═══════════════════════════════════════════════════════════════════════════

    init {
        // Загружаем текущие настройки в input поля
        viewModelScope.launch {
            appSettings.gitHubConfig.collect { config ->
                _githubOwnerInput.value = config.owner
                _githubRepoInput.value = config.repo
                _githubBranchInput.value = config.branch
                _githubTokenInput.value = config.token
            }
        }
        
        viewModelScope.launch {
            appSettings.anthropicApiKey.collect { key ->
                _anthropicKeyInput.value = key
            }
        }
        
        viewModelScope.launch {
            appSettings.claudeModel.collect { model ->
                _claudeModelInput.value = model
            }
        }
        
        viewModelScope.launch {
            appSettings.cacheConfig.collect { config ->
                _cacheTimeoutInput.value = config.timeoutMinutes
                _maxCacheFilesInput.value = config.maxFiles
                _autoClearCacheInput.value = config.autoClear
            }
        }

        // Fallback: используем BuildConfig если DataStore пуст
        viewModelScope.launch {
            val config = appSettings.gitHubConfig.first()
            if (!config.isConfigured && BuildConfig.GITHUB_TOKEN.isNotEmpty()) {
                appSettings.setGitHubConfig(
                    owner = BuildConfig.GITHUB_OWNER,
                    repo = BuildConfig.GITHUB_REPO,
                    branch = "main"
                )
                appSettings.setGitHubToken(BuildConfig.GITHUB_TOKEN)
            }
        }
        
        viewModelScope.launch {
            val key = appSettings.anthropicApiKey.first()
            if (key.isEmpty() && BuildConfig.ANTHROPIC_API_KEY.isNotEmpty()) {
                appSettings.setAnthropicApiKey(BuildConfig.ANTHROPIC_API_KEY)
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // INPUT HANDLERS
    // ═══════════════════════════════════════════════════════════════════════════

    fun updateGitHubOwner(value: String) { _githubOwnerInput.value = value }
    fun updateGitHubRepo(value: String) { _githubRepoInput.value = value }
    fun updateGitHubToken(value: String) { _githubTokenInput.value = value }
    fun updateGitHubBranch(value: String) { _githubBranchInput.value = value }
    fun updateAnthropicKey(value: String) { _anthropicKeyInput.value = value }
    fun updateClaudeModel(value: String) { _claudeModelInput.value = value }
    fun updateCacheTimeout(value: Int) { _cacheTimeoutInput.value = value.coerceIn(1, 30) }
    fun updateMaxCacheFiles(value: Int) { _maxCacheFilesInput.value = value.coerceIn(1, 50) }
    fun updateAutoClearCache(value: Boolean) { _autoClearCacheInput.value = value }

    // ═══════════════════════════════════════════════════════════════════════════
    // SAVE OPERATIONS
    // ═══════════════════════════════════════════════════════════════════════════

    fun saveGitHubSettings() {
        viewModelScope.launch {
            _isSaving.value = true
            
            appSettings.setGitHubConfig(
                owner = _githubOwnerInput.value,
                repo = _githubRepoInput.value,
                branch = _githubBranchInput.value
            )
            appSettings.setGitHubToken(_githubTokenInput.value)
            
            _message.value = "GitHub settings saved!"
            _isSaving.value = false
            
            // Автоматически тестируем подключение
            testGitHubConnection()
        }
    }

    /**
     * ✅ ОБНОВЛЕНО: Сохранение с опциональной биометрией
     */
    fun saveAnthropicSettings(useBiometric: Boolean = false) {
        viewModelScope.launch {
            _isSaving.value = true
            
            if (useBiometric) {
                // Сохраняем в защищённое хранилище
                secureSettings.setAnthropicApiKey(_anthropicKeyInput.value)
            } else {
                // Обычное сохранение
                appSettings.setAnthropicApiKey(_anthropicKeyInput.value)
            }
            
            appSettings.setClaudeModel(_claudeModelInput.value)
            
            _message.value = if (useBiometric) {
                "Anthropic settings saved with biometric protection!"
            } else {
                "Anthropic settings saved!"
            }
            _isSaving.value = false
            
            testClaudeConnection()
        }
    }

    fun saveCacheSettings() {
        viewModelScope.launch {
            _isSaving.value = true
            
            appSettings.setCacheSettings(
                timeoutMinutes = _cacheTimeoutInput.value,
                maxFiles = _maxCacheFilesInput.value,
                autoClear = _autoClearCacheInput.value
            )
            
            _message.value = "Cache settings saved!"
            _isSaving.value = false
        }
    }

    fun saveAllSettings() {
        viewModelScope.launch {
            _isSaving.value = true
            
            appSettings.setGitHubConfig(
                _githubOwnerInput.value,
                _githubRepoInput.value,
                _githubBranchInput.value
            )
            appSettings.setGitHubToken(_githubTokenInput.value)
            appSettings.setAnthropicApiKey(_anthropicKeyInput.value)
            appSettings.setClaudeModel(_claudeModelInput.value)
            appSettings.setCacheSettings(
                _cacheTimeoutInput.value,
                _maxCacheFilesInput.value,
                _autoClearCacheInput.value
            )
            
            _message.value = "All settings saved!"
            _isSaving.value = false
            
            testAllConnections()
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CONNECTION TESTS
    // ═══════════════════════════════════════════════════════════════════════════

    fun testGitHubConnection() {
        viewModelScope.launch {
            _githubStatus.value = ConnectionStatus.Testing
            
            gitHubClient.getRepository()
                .onSuccess { repo ->
                    _githubStatus.value = ConnectionStatus.Connected
                    _repoInfo.value = repo
                }
                .onFailure { e ->
                    _githubStatus.value = ConnectionStatus.Error(e.message ?: "Connection failed")
                    _repoInfo.value = null
                }
        }
    }

    fun testClaudeConnection() {
        viewModelScope.launch {
            _claudeStatus.value = ConnectionStatus.Testing
            
            claudeClient.sendMessage(
                messages = listOf(ClaudeMessage("user", "Reply with just: OK")),
                maxTokens = 10
            )
                .onSuccess {
                    _claudeStatus.value = ConnectionStatus.Connected
                }
                .onFailure { e ->
                    _claudeStatus.value = ConnectionStatus.Error(e.message ?: "Connection failed")
                }
        }
    }

    fun testAllConnections() {
        testGitHubConnection()
        testClaudeConnection()
    }

    /**
     * ✅ ИСПРАВЛЕНО: Проблема №8 - Убран FragmentActivity из ViewModel
     * Теперь ViewModel только сигнализирует UI о необходимости биометрии
     */
    fun requestBiometricAuth() {
        _biometricAuthRequest.value = true
    }

    /**
     * ✅ ДОБАВЛЕНО: Очистка биометрического запроса после обработки
     */
    fun clearBiometricRequest() {
        _biometricAuthRequest.value = false
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // RESET
    // ═══════════════════════════════════════════════════════════════════════════

    fun resetToDefaults() {
        viewModelScope.launch {
            appSettings.clearAll()
            _message.value = "Settings reset to defaults"
            
            // Reload from BuildConfig
            _githubOwnerInput.value = BuildConfig.GITHUB_OWNER
            _githubRepoInput.value = BuildConfig.GITHUB_REPO
            _githubTokenInput.value = BuildConfig.GITHUB_TOKEN
            _githubBranchInput.value = "main"
            _anthropicKeyInput.value = BuildConfig.ANTHROPIC_API_KEY
            _claudeModelInput.value = "claude-opus-4-5-20251101"
            _cacheTimeoutInput.value = 5
            _maxCacheFilesInput.value = 20
            _autoClearCacheInput.value = true
            
            _githubStatus.value = ConnectionStatus.Unknown
            _claudeStatus.value = ConnectionStatus.Unknown
            _repoInfo.value = null
        }
    }

    fun clearMessage() {
        _message.value = null
    }
}

sealed class ConnectionStatus {
    data object Unknown : ConnectionStatus()
    data object Testing : ConnectionStatus()
    data object Connected : ConnectionStatus()
    data class Error(val message: String) : ConnectionStatus()
}