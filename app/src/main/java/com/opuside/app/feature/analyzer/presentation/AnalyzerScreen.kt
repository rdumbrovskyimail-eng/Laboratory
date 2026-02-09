package com.opuside.app.feature.analyzer.presentation

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.opuside.app.core.ai.ClaudeModelConfig
import com.opuside.app.core.database.entity.ChatMessageEntity
import com.opuside.app.core.database.entity.MessageRole

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyzerScreen(
    viewModel: AnalyzerViewModel = hiltViewModel()
) {
    val messages by viewModel.messages.collectAsState(initial = emptyList())
    val isStreaming by viewModel.isStreaming.collectAsState()
    val chatError by viewModel.chatError.collectAsState()
    val selectedModel by viewModel.selectedModel.collectAsState()
    val currentSession by viewModel.currentSession.collectAsState()
    val cachingEnabled by viewModel.cachingEnabled.collectAsState()
    val operationsLog by viewModel.operationsLog.collectAsState()
    val autoHaikuEnabled by viewModel.autoHaikuEnabled.collectAsState()
    val sessionTokens by viewModel.sessionTokens.collectAsState()
    
    var userInput by remember { mutableStateOf("") }
    var showModelDialog by remember { mutableStateOf(false) }
    var showEconomySheet by remember { mutableStateOf(false) }
    var showSessionStats by remember { mutableStateOf(false) }
    
    val chatListState = rememberLazyListState()
    val opsListState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    
    LaunchedEffect(messages.size) {
        if (messages.isNotEmpty()) {
            chatListState.animateScrollToItem(messages.size - 1)
        }
    }
    
    LaunchedEffect(operationsLog.size) {
        if (operationsLog.isNotEmpty()) {
            opsListState.animateScrollToItem(operationsLog.size - 1)
        }
    }
    
    val terminalBg = Color(0xFF0D1117)
    val terminalSurface = Color(0xFF161B22)
    val terminalBorder = Color(0xFF30363D)
    val accentGreen = Color(0xFF3FB950)
    val accentBlue = Color(0xFF58A6FF)
    val accentYellow = Color(0xFFD29922)
    val accentRed = Color(0xFFF85149)
    val textPrimary = Color(0xFFE6EDF3)
    val textSecondary = Color(0xFF8B949E)
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            "Analyzer",
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${selectedModel.emoji} ${selectedModel.displayName}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (sessionTokens != null) {
                                Text(
                                    " • ${"%,d".format(sessionTokens!!.totalTokens)} tok • €${String.format("%.3f", sessionTokens!!.totalCostEUR)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                },
                actions = {
                    IconButton(onClick = { showEconomySheet = true }) {
                        Icon(Icons.Default.Savings, "Economy", tint = accentGreen)
                    }
                    IconButton(onClick = { showModelDialog = true }) {
                        Icon(Icons.Default.Psychology, "Model")
                    }
                    IconButton(onClick = { showSessionStats = true }) {
                        Icon(Icons.Default.Analytics, "Stats")
                    }
                    IconButton(onClick = { viewModel.startNewSession() }) {
                        Icon(Icons.Default.RestartAlt, "New Session")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = terminalBg,
                    titleContentColor = textPrimary,
                    actionIconContentColor = textSecondary
                )
            )
        },
        containerColor = terminalBg
    ) { padding ->
        
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.28f)
                    .background(terminalSurface)
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF1C2128))
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("⚙️", fontSize = 14.sp)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "OPERATIONS LOG",
                                color = textSecondary,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (cachingEnabled) accentGreen else accentRed)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "Cache ${if (cachingEnabled) "ON" else "OFF"}",
                                color = textSecondary,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Spacer(Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .clip(CircleShape)
                                    .background(if (autoHaikuEnabled) accentBlue else accentRed)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "Haiku ${if (autoHaikuEnabled) "ON" else "OFF"}",
                                color = textSecondary,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace
                            )
                            Spacer(Modifier.width(8.dp))
                            IconButton(
                                onClick = { viewModel.clearOperationsLog() },
                                modifier = Modifier.size(20.dp)
                            ) {
                                Icon(
                                    Icons.Default.DeleteSweep,
                                    contentDescription = "Clear log",
                                    tint = textSecondary,
                                    modifier = Modifier.size(14.dp)
                                )
                            }
                        }
                    }
                    
                    if (operationsLog.isEmpty()) {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "Операции будут отображаться здесь...",
                                color = textSecondary,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    } else {
                        LazyColumn(
                            state = opsListState,
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            items(operationsLog) { item ->
                                OperationLogRow(item, textPrimary, textSecondary, accentGreen, accentRed, accentYellow)
                            }
                        }
                    }
                }
            }
            
            HorizontalDivider(
                color = terminalBorder,
                thickness = 2.dp
            )
            
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(0.72f)
            ) {
                AnimatedVisibility(visible = chatError != null) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        color = Color(0xFF3D1F1F),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("❌", fontSize = 16.sp)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                chatError ?: "",
                                color = accentRed,
                                fontSize = 13.sp,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(
                                onClick = { viewModel.dismissError() },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(Icons.Default.Close, "Dismiss", tint = textSecondary, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
                
                LazyColumn(
                    state = chatListState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    items(messages) { message ->
                        ChatMessageBubble(
                            message = message,
                            terminalBg = terminalBg,
                            terminalSurface = terminalSurface,
                            textPrimary = textPrimary,
                            textSecondary = textSecondary,
                            accentBlue = accentBlue,
                            accentGreen = accentGreen
                        )
                    }
                    
                    if (isStreaming) {
                        item {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(16.dp),
                                    color = accentBlue,
                                    strokeWidth = 2.dp
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    "Claude пишет...",
                                    color = textSecondary,
                                    fontSize = 13.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
                
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Color(0xFF1C2128),
                    tonalElevation = 2.dp
                ) {
                    Row(
                        modifier = Modifier
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = userInput,
                            onValueChange = { userInput = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { 
                                Text(
                                    "Сообщение для Claude...",
                                    color = textSecondary,
                                    fontSize = 14.sp
                                ) 
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = accentBlue,
                                unfocusedBorderColor = terminalBorder,
                                cursorColor = accentBlue,
                                focusedTextColor = textPrimary,
                                unfocusedTextColor = textPrimary
                            ),
                            maxLines = 5,
                            textStyle = LocalTextStyle.current.copy(
                                fontSize = 14.sp,
                                fontFamily = FontFamily.Monospace
                            ),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(
                                onSend = {
                                    if (userInput.isNotBlank() && !isStreaming) {
                                        viewModel.sendMessage(userInput.trim())
                                        userInput = ""
                                        focusManager.clearFocus()
                                    }
                                }
                            ),
                            shape = RoundedCornerShape(12.dp)
                        )
                        
                        Spacer(Modifier.width(8.dp))
                        
                        FilledIconButton(
                            onClick = {
                                if (userInput.isNotBlank() && !isStreaming) {
                                    viewModel.sendMessage(userInput.trim())
                                    userInput = ""
                                    focusManager.clearFocus()
                                }
                            },
                            enabled = userInput.isNotBlank() && !isStreaming,
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = accentBlue,
                                contentColor = Color.White,
                                disabledContainerColor = terminalBorder,
                                disabledContentColor = textSecondary
                            ),
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                if (isStreaming) Icons.Default.HourglassTop else Icons.Default.Send,
                                contentDescription = "Send"
                            )
                        }
                    }
                }
            }
        }
    }
    
    if (showModelDialog) {
        AlertDialog(
            onDismissRequest = { showModelDialog = false },
            title = { Text("Выбрать модель") },
            text = {
                Column {
                    ClaudeModelConfig.ClaudeModel.entries.forEach { model ->
                        val isSelected = model == selectedModel
                        Surface(
                            onClick = {
                                viewModel.selectModel(model)
                                showModelDialog = false
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                    else MaterialTheme.colorScheme.surface,
                            tonalElevation = if (isSelected) 4.dp else 0.dp
                        ) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(model.emoji, fontSize = 20.sp)
                                Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(
                                        model.displayName,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 15.sp
                                    )
                                    Text(
                                        model.description,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        "$${model.inputPricePerM}/$${model.outputPricePerM} per 1M tokens",
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (isSelected) {
                                    Icon(Icons.Default.CheckCircle, "Selected", tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showModelDialog = false }) {
                    Text("Закрыть")
                }
            }
        )
    }
    
    if (showEconomySheet) {
        AlertDialog(
            onDismissRequest = { showEconomySheet = false },
            title = { Text("💰 Экономия API") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("📦 Prompt Caching", fontWeight = FontWeight.Bold)
                            Text(
                                "90% экономия на повторных запросах",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = cachingEnabled,
                            onCheckedChange = { viewModel.toggleCaching() }
                        )
                    }
                    
                    HorizontalDivider()
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("💨 Auto-Haiku", fontWeight = FontWeight.Bold)
                            Text(
                                "Простые команды (дерево, чтение) → Haiku ($0.80/1M вместо $5/1M)",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = autoHaikuEnabled,
                            onCheckedChange = { viewModel.toggleAutoHaiku() }
                        )
                    }
                    
                    HorizontalDivider()
                    
                    sessionTokens?.let { cost ->
                        if (cost.savingsPercentage > 0) {
                            Surface(
                                color = Color(0xFF1A3D1A),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Column(Modifier.padding(12.dp)) {
                                    Text("✨ Экономия в сеансе", fontWeight = FontWeight.Bold, color = Color(0xFF3FB950))
                                    Text(
                                        "Сохранено: €${String.format("%.4f", cost.cacheSavingsEUR)} (${String.format("%.0f", cost.savingsPercentage)}%)",
                                        fontSize = 13.sp,
                                        color = Color(0xFF3FB950)
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showEconomySheet = false }) {
                    Text("Закрыть")
                }
            }
        )
    }
    
    if (showSessionStats) {
        AlertDialog(
            onDismissRequest = { showSessionStats = false },
            title = { Text("📊 Статистика сеанса") },
            text = {
                val stats = viewModel.getSessionStats()
                if (stats != null) {
                    Text(
                        stats,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 18.sp
                    )
                } else {
                    Text("Нет активного сеанса")
                }
            },
            confirmButton = {
                TextButton(onClick = { showSessionStats = false }) {
                    Text("Закрыть")
                }
            }
        )
    }
}

@Composable
private fun OperationLogRow(
    item: AnalyzerViewModel.OperationLogItem,
    textPrimary: Color,
    textSecondary: Color,
    accentGreen: Color,
    accentRed: Color,
    accentYellow: Color
) {
    val textColor = when (item.type) {
        AnalyzerViewModel.OperationLogType.SUCCESS -> accentGreen
        AnalyzerViewModel.OperationLogType.ERROR -> accentRed
        AnalyzerViewModel.OperationLogType.PROGRESS -> accentYellow
        AnalyzerViewModel.OperationLogType.INFO -> textSecondary
    }
    
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            item.icon,
            fontSize = 12.sp,
            modifier = Modifier.width(20.dp)
        )
        Text(
            item.message,
            color = textColor,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Text(
            java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault())
                .format(java.util.Date(item.timestamp)),
            color = textSecondary.copy(alpha = 0.5f),
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun ChatMessageBubble(
    message: ChatMessageEntity,
    terminalBg: Color,
    terminalSurface: Color,
    textPrimary: Color,
    textSecondary: Color,
    accentBlue: Color,
    accentGreen: Color
) {
    val isUser = message.role == MessageRole.USER
    val isSystem = message.role == MessageRole.SYSTEM
    
    val bubbleColor = when {
        isUser -> Color(0xFF1A2332)
        isSystem -> Color(0xFF1A2E1A)
        else -> terminalSurface
    }
    
    val contentColor = when {
        isSystem -> accentGreen
        else -> textPrimary
    }
    
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Text(
            when {
                isUser -> "👤 You"
                isSystem -> "⚙️ System"
                else -> "🤖 Claude"
            },
            color = textSecondary,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
        
        Surface(
            modifier = Modifier
                .fillMaxWidth(if (isUser) 0.85f else 0.95f),
            color = bubbleColor,
            shape = RoundedCornerShape(
                topStart = if (isUser) 12.dp else 4.dp,
                topEnd = if (isUser) 4.dp else 12.dp,
                bottomStart = 12.dp,
                bottomEnd = 12.dp
            )
        ) {
            Text(
                message.content,
                color = contentColor,
                fontSize = 13.sp,
                fontFamily = FontFamily.Monospace,
                lineHeight = 19.sp,
                modifier = Modifier.padding(12.dp)
            )
        }
    }
}