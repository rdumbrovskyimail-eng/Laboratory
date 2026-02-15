package com.opuside.app.feature.workflows.presentation

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.opuside.app.core.network.github.model.WorkflowRun
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * ════════════════════════════════════════════════════════════════════════════
 * WORKFLOWS SCREEN - GitHub Actions Monitor
 * ════════════════════════════════════════════════════════════════════════════
 * 
 * Экран отображает:
 * - Список активных workflow runs
 * - Их статус (running, completed, failed)
 * - Время выполнения
 * - Детали при клике на workflow
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkflowsScreen(
    viewModel: WorkflowsViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsState()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("GitHub Actions") },
                actions = {
                    // Кнопка обновления
                    IconButton(onClick = { viewModel.refreshWorkflows() }) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Refresh"
                        )
                    }
                    
                    // Кнопка скачивания репозитория
                    IconButton(onClick = { viewModel.downloadRepository(context) }) {
                        Icon(
                            imageVector = Icons.Default.Download,
                            contentDescription = "Download Repository as ZIP"
                        )
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                state.isLoading && state.workflows.isEmpty() -> {
                    // Первоначальная загрузка
                    LoadingState()
                }
                
                state.error != null && state.workflows.isEmpty() -> {
                    // Ошибка при первой загрузке
                    ErrorState(
                        error = state.error!!,
                        onRetry = { viewModel.refreshWorkflows() }
                    )
                }
                
                state.workflows.isEmpty() -> {
                    // Нет workflow runs
                    EmptyState()
                }
                
                else -> {
                    // Показываем список workflows
                    WorkflowsList(
                        workflows = state.workflows,
                        selectedWorkflow = state.selectedWorkflow,
                        onWorkflowClick = { viewModel.selectWorkflow(it) },
                        onRefresh = { viewModel.refreshWorkflows() },
                        isRefreshing = state.isLoading
                    )
                }
            }
            
            // Snackbar для сообщений
            state.message?.let { message ->
                LaunchedEffect(message) {
                    kotlinx.coroutines.delay(3000)
                    viewModel.clearMessage()
                }
                
                Snackbar(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                    action = {
                        TextButton(onClick = { viewModel.clearMessage() }) {
                            Text("OK")
                        }
                    }
                ) {
                    Text(message)
                }
            }
        }
    }
    
    // Диалог с деталями workflow
    state.selectedWorkflow?.let { workflow ->
        WorkflowDetailDialog(
            workflow = workflow,
            jobLogs = state.jobLogs,
            isLoadingLogs = state.isLoadingLogs,
            onDismiss = { viewModel.clearSelection() },
            onLoadLogs = { viewModel.loadWorkflowLogs(workflow.id) }
        )
    }
}

// ════════════════════════════════════════════════════════════════════════════
// WORKFLOWS LIST
// ════════════════════════════════════════════════════════════════════════════

@Composable
private fun WorkflowsList(
    workflows: List<WorkflowRun>,
    selectedWorkflow: WorkflowRun?,
    onWorkflowClick: (WorkflowRun) -> Unit,
    onRefresh: () -> Unit,
    isRefreshing: Boolean
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Header с количеством
        item {
            Text(
                text = "${workflows.size} workflow runs",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
        
        // Список workflow runs
        items(workflows, key = { it.id }) { workflow ->
            WorkflowCard(
                workflow = workflow,
                isSelected = workflow.id == selectedWorkflow?.id,
                onClick = { onWorkflowClick(workflow) }
            )
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
// WORKFLOW CARD
// ════════════════════════════════════════════════════════════════════════════

@Composable
private fun WorkflowCard(
    workflow: WorkflowRun,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) 
                MaterialTheme.colorScheme.primaryContainer 
            else 
                MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(
            defaultElevation = if (isSelected) 8.dp else 2.dp
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header: название и статус
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = workflow.name ?: "Workflow #${workflow.id}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = workflow.headBranch,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                StatusBadge(status = workflow.status, conclusion = workflow.conclusion)
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // Детали
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Commit SHA (короткая версия)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Code,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = workflow.headSha.take(7),
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                // Время выполнения
                workflow.runStartedAt?.let { startTime ->
                    val duration = calculateDuration(startTime, workflow.updatedAt)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Timer,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = duration,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            
            // Время создания
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = formatTime(workflow.createdAt),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
// STATUS BADGE
// ════════════════════════════════════════════════════════════════════════════

@Composable
private fun StatusBadge(status: String, conclusion: String?) {
    val (color, icon, text) = when {
        status == "completed" && conclusion == "success" -> 
            Triple(Color(0xFF10B981), Icons.Default.CheckCircle, "Success")
        status == "completed" && conclusion == "failure" -> 
            Triple(Color(0xFFEF4444), Icons.Default.Error, "Failed")
        status == "completed" && conclusion == "cancelled" -> 
            Triple(Color(0xFF6B7280), Icons.Default.Cancel, "Cancelled")
        status == "in_progress" || status == "queued" -> 
            Triple(Color(0xFF3B82F6), Icons.Default.HourglassEmpty, "Running")
        else -> 
            Triple(Color(0xFF6B7280), Icons.Default.HelpOutline, status)
    }
    
    Surface(
        color = color.copy(alpha = 0.1f),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = color
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelMedium,
                color = color,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
// WORKFLOW DETAIL DIALOG
// ════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun WorkflowDetailDialog(
    workflow: WorkflowRun,
    jobLogs: String?,
    isLoadingLogs: Boolean,
    onDismiss: () -> Unit,
    onLoadLogs: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier
            .fillMaxWidth(0.95f)
            .fillMaxHeight(0.85f)
    ) {
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // Header
                TopAppBar(
                    title = {
                        Column {
                            Text(
                                text = workflow.name ?: "Workflow Details",
                                style = MaterialTheme.typography.titleLarge
                            )
                            Text(
                                text = "#${workflow.id}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, "Close")
                        }
                    },
                    actions = {
                        IconButton(
                            onClick = onLoadLogs,
                            enabled = !isLoadingLogs
                        ) {
                            Icon(
                                imageVector = if (isLoadingLogs) 
                                    Icons.Default.HourglassEmpty 
                                else 
                                    Icons.Default.Refresh,
                                contentDescription = "Load Logs"
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent
                    )
                )
                
                Divider()
                
                // Content
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .weight(1f),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Workflow Info
                    item {
                        WorkflowInfoSection(workflow)
                    }
                    
                    // Fast Build Debug APK Logs
                    item {
                        Text(
                            text = "Fast Build Debug APK - Logs",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 8.dp)
                        )
                        
                        when {
                            isLoadingLogs -> {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(200.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator()
                                }
                            }
                            
                            jobLogs != null -> {
                                LogsSection(logs = jobLogs)
                            }
                            
                            else -> {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                                    )
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(24.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Description,
                                                contentDescription = null,
                                                modifier = Modifier.size(48.dp),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                            Text(
                                                text = "Click refresh to load logs",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
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
    }
}

// ════════════════════════════════════════════════════════════════════════════
// WORKFLOW INFO SECTION
// ════════════════════════════════════════════════════════════════════════════

@Composable
private fun WorkflowInfoSection(workflow: WorkflowRun) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            InfoRow("Status", workflow.status)
            workflow.conclusion?.let { InfoRow("Conclusion", it) }
            InfoRow("Branch", workflow.headBranch)
            InfoRow("Commit", workflow.headSha.take(7))
            InfoRow("Started", formatTime(workflow.createdAt))
            workflow.runStartedAt?.let {
                InfoRow("Duration", calculateDuration(it, workflow.updatedAt))
            }
            
            // Ссылка на GitHub
            workflow.htmlUrl.let { url ->
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { /* Открыть в браузере */ },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.OpenInBrowser, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("View on GitHub")
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (label == "Commit") FontFamily.Monospace else null
        )
    }
}

// ════════════════════════════════════════════════════════════════════════════
// LOGS SECTION
// ════════════════════════════════════════════════════════════════════════════

@Composable
private fun LogsSection(logs: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF1E1E1E)
        )
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 400.dp)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            val logLines = logs.lines()
            items(logLines.size) { index ->
                Text(
                    text = logLines[index],
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    color = getLogLineColor(logLines[index])
                )
            }
        }
    }
}

private fun getLogLineColor(line: String): Color {
    return when {
        line.contains("error", ignoreCase = true) || 
        line.contains("failed", ignoreCase = true) -> Color(0xFFEF4444)
        
        line.contains("warning", ignoreCase = true) -> Color(0xFFF59E0B)
        
        line.contains("success", ignoreCase = true) || 
        line.contains("completed", ignoreCase = true) -> Color(0xFF10B981)
        
        line.startsWith(">") || line.startsWith("$") -> Color(0xFF60A5FA)
        
        else -> Color(0xFFE5E7EB)
    }
}

// ════════════════════════════════════════════════════════════════════════════
// STATE SCREENS
// ════════════════════════════════════════════════════════════════════════════

@Composable
private fun LoadingState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CircularProgressIndicator()
            Text(
                text = "Loading workflows...",
                style = MaterialTheme.typography.bodyLarge
            )
        }
    }
}

@Composable
private fun ErrorState(error: String, onRetry: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                imageVector = Icons.Default.ErrorOutline,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.error
            )
            Text(
                text = "Error loading workflows",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Button(onClick = onRetry) {
                Icon(Icons.Default.Refresh, null)
                Spacer(Modifier.width(8.dp))
                Text("Retry")
            }
        }
    }
}

@Composable
private fun EmptyState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp),
            modifier = Modifier.padding(32.dp)
        ) {
            Icon(
                imageVector = Icons.Default.WorkOff,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "No workflow runs found",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Workflow runs will appear here once they start",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ════════════════════════════════════════════════════════════════════════════
// UTILITY FUNCTIONS
// ════════════════════════════════════════════════════════════════════════════

private fun formatTime(isoString: String): String {
    return try {
        val instant = Instant.parse(isoString)
        val formatter = DateTimeFormatter
            .ofPattern("MMM dd, yyyy HH:mm")
            .withZone(ZoneId.systemDefault())
        formatter.format(instant)
    } catch (e: Exception) {
        isoString
    }
}

private fun calculateDuration(startTime: String, endTime: String): String {
    return try {
        val start = Instant.parse(startTime)
        val end = Instant.parse(endTime)
        val duration = Duration.between(start, end)
        
        val minutes = duration.toMinutes()
        val seconds = duration.seconds % 60
        
        when {
            minutes > 0 -> "${minutes}m ${seconds}s"
            else -> "${seconds}s"
        }
    } catch (e: Exception) {
        "N/A"
    }
}
