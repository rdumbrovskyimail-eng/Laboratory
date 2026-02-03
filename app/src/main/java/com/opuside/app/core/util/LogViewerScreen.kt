package com.opuside.app.core.util

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import java.io.File

/**
 * 📋 Log Viewer Screen - Просмотр всех логов
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogViewerScreen(
    onBack: () -> Unit
) {
    val crashLogger = remember { CrashLogger.getInstance() }
    var logs by remember { mutableStateOf<List<LogFile>>(emptyList()) }
    var selectedLog by remember { mutableStateOf<LogFile?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    
    LaunchedEffect(Unit) {
        logs = crashLogger?.getAllLogs() ?: emptyList()
        isLoading = false
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Crash & LogCat Logs") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
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
        }
    ) { padding ->
        if (selectedLog != null) {
            // Показываем содержимое выбранного лога
            LogContentViewer(
                logFile = selectedLog!!,
                onBack = { selectedLog = null }
            )
        } else {
            // Показываем список логов
            LogListView(
                logs = logs,
                isLoading = isLoading,
                onLogClick = { selectedLog = it },
                onDeleteLog = { logToDelete ->
                    logToDelete.file.delete()
                    logs = crashLogger?.getAllLogs() ?: emptyList()
                },
                modifier = Modifier.padding(padding)
            )
        }
    }
}

/**
 * 📄 Список всех логов
 */
@Composable
private fun LogListView(
    logs: List<LogFile>,
    isLoading: Boolean,
    onLogClick: (LogFile) -> Unit,
    onDeleteLog: (LogFile) -> Unit,
    modifier: Modifier = Modifier
) {
    Box(modifier = modifier.fillMaxSize()) {
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
                            onClick = { onLogClick(log) },
                            onDelete = { onDeleteLog(log) }
                        )
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
 * 📖 Просмотр содержимого лога с подсветкой ошибок
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LogContentViewer(
    logFile: LogFile,
    onBack: () -> Unit
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
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text(
                            logFile.name,
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            "${logFile.sizeKB} KB • ${logFile.formattedDate}",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .background(Color(0xFF1E1E1E)),
                contentPadding = PaddingValues(8.dp)
            ) {
                // Разбиваем на строки и подсвечиваем ошибки
                val lines = content.lines()
                items(lines.size) { index ->
                    val line = lines[index]
                    val isError = line.contains("ERROR", ignoreCase = true) ||
                                line.contains(" E/") ||
                                line.contains("Exception") ||
                                line.contains("Error:") ||
                                line.contains("FATAL") ||
                                line.contains("❌") ||
                                line.contains("🔥")
                    
                    Text(
                        text = line,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp,
                        color = if (isError) Color(0xFFFF5555) else Color(0xFFCCCCCC),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 1.dp)
                    )
                }
            }
        }
    }
}
