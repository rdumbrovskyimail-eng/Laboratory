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
import com.opuside.app.core.security.SecurityUtils
import com.opuside.app.core.ui.theme.OpusIDETheme
import com.opuside.app.core.util.CrashLogger
import com.opuside.app.navigation.OpusIDENavigation
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "app_preferences")

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 🔥 Проверяем, есть ли свежие краш-логи
        checkForRecentCrashes()
        
        enableEdgeToEdge()

        setContent {
            OpusIDETheme {
                // ✅ Проверяем настройку показа Root Dialog
                val showRootDialogSetting = remember {
                    runBlocking {
                        dataStore.data.map { prefs ->
                            prefs[booleanPreferencesKey("show_root_dialog_on_startup")] ?: true
                        }.first()
                    }
                }
                
                val isRooted = remember { SecurityUtils.isDeviceRooted() }
                var rootDialogDismissed by remember { mutableStateOf(!showRootDialogSetting) }
                var sensitiveFeatureDisabled by remember { mutableStateOf(false) }
                
                if (!rootDialogDismissed) {
                    // ✅ ВСЕГДА показываем диалог при первом запуске (если настройка включена)
                    RootStatusDialog(
                        isRooted = isRooted,
                        onExitApp = {
                            finish()
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
                } else {
                    // Основной UI приложения
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
            
            // Проверяем, был ли краш в последние 5 минут
            val fiveMinutesAgo = System.currentTimeMillis() - (5 * 60 * 1000)
            if (latestCrash.lastModified() > fiveMinutesAgo) {
                android.util.Log.w("MainActivity", "━".repeat(80))
                android.util.Log.w("MainActivity", "🔥 RECENT CRASH DETECTED!")
                android.util.Log.w("MainActivity", "━".repeat(80))
                android.util.Log.w("MainActivity", "📁 Location: ${latestCrash.absolutePath}")
                android.util.Log.w("MainActivity", "📊 Size: ${latestCrash.length() / 1024} KB")
                android.util.Log.w("MainActivity", "🕐 Time: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(latestCrash.lastModified())}")
                android.util.Log.w("MainActivity", "━".repeat(80))
                
                // Выводим первые 50 строк лога в logcat для быстрой диагностики
                android.util.Log.i("MainActivity", "📋 First 50 lines of crash log:")
                latestCrash.readLines().take(50).forEach { line ->
                    android.util.Log.i("MainActivity", line)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("MainActivity", "Error checking for crashes", e)
        }
    }
}

/**
 * ✅ НОВЫЙ КОМПОНЕНТ: Root Status Dialog
 * Показывается ВСЕГДА при старте, отображает реальный статус root
 * Кнопки активны/неактивны в зависимости от наличия root
 */
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
                    // УСТРОЙСТВО С ROOT
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
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    // УСТРОЙСТВО БЕЗ ROOT
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
            // ✅ КНОПКА "Proceed" - активна только если НЕТ root
            TextButton(
                onClick = onProceedAnyway,
                enabled = !isRooted,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = if (isRooted) 
                        MaterialTheme.colorScheme.error 
                    else 
                        MaterialTheme.colorScheme.primary,
                    disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                )
            ) {
                Text(if (isRooted) "Proceed Anyway (Risky)" else "Continue")
            }
        },
        dismissButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // ✅ КНОПКА "Exit App" - всегда активна
                TextButton(
                    onClick = onExitApp,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                ) {
                    Text("Exit App")
                }
                
                // ✅ КНОПКА "Disable Features" - активна только если ЕСТЬ root
                TextButton(
                    onClick = onDisableSensitiveFeatures,
                    enabled = isRooted,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary,
                        disabledContentColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    )
                ) {
                    Text("Disable Sensitive Features")
                }
            }
        }
    )
}