package com.opuside.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.opuside.app.core.security.SecurityUtils
import com.opuside.app.core.ui.theme.OpusIDETheme
import com.opuside.app.core.util.CrashLogger
import com.opuside.app.navigation.OpusIDENavigation
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 🔥 Проверяем, есть ли свежие краш-логи
        checkForRecentCrashes()
        
        // ✅ ИСПРАВЛЕНО: Убрали return, теперь диалог показывается корректно
        enableEdgeToEdge()

        setContent {
            OpusIDETheme {
                // ✅ ИСПРАВЛЕНО: Проверяем root прямо в Compose
                val isRooted = remember { SecurityUtils.isDeviceRooted() }
                var rootDialogDismissed by remember { mutableStateOf(false) }
                var sensitiveFeatureDisabled by remember { mutableStateOf(false) }
                
                if (isRooted && !rootDialogDismissed) {
                    // Показываем Root Warning Dialog
                    RootWarningDialog(
                        onExitApp = {
                            finish() // Закрываем приложение
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
 * ✅ НОВЫЙ КОМПОНЕНТ: Root Warning Dialog с 3 кнопками
 * Соответствует спецификации из документа "Все микрофункции"
 */
@Composable
fun RootWarningDialog(
    onExitApp: () -> Unit,
    onDisableSensitiveFeatures: () -> Unit,
    onProceedAnyway: () -> Unit
) {
    AlertDialog(
        onDismissRequest = { /* Non-cancelable - пользователь ДОЛЖЕН выбрать действие */ },
        icon = {
            Text("⚠️", style = MaterialTheme.typography.displayMedium)
        },
        title = {
            Text(
                text = "Rooted Device Detected",
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Your device has root access enabled. This significantly increases security risks:",
                    style = MaterialTheme.typography.bodyMedium
                )
                
                // Список рисков
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
            }
        },
        confirmButton = {
            // ✅ КНОПКА 3: "Proceed Anyway" (рискованный вариант)
            TextButton(
                onClick = onProceedAnyway,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("Proceed Anyway")
            }
        },
        dismissButton = {
            // Группируем 2 кнопки слева
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // ✅ КНОПКА 1: "Exit App" (безопасный вариант)
                TextButton(
                    onClick = onExitApp,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.onSurface
                    )
                ) {
                    Text("Exit App")
                }
                
                // ✅ КНОПКА 2: "Disable Sensitive Features" (компромисс)
                TextButton(
                    onClick = onDisableSensitiveFeatures,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Text("Disable Sensitive Features")
                }
            }
        }
    )
}
