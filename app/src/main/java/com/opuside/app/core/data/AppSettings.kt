package com.opuside.app.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.opuside.app.core.security.SecureSettingsDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "opuside_settings")

typealias GitHubConfig = SecureSettingsDataStore.GitHubConfig

/**
 * ⚙️ APP SETTINGS v3.0
 *
 * Хранилище общих настроек приложения:
 * - Модели строго: gemini-3.5-flash-lite (по умолчанию) и gemini-3.1-flash-lite
 * - Сквозная синхронизация API-ключей с SecureSettingsDataStore
 * - Сохранение состояния галочек бэкапа и TXT-отчета Pipeline
 */
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
        val GEMINI_API_KEY = stringPreferencesKey("gemini_api_key")

        // ── Опции Pipeline (Бэкап и TXT-отчет) ────────────────────────
        val PIPELINE_DETAILED_REPORT = booleanPreferencesKey("pipeline_detailed_report")
        val PIPELINE_BACKUP_ENABLED = booleanPreferencesKey("pipeline_backup_enabled")
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GITHUB CONFIG & TOKENS (Делегировано в SecureSettingsDataStore)
    // ═══════════════════════════════════════════════════════════════════════════

    val gitHubToken: Flow<String> = secureSettings.getGitHubToken()

    suspend fun setGitHubToken(token: String, useBiometric: Boolean = false) {
        secureSettings.setGitHubToken(token, useBiometric)
    }

    val gitHubConfig: Flow<GitHubConfig> = secureSettings.gitHubConfig

    suspend fun setGitHubConfig(owner: String, repo: String, branch: String = "main") {
        secureSettings.setGitHubConfig(owner, repo, branch)
    }

    val anthropicApiKey: Flow<String> = secureSettings.getAnthropicApiKey()

    suspend fun setAnthropicApiKey(key: String, useBiometric: Boolean = false) {
        secureSettings.setAnthropicApiKey(key, useBiometric)
    }

    val claudeModel: Flow<String> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[Keys.CLAUDE_MODEL] ?: "claude-opus-4-6" }

    suspend fun setClaudeModel(model: String) {
        dataStore.edit { it[Keys.CLAUDE_MODEL] = model }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GEMINI SETTINGS (Синхронизировано)
    // ═══════════════════════════════════════════════════════════════════════════

    val geminiApiKey: Flow<String> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs ->
            val stored = prefs[Keys.GEMINI_API_KEY] ?: ""
            if (stored.isNotBlank()) stored else secureSettings.getActiveGeminiApiKey().first()
        }

    suspend fun setGeminiApiKey(key: String) {
        dataStore.edit { it[Keys.GEMINI_API_KEY] = key }
        secureSettings.setGeminiApiKey(key)
    }

    // Модель по умолчанию строго gemini-3.5-flash-lite с авто-миграцией старых версий
    val geminiModel: Flow<String> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs ->
            val raw = prefs[Keys.GEMINI_MODEL]?.trim()?.lowercase() ?: ""
            when {
                raw.contains("3.1") -> "gemini-3.1-flash-lite"
                else -> "gemini-3.5-flash-lite"
            }
        }

    suspend fun setGeminiModel(model: String) {
        val clean = model.trim().lowercase()
        val validModel = if (clean.contains("3.1")) "gemini-3.1-flash-lite" else "gemini-3.5-flash-lite"
        dataStore.edit { it[Keys.GEMINI_MODEL] = validModel }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // ОПЦИИ ПАЙПЛАЙНА (СОХРАНЕНИЕ МЕЖДУ СЕССИЯМИ)
    // ═══════════════════════════════════════════════════════════════════════════

    val pipelineDetailedReport: Flow<Boolean> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[Keys.PIPELINE_DETAILED_REPORT] ?: false }

    suspend fun setPipelineDetailedReport(enabled: Boolean) {
        dataStore.edit { it[Keys.PIPELINE_DETAILED_REPORT] = enabled }
    }

    val pipelineBackupEnabled: Flow<Boolean> = dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { it[Keys.PIPELINE_BACKUP_ENABLED] ?: true }

    suspend fun setPipelineBackupEnabled(enabled: Boolean) {
        dataStore.edit { it[Keys.PIPELINE_BACKUP_ENABLED] = enabled }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // UI НАСТРОЙКИ
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
    // СБРОС И ОЧИСТКА
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