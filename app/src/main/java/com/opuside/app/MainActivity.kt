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
import com.opuside.app.navigation.OpusIDENavigation
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
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