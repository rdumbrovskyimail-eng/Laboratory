package com.opuside.app.feature.analyzer.presentation

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
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
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private object DarkColors {
    val bg = Color(0xFF0D1117); val surface = Color(0xFF161B22); val border = Color(0xFF30363D)
    val green = Color(0xFF3FB950); val blue = Color(0xFF58A6FF); val yellow = Color(0xFFD29922)
    val red = Color(0xFFF85149); val text1 = Color(0xFFE6EDF3); val text2 = Color(0xFF8B949E); val input = Color(0xFF1C2128)
}

private object LightColors {
    val bg = Color(0xFFF8FAFE); val surface = Color(0xFFFFFFFF); val border = Color(0xFFD0D7DE)
    val green = Color(0xFF1A7F37); val blue = Color(0xFF0969DA); val blueSoft = Color(0xFFDDF4FF)
    val yellow = Color(0xFF9A6700); val red = Color(0xFFCF222E); val text1 = Color(0xFF1F2328)
    val text2 = Color(0xFF656D76); val input = Color(0xFFEFF2F5)
}

private val opsTimeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AnalyzerScreen(viewModel: AnalyzerViewModel = hiltViewModel()) {
    val messages by viewModel.messages.collectAsState(initial = emptyList())
    val isStreaming by viewModel.isStreaming.collectAsState()
    val chatError by viewModel.chatError.collectAsState()
    val selectedModel by viewModel.selectedModel.collectAsState()
    val sessionTokens by viewModel.sessionTokens.collectAsState()
    val ecoOutputMode by viewModel.ecoOutputMode.collectAsState()
    val operationsLog by viewModel.operationsLog.collectAsState()
    val cacheModeEnabled by viewModel.cacheModeEnabled.collectAsState()
    val cacheTimerMs by viewModel.cacheTimerMs.collectAsState()
    val cacheIsWarmed by viewModel.cacheIsWarmed.collectAsState()
    val cacheTotalReadTokens by viewModel.cacheTotalReadTokens.collectAsState()
    val cacheTotalWriteTokens by viewModel.cacheTotalWriteTokens.collectAsState()
    val cacheTotalSavingsEUR by viewModel.cacheTotalSavingsEUR.collectAsState()
    val cacheHitCount by viewModel.cacheHitCount.collectAsState()
    val streamingText by viewModel.streamingText.collectAsState()
    val thinkingEnabled by viewModel.thinkingEnabled.collectAsState()
    val sendToolsEnabled by viewModel.sendToolsEnabled.collectAsState()
    val sendSystemPromptEnabled by viewModel.sendSystemPromptEnabled.collectAsState()
    val longContextEnabled by viewModel.longContextEnabled.collectAsState()
    val attachedFileName by viewModel.attachedFileName.collectAsState()
    val attachedFileSize by viewModel.attachedFileSize.collectAsState()

    var userInput by remember { mutableStateOf("") }
    var showModelDialog by remember { mutableStateOf(false) }
    var showSessionStats by remember { mutableStateOf(false) }
    val chatListState = rememberLazyListState()
    val opsListState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    val imeVisible = imeBottomPx > 0
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()

    // Snackbar для подтверждения копирования
    val snackbarHostState = remember { SnackbarHostState() }

    val cm = cacheModeEnabled
    val bg = if (cm) LightColors.bg else DarkColors.bg
    val sf = if (cm) LightColors.surface else DarkColors.surface
    val bd = if (cm) LightColors.border else DarkColors.border
    val ac = if (cm) LightColors.blue else DarkColors.blue
    val gr = if (cm) LightColors.green else DarkColors.green
    val yl = if (cm) LightColors.yellow else DarkColors.yellow
    val rd = if (cm) LightColors.red else DarkColors.red
    val t1 = if (cm) LightColors.text1 else DarkColors.text1
    val t2 = if (cm) LightColors.text2 else DarkColors.text2
    val inp = if (cm) LightColors.input else DarkColors.input

    val context = LocalContext.current

    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        try {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            val fileName = cursor?.use {
                if (it.moveToFirst()) {
                    val idx = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) it.getString(idx) else "file.txt"
                } else "file.txt"
            } ?: "file.txt"

            val inputStream = context.contentResolver.openInputStream(uri)
            val bytes = inputStream?.readBytes() ?: return@rememberLauncherForActivityResult
            inputStream.close()

            val content = String(bytes, Charsets.UTF_8)
            viewModel.attachFile(fileName, content, bytes.size.toLong())
        } catch (e: Exception) {
            viewModel.addOperation("❌", "Ошибка чтения файла: ${e.message}",
                AnalyzerViewModel.OperationLogType.ERROR)
        }
    }

    val hasStreamingBubble = isStreaming && streamingText != null
    val totalItems = messages.size + (if (hasStreamingBubble) 1 else 0)
    LaunchedEffect(totalItems) { if (totalItems > 0) chatListState.animateScrollToItem(totalItems - 1) }
    LaunchedEffect(imeVisible) { if (imeVisible && totalItems > 0) chatListState.animateScrollToItem(totalItems - 1) }
    LaunchedEffect(operationsLog.size) { if (operationsLog.isNotEmpty()) opsListState.animateScrollToItem(operationsLog.size - 1) }

    val cacheButtonEnabled = !ecoOutputMode || cacheModeEnabled
    val ecoButtonEnabled = !cacheModeEnabled

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Analyzer", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                            if (cm) {
                                Spacer(Modifier.width(8.dp))
                                Surface(shape = RoundedCornerShape(4.dp), color = LightColors.blueSoft) {
                                    Text("CACHE", Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        fontSize = 10.sp, fontWeight = FontWeight.Bold, color = ac)
                                }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${selectedModel.emoji} ${selectedModel.displayName}",
                                style = MaterialTheme.typography.labelSmall, color = t2)
                            sessionTokens?.let {
                                Text(" | ${"%,d".format(it.totalTokens)} tok | EUR${String.format("%.3f", it.totalCostEUR)}",
                                    style = MaterialTheme.typography.labelSmall, color = t2)
                            }
                        }
                        
                        // ═══ TOGGLES ROW ═══
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(top = 2.dp)
                        ) {
                            // Thinking toggle
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable { viewModel.toggleThinking() }
                            ) {
                                Box(
                                    Modifier.size(14.dp).clip(RoundedCornerShape(3.dp))
                                        .background(if (thinkingEnabled) Color(0xFFFF6B00) else Color.Transparent)
                                        .border(1.dp, if (thinkingEnabled) Color(0xFFFF6B00) else t2, RoundedCornerShape(3.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (thinkingEnabled) Text("✓", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                }
                                Spacer(Modifier.width(3.dp))
                                Text("🧠", fontSize = 10.sp)
                                Spacer(Modifier.width(6.dp))
                            }

                            // Tools toggle
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable { viewModel.toggleSendTools() }
                            ) {
                                Box(
                                    Modifier.size(14.dp).clip(RoundedCornerShape(3.dp))
                                        .background(if (sendToolsEnabled) ac else Color.Transparent)
                                        .border(1.dp, if (sendToolsEnabled) ac else t2, RoundedCornerShape(3.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (sendToolsEnabled) Text("✓", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                }
                                Spacer(Modifier.width(3.dp))
                                Text("🔧", fontSize = 10.sp)
                                Spacer(Modifier.width(6.dp))
                            }

                            // System Prompt toggle
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable { viewModel.toggleSendSystemPrompt() }
                            ) {
                                Box(
                                    Modifier.size(14.dp).clip(RoundedCornerShape(3.dp))
                                        .background(if (sendSystemPromptEnabled) ac else Color.Transparent)
                                        .border(1.dp, if (sendSystemPromptEnabled) ac else t2, RoundedCornerShape(3.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (sendSystemPromptEnabled) Text("✓", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                }
                                Spacer(Modifier.width(3.dp))
                                Text("📋", fontSize = 10.sp)
                                Spacer(Modifier.width(6.dp))
                            }

                            // Long Context toggle
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.clickable { viewModel.toggleLongContext() }
                            ) {
                                Box(
                                    Modifier.size(14.dp).clip(RoundedCornerShape(3.dp))
                                        .background(if (longContextEnabled) Color(0xFFFF4444) else Color.Transparent)
                                        .border(1.dp, if (longContextEnabled) Color(0xFFFF4444) else t2, RoundedCornerShape(3.dp)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (longContextEnabled) Text("✓", color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                                }
                                Spacer(Modifier.width(3.dp))
                                Text("1M", fontSize = 9.sp, fontWeight = FontWeight.Bold,
                                    color = if (longContextEnabled) Color(0xFFFF4444) else t2,
                                    fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                },
                actions = {
                    // ═══ КНОПКА CONVERSATION HISTORY ═══
                    val conversationHistoryEnabled by viewModel.conversationHistoryEnabled.collectAsState()
                    IconButton(
                        onClick = { viewModel.toggleConversationHistory() },
                        enabled = !cacheModeEnabled
                    ) {
                        Icon(
                            Icons.Default.History, 
                            "History",
                            tint = if (conversationHistoryEnabled) {
                                ac
                            } else if (cacheModeEnabled) {
                                t2.copy(alpha = 0.3f)
                            } else {
                                t2
                            }
                        )
                    }
                    
                    // ═══ КНОПКА КОПИРОВАНИЯ ВСЕГО ЧАТА ═══
                    IconButton(onClick = {
                        coroutineScope.launch {
                            val chatText = viewModel.getChatAsText()
                            if (chatText.isNotEmpty()) {
                                clipboardManager.setText(AnnotatedString(chatText))
                                snackbarHostState.showSnackbar("✅ Чат скопирован", duration = SnackbarDuration.Short)
                            } else {
                                snackbarHostState.showSnackbar("Чат пуст", duration = SnackbarDuration.Short)
                            }
                        }
                    }) {
                        Icon(Icons.Default.CopyAll, "Copy chat", tint = t2)
                    }
                    IconButton(onClick = { viewModel.toggleCacheMode() }, enabled = cacheButtonEnabled) {
                        Icon(Icons.Default.Cached, "Cache", tint = when {
                            !cacheButtonEnabled -> t2.copy(alpha = 0.3f); cm -> ac; else -> t2
                        })
                    }
                    IconButton(onClick = { showModelDialog = true }) { Icon(Icons.Default.Psychology, "Model", tint = t2) }
                    IconButton(onClick = { showSessionStats = true }) { Icon(Icons.Default.Analytics, "Stats", tint = t2) }
                    IconButton(onClick = { viewModel.startNewSession() }) { Icon(Icons.Default.RestartAlt, "New", tint = t2) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = bg, titleContentColor = t1)
            )
        },
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = if (cm) LightColors.surface else DarkColors.surface,
                    contentColor = t1,
                    shape = RoundedCornerShape(12.dp)
                )
            }
        },
        containerColor = bg
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
            AnimatedVisibility(visible = cm) {
                CacheStatusBar(cacheTimerMs, cacheIsWarmed, cacheTotalReadTokens, cacheTotalWriteTokens,
                    cacheTotalSavingsEUR, cacheHitCount, viewModel, ac, gr, rd, t1, t2, bd)
            }

            Box(Modifier.fillMaxWidth().weight(if (cm) 0.22f else 0.28f).background(sf)) {
                Column {
                    Row(Modifier.fillMaxWidth().background(inp).padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("OPS", color = t2, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(if (cm) ac else if (ecoOutputMode) gr else rd))
                            Spacer(Modifier.width(4.dp))
                            Text(if (cm) "CACHE" else if (ecoOutputMode) "ECO" else "MAX",
                                color = if (cm) ac else if (ecoOutputMode) gr else rd,
                                fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                            Spacer(Modifier.width(8.dp))
                            IconButton(onClick = { viewModel.clearOperationsLog() }, Modifier.size(20.dp)) {
                                Icon(Icons.Default.DeleteSweep, "Clear", tint = t2, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                    if (operationsLog.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("...", color = t2, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        }
                    } else {
                        LazyColumn(state = opsListState, modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                            items(operationsLog, key = { it.id }) { item ->
                                OpLogRow(item, t1, t2, gr, rd, yl)
                            }
                        }
                    }
                }
            }

            HorizontalDivider(color = bd, thickness = 2.dp)

            Column(Modifier.fillMaxWidth().weight(if (cm) 0.78f else 0.72f)) {
                AnimatedVisibility(visible = chatError != null) {
                    Surface(Modifier.fillMaxWidth().padding(8.dp),
                        color = if (cm) Color(0xFFFFF0F0) else Color(0xFF3D1F1F), shape = RoundedCornerShape(8.dp)) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(chatError ?: "", color = rd, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            IconButton(onClick = { viewModel.dismissError() }, Modifier.size(24.dp)) {
                                Icon(Icons.Default.Close, "X", tint = t2, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }

                LazyColumn(state = chatListState, modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                    items(messages, key = { it.id }) { msg ->
                        MsgBubble(
                            msg = msg,
                            cm = cm,
                            sf = sf,
                            t1 = t1,
                            t2 = t2,
                            ac = ac,
                            gr = gr,
                            clipboardManager = clipboardManager,
                            snackbarHostState = snackbarHostState,
                            coroutineScope = coroutineScope
                        )
                    }
                    if (hasStreamingBubble) {
                        item(key = "streaming_bubble") {
                            StreamingBubble(text = streamingText ?: "", cm = cm, sf = sf, t1 = t1, t2 = t2, ac = ac)
                        }
                    }
                }

                // Индикатор прикреплённого файла
                AnimatedVisibility(visible = attachedFileName != null) {
                    Surface(
                        Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
                        color = if (cm) Color(0xFFE8F5E9) else Color(0xFF1A2E1A),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Row(
                            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("📎", fontSize = 14.sp)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "${attachedFileName ?: ""} (${attachedFileSize / 1024}KB)",
                                color = gr,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            IconButton(
                                onClick = { viewModel.detachFile() },
                                modifier = Modifier.size(20.dp)
                            ) {
                                Icon(Icons.Default.Close, "Detach", tint = t2, modifier = Modifier.size(14.dp))
                            }
                        }
                    }
                }

                Surface(Modifier.fillMaxWidth(), color = inp, tonalElevation = 2.dp) {
                    Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { viewModel.toggleOutputMode() }, modifier = Modifier.size(40.dp), enabled = ecoButtonEnabled) {
                            Box(contentAlignment = Alignment.Center) {
                                val dotColor = when { cm -> ac; ecoOutputMode -> gr; else -> rd }
                                Box(Modifier.size(12.dp).clip(CircleShape).background(if (ecoButtonEnabled) dotColor else dotColor.copy(alpha = 0.3f)))
                            }
                        }
                        
                        // Кнопка прикрепления файла
                        IconButton(
                            onClick = {
                                if (attachedFileName != null) {
                                    viewModel.detachFile()
                                } else {
                                    filePickerLauncher.launch(arrayOf("text/*"))
                                }
                            },
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = if (attachedFileName != null) Icons.Default.AttachFile else Icons.Default.AttachFile,
                                contentDescription = "Attach file",
                                tint = if (attachedFileName != null) gr else t2,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                        
                        OutlinedTextField(
                            value = userInput, onValueChange = { userInput = it },
                            modifier = Modifier.weight(1f),
                            placeholder = {
                                val maxTok = viewModel.getEffectiveMaxTokens()
                                val label = when {
                                    cm && cacheIsWarmed -> "CACHE ${"%,d".format(maxTok)}..."
                                    cm -> "CACHE ${"%,d".format(maxTok)}..."
                                    ecoOutputMode -> "ECO 8K..."
                                    else -> "MAX ${"%,d".format(maxTok)}..."
                                }
                                Text(label, color = t2, fontSize = 13.sp)
                            },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = ac, unfocusedBorderColor = bd,
                                cursorColor = ac, focusedTextColor = t1, unfocusedTextColor = t1),
                            maxLines = 5,
                            textStyle = LocalTextStyle.current.copy(fontSize = 14.sp, fontFamily = FontFamily.Monospace),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                            keyboardActions = KeyboardActions(onSend = {
                                if (userInput.isNotBlank() && !isStreaming) {
                                    viewModel.sendMessage(userInput.trim()); userInput = ""; focusManager.clearFocus()
                                }
                            }),
                            shape = RoundedCornerShape(12.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        FilledIconButton(
                            onClick = {
                                if (userInput.isNotBlank() && !isStreaming) {
                                    viewModel.sendMessage(userInput.trim()); userInput = ""; focusManager.clearFocus()
                                }
                            },
                            enabled = userInput.isNotBlank() && !isStreaming,
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = ac, contentColor = Color.White,
                                disabledContainerColor = bd, disabledContentColor = t2),
                            modifier = Modifier.size(48.dp)
                        ) { Icon(if (isStreaming) Icons.Default.HourglassTop else Icons.Default.Send, "Send") }
                    }
                }
            }
        }
    }

    if (showModelDialog) {
        AlertDialog(
            onDismissRequest = { showModelDialog = false },
            title = { Text("Model") },
            text = {
                LazyColumn(Modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(ClaudeModelConfig.ClaudeModel.entries.toList()) { model ->
                        val isSel = model == selectedModel
                        val isExp = model == ClaudeModelConfig.ClaudeModel.OPUS_4_1 || model == ClaudeModelConfig.ClaudeModel.OPUS_4
                        Surface(
                            onClick = { viewModel.selectModel(model); showModelDialog = false },
                            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp),
                            color = when { isSel -> MaterialTheme.colorScheme.primaryContainer; isExp -> Color(0xFFFFF3E0); else -> MaterialTheme.colorScheme.surface }
                        ) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(model.emoji, fontSize = 20.sp); Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Text(model.displayName, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                    Text("In: $${model.inputPricePerM} | Out: $${model.outputPricePerM} | CacheR: $${model.cacheReadPricePerM}",
                                        fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("Max out: ${"%,d".format(model.maxOutputTokens)} tok", fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (isSel) Icon(Icons.Default.CheckCircle, "Sel", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showModelDialog = false }) { Text("Close") } }
        )
    }

    if (showSessionStats) {
        AlertDialog(
            onDismissRequest = { showSessionStats = false },
            title = { Text("Stats") },
            text = {
                val s = viewModel.getSessionStats()
                if (s != null) Text(s, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp) else Text("No session")
            },
            confirmButton = { TextButton(onClick = { showSessionStats = false }) { Text("Close") } }
        )
    }
}

@Composable
private fun CacheStatusBar(
    timerMs: Long, warmed: Boolean, readTok: Int, writeTok: Int, savEUR: Double, hits: Int,
    vm: AnalyzerViewModel, accent: Color, green: Color, red: Color, txt1: Color, txt2: Color, border: Color
) {
    val prog = if (timerMs > 0) (timerMs.toFloat() / ClaudeModelConfig.CACHE_TTL_MS).coerceIn(0f, 1f) else 0f
    val tc = when { timerMs <= 0 -> txt2; timerMs <= 60_000 -> red; timerMs <= 120_000 -> DarkColors.yellow; else -> green }
    Surface(Modifier.fillMaxWidth(), color = LightColors.blueSoft, tonalElevation = 1.dp) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Timer, "T", tint = tc, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(vm.getCacheTimerFormatted(timerMs), fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = tc)
                }
                Surface(shape = RoundedCornerShape(12.dp), color = if (warmed && timerMs > 0) green.copy(alpha = 0.15f) else red.copy(alpha = 0.15f)) {
                    Text(if (warmed && timerMs > 0) "CACHED" else "EMPTY", Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        fontSize = 10.sp, fontWeight = FontWeight.Bold, color = if (warmed && timerMs > 0) green else red)
                }
            }
            if (warmed) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(progress = { prog }, Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)), color = tc, trackColor = border)
            }
            if (writeTok > 0 || readTok > 0) {
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("W:${"%,d".format(writeTok)}", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = txt2)
                    Text("R:${"%,d".format(readTok)}", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = accent)
                    Text("Hits:$hits", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = txt2)
                    Text("-EUR${String.format("%.4f", savEUR)}", fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = green)
                }
            }
        }
    }
}

@Composable
private fun OpLogRow(item: AnalyzerViewModel.OperationLogItem, t1: Color, t2: Color, g: Color, r: Color, y: Color) {
    val c = when (item.type) { AnalyzerViewModel.OperationLogType.SUCCESS -> g; AnalyzerViewModel.OperationLogType.ERROR -> r; AnalyzerViewModel.OperationLogType.PROGRESS -> y; else -> t2 }
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(item.icon, fontSize = 12.sp, modifier = Modifier.width(20.dp))
        Text(item.message, color = c, fontSize = 11.sp, fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        Text(opsTimeFormat.format(Date(item.timestamp)), color = t2.copy(alpha = 0.5f), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun MsgBubble(
    msg: ChatMessageEntity,
    cm: Boolean,
    sf: Color,
    t1: Color,
    t2: Color,
    ac: Color,
    gr: Color,
    clipboardManager: androidx.compose.ui.platform.ClipboardManager,
    snackbarHostState: SnackbarHostState,
    coroutineScope: kotlinx.coroutines.CoroutineScope
) {
    val isU = msg.role == MessageRole.USER
    val isS = msg.role == MessageRole.SYSTEM
    val isAssistant = msg.role == MessageRole.ASSISTANT

    // Защита от зависания UI на больших ответах
    val MAX_DISPLAY_CHARS = 16_000
    val isLongContent = msg.content.length > MAX_DISPLAY_CHARS
    var contentExpanded by remember { mutableStateOf(false) }

    val displayContent = if (isLongContent && !contentExpanded) {
        msg.content.take(MAX_DISPLAY_CHARS) + 
            "\n\n─── Показано ${MAX_DISPLAY_CHARS / 1024}KB из ${msg.content.length / 1024}KB ───"
    } else {
        msg.content
    }

    val bc = when { cm && isU -> Color(0xFFE8F0FE); cm && isS -> Color(0xFFE6F4EA); cm -> sf; isU -> Color(0xFF1A2332); isS -> Color(0xFF1A2E1A); else -> sf }
    val cc = if (isS) gr else t1
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalAlignment = if (isU) Alignment.End else Alignment.Start) {
        Text(when { isU -> "You"; isS -> "System"; else -> "Claude" },
            color = t2, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
        Surface(
            Modifier.fillMaxWidth(if (isU) 0.85f else 0.95f), color = bc,
            shape = RoundedCornerShape(topStart = if (isU) 12.dp else 4.dp, topEnd = if (isU) 4.dp else 12.dp, bottomStart = 12.dp, bottomEnd = 12.dp),
            border = if (cm) BorderStroke(0.5.dp, Color(0xFFD0D7DE)) else null
        ) {
            Text(displayContent, color = cc, fontSize = 13.sp, fontFamily = FontFamily.Monospace, lineHeight = 19.sp, modifier = Modifier.padding(12.dp))
        }
        
        // Кнопка "Показать всё" для длинных ответов
        if (isAssistant && isLongContent) {
            TextButton(
                onClick = { contentExpanded = !contentExpanded },
                modifier = Modifier.padding(start = 4.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
            ) {
                Text(
                    text = if (contentExpanded) "⬆ Свернуть" 
                           else "⬇ Показать всё (${msg.content.length / 1024}KB)",
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = ac
                )
            }
        }
        
        // ═══ КНОПКА КОПИРОВАНИЯ ПОД ОТВЕТОМ CLAUDE ═══
        if (isAssistant && msg.content.isNotBlank()) {
            var copied by remember { mutableStateOf(false) }
            Row(
                modifier = Modifier.padding(start = 8.dp, top = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(msg.content))
                        copied = true
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar("✅ Ответ скопирован", duration = SnackbarDuration.Short)
                            kotlinx.coroutines.delay(2000)
                            copied = false
                        }
                    },
                    modifier = Modifier.size(28.dp)
                ) {
                    Icon(
                        imageVector = if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                        contentDescription = "Copy response",
                        tint = if (copied) gr else t2,
                        modifier = Modifier.size(14.dp)
                    )
                }
                AnimatedVisibility(visible = copied) {
                    Text(
                        "Copied",
                        color = gr,
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(start = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun StreamingBubble(text: String, cm: Boolean, sf: Color, t1: Color, t2: Color, ac: Color) {
    val bc = if (cm) sf else Color(0xFF161B22)
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalAlignment = Alignment.Start) {
        Row(Modifier.padding(horizontal = 8.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Claude", color = t2, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.width(8.dp))
            CircularProgressIndicator(modifier = Modifier.size(10.dp), color = ac, strokeWidth = 1.5.dp)
        }
        Surface(
            Modifier.fillMaxWidth(0.95f), color = bc,
            shape = RoundedCornerShape(topStart = 4.dp, topEnd = 12.dp, bottomStart = 12.dp, bottomEnd = 12.dp),
            border = if (cm) BorderStroke(0.5.dp, Color(0xFFD0D7DE)) else null
        ) {
            // При стриминге показываем только хвост — иначе UI зависнет на больших ответах
            val displayText = if (text.length > 8192) {
                "⏳ Получено ${text.length / 1024}KB...\n${"─".repeat(30)}\n${text.takeLast(8192)}"
            } else {
                text
            }
            
            if (displayText.isNotEmpty()) {
                Text(displayText, color = t1, fontSize = 13.sp, fontFamily = FontFamily.Monospace, lineHeight = 19.sp, modifier = Modifier.padding(12.dp))
            } else {
                Text("|", color = ac, fontSize = 13.sp, fontFamily = FontFamily.Monospace, modifier = Modifier.padding(12.dp))
            }
        }
    }
}