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

/**
 * ✅ КРИТИЧЕСКИ ИСПРАВЛЕНО (2026 стандарты):
 * 
 * ПРОБЛЕМА #1: Бесконечный .collect {} в loadSettings()
 * ────────────────────────────────────────────────────────
 * БЫЛО:
 * ```kotlin
 * private fun loadSettings() {
 *     viewModelScope.launch {
 *         gitHubConfig.collect { config ->  // ← НИКОГДА не завершается!
 *             _githubOwnerInput.value = config.owner
 *         }
 *     }
 *     viewModelScope.launch { /* Этот код НИКОГДА не выполнится */ }
 * }
 * ```
 * 
 * ПОСЛЕДСТВИЯ:
 * - После Save данные перезаписывались пустыми значениями из DataStore
 * - 4 разных coroutine конкурировали за обновление UI
 * - Memory leak при rotation
 * - Поля очищались после сохранения
 * 
 * РЕШЕНИЕ:
 * ────────
 * - Используем .first() для one-shot загрузки
 * - Инициализация ОДИН РАЗ в init {}
 * - Нет бесконечных collect {}
 * - После Save поля НЕ перезаписываются
 * 
 * ПРОБЛЕМА #2: saveGitHubSettings() вызывается внутри saveAllSettings()
 * ─────────────────────────────────────────────────────────────────────
 * БЫЛО:
 * ```kotlin
 * fun saveAllSettings() {
 *     saveGitHubSettings()  // ← suspend fun в non-suspend context!
 * }
 * ```
 * 
 * РЕШЕНИЕ:
 * - Дублируем логику сохранения в saveAllSettings()
 * - Каждая функция автономна
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
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

    private val _claudeModelInput = MutableStateFlow("claude-opus-4-5-20251101")
    val claudeModelInput: StateFlow<String> = _claudeModelInput.asStateFlow()

    private val _claudeStatus = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Unknown)
    val claudeStatus: StateFlow<ConnectionStatus> = _claudeStatus.asStateFlow()

    // ═════════════════════════════════════════════════════════════════════════
    // STATE - Cache Settings
    // ═════════════════════════════════════════════════════════════════════════
    
    private val _cacheTimeoutInput = MutableStateFlow(5)
    val cacheTimeoutInput: StateFlow<Int> = _cacheTimeoutInput.asStateFlow()

    private val _maxCacheFilesInput = MutableStateFlow(20)
    val maxCacheFilesInput: StateFlow<Int> = _maxCacheFilesInput.asStateFlow()

    private val _autoClearCacheInput = MutableStateFlow(true)
    val autoClearCacheInput: StateFlow<Boolean> = _autoClearCacheInput.asStateFlow()

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
    // PUBLIC PROPERTIES
    // ═════════════════════════════════════════════════════════════════════════
    
    val gitHubConfig = appSettings.gitHubConfig
    val appVersion = BuildConfig.VERSION_NAME
    val buildType = if (BuildConfig.DEBUG) "Debug" else "Release"

    // ═════════════════════════════════════════════════════════════════════════
    // INITIALIZATION
    // ═════════════════════════════════════════════════════════════════════════

    init {
        loadSettings()
    }

    /**
     * ✅ КРИТИЧЕСКИ ИСПРАВЛЕНО: Правильная инициализация БЕЗ бесконечных collect {}
     * 
     * БЫЛО:
     * ```kotlin
     * private fun loadSettings() {
     *     viewModelScope.launch {
     *         gitHubConfig.collect { config ->  // ← БЕСКОНЕЧНЫЙ ЦИКЛ
     *             _githubOwnerInput.value = config.owner
     *         }
     *     }
     *     viewModelScope.launch { /* НИКОГДА не выполнится */ }
     * }
     * ```
     * 
     * СТАЛО:
     * ```kotlin
     * private fun loadSettings() {
     *     viewModelScope.launch {
     *         val config = appSettings.gitHubConfig.first()  // ← ONE-SHOT
     *         _githubOwnerInput.value = config.owner
     *     }
     * }
     * ```
     */
    private fun loadSettings() {
        viewModelScope.launch {
            // ✅ ONE-SHOT загрузка настроек (не бесконечный collect!)
            
            // GitHub config
            val config = appSettings.gitHubConfig.first()
            _githubOwnerInput.value = config.owner
            _githubRepoInput.value = config.repo
            _githubBranchInput.value = config.branch
            _githubTokenInput.value = config.token
            
            // Anthropic key
            val apiKey = appSettings.anthropicApiKey.first()
            _anthropicKeyInput.value = apiKey
            
            // Claude model
            val model = appSettings.claudeModel.first()
            _claudeModelInput.value = model
            
            // Cache settings
            val cacheConfig = appSettings.cacheConfig.first()
            _cacheTimeoutInput.value = cacheConfig.timeoutMinutes
            _maxCacheFilesInput.value = cacheConfig.maxFiles
            _autoClearCacheInput.value = cacheConfig.autoClear
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // UPDATE FUNCTIONS - GitHub
    // ═════════════════════════════════════════════════════════════════════════

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

    // ═════════════════════════════════════════════════════════════════════════
    // UPDATE FUNCTIONS - Anthropic
    // ═════════════════════════════════════════════════════════════════════════

    fun updateAnthropicKey(key: String) {
        _anthropicKeyInput.value = key
    }

    fun updateClaudeModel(model: String) {
        _claudeModelInput.value = model
    }

    // ═════════════════════════════════════════════════════════════════════════
    // UPDATE FUNCTIONS - Cache
    // ═════════════════════════════════════════════════════════════════════════

    fun updateCacheTimeout(minutes: Int) {
        _cacheTimeoutInput.value = minutes
    }

    fun updateMaxCacheFiles(count: Int) {
        _maxCacheFilesInput.value = count
    }

    fun updateAutoClearCache(enabled: Boolean) {
        _autoClearCacheInput.value = enabled
    }

    // ═════════════════════════════════════════════════════════════════════════
    // SAVE OPERATIONS
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * ✅ ИСПРАВЛЕНО: Правильное сохранение GitHub настроек
     */
    fun saveGitHubSettings() {
        viewModelScope.launch {
            _isSaving.value = true

            try {
                // Сохраняем токен (зашифрованно)
                secureSettings.setGitHubToken(_githubTokenInput.value)
                
                // Сохраняем owner/repo/branch (незашифрованно)
                secureSettings.setGitHubConfig(
                    owner = _githubOwnerInput.value,
                    repo = _githubRepoInput.value,
                    branch = _githubBranchInput.value
                )
                
                _message.value = "✅ GitHub settings saved successfully"
            } catch (e: Exception) {
                _message.value = "❌ Failed to save: ${e.message}"
                android.util.Log.e("SettingsViewModel", "Save failed", e)
            } finally {
                _isSaving.value = false
            }
        }
    }

    fun saveAnthropicSettings(useBiometric: Boolean) {
        viewModelScope.launch {
            _isSaving.value = true

            try {
                secureSettings.setAnthropicApiKey(_anthropicKeyInput.value, useBiometric)
                appSettings.setClaudeModel(_claudeModelInput.value)
                _message.value = "✅ Claude settings saved successfully"
            } catch (e: Exception) {
                _message.value = "❌ Failed to save: ${e.message}"
                android.util.Log.e("SettingsViewModel", "Save failed", e)
            } finally {
                _isSaving.value = false
            }
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
                _message.value = "✅ Cache settings saved successfully"
            } catch (e: Exception) {
                _message.value = "❌ Failed to save: ${e.message}"
                android.util.Log.e("SettingsViewModel", "Save failed", e)
            } finally {
                _isSaving.value = false
            }
        }
    }

    /**
     * ✅ ИСПРАВЛЕНО: Правильное сохранение всех настроек
     * 
     * ПРОБЛЕМА: suspend функции нельзя вызывать из non-suspend контекста
     * РЕШЕНИЕ: Дублируем логику сохранения
     */
    fun saveAllSettings() {
        viewModelScope.launch {
            _isSaving.value = true

            try {
                // GitHub
                secureSettings.setGitHubToken(_githubTokenInput.value)
                secureSettings.setGitHubConfig(
                    owner = _githubOwnerInput.value,
                    repo = _githubRepoInput.value,
                    branch = _githubBranchInput.value
                )
                
                // Anthropic
                secureSettings.setAnthropicApiKey(_anthropicKeyInput.value, false)
                appSettings.setClaudeModel(_claudeModelInput.value)
                
                // Cache
                appSettings.setCacheSettings(
                    timeoutMinutes = _cacheTimeoutInput.value,
                    maxFiles = _maxCacheFilesInput.value,
                    autoClear = _autoClearCacheInput.value
                )
                
                _message.value = "✅ All settings saved successfully"
            } catch (e: Exception) {
                _message.value = "❌ Failed to save: ${e.message}"
                android.util.Log.e("SettingsViewModel", "Save all failed", e)
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

    // ═════════════════════════════════════════════════════════════════════════
    // BIOMETRIC
    // ═════════════════════════════════════════════════════════════════════════

    fun requestBiometricAuth() {
        _biometricAuthRequest.value = true
    }

    fun clearBiometricRequest() {
        _biometricAuthRequest.value = false
    }

    // ═════════════════════════════════════════════════════════════════════════
    // UTILITIES
    // ═════════════════════════════════════════════════════════════════════════

    fun resetToDefaults() {
        _cacheTimeoutInput.value = 5
        _maxCacheFilesInput.value = 20
        _autoClearCacheInput.value = true
        _claudeModelInput.value = "claude-opus-4-5-20251101"
        _message.value = "⚠️ Settings reset to defaults (not saved)"
    }

    fun clearMessage() {
        _message.value = null
    }
}