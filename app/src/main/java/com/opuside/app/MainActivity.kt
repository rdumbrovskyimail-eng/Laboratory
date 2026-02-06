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

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var claudeApiClient: ClaudeApiClient
    
    @Inject
    lateinit var appSettings: AppSettings

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 🔥 Проверяем, есть ли свежие краш-логи
        checkForRecentCrashes()
        
        // ✅ НОВОЕ: Автоматическая валидация API ключей при старте
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
     * ✅ НОВЫЙ МЕТОД: Валидация API ключей при старте приложения
     * 
     * Проверяет:
     * - Наличие и корректность Anthropic API ключа
     * - Настройки GitHub репозитория
     * - Логирует результаты для диагностики
     */
    private fun performStartupValidation() {
        lifecycleScope.launch {
            android.util.Log.d("MainActivity", "━".repeat(80))
            android.util.Log.d("MainActivity", "🔍 STARTUP VALIDATION")
            android.util.Log.d("MainActivity", "━".repeat(80))
            
            // ═══════════════════════════════════════════════════════════
            // ВАЛИДАЦИЯ CLAUDE API
            // ═══════════════════════════════════════════════════════════
            val isClaudeReady = try {
                claudeApiClient.validateApiKey()
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "Claude API validation error", e)
                false
            }
            
            if (isClaudeReady) {
                android.util.Log.i("MainActivity", "✅ Claude API: Ready")
                android.util.Log.i("MainActivity", "   • API key configured and valid")
                android.util.Log.i("MainActivity", "   • Can send requests to Anthropic")
            } else {
                android.util.Log.w("MainActivity", "⚠️ Claude API: Not configured")
                android.util.Log.w("MainActivity", "   • Please set API key in Settings")
                android.util.Log.w("MainActivity", "   • Analyzer tab will show error until configured")
            }
            
            // ═══════════════════════════════════════════════════════════
            // ВАЛИДАЦИЯ GITHUB CONFIG
            // ═══════════════════════════════════════════════════════════
            val gitHubConfig = try {
                appSettings.gitHubConfig.first()
            } catch (e: Exception) {
                android.util.Log.e("MainActivity", "GitHub config read error", e)
                null
            }
            
            val isGitHubReady = gitHubConfig?.isConfigured == true
            
            if (isGitHubReady && gitHubConfig != null) {
                android.util.Log.i("MainActivity", "✅ GitHub: Ready")
                android.util.Log.i("MainActivity", "   • Repository: ${gitHubConfig.fullName}")
                android.util.Log.i("MainActivity", "   • Branch: ${gitHubConfig.branch}")
                android.util.Log.i("MainActivity", "   • Token: ${if (gitHubConfig.token.isNotEmpty()) "Configured" else "Missing"}")
            } else {
                android.util.Log.w("MainActivity", "⚠️ GitHub: Not configured")
                android.util.Log.w("MainActivity", "   • Please configure repository in Settings")
                android.util.Log.w("MainActivity", "   • Creator tab will be limited until configured")
            }
            
            // ═══════════════════════════════════════════════════════════
            // ИТОГОВЫЙ СТАТУС
            // ═══════════════════════════════════════════════════════════
            android.util.Log.d("MainActivity", "━".repeat(80))
            when {
                isClaudeReady && isGitHubReady -> {
                    android.util.Log.i("MainActivity", "🎉 ALL SYSTEMS GO - App fully functional")
                }
                isClaudeReady -> {
                    android.util.Log.i("MainActivity", "⚡ PARTIAL - Analyzer ready, Creator limited")
                }
                isGitHubReady -> {
                    android.util.Log.i("MainActivity", "⚡ PARTIAL - Creator ready, Analyzer limited")
                }
                else -> {
                    android.util.Log.w("MainActivity", "⚠️ LIMITED MODE - Please configure Settings")
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