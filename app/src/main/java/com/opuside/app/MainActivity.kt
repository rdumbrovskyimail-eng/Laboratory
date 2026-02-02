package com.opuside.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
        
        if (SecurityUtils.isDeviceRooted()) {
            showRootEnforcementDialog()
            return
        }
        
        enableEdgeToEdge()

        setContent {
            OpusIDETheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    OpusIDENavigation()
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

    private fun showRootEnforcementDialog() {
        setContent {
            OpusIDETheme {
                val showDialog = remember { mutableStateOf(true) }
                
                if (showDialog.value) {
                    AlertDialog(
                        onDismissRequest = { /* Non-cancelable */ },
                        title = { Text("🔓 Rooted Device Detected") },
                        text = {
                            Text(
                                "Your device has root access enabled. This significantly increases security risks:\n\n" +
                                "• API keys can be extracted from memory\n" +
                                "• Database files are readable\n" +
                                "• Encryption keys can be compromised\n\n" +
                                "How would you like to proceed?"
                            )
                        },
                        confirmButton = {
                            TextButton(onClick = {
                                showDialog.value = false
                                proceedWithLimitedMode()
                            }) {
                                Text("Disable Sensitive Features")
                            }
                        },
                        dismissButton = {
                            TextButton(onClick = {
                                finish()
                            }) {
                                Text("Exit App")
                            }
                        }
                    )
                }
            }
        }
    }

    private fun proceedWithLimitedMode() {
        enableEdgeToEdge()
        setContent {
            OpusIDETheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    OpusIDENavigation()
                }
            }
        }
    }

    private fun proceedWithFullMode() {
        enableEdgeToEdge()
        setContent {
            OpusIDETheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    OpusIDENavigation()
                }
            }
        }
    }
}