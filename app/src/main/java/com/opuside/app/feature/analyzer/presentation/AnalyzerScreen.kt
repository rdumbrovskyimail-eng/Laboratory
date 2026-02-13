package com.opuside.app.ui.analyzer

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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.opuside.app.core.ai.ClaudeModelConfig
import com.opuside.app.core.database.entity.ChatMessageEntity
import com.opuside.app.core.database.entity.MessageRole
import kotlinx.coroutines.launch

/**
 * 🤖 ANALYZER SCREEN v12.0 (CACHE + FIRST MESSAGE CACHING + HISTORY LOCK)
 *
 * ✅ UI ИЗМЕНЕНИЯ:
 * 1. Кнопка History заблокирована в Cache Mode
 * 2. Визуальная индикация блокировки
 * 3. Cache таймер и статистика
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyzerScreen(
    viewModel: AnalyzerViewModel = hiltViewModel()
) {
    val selectedModel by viewModel.selectedModel.collectAsState()
    val ecoOutputMode by viewModel.ecoOutputMode.collectAsState()
    val conversationHistoryEnabled by viewModel.conversationHistoryEnabled.collectAsState()
    val cacheModeEnabled by viewModel.cacheModeEnabled.collectAsState()
    val cacheIsWarmed by viewModel.cacheIsWarmed.collectAsState()
    val cacheTimerMs by viewModel.cacheTimerMs.collectAsState()
    val cacheTotalReadTokens by viewModel.cacheTotalReadTokens.collectAsState()
    val cacheTotalWriteTokens by viewModel.cacheTotalWriteTokens.collectAsState()
    val cacheTotalSavingsEUR by viewModel.cacheTotalSavingsEUR.collectAsState()
    val cacheHitCount by viewModel.cacheHitCount.collectAsState()
    val selectedFiles by viewModel.selectedFiles.collectAsState()
    val isStreaming by viewModel.isStreaming.collectAsState()
    val streamingText by viewModel.streamingText.collectAsState()
    val chatError by viewModel.chatError.collectAsState()
    val currentSession by viewModel.currentSession.collectAsState()
    val operationsLog by viewModel.operationsLog.collectAsState()

    var showModelPicker by remember { mutableStateOf(false) }
    var showOperationsLog by remember { mutableStateOf(false) }
    var messageText by remember { mutableStateOf("") }

    val colors = MaterialTheme.colorScheme
    val ac = colors.primary
    val bg = colors.background
    val sf = colors.surface
    val t1 = colors.onSurface
    val t2 = colors.onSurfaceVariant
    val err = colors.error

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Column {
                        Text("AI Analyzer", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        Text(
                            "${selectedModel.emoji} ${selectedModel.displayName}",
                            fontSize = 12.sp,
                            color = t2
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = sf),
                actions = {
                    // ═══════════════════════════════════════════════════
                    // CONVERSATION HISTORY BUTTON - ✅ ЗАБЛОКИРОВАНА В CACHE MODE
                    // ═══════════════════════════════════════════════════
                    IconButton(
                        onClick = { viewModel.toggleConversationHistory() },
                        enabled = !cacheModeEnabled  // ✅ НОВОЕ: Заблокирована в Cache Mode
                    ) {
                        Icon(
                            Icons.Default.History,
                            "History",
                            tint = if (conversationHistoryEnabled) {
                                ac
                            } else if (cacheModeEnabled) {
                                t2.copy(alpha = 0.3f)  // ✅ Затемнена когда заблокирована
                            } else {
                                t2
                            }
                        )
                    }

                    // ═══════════════════════════════════════════════════
                    // ECO MODE BUTTON
                    // ═══════════════════════════════════════════════════
                    IconButton(
                        onClick = { viewModel.toggleEcoOutputMode() },
                        enabled = !cacheModeEnabled
                    ) {
                        Icon(
                            Icons.Default.Savings,
                            "Eco Mode",
                            tint = if (ecoOutputMode) {
                                Color(0xFF4CAF50)
                            } else if (cacheModeEnabled) {
                                t2.copy(alpha = 0.3f)
                            } else {
                                t2
                            }
                        )
                    }

                    // ═══════════════════════════════════════════════════
                    // CACHE MODE BUTTON
                    // ═══════════════════════════════════════════════════
                    IconButton(onClick = { viewModel.toggleCacheMode() }) {
                        Icon(
                            Icons.Default.Cached,
                            "Cache Mode",
                            tint = if (cacheModeEnabled) Color(0xFFFF9800) else t2
                        )
                    }

                    // ═══════════════════════════════════════════════════
                    // OPERATIONS LOG BUTTON
                    // ═══════════════════════════════════════════════════
                    IconButton(onClick = { showOperationsLog = !showOperationsLog }) {
                        Badge(
                            containerColor = if (operationsLog.isNotEmpty()) ac else Color.Transparent
                        ) {
                            Text(
                                operationsLog.size.toString(),
                                fontSize = 10.sp,
                                color = Color.White
                            )
                        }
                        Icon(Icons.Default.List, "Operations")
                    }

                    // ═══════════════════════════════════════════════════
                    // MODEL PICKER BUTTON
                    // ═══════════════════════════════════════════════════
                    IconButton(onClick = { showModelPicker = true }) {
                        Icon(Icons.Default.Settings, "Settings")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(bg)
        ) {
            // ═══════════════════════════════════════════════════════════
            // CACHE STATUS BANNER
            // ═══════════════════════════════════════════════════════════
            if (cacheModeEnabled) {
                CacheStatusBanner(
                    isWarmed = cacheIsWarmed,
                    timerMs = cacheTimerMs,
                    totalReadTokens = cacheTotalReadTokens,
                    totalWriteTokens = cacheTotalWriteTokens,
                    totalSavingsEUR = cacheTotalSavingsEUR,
                    hitCount = cacheHitCount,
                    colors = colors
                )
            }

            // ═══════════════════════════════════════════════════════════
            // SESSION STATS
            // ═══════════════════════════════════════════════════════════
            currentSession?.let { session ->
                SessionStatsCard(session = session, colors = colors)
            }

            // ═══════════════════════════════════════════════════════════
            // OPERATIONS LOG (COLLAPSIBLE)
            // ═══════════════════════════════════════════════════════════
            if (showOperationsLog && operationsLog.isNotEmpty()) {
                OperationsLogPanel(
                    operations = operationsLog,
                    onClear = { viewModel.clearOperationsLog() },
                    onDismiss = { showOperationsLog = false },
                    colors = colors
                )
            }

            // ═══════════════════════════════════════════════════════════
            // CHAT AREA
            // ═══════════════════════════════════════════════════════════
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                ChatArea(
                    sessionId = viewModel.sessionId,
                    streamingText = streamingText,
                    colors = colors
                )
            }

            // ═══════════════════════════════════════════════════════════
            // ERROR BANNER
            // ═══════════════════════════════════════════════════════════
            chatError?.let { error ->
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = err.copy(alpha = 0.1f)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Error, "Error", tint = err)
                        Spacer(Modifier.width(8.dp))
                        Text(error, color = err, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        IconButton(onClick = { viewModel.clearError() }) {
                            Icon(Icons.Default.Close, "Dismiss", tint = err)
                        }
                    }
                }
            }

            // ═══════════════════════════════════════════════════════════
            // INPUT AREA
            // ═══════════════════════════════════════════════════════════
            InputArea(
                messageText = messageText,
                onMessageTextChange = { messageText = it },
                onSendClick = {
                    if (messageText.isNotBlank()) {
                        viewModel.sendMessage(messageText)
                        messageText = ""
                    }
                },
                onStopClick = { viewModel.stopStreaming() },
                isStreaming = isStreaming,
                colors = colors
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════════
    // MODEL PICKER DIALOG
    // ═══════════════════════════════════════════════════════════════════
    if (showModelPicker) {
        ModelPickerDialog(
            currentModel = selectedModel,
            onModelSelected = { 
                viewModel.selectModel(it)
                showModelPicker = false
            },
            onDismiss = { showModelPicker = false }
        )
    }
}

/**
 * ✅ CACHE STATUS BANNER
 */
@Composable
fun CacheStatusBanner(
    isWarmed: Boolean,
    timerMs: Long,
    totalReadTokens: Int,
    totalWriteTokens: Int,
    totalSavingsEUR: Double,
    hitCount: Int,
    colors: ColorScheme
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = if (isWarmed) Color(0xFF4CAF50).copy(alpha = 0.1f) else Color(0xFFFF9800).copy(alpha = 0.1f)
    ) {
        Column(
            modifier = Modifier.padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Cached,
                        "Cache",
                        tint = if (isWarmed) Color(0xFF4CAF50) else Color(0xFFFF9800),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        if (isWarmed) "Cache Active" else "Cache Warming...",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.onSurface
                    )
                }

                if (isWarmed && timerMs > 0) {
                    val minutes = (timerMs / 1000 / 60).toInt()
                    val seconds = ((timerMs / 1000) % 60).toInt()
                    Text(
                        "⏰ ${minutes}:${seconds.toString().padStart(2, '0')}",
                        fontSize = 12.sp,
                        color = colors.onSurfaceVariant
                    )
                }
            }

            if (totalReadTokens > 0 || totalWriteTokens > 0) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    CacheStatChip(
                        label = "Write",
                        value = "%,d".format(totalWriteTokens),
                        icon = "📝",
                        colors = colors
                    )
                    CacheStatChip(
                        label = "Read",
                        value = "%,d".format(totalReadTokens),
                        icon = "⚡",
                        colors = colors
                    )
                    CacheStatChip(
                        label = "Hits",
                        value = hitCount.toString(),
                        icon = "🎯",
                        colors = colors
                    )
                    CacheStatChip(
                        label = "Saved",
                        value = "€${String.format("%.4f", totalSavingsEUR)}",
                        icon = "💰",
                        colors = colors
                    )
                }
            }
        }
    }
}

@Composable
fun CacheStatChip(
    label: String,
    value: String,
    icon: String,
    colors: ColorScheme
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            "$icon $value",
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = colors.onSurface
        )
        Text(
            label,
            fontSize = 10.sp,
            color = colors.onSurfaceVariant
        )
    }
}

/**
 * ✅ SESSION STATS CARD
 */
@Composable
fun SessionStatsCard(
    session: ClaudeModelConfig.ChatSession,
    colors: ColorScheme
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        color = colors.surfaceVariant.copy(alpha = 0.5f),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    "Session Stats",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurface
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${session.messageCount} msg • ${"%,d".format(session.totalInputTokens + session.totalOutputTokens)} tok",
                    fontSize = 11.sp,
                    color = colors.onSurfaceVariant
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    "€${String.format("%.4f", session.currentCost.totalCostEUR)}",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.primary
                )
                if (session.currentCost.savingsPercentage > 0) {
                    Text(
                        "↓ ${String.format("%.1f", session.currentCost.savingsPercentage)}%",
                        fontSize = 10.sp,
                        color = Color(0xFF4CAF50)
                    )
                }
            }
        }
    }
}

/**
 * ✅ OPERATIONS LOG PANEL
 */
@Composable
fun OperationsLogPanel(
    operations: List<AnalyzerViewModel.OperationLogEntry>,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
    colors: ColorScheme
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .height(200.dp),
        color = colors.surfaceVariant.copy(alpha = 0.3f)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "Operations Log (${operations.size})",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.onSurface
                )
                Row {
                    IconButton(onClick = onClear) {
                        Icon(Icons.Default.Clear, "Clear", tint = colors.onSurfaceVariant)
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, "Close", tint = colors.onSurfaceVariant)
                    }
                }
            }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                reverseLayout = true
            ) {
                items(operations.reversed(), key = { it.id }) { op ->
                    OperationLogItem(operation = op, colors = colors)
                }
            }
        }
    }
}

@Composable
fun OperationLogItem(
    operation: AnalyzerViewModel.OperationLogEntry,
    colors: ColorScheme
) {
    val opColor = when (operation.type) {
        AnalyzerViewModel.OperationLogType.SUCCESS -> Color(0xFF4CAF50)
        AnalyzerViewModel.OperationLogType.ERROR -> colors.error
        AnalyzerViewModel.OperationLogType.PROGRESS -> Color(0xFF2196F3)
        AnalyzerViewModel.OperationLogType.INFO -> colors.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            operation.icon,
            fontSize = 16.sp,
            modifier = Modifier.width(24.dp)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            operation.message,
            fontSize = 12.sp,
            color = opColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

/**
 * ✅ CHAT AREA
 */
@Composable
fun ChatArea(
    sessionId: String,
    streamingText: String?,
    colors: ColorScheme,
    viewModel: AnalyzerViewModel = hiltViewModel()
) {
    val messages by remember(sessionId) {
        viewModel.chatDao.observeSession(sessionId)
    }.collectAsState(initial = emptyList())

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(messages.size, streamingText) {
        if (messages.isNotEmpty()) {
            coroutineScope.launch {
                listState.animateScrollToItem(messages.size)
            }
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .padding(8.dp),
        contentPadding = PaddingValues(bottom = 16.dp)
    ) {
        items(messages, key = { it.id }) { message ->
            ChatMessageItem(message = message, colors = colors)
        }

        streamingText?.let { text ->
            item {
                StreamingMessageItem(text = text, colors = colors)
            }
        }
    }
}

@Composable
fun ChatMessageItem(
    message: ChatMessageEntity,
    colors: ColorScheme
) {
    val isUser = message.role == MessageRole.USER
    val bgColor = if (isUser) colors.primaryContainer else colors.surfaceVariant
    val textColor = if (isUser) colors.onPrimaryContainer else colors.onSurface

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 320.dp),
            color = bgColor,
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(
                    if (isUser) "You" else "Assistant",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = textColor.copy(alpha = 0.7f)
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    message.content,
                    fontSize = 14.sp,
                    color = textColor
                )
            }
        }
    }
}

@Composable
fun StreamingMessageItem(
    text: String,
    colors: ColorScheme
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.Start
    ) {
        Surface(
            modifier = Modifier.widthIn(max = 320.dp),
            color = colors.surfaceVariant,
            shape = RoundedCornerShape(12.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Assistant",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = colors.onSurface.copy(alpha = 0.7f)
                    )
                    Spacer(Modifier.width(8.dp))
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 2.dp,
                        color = colors.primary
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text,
                    fontSize = 14.sp,
                    color = colors.onSurface
                )
            }
        }
    }
}

/**
 * ✅ INPUT AREA
 */
@Composable
fun InputArea(
    messageText: String,
    onMessageTextChange: (String) -> Unit,
    onSendClick: () -> Unit,
    onStopClick: () -> Unit,
    isStreaming: Boolean,
    colors: ColorScheme
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = colors.surface,
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = messageText,
                onValueChange = onMessageTextChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Ask me anything...") },
                enabled = !isStreaming,
                maxLines = 4,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = colors.primary,
                    unfocusedBorderColor = colors.onSurfaceVariant.copy(alpha = 0.3f)
                )
            )

            Spacer(Modifier.width(8.dp))

            if (isStreaming) {
                IconButton(
                    onClick = onStopClick,
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        Icons.Default.Stop,
                        "Stop",
                        tint = colors.error
                    )
                }
            } else {
                IconButton(
                    onClick = onSendClick,
                    enabled = messageText.isNotBlank(),
                    modifier = Modifier.size(48.dp)
                ) {
                    Icon(
                        Icons.Default.Send,
                        "Send",
                        tint = if (messageText.isNotBlank()) colors.primary else colors.onSurfaceVariant.copy(alpha = 0.3f)
                    )
                }
            }
        }
    }
}

/**
 * ✅ MODEL PICKER DIALOG
 */
@Composable
fun ModelPickerDialog(
    currentModel: ClaudeModelConfig.ClaudeModel,
    onModelSelected: (ClaudeModelConfig.ClaudeModel) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Model") },
        text = {
            LazyColumn {
                items(ClaudeModelConfig.ClaudeModel.entries) { model ->
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onModelSelected(model) }
                            .padding(vertical = 8.dp),
                        color = if (model == currentModel) {
                            MaterialTheme.colorScheme.primaryContainer
                        } else {
                            Color.Transparent
                        },
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    "${model.emoji} ${model.displayName}",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp
                                )
                                Text(
                                    "$${model.inputPricePerM}/$${model.outputPricePerM}",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Text(
                                model.description,
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                "Context: ${"%,d".format(model.contextWindow)} • Output: ${"%,d".format(model.maxOutputTokens)}",
                                fontSize = 11.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
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