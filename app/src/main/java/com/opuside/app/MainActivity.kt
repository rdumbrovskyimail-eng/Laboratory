package com.opuside.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.opuside.app.navigation.OpusIDENavigation
import com.opuside.app.core.ui.theme.OpusIDETheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * MainActivity - единственная Activity в приложении.
 * 
 * Использует Single Activity Architecture с Jetpack Compose Navigation.
 * Все экраны (Creator, Analyzer, Settings) - это Composable функции.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
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
}
