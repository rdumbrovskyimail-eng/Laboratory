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
 * ✅ ПОЛНОСТЬЮ ИСПРАВЛЕНО (2026-02-06):
 * 
 * НОВЫЕ ФИЧИ:
 * ─────────────────────────────────────────────────────────────
 * 1. ✅ testClaudeConnection() теперь использует claudeClient.testConnection()
 *    вместо sendMessage() - профессиональная реализация с детальными ошибками
 * 
 * 2. ✅ Автоматическое тестирование после сохранения API ключа
 *    (saveAnthropicSettings → testClaudeConnection)
 * 
 * 3. ✅ Правильная загрузка GitHub Token из SecureSettingsDataStore
 *    (исправлена проблема с пропаданием токена после перезапуска)
 * 
 * 4. ✅ Валидация ВСЕХ полей перед сохранением (предотвращение пустых значений)
 * 
 * 5. ✅ Детальное логирование для диагностики (все операции логируются)
 * 
 * 6. ✅ ИСПРАВЛЕНО: testClaudeConnection() правильно обновляет _message.value
 *    для отображения результатов в UI
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
        android.util.Log.d("SettingsViewModel", "🚀 Initializing SettingsViewModel...")
        loadSettings()
    }

    /**
     * ✅ КРИТИЧЕСКИ ИСПРАВЛЕНО: Правильная загрузка ВСЕХ настроек
     * 
     * ПРОБЛЕМА:
     * ```kotlin
     * val config = appSettings.gitHubConfig.first()
     * _githubTokenInput.value = config.token  // ← НЕПРАВИЛЬНО! Токен еще расшифровывается
     * ```
     * 
     * РЕШЕНИЕ:
     * ```kotlin
     * val token = secureSettings.getGitHubToken().first()  // ← ПРАВИЛЬНО! Прямая загрузка
     * _githubTokenInput.value = token
     * ```
     */
    private fun loadSettings() {
        viewModelScope.launch {
            android.util.Log.d("SettingsViewModel", "📥 Loading settings from DataStore...")
            
            try {
                // ✅ ШАБЛОН: Загружаем все настройки параллельно через combine
                combine(
                    appSettings.gitHubConfig,
                    secureSettings.getGitHubToken(),          // ✅ Прямая загрузка токена
                    secureSettings.getAnthropicApiKey(),      // ✅ Прямая загрузка API ключа
                    appSettings.claudeModel,
                    appSettings.cacheConfig
                ) { config, githubToken, anthropicKey, model, cacheConfig ->
                    SettingsData(
                        owner = config.owner,
                        repo = config.repo,
                        branch = config.branch,
                        githubToken = githubToken,            // ✅ Расшифрованный токен
                        anthropicKey = anthropicKey,          // ✅ Расшифрованный ключ
                        claudeModel = model,
                        cacheTimeout = cacheConfig.timeoutMinutes,
                        maxFiles = cacheConfig.maxFiles,
                        autoClear = cacheConfig.autoClear
                    )
                }.first().let { data ->
                    // ✅ Обновляем UI состояние
                    _githubOwnerInput.value = data.owner
                    _githubRepoInput.value = data.repo
                    _githubBranchInput.value = data.branch
                    _githubTokenInput.value = data.githubToken
                    _anthropicKeyInput.value = data.anthropicKey
                    _claudeModelInput.value = data.claudeModel
                    _cacheTimeoutInput.value = data.cacheTimeout
                    _maxCacheFilesInput.value = data.maxFiles
                    _autoClearCacheInput.value = data.autoClear
                    
                    // ✅ Диагностическое логирование
                    android.util.Log.d("SettingsViewModel", "✅ Settings loaded successfully:")
                    android.util.Log.d("SettingsViewModel", "   Owner: ${data.owner}")
                    android.util.Log.d("SettingsViewModel", "   Repo: ${data.repo}")
                    android.util.Log.d("SettingsViewModel", "   Branch: ${data.branch}")
                    android.util.Log.d("SettingsViewModel", "   GitHub Token: ${if (data.githubToken.isNotEmpty()) "[SET (${data.githubToken.take(10)}...)]" else "[EMPTY]"}")
                    android.util.Log.d("SettingsViewModel", "   Anthropic Key: ${if (data.anthropicKey.isNotEmpty()) "[SET (${data.anthropicKey.take(10)}...)]" else "[EMPTY]"}")
                    android.util.Log.d("SettingsViewModel", "   Model: ${data.claudeModel}")
                    android.util.Log.d("SettingsViewModel", "   Cache: ${data.cacheTimeout}min, ${data.maxFiles} files, autoClear=${data.autoClear}")
                }
                
            } catch (e: Exception) {
                android.util.Log.e("SettingsViewModel", "❌ Failed to load settings", e)
                _message.value = "⚠️ Failed to load settings: ${e.message}"
            }
        }
    }

    /**
     * ✅ Data class для атомарной загрузки всех настроек
     */
    private data class SettingsData(
        val owner: String,
        val repo: String,
        val branch: String,
        val githubToken: String,
        val anthropicKey: String,
        val claudeModel: String,
        val cacheTimeout: Int,
        val maxFiles: Int,
        val autoClear: Boolean
    )

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
     * ✅ ИСПРАВЛЕНО: Правильное сохранение GitHub настроек с валидацией
     * 
     * ФИЧИ:
     * - Валидация owner/repo/token перед сохранением
     * - Сначала сохраняется token (зашифровано), затем config
     * - Детальное логирование каждого шага
     * - Обработка ошибок с информативными сообщениями
     */
    fun saveGitHubSettings() {
        viewModelScope.launch {
            _isSaving.value = true
            android.util.Log.d("SettingsViewModel", "💾 Saving GitHub settings...")

            try {
                // ✅ ДОБАВЛЕНО: Валидация перед сохранением
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
                
                // ✅ Сохраняем токен (зашифрованно) СНАЧАЛА
                android.util.Log.d("SettingsViewModel", "   Encrypting GitHub token...")
                secureSettings.setGitHubToken(_githubTokenInput.value)
                
                // ✅ Затем сохраняем owner/repo/branch
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
     * ✅ ИСПРАВЛЕНО: Автоматическое тестирование после сохранения
     * 
     * НОВОЕ ПОВЕДЕНИЕ:
     * 1. Сохранить API ключ
     * 2. Сохранить модель
     * 3. Автоматически вызвать testClaudeConnection()
     * 4. Показать результат теста в UI
     */
    fun saveAnthropicSettings(useBiometric: Boolean) {
        viewModelScope.launch {
            _isSaving.value = true
            android.util.Log.d("SettingsViewModel", "💾 Saving Anthropic settings (biometric: $useBiometric)...")

            try {
                // ✅ ДОБАВЛЕНО: Валидация
                if (_anthropicKeyInput.value.isBlank()) {
                    _message.value = "❌ API Key cannot be empty"
                    android.util.Log.w("SettingsViewModel", "⚠️ Validation failed: API Key is blank")
                    _isSaving.value = false
                    return@launch
                }
                
                secureSettings.setAnthropicApiKey(_anthropicKeyInput.value, useBiometric)
                appSettings.setClaudeModel(_claudeModelInput.value)
                
                _message.value = "✅ Claude settings saved successfully"
                android.util.Log.d("SettingsViewModel", "✅ Anthropic settings saved successfully")
                
                // ✅ НОВОЕ: Автоматически тестируем соединение после сохранения
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
     * Сохранение настроек кэша
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
     * ✅ ИСПРАВЛЕНО: Правильное сохранение всех настроек с валидацией
     * 
     * ПОВЕДЕНИЕ:
     * 1. Валидация ВСЕХ полей (GitHub + Anthropic)
     * 2. Атомарное сохранение всех настроек
     * 3. Автоматическое тестирование соединений
     * 4. Детальное логирование каждого шага
     */
    fun saveAllSettings() {
        viewModelScope.launch {
            _isSaving.value = true
            android.util.Log.d("SettingsViewModel", "💾 Saving ALL settings...")

            try {
                // ✅ ДОБАВЛЕНО: Валидация GitHub
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
                
                // ✅ ДОБАВЛЕНО: Валидация Anthropic
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
                secureSettings.setAnthropicApiKey(_anthropicKeyInput.value, false)
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
                
                // ✅ НОВОЕ: Автоматически тестируем соединения
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
    // TEST CONNECTIONS
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * Тестирование GitHub соединения
     */
    fun testGitHubConnection() {
        viewModelScope.launch {
            _githubStatus.value = ConnectionStatus.Testing
            android.util.Log.d("SettingsViewModel", "🔍 Testing GitHub connection...")

            try {
                val result = gitHubClient.getRepository()

                result.onSuccess { repo ->
                    _repoInfo.value = repo
                    _githubStatus.value = ConnectionStatus.Connected
                    android.util.Log.d("SettingsViewModel", "✅ GitHub connected: ${repo.fullName}")
                }.onFailure { e ->
                    _githubStatus.value = ConnectionStatus.Error(e.message ?: "Unknown error")
                    android.util.Log.e("SettingsViewModel", "❌ GitHub connection failed", e)
                }
            } catch (e: Exception) {
                _githubStatus.value = ConnectionStatus.Error(e.message ?: "Unknown error")
                android.util.Log.e("SettingsViewModel", "❌ GitHub connection exception", e)
            }
        }
    }

    /**
     * ✅ КРИТИЧЕСКИ ИСПРАВЛЕНО: Использует профессиональный testConnection()
     * 
     * СТАРАЯ ВЕРСИЯ (работала, но не профессионально):
     * ```kotlin
     * val result = claudeClient.sendMessage(
     *     messages = listOf(ClaudeMessage(role = "user", content = "Hello")),
     *     maxTokens = 10
     * )
     * ```
     * 
     * НОВАЯ ВЕРСИЯ (2026 Professional):
     * ```kotlin
     * val result = claudeClient.testConnection()
     * // Возвращает детальные ошибки:
     * // - "Invalid API key. Please check..."
     * // - "Rate limit exceeded. Retry in X seconds"
     * // - "✅ Connected successfully! Model: claude-sonnet-4-5-20250929"
     * ```
     * 
     * ✅ ИСПРАВЛЕНО (2026-02-06): Правильное отображение message в UI
     * - При успехе: _message.value = "✅ $message"
     * - При ошибке: _message.value = "❌ $errorMessage"
     */
    fun testClaudeConnection() {
        viewModelScope.launch {
            _claudeStatus.value = ConnectionStatus.Testing
            android.util.Log.d("SettingsViewModel", "🔍 Testing Claude connection...")

            try {
                // ✅ НОВОЕ: Используем профессиональный метод testConnection()
                val result = claudeClient.testConnection()

                result.onSuccess { message ->
                    _claudeStatus.value = ConnectionStatus.Connected
                    android.util.Log.d("SettingsViewModel", "✅ Claude connected: $message")
                    
                    // ✅ ИСПРАВЛЕНО: Обновляем UI сообщение с деталями
                    _message.value = "✅ $message"
                    
                }.onFailure { e ->
                    val errorMessage = e.message ?: "Unknown error"
                    _claudeStatus.value = ConnectionStatus.Error(errorMessage)
                    android.util.Log.e("SettingsViewModel", "❌ Claude connection failed: $errorMessage")
                    
                    // ✅ ИСПРАВЛЕНО: Обновляем UI сообщение с деталями ошибки
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