package com.opuside.app.core.util

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File

/**
 * 📋 Log Viewer Dialog - Список логов в виде диалога
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogViewerDialog(
    onDismiss: () -> Unit,
    onLogSelected: (LogFile) -> Unit
) {
    val crashLogger = remember { CrashLogger.getInstance() }
    var logs by remember { mutableStateOf<List<LogFile>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    
    LaunchedEffect(Unit) {
        logs = crashLogger?.getAllLogs() ?: emptyList()
        isLoading = false
    }
    
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = true
        )
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.9f),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header
                TopAppBar(
                    title = { Text("Crash & LogCat Logs") },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, "Close")
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = {
                                logs = crashLogger?.getAllLogs() ?: emptyList()
                            }
                        ) {
                            Icon(Icons.Default.Refresh, "Refresh")
                        }
                    }
                )
                
                // Content
                Box(modifier = Modifier.fillMaxSize()) {
                    when {
                        isLoading -> {
                            CircularProgressIndicator(
                                modifier = Modifier.align(Alignment.Center)
                            )
                        }
                        logs.isEmpty() -> {
                            Column(
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .padding(32.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Icon(
                                    Icons.Default.Description,
                                    null,
                                    modifier = Modifier.size(64.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.height(16.dp))
                                Text(
                                    "No logs found",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    "Crash logs will appear here automatically",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        else -> {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(16.dp),
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                items(logs, key = { it.file.absolutePath }) { log ->
                                    LogListItem(
                                        logFile = log,
                                        onClick = { 
                                            onLogSelected(log)
                                        },
                                        onDelete = {
                                            log.file.delete()
                                            logs = crashLogger?.getAllLogs() ?: emptyList()
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 📄 Элемент списка лога
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogListItem(
    logFile: LogFile,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    var showDeleteConfirm by remember { mutableStateOf(false) }
    
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Иконка типа лога
            Icon(
                when (logFile.type) {
                    LogType.CRASH -> Icons.Default.Warning
                    LogType.LOGCAT -> Icons.Default.Description
                },
                null,
                modifier = Modifier.size(32.dp),
                tint = when (logFile.type) {
                    LogType.CRASH -> Color(0xFFEF4444)
                    LogType.LOGCAT -> Color(0xFF3B82F6)
                }
            )
            
            Spacer(Modifier.width(12.dp))
            
            // Информация о логе
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    logFile.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    logFile.formattedDate,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    "${logFile.sizeKB} KB • ${logFile.type.name}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            // Кнопка удаления
            IconButton(onClick = { showDeleteConfirm = true }) {
                Icon(Icons.Default.Delete, "Delete", tint = Color(0xFFEF4444))
            }
        }
    }
    
    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            icon = { Icon(Icons.Default.DeleteForever, null) },
            title = { Text("Delete Log?") },
            text = { Text("This action cannot be undone.") },
            confirmButton = {
                Button(
                    onClick = {
                        onDelete()
                        showDeleteConfirm = false
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFEF4444)
                    )
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

/**
 * 📖 Просмотр содержимого лога в диалоге с подсветкой ошибок
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogContentDialog(
    logFile: LogFile,
    onDismiss: () -> Unit
) {
    var content by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(true) }
    
    LaunchedEffect(logFile) {
        content = try {
            logFile.file.readText()
        } catch (e: Exception) {
            "❌ Failed to read log: ${e.message}"
        }
        isLoading = false
    }
    
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.9f),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFF1E1E1E)
            )
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Header with date/time
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFF2D2D2D)
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    logFile.name,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color.White,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(4.dp))
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    // Date/Time Badge
                                    Surface(
                                        color = Color(0xFF3B82F6),
                                        shape = MaterialTheme.shapes.small
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                Icons.Default.CalendarToday,
                                                null,
                                                modifier = Modifier.size(14.dp),
                                                tint = Color.White
                                            )
                                            Spacer(Modifier.width(4.dp))
                                            Text(
                                                logFile.formattedDate,
                                                style = MaterialTheme.typography.bodySmall,
                                                color = Color.White
                                            )
                                        }
                                    }
                                    
                                    // Size Badge
                                    Surface(
                                        color = Color(0xFF6B7280),
                                        shape = MaterialTheme.shapes.small
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                Icons.Default.Description,
                                                null,
                                                modifier = Modifier.size(14.dp),
                                                tint = Color.White
                                            )
                                            Spacer(Modifier.width(4.dp))
                                            Text(
                                                "${logFile.sizeKB} KB",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = Color.White
                                            )
                                        }
                                    }
                                    
                                    // Type Badge
                                    Surface(
                                        color = when (logFile.type) {
                                            LogType.CRASH -> Color(0xFFEF4444)
                                            LogType.LOGCAT -> Color(0xFF10B981)
                                        },
                                        shape = MaterialTheme.shapes.small
                                    ) {
                                        Text(
                                            logFile.type.name,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = Color.White
                                        )
                                    }
                                }
                            }
                            
                            IconButton(onClick = onDismiss) {
                                Icon(
                                    Icons.Default.Close,
                                    "Close",
                                    tint = Color.White
                                )
                            }
                        }
                    }
                }
                
                HorizontalDivider(color = Color(0xFF404040))
                
                // Log content with error highlighting
                if (isLoading) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = Color.White)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0xFF1E1E1E)),
                        contentPadding = PaddingValues(12.dp)
                    ) {
                        // Разбиваем на строки и подсвечиваем ошибки
                        val lines = content.lines()
                        items(lines.size) { index ->
                            val line = lines[index]
                            
                            // Проверяем, является ли строка ошибкой
                            val isError = line.contains("ERROR", ignoreCase = true) ||
                                        line.contains(" E/") ||
                                        line.contains("Exception") ||
                                        line.contains("Error:") ||
                                        line.contains("FATAL") ||
                                        line.contains("❌") ||
                                        line.contains("🔥") ||
                                        line.contains("at ") && line.contains(".kt:") || // Stack trace
                                        line.contains("Caused by:")
                            
                            val isWarning = !isError && (
                                        line.contains("WARNING", ignoreCase = true) ||
                                        line.contains(" W/") ||
                                        line.contains("⚠️")
                                        )
                            
                            val isHeader = line.startsWith("=") || 
                                          line.startsWith("-") ||
                                          line.startsWith("🔥") ||
                                          line.startsWith("📋")
                            
                            Text(
                                text = line.ifEmpty { " " }, // Empty lines as spaces for visibility
                                fontFamily = FontFamily.Monospace,
                                fontSize = 11.sp,
                                color = when {
                                    isError -> Color(0xFFFF5555)
                                    isWarning -> Color(0xFFFBBF24)
                                    isHeader -> Color(0xFF60A5FA)
                                    else -> Color(0xFFCCCCCC)
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 1.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

