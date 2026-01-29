package com.opuside.app.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "opuside_settings")

/**
 * Централизованное хранилище настроек приложения.
 * 
 * Сохраняет:
 * - API ключи (опционально, если не в BuildConfig)
 * - Репозиторий по умолчанию
 * - Настройки кеша
 * - Выбранная ветка
 */
@Singleton
class AppSettings @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.dataStore

    // ═══════════════════════════════════════════════════════════════════════════
    // KEYS
    // ═══════════════════════════════════════════════════════════════════════════

    private object Keys {
        // GitHub
        val GITHUB_OWNER = stringPreferencesKey("github_owner")
        val GITHUB_REPO = stringPreferencesKey("github_repo")
        val GITHUB_BRANCH = stringPreferencesKey("github_branch")
        val GITHUB_TOKEN = stringPreferencesKey("github_token")
        
        // Anthropic
        val ANTHROPIC_API_KEY = stringPreferencesKey("anthropic_api_key")
        val CLAUDE_MODEL = stringPreferencesKey("claude_model")
        
        // Cache
        val CACHE_TIMEOUT_MINUTES = intPreferencesKey("cache_timeout_minutes")
        val MAX_CACHE_FILES = intPreferencesKey("max_cache_files")
        val AUTO_CLEAR_CACHE = booleanPreferencesKey("auto_clear_cache")
        
        // UI
        val DARK_THEME = booleanPreferencesKey("dark_theme")
        val EDITOR_FONT_SIZE = intPreferencesKey("editor_font_size")
        val SHOW_LINE_NUMBERS = booleanPreferencesKey("show_line_numbers")
        
        // Last used
        val LAST_OPENED_PATH = stringPreferencesKey("last_opened_path")
        val LAST_SESSION_ID = stringPreferencesKey("last_session_id")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GITHUB SETTINGS
    // ═══════════════════════════════════════════════════════════════════════════

    val githubOwner: Flow<String> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[Keys.GITHUB_OWNER] ?: "" }

    val githubRepo: Flow<String> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[Keys.GITHUB_REPO] ?: "" }

    val githubBranch: Flow<String> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[Keys.GITHUB_BRANCH] ?: "main" }

    val githubToken: Flow<String> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[Keys.GITHUB_TOKEN] ?: "" }

    suspend fun setGitHubConfig(owner: String, repo: String, branch: String = "main") {
        dataStore.edit { prefs ->
            prefs[Keys.GITHUB_OWNER] = owner
            prefs[Keys.GITHUB_REPO] = repo
            prefs[Keys.GITHUB_BRANCH] = branch
        }
    }

    suspend fun setGitHubToken(token: String) {
        dataStore.edit { it[Keys.GITHUB_TOKEN] = token }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ANTHROPIC SETTINGS
    // ═══════════════════════════════════════════════════════════════════════════

    val anthropicApiKey: Flow<String> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[Keys.ANTHROPIC_API_KEY] ?: "" }

    val claudeModel: Flow<String> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[Keys.CLAUDE_MODEL] ?: "claude-opus-4-5-20251101" }

    suspend fun setAnthropicApiKey(key: String) {
        dataStore.edit { it[Keys.ANTHROPIC_API_KEY] = key }
    }

    suspend fun setClaudeModel(model: String) {
        dataStore.edit { it[Keys.CLAUDE_MODEL] = model }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CACHE SETTINGS
    // ═══════════════════════════════════════════════════════════════════════════

    val cacheTimeoutMinutes: Flow<Int> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[Keys.CACHE_TIMEOUT_MINUTES] ?: 5 }

    val maxCacheFiles: Flow<Int> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[Keys.MAX_CACHE_FILES] ?: 20 }

    val autoClearCache: Flow<Boolean> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[Keys.AUTO_CLEAR_CACHE] ?: true }

    suspend fun setCacheSettings(timeoutMinutes: Int, maxFiles: Int, autoClear: Boolean) {
        dataStore.edit { prefs ->
            prefs[Keys.CACHE_TIMEOUT_MINUTES] = timeoutMinutes
            prefs[Keys.MAX_CACHE_FILES] = maxFiles
            prefs[Keys.AUTO_CLEAR_CACHE] = autoClear
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // UI SETTINGS
    // ═══════════════════════════════════════════════════════════════════════════

    val darkTheme: Flow<Boolean?> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[Keys.DARK_THEME] }

    val editorFontSize: Flow<Int> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[Keys.EDITOR_FONT_SIZE] ?: 14 }

    val showLineNumbers: Flow<Boolean> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[Keys.SHOW_LINE_NUMBERS] ?: true }

    suspend fun setDarkTheme(enabled: Boolean?) {
        dataStore.edit { prefs ->
            if (enabled != null) {
                prefs[Keys.DARK_THEME] = enabled
            } else {
                prefs.remove(Keys.DARK_THEME)
            }
        }
    }

    suspend fun setEditorFontSize(size: Int) {
        dataStore.edit { it[Keys.EDITOR_FONT_SIZE] = size.coerceIn(10, 24) }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // STATE PERSISTENCE
    // ═══════════════════════════════════════════════════════════════════════════

    val lastOpenedPath: Flow<String> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[Keys.LAST_OPENED_PATH] ?: "" }

    suspend fun setLastOpenedPath(path: String) {
        dataStore.edit { it[Keys.LAST_OPENED_PATH] = path }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // COMBINED FLOWS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Полная конфигурация GitHub.
     */
    data class GitHubConfig(
        val owner: String,
        val repo: String,
        val branch: String,
        val token: String
    ) {
        val isConfigured: Boolean get() = owner.isNotBlank() && repo.isNotBlank() && token.isNotBlank()
        val fullName: String get() = "$owner/$repo"
    }

    val gitHubConfig: Flow<GitHubConfig> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs ->
            GitHubConfig(
                owner = prefs[Keys.GITHUB_OWNER] ?: "",
                repo = prefs[Keys.GITHUB_REPO] ?: "",
                branch = prefs[Keys.GITHUB_BRANCH] ?: "main",
                token = prefs[Keys.GITHUB_TOKEN] ?: ""
            )
        }

    /**
     * Полная конфигурация кеша.
     */
    data class CacheConfig(
        val timeoutMinutes: Int,
        val maxFiles: Int,
        val autoClear: Boolean
    ) {
        val timeoutMs: Long get() = timeoutMinutes * 60 * 1000L
    }

    val cacheConfig: Flow<CacheConfig> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs ->
            CacheConfig(
                timeoutMinutes = prefs[Keys.CACHE_TIMEOUT_MINUTES] ?: 5,
                maxFiles = prefs[Keys.MAX_CACHE_FILES] ?: 20,
                autoClear = prefs[Keys.AUTO_CLEAR_CACHE] ?: true
            )
        }

    // ═══════════════════════════════════════════════════════════════════════════
    // RESET
    // ═══════════════════════════════════════════════════════════════════════════

    suspend fun clearAll() {
        dataStore.edit { it.clear() }
    }

    suspend fun clearGitHubConfig() {
        dataStore.edit { prefs ->
            prefs.remove(Keys.GITHUB_OWNER)
            prefs.remove(Keys.GITHUB_REPO)
            prefs.remove(Keys.GITHUB_BRANCH)
            prefs.remove(Keys.GITHUB_TOKEN)
        }
    }
}
