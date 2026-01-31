package com.opuside.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AlertDialog
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.opuside.app.core.security.SecurityUtils
import com.opuside.app.core.ui.theme.OpusIDETheme
import com.opuside.app.navigation.OpusIDENavigation
import dagger.hilt.android.AndroidEntryPoint

/**
 * MainActivity - единственная Activity в приложении.
 * 
 * Использует Single Activity Architecture с Jetpack Compose Navigation.
 * Все экраны (Creator, Analyzer, Settings) - это Composable функции.
 * 
 * ✅ ОБНОВЛЕНО: Добавлена проверка root-доступа при запуске
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // ✅ НОВОЕ: Проверка root-доступа
        if (SecurityUtils.isDeviceRooted()) {
            showRootWarning()
        }
        
        // Включаем edge-to-edge для Android 16
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
     * ✅ НОВОЕ: Предупреждение о root-доступе
     */
    private fun showRootWarning() {
        AlertDialog.Builder(this)
            .setTitle("⚠️ Security Warning")
            .setMessage("This device appears to be rooted. Your API keys and sensitive data may be at risk.\n\nWe recommend using OpusIDE only on non-rooted devices for maximum security.")
            .setPositiveButton("I Understand") { dialog, _ ->
                dialog.dismiss()
            }
            .setCancelable(false)
            .show()
    }
} 