package com.opuside.app.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.opuside.app.core.security.SecureSettingsDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "opuside_settings")

// ✅ ИСПРАВЛЕНО: typealias на уровне файла, а не внутри класса
typealias GitHubConfig = SecureSettingsDataStore.GitHubConfig

/**
 * ✅ КРИТИЧЕСКИ ИСПРАВЛЕНО (Проблема #11 - Network Spam on Tab Switch)
 * 
 * ПРОБЛЕМА:
 * ─────────
 * При переходе на вкладку Creator происходит спам сетевых запросов из-за того,
 * что каждое изменение в gitHubConfig Flow триггерит новую загрузку файлов.
 * 
 * РЕШЕНИЕ:
 * ────────
 * gitHubConfig теперь возвращает ХОЛОДНЫЙ Flow из secureSettings.
 * CreatorViewModel использует debounce + distinctUntilChanged для фильтрации.
 * 
 * ВАЖНО:
 * ──────
 * Этот класс НЕ изменен, потому что проблема была в CreatorViewModel.
 * Но добавлены комментарии для ясности.
 */
@Singleton
class AppSettings @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secureSettings: SecureSettingsDataStore
) {
    private val dataStore = context.dataStore

    private object Keys {
        val CACHE_TIMEOUT_MINUTES = intPreferencesKey("cache_timeout_minutes")
        val MAX_CACHE_FILES = intPreferencesKey("max_cache_files")
        val AUTO_CLEAR_CACHE = booleanPreferencesKey("auto_clear_cache")
        val DARK_THEME = booleanPreferencesKey("dark_theme")
        val EDITOR_FONT_SIZE = intPreferencesKey("editor_font_size")
        val SHOW_LINE_NUMBERS = booleanPreferencesKey("show_line_numbers")
        val LAST_OPENED_PATH = stringPreferencesKey("last_opened_path")
        val LAST_SESSION_ID = stringPreferencesKey("last_session_id")
        val CLAUDE_MODEL = stringPreferencesKey("claude_model")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // API KEYS (Delegated to SecureSettingsDataStore)
    // ═══════════════════════════════════════════════════════════════════════════
    
    val anthropicApiKey: Flow<String> = secureSettings.getAnthropicApiKey()
    
    suspend fun setAnthropicApiKey(key: String, useBiometric: Boolean = false) {
        secureSettings.setAnthropicApiKey(key, useBiometric)
    }

    val gitHubToken: Flow<String> = secureSettings.getGitHubToken()
    
    suspend fun setGitHubToken(token: String, useBiometric: Boolean = false) {
        secureSettings.setGitHubToken(token, useBiometric)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GITHUB CONFIG
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * ✅ ВАЖНО: Это ХОЛОДНЫЙ Flow
     * 
     * Каждый вызов .collect {} создает новую подписку к DataStore.
     * CreatorViewModel ДОЛЖЕН использовать:
     * - debounce(500) для фильтрации быстрых изменений
     * - distinctUntilChanged() для игнорирования дубликатов
     * - collectLatest {} для отмены предыдущих запросов
     */
    val gitHubConfig: Flow<GitHubConfig> = secureSettings.gitHubConfig

    suspend fun setGitHubConfig(owner: String, repo: String, branch: String = "main") {
        secureSettings.setGitHubConfig(owner, repo, branch)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CLAUDE MODEL
    // ═══════════════════════════════════════════════════════════════════════════

    val claudeModel: Flow<String> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[Keys.CLAUDE_MODEL] ?: "claude-opus-4-5-20251101" }

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
    // UI SETTINGS
    // ═══════════════════════════════════════════════════════════════════════════

    val darkTheme: Flow<Boolean?> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[Keys.DARK_THEME] }

    suspend fun setDarkTheme(enabled: Boolean?) {
        dataStore.edit { prefs ->
            if (enabled != null) {
                prefs[Keys.DARK_THEME] = enabled
            } else {
                prefs.remove(Keys.DARK_THEME)
            }
        }
    }

    val editorFontSize: Flow<Int> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[Keys.EDITOR_FONT_SIZE] ?: 14 }

    suspend fun setEditorFontSize(size: Int) {
        dataStore.edit { it[Keys.EDITOR_FONT_SIZE] = size.coerceIn(10, 24) }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // RESET & SECURITY
    // ═══════════════════════════════════════════════════════════════════════════

    suspend fun clearAll() {
        dataStore.edit { it.clear() }
        secureSettings.clearSecureData()
    }

    suspend fun clearGitHubConfig() {
        // Очищаем только незашифрованные данные
        secureSettings.setGitHubConfig("", "", "main")
    }
    
    suspend fun verifySecurityIntegrity(): Boolean {
        return secureSettings.verifyDataIntegrity()
    }
}