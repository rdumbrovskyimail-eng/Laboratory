package com.opuside.app.feature.analyzer.presentation

import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.ui.platform.LocalDensity
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

    var userInput by remember { mutableStateOf("") }
    var showModelDialog by remember { mutableStateOf(false) }
    var showSessionStats by remember { mutableStateOf(false) }

    val chatListState = rememberLazyListState()
    val opsListState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    val imeVisible = imeBottomPx > 0

    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) chatListState.animateScrollToItem(messages.size - 1) }
    LaunchedEffect(imeVisible) { if (imeVisible && messages.isNotEmpty()) chatListState.animateScrollToItem(messages.size - 1) }
    LaunchedEffect(operationsLog.size) { if (operationsLog.isNotEmpty()) opsListState.animateScrollToItem(operationsLog.size - 1) }

    // DARK
    val dBg = Color(0xFF0D1117); val dSurf = Color(0xFF161B22); val dBord = Color(0xFF30363D)
    val dGreen = Color(0xFF3FB950); val dBlue = Color(0xFF58A6FF); val dYellow = Color(0xFFD29922)
    val dRed = Color(0xFFF85149); val dTxt1 = Color(0xFFE6EDF3); val dTxt2 = Color(0xFF8B949E)
    val dInp = Color(0xFF1C2128)
    // LIGHT (cache mode)
    val lBg = Color(0xFFF8FAFE); val lSurf = Color(0xFFFFFFFF); val lBord = Color(0xFFD0D7DE)
    val lBlue = Color(0xFF0969DA); val lBlueSoft = Color(0xFFDDF4FF); val lGreen = Color(0xFF1A7F37)
    val lYellow = Color(0xFF9A6700); val lRed = Color(0xFFCF222E); val lTxt1 = Color(0xFF1F2328)
    val lTxt2 = Color(0xFF656D76); val lInp = Color(0xFFEFF2F5)
    // Active
    val cm = cacheModeEnabled
    val bg = if (cm) lBg else dBg; val sf = if (cm) lSurf else dSurf; val bd = if (cm) lBord else dBord
    val ac = if (cm) lBlue else dBlue; val gr = if (cm) lGreen else dGreen; val yl = if (cm) lYellow else dYellow
    val rd = if (cm) lRed else dRed; val t1 = if (cm) lTxt1 else dTxt1; val t2 = if (cm) lTxt2 else dTxt2
    val inp = if (cm) lInp else dInp

    // Кнопка Cache заблокирована если ECO включён
    val cacheButtonEnabled = !ecoOutputMode || cacheModeEnabled
    // Кнопка ECO заблокирована если Cache Mode включён
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
                                Surface(shape = RoundedCornerShape(4.dp), color = lBlueSoft) {
                                    Text("CACHE", Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        fontSize = 10.sp, fontWeight = FontWeight.Bold, color = ac)
                                }
                            }
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("${selectedModel.emoji} ${selectedModel.displayName}",
                                style = MaterialTheme.typography.labelSmall, color = t2)
                            sessionTokens?.let {
                                Text(" • ${"%,d".format(it.totalTokens)} tok • €${String.format("%.3f", it.totalCostEUR)}",
                                    style = MaterialTheme.typography.labelSmall, color = t2)
                            }
                        }
                    }
                },
                actions = {
                    // CACHE TOGGLE — disabled when ECO is on
                    IconButton(
                        onClick = { viewModel.toggleCacheMode() },
                        enabled = cacheButtonEnabled
                    ) {
                        Icon(
                            Icons.Default.Cached,
                            "Cache",
                            tint = when {
                                !cacheButtonEnabled -> t2.copy(alpha = 0.3f)
                                cm -> ac
                                else -> t2
                            }
                        )
                    }
                    IconButton(onClick = { showModelDialog = true }) { Icon(Icons.Default.Psychology, "Model", tint = t2) }
                    IconButton(onClick = { showSessionStats = true }) { Icon(Icons.Default.Analytics, "Stats", tint = t2) }
                    IconButton(onClick = { viewModel.startNewSession() }) { Icon(Icons.Default.RestartAlt, "New", tint = t2) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = bg, titleContentColor = t1, actionIconContentColor = t2)
            )
        },
        containerColor = bg
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding()) {

            // CACHE STATUS BAR
            AnimatedVisibility(visible = cm) {
                CacheStatusBar(cacheTimerMs, cacheIsWarmed, cacheTotalReadTokens, cacheTotalWriteTokens,
                    cacheTotalSavingsEUR, cacheHitCount, viewModel, ac, gr, rd, t1, t2, bd, lBlueSoft)
            }

            // OPS LOG
            Box(Modifier.fillMaxWidth().weight(if (cm) 0.22f else 0.28f).background(sf)) {
                Column {
                    Row(Modifier.fillMaxWidth().background(inp).padding(horizontal = 12.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("⚙️", fontSize = 14.sp); Spacer(Modifier.width(6.dp))
                            Text("OPERATIONS LOG", color = t2, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(
                                if (cm) ac else if (ecoOutputMode) gr else rd))
                            Spacer(Modifier.width(4.dp))
                            Text(
                                if (cm) "CACHE MAX" else if (ecoOutputMode) "ECO" else "MAX",
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
                            Text("Операции...", color = t2, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                        }
                    } else {
                        LazyColumn(state = opsListState, modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                            items(operationsLog) { OpLogRow(it, t1, t2, gr, rd, yl) }
                        }
                    }
                }
            }

            HorizontalDivider(color = bd, thickness = 2.dp)

            // CHAT + INPUT
            Column(Modifier.fillMaxWidth().weight(if (cm) 0.78f else 0.72f)) {
                // Error
                AnimatedVisibility(visible = chatError != null) {
                    Surface(Modifier.fillMaxWidth().padding(8.dp),
                        color = if (cm) Color(0xFFFFF0F0) else Color(0xFF3D1F1F), shape = RoundedCornerShape(8.dp)) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("❌", fontSize = 16.sp); Spacer(Modifier.width(8.dp))
                            Text(chatError ?: "", color = rd, fontSize = 13.sp, modifier = Modifier.weight(1f))
                            IconButton(onClick = { viewModel.dismissError() }, Modifier.size(24.dp)) {
                                Icon(Icons.Default.Close, "X", tint = t2, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }

                // Messages
                LazyColumn(state = chatListState, modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)) {
                    items(messages) { msg -> MsgBubble(msg, cm, bg, sf, t1, t2, ac, gr, lBlueSoft) }
                    if (isStreaming) { item {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(16.dp), color = ac, strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("Claude пишет...", color = t2, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                        }
                    }}
                }

                // INPUT
                Surface(Modifier.fillMaxWidth(), color = inp, tonalElevation = 2.dp) {
                    Row(Modifier.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        // ECO/MAX — disabled in cache mode
                        IconButton(
                            onClick = { viewModel.toggleOutputMode() },
                            modifier = Modifier.size(40.dp),
                            enabled = ecoButtonEnabled
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                val dotColor = when {
                                    cm -> ac  // В cache mode — синий (CACHE MAX)
                                    ecoOutputMode -> gr
                                    else -> rd
                                }
                                Box(Modifier.size(12.dp).clip(CircleShape).background(
                                    if (ecoButtonEnabled) dotColor else dotColor.copy(alpha = 0.3f)))
                            }
                        }

                        OutlinedTextField(
                            value = userInput, onValueChange = { userInput = it },
                            modifier = Modifier.weight(1f),
                            placeholder = {
                                val maxTok = viewModel.getEffectiveMaxTokens()
                                val label = when {
                                    cm && cacheIsWarmed -> "CACHE MAX ${"%,d".format(maxTok)} [📦]..."
                                    cm -> "CACHE MAX ${"%,d".format(maxTok)}..."
                                    ecoOutputMode -> "ECO 🟢 8K..."
                                    else -> "MAX 🔴 ${"%,d".format(maxTok)}..."
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
                        ) {
                            Icon(if (isStreaming) Icons.Default.HourglassTop else Icons.Default.Send, "Send")
                        }
                    }
                }
            }
        }
    }

    // DIALOGS
    if (showModelDialog) {
        AlertDialog(
            onDismissRequest = { showModelDialog = false },
            title = { Text("Выбрать модель") },
            text = {
                LazyColumn(Modifier.heightIn(max = 480.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    items(ClaudeModelConfig.ClaudeModel.entries.toList()) { model ->
                        val isSel = model == selectedModel
                        val isExp = model == ClaudeModelConfig.ClaudeModel.OPUS_4_1 || model == ClaudeModelConfig.ClaudeModel.OPUS_4
                        Surface(
                            onClick = { viewModel.selectModel(model); showModelDialog = false },
                            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(8.dp),
                            color = when { isSel -> MaterialTheme.colorScheme.primaryContainer; isExp -> Color(0xFFFFF3E0); else -> MaterialTheme.colorScheme.surface },
                            tonalElevation = if (isSel) 4.dp else 0.dp
                        ) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(model.emoji, fontSize = 20.sp); Spacer(Modifier.width(12.dp))
                                Column(Modifier.weight(1f)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(model.displayName, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                        if (isExp) { Spacer(Modifier.width(6.dp)); Text("⚠️ 3×", fontSize = 10.sp, color = Color(0xFFE65100)) }
                                    }
                                    Text(model.description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("In: \$${model.inputPricePerM} | Out: \$${model.outputPricePerM} | Cache R: \$${model.cacheReadPricePerM}",
                                        fontSize = 11.sp, color = if (isExp) Color(0xFFE65100) else MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text("Max output: ${"%,d".format(model.maxOutputTokens)} tok",
                                        fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                if (isSel) Icon(Icons.Default.CheckCircle, "Sel", tint = MaterialTheme.colorScheme.primary)
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showModelDialog = false }) { Text("Закрыть") } }
        )
    }

    if (showSessionStats) {
        AlertDialog(
            onDismissRequest = { showSessionStats = false },
            title = { Text("📊 Статистика") },
            text = {
                val s = viewModel.getSessionStats()
                if (s != null) Text(s, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp)
                else Text("Нет сеанса")
            },
            confirmButton = { TextButton(onClick = { showSessionStats = false }) { Text("Закрыть") } }
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════
// CACHE STATUS BAR
// ═══════════════════════════════════════════════════════════════════════

@Composable
private fun CacheStatusBar(
    timerMs: Long, warmed: Boolean, readTok: Int, writeTok: Int, savEUR: Double, hits: Int,
    vm: AnalyzerViewModel, accent: Color, green: Color, red: Color, txt1: Color, txt2: Color, border: Color, blueSoft: Color
) {
    val prog = if (timerMs > 0) (timerMs.toFloat() / ClaudeModelConfig.CACHE_TTL_MS).coerceIn(0f, 1f) else 0f
    val tc = when { timerMs <= 0 -> txt2; timerMs <= 60_000 -> red; timerMs <= 120_000 -> Color(0xFFD29922); else -> green }

    Surface(Modifier.fillMaxWidth(), color = blueSoft, tonalElevation = 1.dp) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Timer, "T", tint = tc, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(vm.getCacheTimerFormatted(timerMs), fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = tc)
                    Spacer(Modifier.width(8.dp))
                    Text(when { !warmed -> "Ожидание..."; timerMs > 0 -> "TTL active"; else -> "TTL expired" },
                        fontSize = 11.sp, color = txt2)
                }
                Surface(shape = RoundedCornerShape(12.dp),
                    color = if (warmed && timerMs > 0) green.copy(alpha = 0.15f) else red.copy(alpha = 0.15f)) {
                    Text(if (warmed && timerMs > 0) "● CACHED" else "○ EMPTY",
                        Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        fontSize = 10.sp, fontWeight = FontWeight.Bold,
                        color = if (warmed && timerMs > 0) green else red)
                }
            }
            if (warmed) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(progress = { prog },
                    Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)), color = tc, trackColor = border)
            }
            if (writeTok > 0 || readTok > 0) {
                Spacer(Modifier.height(6.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("📝 W:${"%,d".format(writeTok)}", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = txt2)
                    Text("⚡ R:${"%,d".format(readTok)}", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = accent)
                    Text("🎯 $hits", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = txt2)
                    Text("💰 -€${String.format("%.4f", savEUR)}", fontSize = 10.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, color = green)
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
        Text(java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(item.timestamp)),
            color = t2.copy(alpha = 0.5f), fontSize = 9.sp, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun MsgBubble(msg: ChatMessageEntity, cm: Boolean, bg: Color, sf: Color, t1: Color, t2: Color, ac: Color, gr: Color, blueSoft: Color) {
    val isU = msg.role == MessageRole.USER; val isS = msg.role == MessageRole.SYSTEM
    val bc = when { cm && isU -> Color(0xFFE8F0FE); cm && isS -> Color(0xFFE6F4EA); cm -> sf; isU -> Color(0xFF1A2332); isS -> Color(0xFF1A2E1A); else -> sf }
    val cc = if (isS) gr else t1
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalAlignment = if (isU) Alignment.End else Alignment.Start) {
        Text(when { isU -> "👤 You"; isS -> "⚙️ System"; else -> "🤖 Claude" },
            color = t2, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp))
        Surface(
            Modifier.fillMaxWidth(if (isU) 0.85f else 0.95f), color = bc,
            shape = RoundedCornerShape(topStart = if (isU) 12.dp else 4.dp, topEnd = if (isU) 4.dp else 12.dp, bottomStart = 12.dp, bottomEnd = 12.dp),
            border = if (cm) BorderStroke(0.5.dp, Color(0xFFD0D7DE)) else null
        ) {
            Text(msg.content, color = cc, fontSize = 13.sp, fontFamily = FontFamily.Monospace, lineHeight = 19.sp, modifier = Modifier.padding(12.dp))
        }
    }
}
