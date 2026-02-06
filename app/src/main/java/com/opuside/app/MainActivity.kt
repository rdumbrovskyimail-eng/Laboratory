package com.opuside.app

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.lifecycleScope
import com.opuside.app.core.data.AppSettings
import com.opuside.app.core.network.anthropic.ClaudeApiClient
import com.opuside.app.core.network.github.GitHubApiClient
import com.opuside.app.core.security.SecurityUtils
import com.opuside.app.core.ui.theme.OpusIDETheme
import com.opuside.app.core.util.CrashLogger
import com.opuside.app.navigation.OpusIDENavigation
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.system.exitProcess

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app_preferences")

/**
 * ✅ КРИТИЧЕСКИ ИСПРАВЛЕНО (2026-02-06):
 * 
 * ПРОБЛЕМЫ:
 * ────────────────────────────────────────────────────────────
 * 1. ❌ Нет автоматического подключения к GitHub и Claude при старте
 * 2. ❌ performStartupValidation() только проверяет наличие ключа, но НЕ тестирует соединение
 * 3. ❌ Отсутствует GitHubApiClient в dependencies
 * 
 * ИСПРАВЛЕНИЯ:
 * ────────────────────────────────────────────────────────────
 * 1. ✅ Добавлен @Inject GitHubApiClient
 * 2. ✅ performStartupValidation() теперь вызывает РЕАЛЬНЫЕ тесты подключения
 * 3. ✅ Автоматическая проверка GitHub и Claude API при каждом запуске
 * 4. ✅ Детальное логирование результатов тестов
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var claudeApiClient: ClaudeApiClient
    
    @Inject
    lateinit var gitHubApiClient: GitHubApiClient // ✅ ДОБАВЛЕНО
    
    @Inject
    lateinit var appSettings: AppSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 🔥 Проверяем, есть ли свежие краш-логи
        checkForRecentCrashes()
        
        // ✅ ИСПРАВЛЕНО: Автоматическая валидация И подключение API при старте
        performStartupValidation()
        
        enableEdgeToEdge()

        setContent {
            OpusIDETheme {
                var showRootDialogSetting by remember { mutableStateOf(true) }
                var isLoading by remember { mutableStateOf(true) }
                
                LaunchedEffect(Unit) {
                    dataStore.data.map { prefs ->
                        prefs[booleanPreferencesKey("show_root_dialog_on_startup")] ?: true
                    }.collect { enabled ->
                        showRootDialogSetting = enabled
                        isLoading = false
                    }
                }
                
                val isRooted = remember { SecurityUtils.isDeviceRooted() }
                var rootDialogDismissed by remember { mutableStateOf(false) }
                var sensitiveFeatureDisabled by remember { mutableStateOf(false) }
                
                if (!isLoading && showRootDialogSetting && !rootDialogDismissed) {
                    RootStatusDialog(
                        isRooted = isRooted,
                        onExitApp = {
                            finishAndRemoveTask()
                            exitProcess(0)
                        },
                        onDisableSensitiveFeatures = {
                            sensitiveFeatureDisabled = true
                            rootDialogDismissed = true
                        },
                        onProceedAnyway = {
                            sensitiveFeatureDisabled = false
                            rootDialogDismissed = true
                        }
                    )
                } else if (!isLoading) {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background
                    ) {
                        OpusIDENavigation(
                            sensitiveFeatureDisabled = sensitiveFeatureDisabled
                        )
                    }
                }
            }
        }
    }

    /**
     * 🔥 Проверяет наличие недавних крашей и логирует их
     */
    private fun checkForRecentCrashes() {
        try {
            val crashLogger = CrashLogger.getInstance() ?: return
            val latestCrash = crashLogger.getLatestCrashLog() ?: return
            
            val fiveMinutesAgo = System.currentTimeMillis() - (5 * 60 * 1000)
            if (latestCrash.lastModified() > fiveMinutesAgo) {
                android.util.Log.w("MainActivity", "━".repeat(80))
                android.util.Log.w("MainActivity", "🔥 RECENT CRASH DETECTED!")
                android.util.Log.w("MainActivity", "━".repeat(80))
                android.util.Log.w("MainActivity", "📁 Location: ${latestCrash.absolutePath}")
                android.util.Log.w("MainActivity", "📊 Size: ${latestCrash.length() / 1024} KB")
                android.util.Log.w("MainActivity", "🕐 Time: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(latestCrash.lastModified())}")
                android.util.Log.w("MainActivity", "━".repeat(80))
                
                android.util.Log.i("MainActivity", "📋 First 50 lines of crash log:")
                latestCrash.readLines().take(50).forEach { line ->
                    android.util.Log.i("MainActivity", line)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error checking for crashes", e)
        }
    }

    /**
     * ✅ КРИТИЧЕСКИ ИСПРАВЛЕНО: Автоматическое подключение к API при старте
     * 
     * БЫЛО:
     * ──────
     * - Только проверка validateApiKey() (проверяет формат, НЕ соединение)
     * - Никаких реальных запросов к API
     * - При повторном запуске приложения нет автоматического подключения
     * 
     * СТАЛО:
     * ──────
     * - Вызов testConnection() для Claude (реальный запрос к API)
     * - Вызов getRepository() для GitHub (реальный запрос к API)
     * - Детальное логирование результатов
     * - Автоматическое подключение при каждом запуске
     */
    private fun performStartupValidation() {
        lifecycleScope.launch {
            android.util.Log.d("MainActivity", "━".repeat(80))
            android.util.Log.d("MainActivity", "🔍 STARTUP VALIDATION & AUTO-CONNECT")
            android.util.Log.d("MainActivity", "━".repeat(80))
            
            // ═══════════════════════════════════════════════════════════
            // ВАЛИДАЦИЯ И АВТОПОДКЛЮЧЕНИЕ CLAUDE API
            // ═══════════════════════════════════════════════════════════
            android.util.Log.d("MainActivity", "  ├─ Claude API:")
            
            val isClaudeReady = try {
                // ✅ ИСПРАВЛЕНО: Сначала проверяем наличие ключа
                val hasKey = claudeApiClient.validateApiKey()
                
                if (!hasKey) {
                    android.util.Log.w("MainActivity", "  │  ├─ ⚠️ API key not configured")
                    false
                } else {
                    android.util.Log.d("MainActivity", "  │  ├─ ✅ API key found")
                    
                    // ✅ НОВОЕ: Тестируем РЕАЛЬНОЕ подключение
                    android.util.Log.d("MainActivity", "  │  ├─ 🔄 Testing connection...")
                    val testResult = claudeApiClient.testConnection()
                    
                    testResult.onSuccess { message ->
                        android.util.Log.i("MainActivity", "  │  └─ ✅ CONNECTED: $message")
                        true
                    }.onFailure { error ->
                        android.util.Log.e("MainActivity", "  │  └─ ❌ Connection failed: ${error.message}")
                        false
                    }.isSuccess
                }
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "  │  └─ ❌ Error: ${e.message}", e)
                false
            }
            
            if (isClaudeReady) {
                android.util.Log.i("MainActivity", "  │")
                android.util.Log.i("MainActivity", "  ├─ ✅ Claude API: READY & CONNECTED")
                android.util.Log.i("MainActivity", "  │  • Can send requests to Anthropic")
                android.util.Log.i("MainActivity", "  │  • Analyzer tab fully functional")
            } else {
                android.util.Log.w("MainActivity", "  │")
                android.util.Log.w("MainActivity", "  ├─ ⚠️ Claude API: NOT READY")
                android.util.Log.w("MainActivity", "  │  • Please configure API key in Settings")
                android.util.Log.w("MainActivity", "  │  • Click 'Test' button to verify connection")
                android.util.Log.w("MainActivity", "  │  • Analyzer tab will show error")
            }
            
            // ═══════════════════════════════════════════════════════════
            // ВАЛИДАЦИЯ И АВТОПОДКЛЮЧЕНИЕ GITHUB API
            // ═══════════════════════════════════════════════════════════
            android.util.Log.d("MainActivity", "  │")
            android.util.Log.d("MainActivity", "  ├─ GitHub API:")
            
            val gitHubConfig = try {
                appSettings.gitHubConfig.first()
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "  │  ├─ ❌ Failed to read config: ${e.message}", e)
                null
            }
            
            val isGitHubReady = if (gitHubConfig?.isConfigured == true) {
                android.util.Log.d("MainActivity", "  │  ├─ ✅ Config found:")
                android.util.Log.d("MainActivity", "  │  │  ├─ Owner: ${gitHubConfig.owner}")
                android.util.Log.d("MainActivity", "  │  │  ├─ Repo: ${gitHubConfig.repo}")
                android.util.Log.d("MainActivity", "  │  │  ├─ Branch: ${gitHubConfig.branch}")
                android.util.Log.d("MainActivity", "  │  │  └─ Token: ${if (gitHubConfig.token.isNotEmpty()) "[${gitHubConfig.token.take(10)}...]" else "[EMPTY]"}")
                
                // ✅ НОВОЕ: Тестируем РЕАЛЬНОЕ подключение
                try {
                    android.util.Log.d("MainActivity", "  │  ├─ 🔄 Testing connection...")
                    val repoResult = gitHubApiClient.getRepository()
                    
                    repoResult.onSuccess { repo ->
                        android.util.Log.i("MainActivity", "  │  └─ ✅ CONNECTED: ${repo.fullName}")
                        android.util.Log.i("MainActivity", "  │     ├─ Description: ${repo.description ?: "N/A"}")
                        android.util.Log.i("MainActivity", "  │     ├─ Private: ${repo.isPrivate}")
                        android.util.Log.i("MainActivity", "  │     └─ Default branch: ${repo.defaultBranch}")
                        true
                    }.onFailure { error ->
                        android.util.Log.e("MainActivity", "  │  └─ ❌ Connection failed: ${error.message}")
                        false
                    }.isSuccess
                } catch (e: Exception) {
                    android.util.Log.e("MainActivity", "  │  └─ ❌ Error: ${e.message}", e)
                    false
                }
            } else {
                android.util.Log.w("MainActivity", "  │  └─ ⚠️ Config not found or incomplete")
                false
            }
            
            if (isGitHubReady) {
                android.util.Log.i("MainActivity", "  │")
                android.util.Log.i("MainActivity", "  ├─ ✅ GitHub API: READY & CONNECTED")
                android.util.Log.i("MainActivity", "  │  • Can access repository")
                android.util.Log.i("MainActivity", "  │  • Creator tab fully functional")
            } else {
                android.util.Log.w("MainActivity", "  │")
                android.util.Log.w("MainActivity", "  ├─ ⚠️ GitHub API: NOT READY")
                android.util.Log.w("MainActivity", "  │  • Please configure repository in Settings")
                android.util.Log.w("MainActivity", "  │  • Click 'Test' button to verify connection")
                android.util.Log.w("MainActivity", "  │  • Creator tab will be limited")
            }
            
            // ═══════════════════════════════════════════════════════════
            // ИТОГОВЫЙ СТАТУС
            // ═══════════════════════════════════════════════════════════
            android.util.Log.d("MainActivity", "  │")
            android.util.Log.d("MainActivity", "━".repeat(80))
            when {
                isClaudeReady && isGitHubReady -> {
                    android.util.Log.i("MainActivity", "🎉 ALL SYSTEMS GO - App fully functional")
                    android.util.Log.i("MainActivity", "   ✅ Claude API connected and ready")
                    android.util.Log.i("MainActivity", "   ✅ GitHub API connected and ready")
                }
                isClaudeReady -> {
                    android.util.Log.i("MainActivity", "⚡ PARTIAL MODE - Analyzer ready, Creator limited")
                    android.util.Log.i("MainActivity", "   ✅ Claude API connected")
                    android.util.Log.i("MainActivity", "   ⚠️ GitHub API needs configuration")
                }
                isGitHubReady -> {
                    android.util.Log.i("MainActivity", "⚡ PARTIAL MODE - Creator ready, Analyzer limited")
                    android.util.Log.i("MainActivity", "   ⚠️ Claude API needs configuration")
                    android.util.Log.i("MainActivity", "   ✅ GitHub API connected")
                }
                else -> {
                    android.util.Log.w("MainActivity", "⚠️ LIMITED MODE - Please configure Settings")
                    android.util.Log.w("MainActivity", "   ⚠️ Claude API needs configuration")
                    android.util.Log.w("MainActivity", "   ⚠️ GitHub API needs configuration")
                }
            }
            android.util.Log.d("MainActivity", "━".repeat(80))
        }
    }
}

@Composable
fun RootStatusDialog(
    isRooted: Boolean,
    onExitApp: () -> Unit,
    onDisableSensitiveFeatures: () -> Unit,
    onProceedAnyway: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { /* Non-cancelable */ },
        icon = {
            Text(
                if (isRooted) "⚠️" else "✅",
                style = MaterialTheme.typography.displayMedium
            )
        },
        title = {
            Text(
                text = if (isRooted) "Rooted Device Detected" else "Device Security Check",
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (isRooted) {
                    Text(
                        text = "Your device has root access enabled. This significantly increases security risks:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    
                    Column(
                        modifier = Modifier.padding(start = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text("• API keys can be extracted from memory", style = MaterialTheme.typography.bodySmall)
                        Text("• Database files are readable by root apps", style = MaterialTheme.typography.bodySmall)
                        Text("• Encryption keys can be compromised", style = MaterialTheme.typography.bodySmall)
                        Text("• Cache content is vulnerable", style = MaterialTheme.typography.bodySmall)
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Text(
                        text = "How would you like to proceed?",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    Text(
                        text = "Security check complete. No root access detected.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("✅", style = MaterialTheme.typography.titleMedium)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "All security features available:",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                            Text("• Secure API key storage", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text("• Encrypted file caching", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text("• Biometric authentication", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            Text("• Full app functionality", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Text(
                        text = "You can disable this dialog in Settings → Developer Tools",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onProceedAnyway,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isRooted) 
                        MaterialTheme.colorScheme.error
                    else 
                        MaterialTheme.colorScheme.primary,
                    contentColor = if (isRooted)
                        MaterialTheme.colorScheme.onError
                    else
                        MaterialTheme.colorScheme.onPrimary
                )
            ) {
                Text(
                    if (isRooted) "Proceed Anyway (Risky)" else "Continue",
                    style = MaterialTheme.typography.labelLarge
                )
            }
        },
        dismissButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedButton(
                    onClick = onExitApp,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                ) {
                    Text("Exit App")
                }
                
                OutlinedButton(
                    onClick = onDisableSensitiveFeatures,
                    enabled = isRooted,
                    colors = ButtonDefaults.outlinedButtonColors(
                        contentColor = if (isRooted)
                            MaterialTheme.colorScheme.tertiary
                        else
                            MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    )
                ) {
                    Text("Disable Sensitive Features")
                }
            }
        }
    )
}