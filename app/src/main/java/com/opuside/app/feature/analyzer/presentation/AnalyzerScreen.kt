package com.opuside.app.feature.analyzer.presentation

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
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

// ═══════════════════════════════════════════════════════════════════════════
// ПРОФЕССИОНАЛЬНАЯ ЦВЕТОВАЯ СХЕМА
// ═══════════════════════════════════════════════════════════════════════════

private object ProColors {
    val darkBg = Color(0xFF0A0E14)
    val darkSurface = Color(0xFF12171E)
    val darkSurfaceVariant = Color(0xFF1A1F26)
    val darkBorder = Color(0xFF2D3339)
    
    val lightBg = Color(0xFFF5F7FA)
    val lightSurface = Color(0xFFFFFFFF)
    val lightSurfaceVariant = Color(0xFFF8FAFB)
    val lightBorder = Color(0xFFE1E4E8)
    
    val blue = Color(0xFF4A9EFF)
    val blueLight = Color(0xFF6BB3FF)
    val blueSoft = Color(0xFFE8F4FF)
    val blueSoftDark = Color(0xFF1A2332)
    
    val green = Color(0xFF4CAF50)
    val greenLight = Color(0xFF66BB6A)
    val greenSoft = Color(0xFFE8F5E9)
    val greenSoftDark = Color(0xFF1A2E1A)
    
    val orange = Color(0xFFFF9800)
    val orangeSoft = Color(0xFFFFF3E0)
    
    val red = Color(0xFFE53935)
    val redSoft = Color(0xFFFFEBEE)
    val redSoftDark = Color(0xFF2E1A1A)
    
    val purple = Color(0xFF9C27B0)
    
    val yellow = Color(0xFFFFC107)
    
    val darkText1 = Color(0xFFE8EDF3)
    val darkText2 = Color(0xFF8B949E)
    val darkText3 = Color(0xFF6E7681)
    
    val lightText1 = Color(0xFF1A1F26)
    val lightText2 = Color(0xFF586069)
    val lightText3 = Color(0xFF8B949E)
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
    val conversationHistoryEnabled by viewModel.conversationHistoryEnabled.collectAsState()

    var userInput by remember { mutableStateOf("") }
    var showModelDialog by remember { mutableStateOf(false) }
    var showSessionStats by remember { mutableStateOf(false) }
    var showSettingsPanel by remember { mutableStateOf(false) }
    
    val chatListState = rememberLazyListState()
    val opsListState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
    val density = LocalDensity.current
    val imeBottomPx = WindowInsets.ime.getBottom(density)
    val imeVisible = imeBottomPx > 0
    val clipboardManager = LocalClipboardManager.current
    val coroutineScope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

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
            viewModel.addOperation("❌", "File error: ${e.message}",
                AnalyzerViewModel.OperationLogType.ERROR)
        }
    }

    val cm = cacheModeEnabled
    val bg = if (cm) ProColors.lightBg else ProColors.darkBg
    val sf = if (cm) ProColors.lightSurface else ProColors.darkSurface
    val sfVar = if (cm) ProColors.lightSurfaceVariant else ProColors.darkSurfaceVariant
    val bd = if (cm) ProColors.lightBorder else ProColors.darkBorder
    val t1 = if (cm) ProColors.lightText1 else ProColors.darkText1
    val t2 = if (cm) ProColors.lightText2 else ProColors.darkText2
    val t3 = if (cm) ProColors.lightText3 else ProColors.darkText3
    val ac = ProColors.blue
    val gr = ProColors.green
    val rd = ProColors.red
    val yl = ProColors.yellow
    val or = ProColors.orange
    val pu = ProColors.purple

    val hasStreamingBubble = isStreaming && streamingText != null
    val totalItems = messages.size + (if (hasStreamingBubble) 1 else 0)
    LaunchedEffect(totalItems) { 
        if (totalItems > 0) chatListState.animateScrollToItem(totalItems - 1) 
    }
    LaunchedEffect(imeVisible) { 
        if (imeVisible && totalItems > 0) chatListState.animateScrollToItem(totalItems - 1) 
    }
    LaunchedEffect(operationsLog.size) { 
        if (operationsLog.isNotEmpty()) opsListState.animateScrollToItem(operationsLog.size - 1) 
    }

    Scaffold(
        topBar = {
            ProfessionalTopBar(
                selectedModel = selectedModel,
                sessionTokens = sessionTokens,
                cacheModeEnabled = cacheModeEnabled,
                thinkingEnabled = thinkingEnabled,
                sendToolsEnabled = sendToolsEnabled,
                sendSystemPromptEnabled = sendSystemPromptEnabled,
                longContextEnabled = longContextEnabled,
                conversationHistoryEnabled = conversationHistoryEnabled,
                onToggleThinking = { viewModel.toggleThinking() },
                onToggleTools = { viewModel.toggleSendTools() },
                onToggleSystemPrompt = { viewModel.toggleSendSystemPrompt() },
                onToggleLongContext = { viewModel.toggleLongContext() },
                onToggleHistory = { viewModel.toggleConversationHistory() },
                onCopyChat = {
                    coroutineScope.launch {
                        val text = viewModel.getChatAsText()
                        if (text.isNotEmpty()) {
                            clipboardManager.setText(AnnotatedString(text))
                            snackbarHostState.showSnackbar("✅ Chat copied")
                        }
                    }
                },
                onToggleCache = { viewModel.toggleCacheMode() },
                onShowModel = { showModelDialog = true },
                onShowStats = { showSessionStats = true },
                onNewSession = { viewModel.startNewSession() },
                onToggleSettings = { showSettingsPanel = !showSettingsPanel },
                cm = cm,
                bg = bg,
                t1 = t1,
                t2 = t2,
                t3 = t3,
                ac = ac,
                gr = gr,
                rd = rd,
                or = or,
                pu = pu
            )
        },
        snackbarHost = {
            SnackbarHost(snackbarHostState) { data ->
                Snackbar(
                    snackbarData = data,
                    containerColor = sf,
                    contentColor = t1,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.padding(12.dp)
                )
            }
        },
        containerColor = bg
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            Column(Modifier.fillMaxSize().imePadding()) {
                AnimatedVisibility(
                    visible = cacheModeEnabled,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    CacheStatusBar(
                        timerMs = cacheTimerMs,
                        warmed = cacheIsWarmed,
                        readTok = cacheTotalReadTokens,
                        writeTok = cacheTotalWriteTokens,
                        savEUR = cacheTotalSavingsEUR,
                        hits = cacheHitCount,
                        viewModel = viewModel,
                        cm = cm,
                        sf = sf,
                        t1 = t1,
                        t2 = t2,
                        t3 = t3,
                        ac = ac,
                        gr = gr,
                        rd = rd,
                        yl = yl,
                        bd = bd
                    )
                }

                OperationsPanel(
                    operationsLog = operationsLog,
                    opsListState = opsListState,
                    ecoOutputMode = ecoOutputMode,
                    cacheModeEnabled = cacheModeEnabled,
                    onClearLog = { viewModel.clearOperationsLog() },
                    cm = cm,
                    sf = sf,
                    sfVar = sfVar,
                    t1 = t1,
                    t2 = t2,
                    t3 = t3,
                    ac = ac,
                    gr = gr,
                    rd = rd,
                    yl = yl,
                    bd = bd
                )

                HorizontalDivider(color = bd, thickness = 1.dp)

                Column(Modifier.fillMaxWidth().weight(1f)) {
                    AnimatedVisibility(
                        visible = chatError != null,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        ErrorBanner(
                            error = chatError ?: "",
                            onDismiss = { viewModel.dismissError() },
                            cm = cm,
                            rd = rd,
                            t1 = t1,
                            t2 = t2
                        )
                    }

                    LazyColumn(
                        state = chatListState,
                        modifier = Modifier.weight(1f).fillMaxWidth(),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(messages, key = { it.id }) { msg ->
                            MessageBubble(
                                msg = msg,
                                cm = cm,
                                sf = sf,
                                sfVar = sfVar,
                                t1 = t1,
                                t2 = t2,
                                t3 = t3,
                                ac = ac,
                                gr = gr,
                                bd = bd,
                                clipboardManager = clipboardManager,
                                snackbarHostState = snackbarHostState,
                                coroutineScope = coroutineScope
                            )
                        }
                        if (hasStreamingBubble) {
                            item(key = "streaming") {
                                StreamingBubble(
                                    text = streamingText ?: "",
                                    cm = cm,
                                    sf = sf,
                                    t1 = t1,
                                    t2 = t2,
                                    ac = ac
                                )
                            }
                        }
                    }

                    AnimatedVisibility(
                        visible = attachedFileName != null,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut()
                    ) {
                        AttachedFileCard(
                            fileName = attachedFileName ?: "",
                            fileSize = attachedFileSize,
                            onDetach = { viewModel.detachFile() },
                            cm = cm,
                            sf = sf,
                            t1 = t1,
                            t2 = t2,
                            gr = gr,
                            bd = bd
                        )
                    }

                    InputArea(
                        userInput = userInput,
                        onInputChange = { userInput = it },
                        isStreaming = isStreaming,
                        ecoOutputMode = ecoOutputMode,
                        cacheModeEnabled = cacheModeEnabled,
                        cacheIsWarmed = cacheIsWarmed,
                        attachedFileName = attachedFileName,
                        maxTokens = viewModel.getEffectiveMaxTokens(),
                        onToggleMode = { viewModel.toggleOutputMode() },
                        onAttachFile = {
                            if (attachedFileName != null) viewModel.detachFile()
                            else filePickerLauncher.launch(arrayOf("text/*"))
                        },
                        onSend = {
                            if (userInput.isNotBlank() && !isStreaming) {
                                viewModel.sendMessage(userInput.trim())
                                userInput = ""
                                focusManager.clearFocus()
                            }
                        },
                        cm = cm,
                        sf = sf,
                        sfVar = sfVar,
                        t1 = t1,
                        t2 = t2,
                        ac = ac,
                        gr = gr,
                        rd = rd,
                        bd = bd
                    )
                }
            }

            AnimatedVisibility(
                visible = showSettingsPanel,
                modifier = Modifier.align(Alignment.TopEnd),
                enter = slideInHorizontally { it } + fadeIn(),
                exit = slideOutHorizontally { it } + fadeOut()
            ) {
                SettingsPanel(
                    thinkingEnabled = thinkingEnabled,
                    thinkingBudget = viewModel.thinkingBudget.collectAsState().value,
                    onToggleThinking = { viewModel.toggleThinking() },
                    onSetThinkingBudget = { viewModel.setThinkingBudget(it) },
                    onClose = { showSettingsPanel = false },
                    cm = cm,
                    sf = sf,
                    t1 = t1,
                    t2 = t2,
                    ac = ac,
                    bd = bd
                )
            }
        }
    }

    if (showModelDialog) {
        ModelSelectorDialog(
            selectedModel = selectedModel,
            onSelectModel = { 
                viewModel.selectModel(it)
                showModelDialog = false
            },
            onDismiss = { showModelDialog = false },
            cm = cm,
            sf = sf,
            t1 = t1,
            t2 = t2,
            ac = ac
        )
    }

    if (showSessionStats) {
        StatsDialog(
            stats = viewModel.getSessionStats(),
            onDismiss = { showSessionStats = false },
            cm = cm,
            sf = sf,
            t1 = t1,
            t2 = t2
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ProfessionalTopBar(
    selectedModel: ClaudeModelConfig.ClaudeModel,
    sessionTokens: ClaudeModelConfig.ModelCost?,
    cacheModeEnabled: Boolean,
    thinkingEnabled: Boolean,
    sendToolsEnabled: Boolean,
    sendSystemPromptEnabled: Boolean,
    longContextEnabled: Boolean,
    conversationHistoryEnabled: Boolean,
    onToggleThinking: () -> Unit,
    onToggleTools: () -> Unit,
    onToggleSystemPrompt: () -> Unit,
    onToggleLongContext: () -> Unit,
    onToggleHistory: () -> Unit,
    onCopyChat: () -> Unit,
    onToggleCache: () -> Unit,
    onShowModel: () -> Unit,
    onShowStats: () -> Unit,
    onNewSession: () -> Unit,
    onToggleSettings: () -> Unit,
    cm: Boolean,
    bg: Color,
    t1: Color,
    t2: Color,
    t3: Color,
    ac: Color,
    gr: Color,
    rd: Color,
    or: Color,
    pu: Color
) {
    Surface(
        color = bg,
        shadowElevation = if (cm) 2.dp else 0.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "ANALYZER",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Black,
                        color = t1,
                        letterSpacing = 1.sp
                    )
                    if (cacheModeEnabled) {
                        Spacer(Modifier.width(8.dp))
                        Surface(
                            color = ProColors.blueSoft,
                            shape = RoundedCornerShape(6.dp),
                            border = BorderStroke(1.dp, ProColors.blue)
                        ) {
                            Text(
                                "CACHE",
                                Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = ProColors.blue,
                                letterSpacing = 0.5.sp
                            )
                        }
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(
                        onClick = onToggleHistory,
                        enabled = !cacheModeEnabled,
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            Icons.Default.History,
                            contentDescription = "History",
                            tint = if (conversationHistoryEnabled) ac else if (cacheModeEnabled) t3.copy(alpha = 0.3f) else t2,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(onClick = onCopyChat, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.CopyAll, "Copy", tint = t2, modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = onToggleCache, modifier = Modifier.size(40.dp)) {
                        Icon(
                            Icons.Default.Cached,
                            "Cache",
                            tint = if (cacheModeEnabled) ac else t2,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    IconButton(onClick = onShowModel, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.Psychology, "Model", tint = t2, modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = onShowStats, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.Analytics, "Stats", tint = t2, modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = onToggleSettings, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.Settings, "Settings", tint = t2, modifier = Modifier.size(20.dp))
                    }
                    IconButton(onClick = onNewSession, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.RestartAlt, "New", tint = t2, modifier = Modifier.size(20.dp))
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(selectedModel.emoji, fontSize = 18.sp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        selectedModel.displayName,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = t1
                    )
                }

                sessionTokens?.let { cost ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "%,d".format(cost.totalTokens),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = ac,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(" tok", fontSize = 11.sp, color = t3)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "€${String.format("%.4f", cost.totalCostEUR)}",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = gr,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FeatureToggle(
                    icon = "🧠",
                    label = "Think",
                    enabled = thinkingEnabled,
                    color = or,
                    onClick = onToggleThinking
                )
                FeatureToggle(
                    icon = "🔧",
                    label = "Tools",
                    enabled = sendToolsEnabled,
                    color = ac,
                    onClick = onToggleTools
                )
                FeatureToggle(
                    icon = "📋",
                    label = "System",
                    enabled = sendSystemPromptEnabled,
                    color = pu,
                    onClick = onToggleSystemPrompt
                )
                FeatureToggle(
                    icon = "1M",
                    label = "Long",
                    enabled = longContextEnabled,
                    color = rd,
                    onClick = onToggleLongContext,
                    isText = true
                )
            }
        }
    }
}

@Composable
private fun FeatureToggle(
    icon: String,
    label: String,
    enabled: Boolean,
    color: Color,
    onClick: () -> Unit,
    isText: Boolean = false
) {
    Surface(
        onClick = onClick,
        color = if (enabled) color.copy(alpha = 0.15f) else Color.Transparent,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.5.dp, if (enabled) color else ProColors.darkBorder)
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            if (isText) {
                Text(
                    icon,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = if (enabled) color else ProColors.darkText3,
                    fontFamily = FontFamily.Monospace
                )
            } else {
                Text(icon, fontSize = 14.sp)
            }
            Text(
                label,
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) color else ProColors.darkText3
            )
        }
    }
}

@Composable
private fun CacheStatusBar(
    timerMs: Long,
    warmed: Boolean,
    readTok: Int,
    writeTok: Int,
    savEUR: Double,
    hits: Int,
    viewModel: AnalyzerViewModel,
    cm: Boolean,
    sf: Color,
    t1: Color,
    t2: Color,
    t3: Color,
    ac: Color,
    gr: Color,
    rd: Color,
    yl: Color,
    bd: Color
) {
    val progress = if (timerMs > 0) (timerMs.toFloat() / ClaudeModelConfig.CACHE_TTL_MS).coerceIn(0f, 1f) else 0f
    val timerColor = when {
        timerMs <= 0 -> t3
        timerMs <= 60_000 -> rd
        timerMs <= 120_000 -> yl
        else -> gr
    }

    Surface(
        color = ProColors.blueSoft,
        shadowElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Timer,
                        "Timer",
                        tint = timerColor,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        viewModel.getCacheTimerFormatted(timerMs),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Black,
                        fontFamily = FontFamily.Monospace,
                        color = timerColor,
                        letterSpacing = 1.sp
                    )
                }

                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (warmed && timerMs > 0) ProColors.greenSoft else ProColors.redSoft,
                    border = BorderStroke(1.5.dp, if (warmed && timerMs > 0) gr else rd)
                ) {
                    Text(
                        if (warmed && timerMs > 0) "CACHED" else "EMPTY",
                        Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = if (warmed && timerMs > 0) gr else rd,
                        letterSpacing = 0.5.sp
                    )
                }
            }

            if (warmed) {
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                    color = timerColor,
                    trackColor = bd
                )
            }

            if (writeTok > 0 || readTok > 0) {
                Spacer(Modifier.height(12.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    StatChip("Write", "%,d".format(writeTok), t2, t3)
                    StatChip("Read", "%,d".format(readTok), ac, ac.copy(alpha = 0.2f))
                    StatChip("Hits", hits.toString(), yl, yl.copy(alpha = 0.2f))
                    StatChip("Saved", "€${String.format("%.4f", savEUR)}", gr, gr.copy(alpha = 0.2f))
                }
            }
        }
    }
}

@Composable
private fun StatChip(label: String, value: String, textColor: Color, bgColor: Color) {
    Surface(
        color = bgColor,
        shape = RoundedCornerShape(6.dp)
    ) {
        Column(
            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                label,
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium,
                color = textColor.copy(alpha = 0.7f)
            )
            Text(
                value,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
                color = textColor
            )
        }
    }
}

@Composable
private fun OperationsPanel(
    operationsLog: List<AnalyzerViewModel.OperationLogItem>,
    opsListState: androidx.compose.foundation.lazy.LazyListState,
    ecoOutputMode: Boolean,
    cacheModeEnabled: Boolean,
    onClearLog: () -> Unit,
    cm: Boolean,
    sf: Color,
    sfVar: Color,
    t1: Color,
    t2: Color,
    t3: Color,
    ac: Color,
    gr: Color,
    rd: Color,
    yl: Color,
    bd: Color
) {
    Surface(
        color = if (cm) sfVar else sf,
        modifier = Modifier.fillMaxWidth().height(180.dp)
    ) {
        Column {
            Surface(
                color = if (cm) ProColors.lightBorder else ProColors.darkSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "OPERATIONS",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Black,
                            fontFamily = FontFamily.Monospace,
                            color = t2,
                            letterSpacing = 0.5.sp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "(${operationsLog.size})",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = t3
                        )
                    }

                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(
                            shape = CircleShape,
                            color = when {
                                cacheModeEnabled -> ac.copy(alpha = 0.2f)
                                ecoOutputMode -> gr.copy(alpha = 0.2f)
                                else -> rd.copy(alpha = 0.2f)
                            }
                        ) {
                            Row(
                                Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Box(
                                    Modifier.size(6.dp).clip(CircleShape).background(
                                        when {
                                            cacheModeEnabled -> ac
                                            ecoOutputMode -> gr
                                            else -> rd
                                        }
                                    )
                                )
                                Text(
                                    when {
                                        cacheModeEnabled -> "CACHE"
                                        ecoOutputMode -> "ECO"
                                        else -> "MAX"
                                    },
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontFamily = FontFamily.Monospace,
                                    color = when {
                                        cacheModeEnabled -> ac
                                        ecoOutputMode -> gr
                                        else -> rd
                                    }
                                )
                            }
                        }

                        IconButton(onClick = onClearLog, modifier = Modifier.size(24.dp)) {
                            Icon(
                                Icons.Default.DeleteSweep,
                                "Clear",
                                tint = t3,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            if (operationsLog.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Waiting for operations...",
                        fontSize = 12.sp,
                        color = t3,
                        fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                    )
                }
            } else {
                LazyColumn(
                    state = opsListState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(operationsLog, key = { it.id }) { item ->
                        OperationLogRow(item, t1, t2, t3, gr, rd, yl)
                    }
                }
            }
        }
    }
}

@Composable
private fun OperationLogRow(
    item: AnalyzerViewModel.OperationLogItem,
    t1: Color,
    t2: Color,
    t3: Color,
    gr: Color,
    rd: Color,
    yl: Color
) {
    val color = when (item.type) {
        AnalyzerViewModel.OperationLogType.SUCCESS -> gr
        AnalyzerViewModel.OperationLogType.ERROR -> rd
        AnalyzerViewModel.OperationLogType.PROGRESS -> yl
        else -> t2
    }

    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(item.icon, fontSize = 13.sp, modifier = Modifier.width(24.dp))
        Text(
            item.message,
            color = color,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(8.dp))
        Text(
            opsTimeFormat.format(Date(item.timestamp)),
            color = t3,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun ErrorBanner(
    error: String,
    onDismiss: () -> Unit,
    cm: Boolean,
    rd: Color,
    t1: Color,
    t2: Color
) {
    Surface(
        color = if (cm) ProColors.redSoft else ProColors.redSoftDark,
        modifier = Modifier.fillMaxWidth().padding(12.dp),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, rd)
    ) {
        Row(
            Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.Error, "Error", tint = rd, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                error,
                color = rd,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Close, "Dismiss", tint = t2, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun MessageBubble(
    msg: ChatMessageEntity,
    cm: Boolean,
    sf: Color,
    sfVar: Color,
    t1: Color,
    t2: Color,
    t3: Color,
    ac: Color,
    gr: Color,
    bd: Color,
    clipboardManager: androidx.compose.ui.platform.ClipboardManager,
    snackbarHostState: SnackbarHostState,
    coroutineScope: kotlinx.coroutines.CoroutineScope
) {
    val isUser = msg.role == MessageRole.USER
    val isSystem = msg.role == MessageRole.SYSTEM
    val isAssistant = msg.role == MessageRole.ASSISTANT

    val MAX_DISPLAY_CHARS = 16_000
    val isLong = msg.content.length > MAX_DISPLAY_CHARS
    var expanded by remember { mutableStateOf(false) }

    val displayContent = if (isLong && !expanded) {
        msg.content.take(MAX_DISPLAY_CHARS) +
                "\n\n─── Shown ${MAX_DISPLAY_CHARS / 1024}KB of ${msg.content.length / 1024}KB ───"
    } else {
        msg.content
    }

    val bgColor = when {
        cm && isUser -> ProColors.blueSoft
        cm && isSystem -> ProColors.greenSoft
        cm -> sf
        isUser -> ProColors.blueSoftDark
        isSystem -> ProColors.greenSoftDark
        else -> sfVar
    }

    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (isUser) Alignment.End else Alignment.Start
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            when {
                isUser -> {
                    Text("👤", fontSize = 12.sp)
                    Text("You", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = ac)
                }
                isSystem -> {
                    Text("⚙️", fontSize = 12.sp)
                    Text("System", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = gr)
                }
                else -> {
                    Text("🤖", fontSize = 12.sp)
                    Text("Claude", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = ProColors.purple)
                }
            }
        }

        Surface(
            color = bgColor,
            shape = RoundedCornerShape(
                topStart = if (isUser) 16.dp else 4.dp,
                topEnd = if (isUser) 4.dp else 16.dp,
                bottomStart = 16.dp,
                bottomEnd = 16.dp
            ),
            border = if (cm) BorderStroke(1.dp, bd) else null,
            shadowElevation = if (cm) 1.dp else 0.dp,
            modifier = Modifier.fillMaxWidth(if (isUser) 0.88f else 0.96f)
        ) {
            SelectionContainer {
                Text(
                    displayContent,
                    color = if (isSystem) gr else t1,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 20.sp,
                    modifier = Modifier.padding(14.dp)
                )
            }
        }

        if (isAssistant) {
            Row(
                Modifier.padding(start = 12.dp, top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                var copied by remember { mutableStateOf(false) }

                Surface(
                    onClick = {
                        clipboardManager.setText(AnnotatedString(msg.content))
                        copied = true
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar("✅ Copied")
                            kotlinx.coroutines.delay(2000)
                            copied = false
                        }
                    },
                    color = if (copied) gr.copy(alpha = 0.1f) else Color.Transparent,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    Row(
                        Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            if (copied) Icons.Default.Check else Icons.Default.ContentCopy,
                            "Copy",
                            tint = if (copied) gr else t3,
                            modifier = Modifier.size(14.dp)
                        )
                        if (copied) {
                            Text(
                                "Copied",
                                fontSize = 10.sp,
                                color = gr,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                if (isLong) {
                    Surface(
                        onClick = { expanded = !expanded },
                        color = ac.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Row(
                            Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                "Expand",
                                tint = ac,
                                modifier = Modifier.size(14.dp)
                            )
                            Text(
                                if (expanded) "Collapse" else "Show all (${msg.content.length / 1024}KB)",
                                fontSize = 10.sp,
                                color = ac,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StreamingBubble(
    text: String,
    cm: Boolean,
    sf: Color,
    t1: Color,
    t2: Color,
    ac: Color
) {
    val infiniteTransition = rememberInfiniteTransition(label = "streaming")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse"
    )

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("🤖", fontSize = 12.sp)
            Text("Claude", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = ProColors.purple)
            CircularProgressIndicator(
                modifier = Modifier.size(12.dp),
                color = ac.copy(alpha = alpha),
                strokeWidth = 2.dp
            )
            Text("streaming...", fontSize = 10.sp, color = t2.copy(alpha = alpha))
        }

        Surface(
            color = if (cm) sf else ProColors.darkSurfaceVariant,
            shape = RoundedCornerShape(topStart = 4.dp, topEnd = 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp),
            border = if (cm) BorderStroke(1.dp, ProColors.lightBorder) else BorderStroke(1.dp, ac.copy(alpha = 0.3f)),
            shadowElevation = if (cm) 1.dp else 0.dp,
            modifier = Modifier.fillMaxWidth(0.96f)
        ) {
            val displayText = if (text.length > 8192) {
                "⏳ Received ${text.length / 1024}KB...\n${"─".repeat(30)}\n${text.takeLast(8192)}"
            } else {
                text.ifEmpty { "█" }
            }

            SelectionContainer {
                Text(
                    displayText,
                    color = t1,
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace,
                    lineHeight = 20.sp,
                    modifier = Modifier.padding(14.dp)
                )
            }
        }
    }
}

@Composable
private fun AttachedFileCard(
    fileName: String,
    fileSize: Long,
    onDetach: () -> Unit,
    cm: Boolean,
    sf: Color,
    t1: Color,
    t2: Color,
    gr: Color,
    bd: Color
) {
    Surface(
        color = if (cm) ProColors.greenSoft else ProColors.greenSoftDark,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, gr)
    ) {
        Row(
            Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Default.AttachFile, "File", tint = gr, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    fileName,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = gr,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "${fileSize / 1024}KB",
                    fontSize = 10.sp,
                    color = gr.copy(alpha = 0.7f),
                    fontFamily = FontFamily.Monospace
                )
            }
            IconButton(onClick = onDetach, modifier = Modifier.size(28.dp)) {
                Icon(Icons.Default.Close, "Detach", tint = t2, modifier = Modifier.size(16.dp))
            }
        }
    }
}

@Composable
private fun InputArea(
    userInput: String,
    onInputChange: (String) -> Unit,
    isStreaming: Boolean,
    ecoOutputMode: Boolean,
    cacheModeEnabled: Boolean,
    cacheIsWarmed: Boolean,
    attachedFileName: String?,
    maxTokens: Int,
    onToggleMode: () -> Unit,
    onAttachFile: () -> Unit,
    onSend: () -> Unit,
    cm: Boolean,
    sf: Color,
    sfVar: Color,
    t1: Color,
    t2: Color,
    ac: Color,
    gr: Color,
    rd: Color,
    bd: Color
) {
    Surface(
        color = if (cm) sfVar else sf,
        shadowElevation = if (cm) 4.dp else 0.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Surface(
                onClick = onToggleMode,
                enabled = !cacheModeEnabled,
                shape = CircleShape,
                color = when {
                    cacheModeEnabled -> ac.copy(alpha = 0.1f)
                    ecoOutputMode -> gr.copy(alpha = 0.1f)
                    else -> rd.copy(alpha = 0.1f)
                },
                border = BorderStroke(
                    2.dp,
                    when {
                        cacheModeEnabled -> ac
                        ecoOutputMode -> gr
                        else -> rd
                    }
                ),
                modifier = Modifier.size(46.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Box(
                        Modifier.size(12.dp).clip(CircleShape).background(
                            when {
                                cacheModeEnabled -> ac
                                ecoOutputMode -> gr
                                else -> rd
                            }
                        )
                    )
                }
            }

            IconButton(
                onClick = onAttachFile,
                modifier = Modifier.size(46.dp)
            ) {
                Icon(
                    Icons.Default.AttachFile,
                    "Attach",
                    tint = if (attachedFileName != null) gr else t2,
                    modifier = Modifier.size(22.dp)
                )
            }

            OutlinedTextField(
                value = userInput,
                onValueChange = onInputChange,
                modifier = Modifier.weight(1f),
                placeholder = {
                    val label = when {
                        cacheModeEnabled && cacheIsWarmed -> "CACHE ${"%,d".format(maxTokens)}..."
                        cacheModeEnabled -> "CACHE ${"%,d".format(maxTokens)}..."
                        ecoOutputMode -> "ECO 8K..."
                        else -> "MAX ${"%,d".format(maxTokens)}..."
                    }
                    Text(label, color = t2, fontSize = 13.sp)
                },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = ac,
                    unfocusedBorderColor = bd,
                    cursorColor = ac,
                    focusedTextColor = t1,
                    unfocusedTextColor = t1
                ),
                maxLines = 6,
                textStyle = LocalTextStyle.current.copy(
                    fontSize = 14.sp,
                    fontFamily = FontFamily.Monospace
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
                shape = RoundedCornerShape(14.dp)
            )

            FilledIconButton(
                onClick = onSend,
                enabled = userInput.isNotBlank() && !isStreaming,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = ac,
                    contentColor = Color.White,
                    disabledContainerColor = bd,
                    disabledContentColor = t2
                ),
                modifier = Modifier.size(52.dp)
            ) {
                Icon(
                    if (isStreaming) Icons.Default.HourglassTop else Icons.Default.Send,
                    "Send",
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
private fun SettingsPanel(
    thinkingEnabled: Boolean,
    thinkingBudget: Int,
    onToggleThinking: () -> Unit,
    onSetThinkingBudget: (Int) -> Unit,
    onClose: () -> Unit,
    cm: Boolean,
    sf: Color,
    t1: Color,
    t2: Color,
    ac: Color,
    bd: Color
) {
    Surface(
        color = sf,
        shadowElevation = 8.dp,
        modifier = Modifier.width(320.dp).fillMaxHeight().padding(top = 80.dp),
        shape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp)
    ) {
        Column(Modifier.padding(20.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "SETTINGS",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Black,
                    color = t1,
                    letterSpacing = 1.sp
                )
                IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Default.Close, "Close", tint = t2)
                }
            }

            Spacer(Modifier.height(24.dp))

            Text(
                "Extended Thinking Budget",
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = t1
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "Current: ${"%,d".format(thinkingBudget)} tokens",
                fontSize = 11.sp,
                color = t2,
                fontFamily = FontFamily.Monospace
            )
            Spacer(Modifier.height(12.dp))

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(10_000, 20_000, 40_000, 60_000).forEach { budget ->
                    Surface(
                        onClick = { onSetThinkingBudget(budget) },
                        color = if (thinkingBudget == budget) ac.copy(alpha = 0.2f) else Color.Transparent,
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, if (thinkingBudget == budget) ac else bd)
                    ) {
                        Text(
                            "${budget / 1000}K",
                            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (thinkingBudget == budget) ac else t2
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelSelectorDialog(
    selectedModel: ClaudeModelConfig.ClaudeModel,
    onSelectModel: (ClaudeModelConfig.ClaudeModel) -> Unit,
    onDismiss: () -> Unit,
    cm: Boolean,
    sf: Color,
    t1: Color,
    t2: Color,
    ac: Color
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "SELECT MODEL",
                fontWeight = FontWeight.Black,
                letterSpacing = 1.sp
            )
        },
        text = {
            LazyColumn(
                Modifier.heightIn(max = 500.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(ClaudeModelConfig.ClaudeModel.entries.toList()) { model ->
                    val selected = model == selectedModel
                    val experimental = model == ClaudeModelConfig.ClaudeModel.OPUS_4_1 || 
                                     model == ClaudeModelConfig.ClaudeModel.OPUS_4

                    Surface(
                        onClick = { onSelectModel(model) },
                        color = when {
                            selected -> ac.copy(alpha = 0.15f)
                            experimental -> ProColors.orangeSoft
                            else -> sf
                        },
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.5.dp, if (selected) ac else ProColors.darkBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(model.emoji, fontSize = 24.sp)
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(
                                    model.displayName,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = t1
                                )
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    "In: $${model.inputPricePerM} | Out: $${model.outputPricePerM}",
                                    fontSize = 10.sp,
                                    color = t2,
                                    fontFamily = FontFamily.Monospace
                                )
                                Text(
                                    "Max out: ${"%,d".format(model.maxOutputTokens)}",
                                    fontSize = 10.sp,
                                    color = t2,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                            if (selected) {
                                Icon(
                                    Icons.Default.CheckCircle,
                                    "Selected",
                                    tint = ac,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("CLOSE", fontWeight = FontWeight.Bold)
            }
        }
    )
}

@Composable
private fun StatsDialog(
    stats: String?,
    onDismiss: () -> Unit,
    cm: Boolean,
    sf: Color,
    t1: Color,
    t2: Color
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "SESSION STATISTICS",
                fontWeight = FontWeight.Black,
                letterSpacing = 1.sp
            )
        },
        text = {
            if (stats != null) {
                SelectionContainer {
                    Text(
                        stats,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        lineHeight = 18.sp,
                        color = t1
                    )
                }
            } else {
                Text(
                    "No active session",
                    color = t2,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("CLOSE", fontWeight = FontWeight.Bold)
            }
        }
    )
}