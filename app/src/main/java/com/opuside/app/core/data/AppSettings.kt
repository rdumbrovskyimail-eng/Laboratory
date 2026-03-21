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

typealias GitHubConfig = SecureSettingsDataStore.GitHubConfig

@Singleton
class AppSettings @Inject constructor(
    @ApplicationContext private val context: Context,
    private val secureSettings: SecureSettingsDataStore
) {
    private val dataStore = context.dataStore

    private object Keys {
        val DARK_THEME = booleanPreferencesKey("dark_theme")
        val EDITOR_FONT_SIZE = intPreferencesKey("editor_font_size")
        val SHOW_LINE_NUMBERS = booleanPreferencesKey("show_line_numbers")
        val LAST_OPENED_PATH = stringPreferencesKey("last_opened_path")
        val LAST_SESSION_ID = stringPreferencesKey("last_session_id")
        val CLAUDE_MODEL = stringPreferencesKey("claude_model")
        val GEMINI_MODEL = stringPreferencesKey("gemini_model")
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

    val gitHubConfig: Flow<GitHubConfig> = secureSettings.gitHubConfig

    suspend fun setGitHubConfig(owner: String, repo: String, branch: String = "main") {
        secureSettings.setGitHubConfig(owner, repo, branch)
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CLAUDE MODEL
    // ═══════════════════════════════════════════════════════════════════════════

    val claudeModel: Flow<String> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[Keys.CLAUDE_MODEL] ?: "claude-opus-4-6" }

    suspend fun setClaudeModel(model: String) {
        dataStore.edit { it[Keys.CLAUDE_MODEL] = model }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GEMINI MODEL
    // ═══════════════════════════════════════════════════════════════════════════

    val geminiModel: Flow<String> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[Keys.GEMINI_MODEL] ?: "gemini-flash-latest" }

    suspend fun setGeminiModel(model: String) {
        dataStore.edit { it[Keys.GEMINI_MODEL] = model }
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
        secureSettings.setGitHubConfig("", "", "main")
    }

    suspend fun verifySecurityIntegrity(): Boolean {
        return secureSettings.verifyDataIntegrity()
    }
}