package com.opuside.app.feature.analyzer.presentation

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.opuside.app.core.ai.ClaudeModelConfig
import com.opuside.app.core.ai.RepositoryAnalyzer
import com.opuside.app.core.database.entity.ChatMessageEntity
import com.opuside.app.core.database.entity.MessageRole

/**
 * Analyzer Screen v2.1 (UPDATED)
 * 
 * ✅ НОВОЕ:
 * - Model Selector
 * - Session Info Bar
 * - Long Context Warning
 * - Detailed Cost Display
 * - Cache Efficiency Indicator
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyzerScreen(
    viewModel: AnalyzerViewModel = hiltViewModel()
) {
    val messages by viewModel.messages.collectAsState(initial = emptyList())
    val isStreaming by viewModel.isStreaming.collectAsState()
    val chatError by viewModel.chatError.collectAsState()
    
    // ✅ НОВОЕ: State для моделей и сеансов
    val selectedModel by viewModel.selectedModel.collectAsState()
    val currentSession by viewModel.currentSession.collectAsState()
    val cachingEnabled by viewModel.cachingEnabled.collectAsState()
    
    val repositoryStructure by viewModel.repositoryStructure.collectAsState()
    val selectedFiles by viewModel.selectedFiles.collectAsState()
    val scanEstimate by viewModel.scanEstimate.collectAsState()
    
    val isApproachingLongContext by viewModel.isApproachingLongContext.collectAsState()
    val isLongContext by viewModel.isLongContext.collectAsState()
    
    var userInput by remember { mutableStateOf("") }
    var showFilePickerDialog by remember { mutableStateOf(false) }
    var showModelSelector by remember { mutableStateOf(false) }
    var showSessionStats by remember { mutableStateOf(false) }
    
    val listState = rememberLazyListState()
    
    // Auto-scroll при новых сообщениях
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            listState.animateScrollToItem(messages.size - 1)
        }
    }
    
    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("Repository Analyzer") },
                    actions = {
                        // ✅ НОВОЕ: Model indicator
                        TextButton(onClick = { showModelSelector = true }) {
                            Text("${selectedModel.emoji} ${selectedModel.displayName}")
                        }
                        
                        // ✅ НОВОЕ: Session stats
                        IconButton(onClick = { showSessionStats = true }) {
                            Icon(Icons.Default.Analytics, "Session Stats")
                        }
                        
                        IconButton(onClick = { viewModel.loadRepositoryStructure() }) {
                            Icon(Icons.Default.Folder, "Repository")
                        }
                        
                        IconButton(onClick = { viewModel.clearChat() }) {
                            Icon(Icons.Default.DeleteSweep, "Clear Chat")
                        }
                    }
                )
                
                // ✅ НОВОЕ: Session Info Bar
                currentSession?.let { session ->
                    SessionInfoBar(
                        session = session,
                        isApproachingLongContext = isApproachingLongContext,
                        isLongContext = isLongContext,
                        onNewSession = { viewModel.startNewSession() }
                    )
                }
            }
        },
        bottomBar = {
            Column {
                // ✅ НОВОЕ: Long Context Warning
                AnimatedVisibility(
                    visible = isApproachingLongContext || isLongContext,
                    enter = slideInVertically() + fadeIn(),
                    exit = slideOutVertically() + fadeOut()
                ) {
                    LongContextWarning(
                        isLongContext = isLongContext,
                        onStartNewSession = { viewModel.startNewSession() }
                    )
                }
                
                // Selected Files Bar
                AnimatedVisibility(
                    visible = selectedFiles.isNotEmpty(),
                    enter = slideInVertically() + fadeIn(),
                    exit = slideOutVertically() + fadeOut()
                ) {
                    SelectedFilesBar(
                        selectedFiles = selectedFiles,
                        scanEstimate = scanEstimate,
                        onClearFiles = { viewModel.clearSelectedFiles() }
                    )
                }
                
                // Input Bar
                InputBar(
                    userInput = userInput,
                    onUserInputChange = { userInput = it },
                    isStreaming = isStreaming,
                    hasSelectedFiles = selectedFiles.isNotEmpty(),
                    onAttachFiles = { showFilePickerDialog = true },
                    onSend = {
                        if (userInput.isNotBlank()) {
                            viewModel.sendMessage(userInput)
                            userInput = ""
                        }
                    },
                    cachingEnabled = cachingEnabled,
                    onToggleCaching = { viewModel.toggleCaching() }
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            if (messages.isEmpty()) {
                EmptyState(
                    onBrowseRepository = { viewModel.loadRepositoryStructure() }
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(messages, key = { it.id }) { message ->
                        MessageBubble(message = message)
                    }
                    
                    if (isStreaming) {
                        item {
                            StreamingIndicator()
                        }
                    }
                }
            }
        }
    }
    
    // ✅ НОВОЕ: Model Selector Dialog
    if (showModelSelector) {
        ModelSelectorDialog(
            selectedModel = selectedModel,
            onSelectModel = {
                viewModel.selectModel(it)
                showModelSelector = false
            },
            onDismiss = { showModelSelector = false }
        )
    }
    
    // ✅ НОВОЕ: Session Stats Dialog
    if (showSessionStats) {
        SessionStatsDialog(
            session = currentSession,
            onDismiss = { showSessionStats = false }
        )
    }
    
    // File Picker Dialog
    if (showFilePickerDialog) {
        FilePickerDialog(
            repositoryStructure = repositoryStructure,
            selectedFiles = selectedFiles,
            onSelectFiles = { viewModel.selectFiles(it) },
            onDismiss = { showFilePickerDialog = false },
            onLoadStructure = { path -> viewModel.loadRepositoryStructure(path) }
        )
    }
    
    // Error Snackbar
    chatError?.let { error ->
        LaunchedEffect(error) {
            // Показываем ошибку
            kotlinx.coroutines.delay(3000)
            viewModel.dismissError()
        }
        
        Snackbar(
            modifier = Modifier.padding(16.dp),
            action = {
                TextButton(onClick = { viewModel.dismissError() }) {
                    Text("OK")
                }
            }
        ) {
            Text(error)
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// ✅ НОВОЕ: SESSION INFO BAR
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun SessionInfoBar(
    session: ClaudeModelConfig.ChatSession,
    isApproachingLongContext: Boolean,
    isLongContext: Boolean,
    onNewSession: () -> Unit
) {
    val backgroundColor = when {
        isLongContext -> MaterialTheme.colorScheme.errorContainer
        isApproachingLongContext -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    
    val contentColor = when {
        isLongContext -> MaterialTheme.colorScheme.onErrorContainer
        isApproachingLongContext -> MaterialTheme.colorScheme.onTertiaryContainer
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = backgroundColor,
            contentColor = contentColor
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Session: ${session.startTimeFormatted}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    
                    if (session.cacheHitRate > 0) {
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                "💾 ${String.format("%.0f", session.cacheHitRate)}%",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
                
                Spacer(Modifier.height(4.dp))
                
                Text(
                    "${session.messageCount} msg • ${session.durationFormatted} • " +
                            "€${String.format("%.4f", session.currentCost.totalCostEUR)}",
                    style = MaterialTheme.typography.labelSmall
                )
                
                if (isApproachingLongContext || isLongContext) {
                    Spacer(Modifier.height(4.dp))
                    val percentage = ((session.totalInputTokens.toDouble() / 
                        session.model.longContextThreshold) * 100).toInt()
                    
                    Text(
                        if (isLongContext) {
                            "⚠️ LONG CONTEXT ACTIVE (${percentage}%)"
                        } else {
                            "⚠️ Approaching long context (${percentage}%)"
                        },
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            
            Button(
                onClick = onNewSession,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isLongContext || isApproachingLongContext)
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(Icons.Default.Refresh, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("New")
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// ✅ НОВОЕ: LONG CONTEXT WARNING
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun LongContextWarning(
    isLongContext: Boolean,
    onStartNewSession: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isLongContext)
                MaterialTheme.colorScheme.errorContainer
            else
                MaterialTheme.colorScheme.tertiaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (isLongContext) Icons.Default.Warning else Icons.Default.Info,
                    contentDescription = null,
                    tint = if (isLongContext)
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.tertiary
                )
                
                Spacer(Modifier.width(8.dp))
                
                Text(
                    if (isLongContext) "LONG CONTEXT ACTIVE" else "APPROACHING LONG CONTEXT",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            
            Spacer(Modifier.height(8.dp))
            
            Text(
                if (isLongContext) {
                    "All prices are now DOUBLED for this entire session. " +
                    "Starting a new session will reset to normal pricing."
                } else {
                    "You're at 80%+ of the 200K token threshold. " +
                    "Next message may trigger long context pricing (2x cost)."
                },
                style = MaterialTheme.typography.bodyMedium
            )
            
            Spacer(Modifier.height(12.dp))
            
            Button(
                onClick = onStartNewSession,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isLongContext)
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.tertiary
                )
            ) {
                Icon(Icons.Default.Refresh, null)
                Spacer(Modifier.width(8.dp))
                Text("START NEW SESSION (RECOMMENDED)")
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// ✅ НОВОЕ: MODEL SELECTOR DIALOG
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun ModelSelectorDialog(
    selectedModel: ClaudeModelConfig.ClaudeModel,
    onSelectModel: (ClaudeModelConfig.ClaudeModel) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Claude Model") },
        text = {
            Column(
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ClaudeModelConfig.ClaudeModel.entries.forEach { model ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelectModel(model) },
                        colors = CardDefaults.cardColors(
                            containerColor = if (model == selectedModel)
                                MaterialTheme.colorScheme.primaryContainer
                            else
                                MaterialTheme.colorScheme.surface
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = model == selectedModel,
                                onClick = { onSelectModel(model) }
                            )
                            
                            Spacer(Modifier.width(12.dp))
                            
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        model.emoji,
                                        style = MaterialTheme.typography.titleLarge
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Text(
                                        model.displayName,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                
                                Spacer(Modifier.height(4.dp))
                                
                                Text(
                                    model.description,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                
                                Spacer(Modifier.height(4.dp))
                                
                                Text(
                                    "$${model.inputPricePerM}/$${model.outputPricePerM} per 1M tokens",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                
                                Text(
                                    "Cache: $${model.cachedInputPricePerM}/1M (90% savings)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                            }
                        }
                    }
                }
                
                Spacer(Modifier.height(8.dp))
                
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(
                            "ℹ️ Note",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "Changing model will start a new session. " +
                            "Current session will be ended.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

// ═══════════════════════════════════════════════════════════════════════════
// ✅ НОВОЕ: SESSION STATS DIALOG
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun SessionStatsDialog(
    session: ClaudeModelConfig.ChatSession?,
    onDismiss: () -> Unit
) {
    if (session == null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("No Active Session") },
            text = { Text("No session data available.") },
            confirmButton = {
                TextButton(onClick = onDismiss) {
                    Text("OK")
                }
            }
        )
        return
    }
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Session Statistics") },
        text = {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                item {
                    StatRow("Model", "${session.model.emoji} ${session.model.displayName}")
                }
                
                item { Divider() }
                
                item {
                    StatRow("Session ID", session.sessionId.take(16) + "...")
                }
                
                item {
                    StatRow("Started", session.startTimeFormatted)
                }
                
                item {
                    StatRow("Duration", session.durationFormatted)
                }
                
                item { Divider() }
                
                item {
                    StatRow("Messages", session.messageCount.toString())
                }
                
                item {
                    StatRow(
                        "Total Tokens",
                        "%,d".format(session.totalInputTokens + session.totalOutputTokens)
                    )
                }
                
                item {
                    StatRow("Input Tokens", "%,d".format(session.totalInputTokens))
                }
                
                item {
                    StatRow("Output Tokens", "%,d".format(session.totalOutputTokens))
                }
                
                item {
                    StatRow("Cached Tokens", "%,d".format(session.totalCachedInputTokens))
                }
                
                item { Divider() }
                
                item {
                    StatRow("Cache Hit Rate", "${String.format("%.1f", session.cacheHitRate)}%")
                }
                
                item {
                    StatRow("Avg Tokens/Msg", "%,d".format(session.averageTokensPerMessage))
                }
                
                item {
                    StatRow("Avg Cost/Msg", "€${String.format("%.4f", session.averageCostPerMessage)}")
                }
                
                item { Divider() }
                
                item {
                    StatRow(
                        "Total Cost",
                        "€${String.format("%.4f", session.currentCost.totalCostEUR)}",
                        highlight = true
                    )
                }
                
                if (session.currentCost.savingsPercentage > 0) {
                    item {
                        StatRow(
                            "Cache Savings",
                            "${String.format("%.0f", session.currentCost.savingsPercentage)}% " +
                                    "(€${String.format("%.4f", session.currentCost.cacheSavingsEUR)})",
                            valueColor = MaterialTheme.colorScheme.tertiary
                        )
                    }
                }
                
                if (session.isLongContext) {
                    item { Divider() }
                    
                    item {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            )
                        ) {
                            Text(
                                "⚠️ LONG CONTEXT ACTIVE",
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
private fun StatRow(
    label: String,
    value: String,
    highlight: Boolean = false,
    valueColor: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Text(
            value,
            style = if (highlight) 
                MaterialTheme.typography.titleMedium
            else 
                MaterialTheme.typography.bodyMedium,
            fontWeight = if (highlight) FontWeight.Bold else FontWeight.Normal,
            color = valueColor
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// SELECTED FILES BAR (обновлено)
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun SelectedFilesBar(
    selectedFiles: Set<String>,
    scanEstimate: RepositoryAnalyzer.ScanEstimate?,
    onClearFiles: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (scanEstimate?.willTriggerLongContext == true)
                MaterialTheme.colorScheme.errorContainer
            else if (scanEstimate?.isApproachingLongContext == true)
                MaterialTheme.colorScheme.tertiaryContainer
            else
                MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "📎 ${selectedFiles.size} files selected",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                    
                    scanEstimate?.let { estimate ->
                        Spacer(Modifier.height(4.dp))
                        
                        Text(
                            "Est: ${estimate.cost.totalTokens.toString().replace(Regex("(\\d)(?=(\\d{3})+$)"), "$1,")} tokens • " +
                                    "€${String.format("%.4f", estimate.cost.totalCostEUR)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        
                        if (estimate.cost.savingsPercentage > 0) {
                            Text(
                                "💾 Cache savings: ${String.format("%.0f", estimate.cost.savingsPercentage)}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                        
                        if (estimate.willTriggerLongContext) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "⚠️ WILL TRIGGER LONG CONTEXT!",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error
                            )
                        } else if (estimate.isApproachingLongContext) {
                            Spacer(Modifier.height(4.dp))
                            Text(
                                "⚠️ Approaching long context",
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.tertiary
                            )
                        }
                    }
                }
                
                IconButton(onClick = onClearFiles) {
                    Icon(Icons.Default.Close, "Clear files")
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// INPUT BAR (обновлено с кнопкой кеширования)
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun InputBar(
    userInput: String,
    onUserInputChange: (String) -> Unit,
    isStreaming: Boolean,
    hasSelectedFiles: Boolean,
    onAttachFiles: () -> Unit,
    onSend: () -> Unit,
    cachingEnabled: Boolean,
    onToggleCaching: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // ✅ НОВОЕ: Cache toggle
            IconButton(
                onClick = onToggleCaching,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    if (cachingEnabled) Icons.Default.Check else Icons.Default.Close,
                    contentDescription = "Toggle Caching",
                    tint = if (cachingEnabled)
                        MaterialTheme.colorScheme.tertiary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            IconButton(
                onClick = onAttachFiles,
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    Icons.Default.AttachFile,
                    contentDescription = "Attach Files",
                    tint = if (hasSelectedFiles)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            OutlinedTextField(
                value = userInput,
                onValueChange = onUserInputChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask Claude...") },
                enabled = !isStreaming,
                maxLines = 4
            )
            
            IconButton(
                onClick = onSend,
                enabled = !isStreaming && userInput.isNotBlank(),
                modifier = Modifier.size(40.dp)
            ) {
                Icon(
                    Icons.Default.Send,
                    contentDescription = "Send",
                    tint = if (isStreaming || userInput.isBlank())
                        MaterialTheme.colorScheme.onSurfaceVariant
                    else
                        MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// MESSAGE BUBBLE (✅ ИСПРАВЛЕНО)
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun MessageBubble(message: ChatMessageEntity) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.role == MessageRole.USER)
            Arrangement.End
        else
            Arrangement.Start
    ) {
        Card(
            modifier = Modifier.widthIn(max = 300.dp),
            colors = CardDefaults.cardColors(
                containerColor = when (message.role) {
                    MessageRole.USER -> MaterialTheme.colorScheme.primaryContainer
                    MessageRole.ASSISTANT -> MaterialTheme.colorScheme.secondaryContainer
                    MessageRole.SYSTEM -> MaterialTheme.colorScheme.tertiaryContainer
                }
            )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    message.content,
                    style = MaterialTheme.typography.bodyMedium
                )
                
                // ✅ ИСПРАВЛЕНО: добавлена проверка на null
                if (message.tokensUsed != null && message.tokensUsed > 0) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${message.tokensUsed} tokens",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun StreamingIndicator() {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp))
        Text(
            "Claude is thinking...",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun EmptyState(
    onBrowseRepository: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Default.Forum,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        
        Spacer(Modifier.height(16.dp))
        
        Text(
            "Welcome to Repository Analyzer",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        
        Spacer(Modifier.height(8.dp))
        
        Text(
            "Ask Claude anything about your repository",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        
        Spacer(Modifier.height(24.dp))
        
        Button(onClick = onBrowseRepository) {
            Icon(Icons.Default.Folder, null)
            Spacer(Modifier.width(8.dp))
            Text("Browse Repository")
        }
    }
}

@Composable
private fun FilePickerDialog(
    repositoryStructure: RepositoryAnalyzer.RepositoryStructure?,
    selectedFiles: Set<String>,
    onSelectFiles: (Set<String>) -> Unit,
    onDismiss: () -> Unit,
    onLoadStructure: (String) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Files") },
        text = {
            if (repositoryStructure == null) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(16.dp))
                    Text("Loading repository...")
                }
            } else {
                LazyColumn {
                    items(repositoryStructure.files) { file ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val newSelection = if (selectedFiles.contains(file.path)) {
                                        selectedFiles - file.path
                                    } else {
                                        selectedFiles + file.path
                                    }
                                    onSelectFiles(newSelection)
                                }
                                .padding(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = selectedFiles.contains(file.path),
                                onCheckedChange = null
                            )
                            
                            Spacer(Modifier.width(8.dp))
                            
                            Column {
                                Text(
                                    file.name,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Text(
                                    "${file.size / 1024} KB",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Done")
            }
        },
        dismissButton = {
            TextButton(
                onClick = {
                    onSelectFiles(emptySet())
                    onDismiss()
                }
            ) {
                Text("Clear All")
            }
        }
    )
}