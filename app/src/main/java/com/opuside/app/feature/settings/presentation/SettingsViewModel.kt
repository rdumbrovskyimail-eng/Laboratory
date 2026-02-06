package com.opuside.app.feature.settings.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.opuside.app.BuildConfig
import com.opuside.app.core.data.AppSettings
import com.opuside.app.core.network.anthropic.ClaudeApiClient
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
 * ✅ КРИТИЧЕСКИ ИСПРАВЛЕНО (2026-02-06):
 * 
 * ИСПРАВЛЕНИЯ:
 * ────────────────────────────────────────────────────────────
 * 1. ✅ Anthropic API ключ теперь правильно загружается из SecureSettingsDataStore
 * 2. ✅ Биометрия восстанавливается из DataStore при старте
 * 3. ✅ Test кнопки теперь сохраняют настройки перед тестированием
 * 4. ✅ Добавлено логирование для диагностики проблем с ключами
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

    // ✅ ДОБАВЛЕНО: State для биометрии
    private val _useBiometricInput = MutableStateFlow(false)
    val useBiometricInput: StateFlow<Boolean> = _useBiometricInput.asStateFlow()

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
        android.util.Log.d("SettingsViewModel", "🚀 Initializing SettingsViewModel...")
        loadSettings()
    }

    /**
     * ✅ КРИТИЧЕСКИ ИСПРАВЛЕНО: Правильная загрузка ВСЕХ настроек включая биометрию
     * 
     * ИЗМЕНЕНИЯ:
     * ─────────────────────────────────────────────────────────────
     * 1. ✅ Anthropic ключ загружается через try-catch с fallback
     * 2. ✅ Биометрия загружается из DataStore
     * 3. ✅ Детальное логирование для диагностики
     * 4. ✅ Обработка ошибок расшифровки ключей
     */
    private fun loadSettings() {
        viewModelScope.launch {
            android.util.Log.d("SettingsViewModel", "📥 Loading settings from DataStore...")
            
            try {
                // ✅ Загружаем GitHub настройки
                val githubConfig = appSettings.gitHubConfig.first()
                val githubToken = try {
                    secureSettings.getGitHubToken().first()
                } catch (e: Exception) {
                    android.util.Log.e("SettingsViewModel", "❌ Failed to load GitHub token", e)
                    ""
                }

                // ✅ ИСПРАВЛЕНО: Загружаем Anthropic ключ с обработкой ошибок
                val anthropicKey = try {
                    val key = secureSettings.getAnthropicApiKey().first()
                    android.util.Log.d("SettingsViewModel", "✅ Anthropic key loaded: ${if (key.isNotEmpty()) "[${key.take(10)}...]" else "[EMPTY]"}")
                    key
                } catch (e: Exception) {
                    android.util.Log.e("SettingsViewModel", "❌ Failed to load Anthropic key", e)
                    ""
                }

                // ✅ ДОБАВЛЕНО: Загружаем статус биометрии
                val biometricEnabled = try {
                    val prefs = secureSettings.dataStore.data.first()
                    prefs[androidx.datastore.preferences.core.booleanPreferencesKey("biometric_enabled")] ?: false
                } catch (e: Exception) {
                    android.util.Log.e("SettingsViewModel", "❌ Failed to load biometric status", e)
                    false
                }

                // ✅ Загружаем остальные настройки
                val claudeModel = appSettings.claudeModel.first()
                val cacheConfig = appSettings.cacheConfig.first()

                // ✅ Обновляем UI состояние
                _githubOwnerInput.value = githubConfig.owner
                _githubRepoInput.value = githubConfig.repo
                _githubBranchInput.value = githubConfig.branch
                _githubTokenInput.value = githubToken
                _anthropicKeyInput.value = anthropicKey
                _claudeModelInput.value = claudeModel
                _useBiometricInput.value = biometricEnabled
                _cacheTimeoutInput.value = cacheConfig.timeoutMinutes
                _maxCacheFilesInput.value = cacheConfig.maxFiles
                _autoClearCacheInput.value = cacheConfig.autoClear
                
                // ✅ Диагностическое логирование
                android.util.Log.d("SettingsViewModel", "✅ Settings loaded successfully:")
                android.util.Log.d("SettingsViewModel", "   GitHub Owner: ${githubConfig.owner}")
                android.util.Log.d("SettingsViewModel", "   GitHub Repo: ${githubConfig.repo}")
                android.util.Log.d("SettingsViewModel", "   GitHub Branch: ${githubConfig.branch}")
                android.util.Log.d("SettingsViewModel", "   GitHub Token: ${if (githubToken.isNotEmpty()) "[SET (${githubToken.take(10)}...)]" else "[EMPTY]"}")
                android.util.Log.d("SettingsViewModel", "   Anthropic Key: ${if (anthropicKey.isNotEmpty()) "[SET (${anthropicKey.take(10)}...)]" else "[EMPTY]"}")
                android.util.Log.d("SettingsViewModel", "   Biometric: $biometricEnabled")
                android.util.Log.d("SettingsViewModel", "   Model: $claudeModel")
                android.util.Log.d("SettingsViewModel", "   Cache: ${cacheConfig.timeoutMinutes}min, ${cacheConfig.maxFiles} files, autoClear=${cacheConfig.autoClear}")
                
            } catch (e: Exception) {
                android.util.Log.e("SettingsViewModel", "❌ Failed to load settings", e)
                _message.value = "⚠️ Failed to load settings: ${e.message}"
            }
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

    // ✅ ДОБАВЛЕНО: Функция для изменения биометрии
    fun updateUseBiometric(enabled: Boolean) {
        _useBiometricInput.value = enabled
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
     * ✅ БЕЗ ИЗМЕНЕНИЙ: Сохранение GitHub настроек
     */
    fun saveGitHubSettings() {
        viewModelScope.launch {
            _isSaving.value = true
            android.util.Log.d("SettingsViewModel", "💾 Saving GitHub settings...")

            try {
                if (_githubOwnerInput.value.isBlank()) {
                    _message.value = "❌ Owner cannot be empty"
                    android.util.Log.w("SettingsViewModel", "⚠️ Validation failed: Owner is blank")
                    _isSaving.value = false
                    return@launch
                }
                if (_githubRepoInput.value.isBlank()) {
                    _message.value = "❌ Repository cannot be empty"
                    android.util.Log.w("SettingsViewModel", "⚠️ Validation failed: Repo is blank")
                    _isSaving.value = false
                    return@launch
                }
                if (_githubTokenInput.value.isBlank()) {
                    _message.value = "❌ Token cannot be empty"
                    android.util.Log.w("SettingsViewModel", "⚠️ Validation failed: Token is blank")
                    _isSaving.value = false
                    return@launch
                }
                
                android.util.Log.d("SettingsViewModel", "   Encrypting GitHub token...")
                secureSettings.setGitHubToken(_githubTokenInput.value)
                
                android.util.Log.d("SettingsViewModel", "   Saving config: ${_githubOwnerInput.value}/${_githubRepoInput.value}@${_githubBranchInput.value}")
                secureSettings.setGitHubConfig(
                    owner = _githubOwnerInput.value,
                    repo = _githubRepoInput.value,
                    branch = _githubBranchInput.value
                )
                
                _message.value = "✅ GitHub settings saved successfully"
                android.util.Log.d("SettingsViewModel", "✅ GitHub settings saved successfully")
                
            } catch (e: Exception) {
                _message.value = "❌ Failed to save: ${e.message}"
                android.util.Log.e("SettingsViewModel", "❌ Save failed", e)
            } finally {
                _isSaving.value = false
            }
        }
    }

    /**
     * ✅ ИСПРАВЛЕНО: Сохранение Anthropic с биометрией и автотестом
     */
    fun saveAnthropicSettings(useBiometric: Boolean = _useBiometricInput.value) {
        viewModelScope.launch {
            _isSaving.value = true
            android.util.Log.d("SettingsViewModel", "💾 Saving Anthropic settings (biometric: $useBiometric)...")

            try {
                if (_anthropicKeyInput.value.isBlank()) {
                    _message.value = "❌ API Key cannot be empty"
                    android.util.Log.w("SettingsViewModel", "⚠️ Validation failed: API Key is blank")
                    _isSaving.value = false
                    return@launch
                }
                
                // ✅ Сохраняем ключ с биометрией
                secureSettings.setAnthropicApiKey(_anthropicKeyInput.value, useBiometric)
                appSettings.setClaudeModel(_claudeModelInput.value)
                
                // ✅ Обновляем локальный state биометрии
                _useBiometricInput.value = useBiometric
                
                _message.value = "✅ Claude settings saved successfully"
                android.util.Log.d("SettingsViewModel", "✅ Anthropic settings saved successfully (biometric: $useBiometric)")
                
                // ✅ Автоматически тестируем соединение после сохранения
                android.util.Log.d("SettingsViewModel", "🧪 Auto-testing Claude connection...")
                testClaudeConnection()
                
            } catch (e: Exception) {
                _message.value = "❌ Failed to save: ${e.message}"
                android.util.Log.e("SettingsViewModel", "❌ Save failed", e)
            } finally {
                _isSaving.value = false
            }
        }
    }

    /**
     * ✅ БЕЗ ИЗМЕНЕНИЙ: Сохранение настроек кэша
     */
    fun saveCacheSettings() {
        viewModelScope.launch {
            _isSaving.value = true
            android.util.Log.d("SettingsViewModel", "💾 Saving cache settings...")

            try {
                appSettings.setCacheSettings(
                    timeoutMinutes = _cacheTimeoutInput.value,
                    maxFiles = _maxCacheFilesInput.value,
                    autoClear = _autoClearCacheInput.value
                )
                _message.value = "✅ Cache settings saved successfully"
                android.util.Log.d("SettingsViewModel", "✅ Cache settings saved successfully")
            } catch (e: Exception) {
                _message.value = "❌ Failed to save: ${e.message}"
                android.util.Log.e("SettingsViewModel", "❌ Save failed", e)
            } finally {
                _isSaving.value = false
            }
        }
    }

    /**
     * ✅ ИСПРАВЛЕНО: Сохранение ВСЕХ настроек с биометрией
     */
    fun saveAllSettings() {
        viewModelScope.launch {
            _isSaving.value = true
            android.util.Log.d("SettingsViewModel", "💾 Saving ALL settings...")

            try {
                if (_githubOwnerInput.value.isBlank()) {
                    _message.value = "❌ GitHub Owner cannot be empty"
                    android.util.Log.w("SettingsViewModel", "⚠️ Validation failed: Owner is blank")
                    _isSaving.value = false
                    return@launch
                }
                if (_githubRepoInput.value.isBlank()) {
                    _message.value = "❌ GitHub Repository cannot be empty"
                    android.util.Log.w("SettingsViewModel", "⚠️ Validation failed: Repo is blank")
                    _isSaving.value = false
                    return@launch
                }
                if (_githubTokenInput.value.isBlank()) {
                    _message.value = "❌ GitHub Token cannot be empty"
                    android.util.Log.w("SettingsViewModel", "⚠️ Validation failed: Token is blank")
                    _isSaving.value = false
                    return@launch
                }
                
                if (_anthropicKeyInput.value.isBlank()) {
                    _message.value = "❌ Anthropic API Key cannot be empty"
                    android.util.Log.w("SettingsViewModel", "⚠️ Validation failed: API Key is blank")
                    _isSaving.value = false
                    return@launch
                }
                
                // GitHub
                android.util.Log.d("SettingsViewModel", "   Saving GitHub config...")
                secureSettings.setGitHubToken(_githubTokenInput.value)
                secureSettings.setGitHubConfig(
                    owner = _githubOwnerInput.value,
                    repo = _githubRepoInput.value,
                    branch = _githubBranchInput.value
                )
                
                // Anthropic
                android.util.Log.d("SettingsViewModel", "   Saving Anthropic config...")
                secureSettings.setAnthropicApiKey(_anthropicKeyInput.value, _useBiometricInput.value)
                appSettings.setClaudeModel(_claudeModelInput.value)
                
                // Cache
                android.util.Log.d("SettingsViewModel", "   Saving cache config...")
                appSettings.setCacheSettings(
                    timeoutMinutes = _cacheTimeoutInput.value,
                    maxFiles = _maxCacheFilesInput.value,
                    autoClear = _autoClearCacheInput.value
                )
                
                _message.value = "✅ All settings saved successfully"
                android.util.Log.d("SettingsViewModel", "✅ All settings saved successfully")
                
                // ✅ Автоматически тестируем соединения
                android.util.Log.d("SettingsViewModel", "🧪 Auto-testing connections...")
                testClaudeConnection()
                testGitHubConnection()
                
            } catch (e: Exception) {
                _message.value = "❌ Failed to save: ${e.message}"
                android.util.Log.e("SettingsViewModel", "❌ Save all failed", e)
            } finally {
                _isSaving.value = false
            }
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // TEST CONNECTIONS - ✅ ИСПРАВЛЕНО: Сохранение перед тестом
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * ✅ ИСПРАВЛЕНО: Test GitHub теперь сохраняет настройки перед тестом
     */
    fun testGitHubConnection() {
        viewModelScope.launch {
            // ✅ ДОБАВЛЕНО: Сохраняем настройки перед тестом
            android.util.Log.d("SettingsViewModel", "💾 Saving GitHub settings before test...")
            saveGitHubSettings()
            
            // ✅ Ждем завершения сохранения
            kotlinx.coroutines.delay(500)
            
            _githubStatus.value = ConnectionStatus.Testing
            android.util.Log.d("SettingsViewModel", "🔍 Testing GitHub connection...")

            try {
                val result = gitHubClient.getRepository()

                result.onSuccess { repo ->
                    _repoInfo.value = repo
                    _githubStatus.value = ConnectionStatus.Connected
                    android.util.Log.d("SettingsViewModel", "✅ GitHub connected: ${repo.fullName}")
                    _message.value = "✅ GitHub connected: ${repo.fullName}"
                }.onFailure { e ->
                    _githubStatus.value = ConnectionStatus.Error(e.message ?: "Unknown error")
                    android.util.Log.e("SettingsViewModel", "❌ GitHub connection failed", e)
                    _message.value = "❌ GitHub test failed: ${e.message}"
                }
            } catch (e: Exception) {
                _githubStatus.value = ConnectionStatus.Error(e.message ?: "Unknown error")
                android.util.Log.e("SettingsViewModel", "❌ GitHub connection exception", e)
                _message.value = "❌ GitHub test error: ${e.message}"
            }
        }
    }

    /**
     * ✅ ИСПРАВЛЕНО: Test Claude теперь сохраняет настройки перед тестом
     */
    fun testClaudeConnection() {
        viewModelScope.launch {
            // ✅ ДОБАВЛЕНО: Сохраняем настройки перед тестом
            android.util.Log.d("SettingsViewModel", "💾 Saving Anthropic settings before test...")
            saveAnthropicSettings(_useBiometricInput.value)
            
            // ✅ Ждем завершения сохранения
            kotlinx.coroutines.delay(500)
            
            _claudeStatus.value = ConnectionStatus.Testing
            android.util.Log.d("SettingsViewModel", "🔍 Testing Claude connection...")

            try {
                val result = claudeClient.testConnection()

                result.onSuccess { message ->
                    _claudeStatus.value = ConnectionStatus.Connected
                    android.util.Log.d("SettingsViewModel", "✅ Claude connected: $message")
                    _message.value = "✅ $message"
                    
                }.onFailure { e ->
                    val errorMessage = e.message ?: "Unknown error"
                    _claudeStatus.value = ConnectionStatus.Error(errorMessage)
                    android.util.Log.e("SettingsViewModel", "❌ Claude connection failed: $errorMessage")
                    _message.value = "❌ $errorMessage"
                }
                
            } catch (e: Exception) {
                val errorMessage = e.message ?: "Unknown error"
                _claudeStatus.value = ConnectionStatus.Error(errorMessage)
                android.util.Log.e("SettingsViewModel", "❌ Claude connection exception", e)
                _message.value = "❌ Connection error: $errorMessage"
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
        android.util.Log.d("SettingsViewModel", "♻️ Reset to defaults")
    }

    fun clearMessage() {
        _message.value = null
    }
}
