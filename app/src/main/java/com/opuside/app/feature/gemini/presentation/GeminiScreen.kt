package com.opuside.app.feature.gemini.presentation

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
import androidx.compose.foundation.text.selection.SelectionContainer
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.hilt.navigation.compose.hiltViewModel
import com.opuside.app.core.ai.GeminiModelConfig
import com.opuside.app.core.ai.GeminiModelConfig.*
import com.opuside.app.core.database.entity.ChatMessageEntity
import com.opuside.app.core.database.entity.MessageRole
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ═══════════════════════════════════════════════════════════════════════════
// GEMINI COLOR SCHEME (Google brand-inspired dark theme)
// ═══════════════════════════════════════════════════════════════════════════

private object G {
    val bg = Color(0xFF0D1117)
    val surface = Color(0xFF161B22)
    val surfaceVar = Color(0xFF1C2333)
    val border = Color(0xFF30363D)
    val blue = Color(0xFF4285F4)
    val green = Color(0xFF34A853)
    val yellow = Color(0xFFFBBC04)
    val red = Color(0xFFEA4335)
    val purple = Color(0xFF9C7CFF)
    val orange = Color(0xFFFF8B3D)
    val cyan = Color(0xFF24C1E0)
    val t1 = Color(0xFFE6EDF3)
    val t2 = Color(0xFF8B949E)
    val t3 = Color(0xFF6E7681)
    val blueSoft = Color(0xFF1A2744)
    val greenSoft = Color(0xFF1A2E1A)
    val redSoft = Color(0xFF2E1A1A)
}

// ═══════════════════════════════════════════════════════════════════════════
// MAIN SCREEN
// ═══════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GeminiScreen(
    viewModel: GeminiViewModel = hiltViewModel(),
    onBackToAnalyzer: () -> Unit
) {
    val messages by viewModel.messages.collectAsState(initial = emptyList())
    val isStreaming by viewModel.isStreaming.collectAsState()
    val chatError by viewModel.chatError.collectAsState()
    val selectedModel by viewModel.selectedModel.collectAsState()
    val sessionCost by viewModel.sessionCost.collectAsState()
    val streamingText by viewModel.streamingText.collectAsState()
    val operationsLog by viewModel.operationsLog.collectAsState()
    val genConfig by viewModel.generationConfig.collectAsState()
    val sendToolsEnabled by viewModel.sendToolsEnabled.collectAsState()
    val sendSystemPromptEnabled by viewModel.sendSystemPromptEnabled.collectAsState()
    val conversationHistoryEnabled by viewModel.conversationHistoryEnabled.collectAsState()
    val attachedFileName by viewModel.attachedFileName.collectAsState()
    val attachedFileSize by viewModel.attachedFileSize.collectAsState()
    val ecoOutputMode by viewModel.ecoOutputMode.collectAsState()

    val isKeyboardVisible = WindowInsets.isImeVisible
    var userInput by remember { mutableStateOf("") }
    var showModelDialog by remember { mutableStateOf(false) }
    var showSettingsPanel by remember { mutableStateOf(false) }
    var showSessionStats by remember { mutableStateOf(false) }

    val chatListState = rememberLazyListState()
    val opsListState = rememberLazyListState()
    val focusManager = LocalFocusManager.current
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
            viewModel.attachFile(fileName, String(bytes, Charsets.UTF_8), bytes.size.toLong())
        } catch (e: Exception) {
            viewModel.addOperation("❌", "File error: ${e.message}", GeminiViewModel.OperationLogType.ERROR)
        }
    }

    val hasStreamingBubble = isStreaming && streamingText != null
    val totalItems = messages.size + (if (hasStreamingBubble) 1 else 0)
    LaunchedEffect(totalItems) { if (totalItems > 0) chatListState.animateScrollToItem(totalItems - 1) }
    LaunchedEffect(operationsLog.size) { if (operationsLog.isNotEmpty()) opsListState.animateScrollToItem(operationsLog.size - 1) }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopBar(
                selectedModel = selectedModel, sessionCost = sessionCost, isStreaming = isStreaming,
                sendToolsEnabled = sendToolsEnabled, sendSystemPromptEnabled = sendSystemPromptEnabled,
                conversationHistoryEnabled = conversationHistoryEnabled,
                onStopStreaming = { viewModel.cancelStreaming() },
                onToggleTools = { viewModel.toggleSendTools() },
                onToggleSystemPrompt = { viewModel.toggleSendSystemPrompt() },
                onToggleHistory = { viewModel.toggleConversationHistory() },
                onCopyChat = {
                    coroutineScope.launch {
                        val text = viewModel.getChatAsText()
                        if (text.isNotEmpty()) { clipboardManager.setText(AnnotatedString(text)); snackbarHostState.showSnackbar("✅ Chat copied") }
                    }
                },
                onShowModel = { showModelDialog = true }, onShowStats = { showSessionStats = true },
                onToggleSettings = { showSettingsPanel = !showSettingsPanel },
                onNewSession = { viewModel.startNewSession() }, onBackToAnalyzer = onBackToAnalyzer
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) { d -> Snackbar(snackbarData = d, containerColor = G.surface, contentColor = G.t1, shape = RoundedCornerShape(12.dp), modifier = Modifier.padding(12.dp)) } },
        containerColor = G.bg
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).imePadding()) {
            Column(Modifier.fillMaxSize()) {
                AnimatedVisibility(visible = !isKeyboardVisible, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    Column { OpsPanel(operationsLog, opsListState, ecoOutputMode) { viewModel.clearOperationsLog() }; HorizontalDivider(color = G.border, thickness = 1.dp) }
                }
                Column(Modifier.fillMaxWidth().weight(1f)) {
                    AnimatedVisibility(visible = chatError != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                        ErrorBanner(chatError ?: "") { viewModel.dismissError() }
                    }
                    LazyColumn(state = chatListState, modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(messages, key = { it.id }) { msg -> MsgBubble(msg, clipboardManager, snackbarHostState, coroutineScope) }
                        if (hasStreamingBubble) { item(key = "streaming") { StreamBubble(streamingText ?: "") } }
                    }
                    AnimatedVisibility(visible = attachedFileName != null, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                        FileCard(attachedFileName ?: "", attachedFileSize) { viewModel.detachFile() }
                    }
                    InputArea(userInput, { userInput = it }, isStreaming, ecoOutputMode, attachedFileName, genConfig.maxOutputTokens,
                        onToggleMode = { viewModel.toggleOutputMode() },
                        onAttachFile = { if (attachedFileName != null) viewModel.detachFile() else filePickerLauncher.launch(arrayOf("text/*")) },
                        onSend = { if (userInput.isNotBlank() && !isStreaming) { viewModel.sendMessage(userInput.trim()); userInput = ""; focusManager.clearFocus() } })
                }
            }
            AnimatedVisibility(visible = showSettingsPanel, modifier = Modifier.align(Alignment.TopEnd), enter = slideInHorizontally { it } + fadeIn(), exit = slideOutHorizontally { it } + fadeOut()) {
                SettingsPanel(genConfig, selectedModel, viewModel) { viewModel.updateGenerationConfig(it) ; showSettingsPanel = showSettingsPanel /* keep open */ }
                // Close via onClose in SettingsPanel header
            }
        }
    }
    if (showModelDialog) ModelDialog(selectedModel, { viewModel.selectModel(it); showModelDialog = false }) { showModelDialog = false }
    if (showSessionStats) StatsDialog(viewModel.getSessionStats()) { showSessionStats = false }
}

// ═══════════════════════════════════════════════════════════════════════════
// TOP BAR
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun TopBar(
    selectedModel: GeminiModel, sessionCost: GeminiCost?, isStreaming: Boolean,
    sendToolsEnabled: Boolean, sendSystemPromptEnabled: Boolean, conversationHistoryEnabled: Boolean,
    onStopStreaming: () -> Unit, onToggleTools: () -> Unit, onToggleSystemPrompt: () -> Unit,
    onToggleHistory: () -> Unit, onCopyChat: () -> Unit, onShowModel: () -> Unit,
    onShowStats: () -> Unit, onToggleSettings: () -> Unit, onNewSession: () -> Unit, onBackToAnalyzer: () -> Unit
) {
    Surface(color = G.bg, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBackToAnalyzer, modifier = Modifier.size(36.dp)) { Icon(Icons.Default.ArrowBack, "Back", tint = G.t2, modifier = Modifier.size(20.dp)) }
                    Spacer(Modifier.width(4.dp))
                    Text("GEMINI", fontSize = 20.sp, fontWeight = FontWeight.Black, color = G.blue, letterSpacing = 1.sp)
                    Spacer(Modifier.width(8.dp))
                    Surface(color = G.blueSoft, shape = RoundedCornerShape(6.dp), border = BorderStroke(1.dp, G.blue)) {
                        Text("API", Modifier.padding(horizontal = 8.dp, vertical = 3.dp), fontSize = 10.sp, fontWeight = FontWeight.ExtraBold, color = G.blue)
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    SmallBtn(Icons.Default.CopyAll, "Copy", G.t2, onCopyChat)
                    SmallBtn(Icons.Default.Psychology, "Model", G.t2, onShowModel)
                    SmallBtn(Icons.Default.Analytics, "Stats", G.t2, onShowStats)
                    SmallBtn(Icons.Default.Tune, "Tune", G.blue, onToggleSettings)
                    SmallBtn(Icons.Default.RestartAlt, "New", G.t2, onNewSession)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(selectedModel.emoji, fontSize = 18.sp); Spacer(Modifier.width(6.dp))
                    Text(selectedModel.displayName, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = G.t1)
                }
                sessionCost?.let { c ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("%,d".format(c.totalTokens), fontSize = 13.sp, fontWeight = FontWeight.Medium, color = G.blue, fontFamily = FontFamily.Monospace)
                        Text(" tok", fontSize = 11.sp, color = G.t3); Spacer(Modifier.width(12.dp))
                        Text("€${String.format("%.4f", c.totalCostEUR)}", fontSize = 13.sp, fontWeight = FontWeight.Medium, color = G.green, fontFamily = FontFamily.Monospace)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                Chip("💬", "History", conversationHistoryEnabled, G.green, onToggleHistory)
                Chip("🔧", "Tools", sendToolsEnabled, G.blue, onToggleTools)
                Chip("📋", "System", sendSystemPromptEnabled, G.purple, onToggleSystemPrompt)
                if (isStreaming) {
                    Surface(onClick = onStopStreaming, color = G.red.copy(0.15f), shape = RoundedCornerShape(8.dp), border = BorderStroke(1.5.dp, G.red)) {
                        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            Icon(Icons.Default.Stop, "Stop", tint = G.red, modifier = Modifier.size(16.dp)); Text("Stop", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = G.red)
                        }
                    }
                }
            }
        }
    }
}

@Composable private fun SmallBtn(icon: androidx.compose.ui.graphics.vector.ImageVector, desc: String, tint: Color, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(34.dp)) { Icon(icon, desc, tint = tint, modifier = Modifier.size(18.dp)) }
}
@Composable private fun Chip(icon: String, label: String, enabled: Boolean, color: Color, onClick: () -> Unit) {
    Surface(onClick = onClick, color = if (enabled) color.copy(0.15f) else Color.Transparent, shape = RoundedCornerShape(8.dp), border = BorderStroke(1.5.dp, if (enabled) color else G.border)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(icon, fontSize = 14.sp); Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = if (enabled) color else G.t3)
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// SETTINGS PANEL (AI Studio style)
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun SettingsPanel(config: GenerationConfig, model: GeminiModel, viewModel: GeminiViewModel, onUpdate: (GenerationConfig) -> Unit) {
    val apiKeyMasked by viewModel.apiKeyMasked.collectAsState()
    var showCloseBtn by remember { mutableStateOf(true) } // always visible

    Surface(color = G.surface, shadowElevation = 12.dp, modifier = Modifier.width(340.dp).fillMaxHeight().padding(top = 60.dp),
        shape = RoundedCornerShape(topStart = 16.dp, bottomStart = 16.dp)) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(20.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("GENERATION CONFIG", fontSize = 14.sp, fontWeight = FontWeight.Black, color = G.blue, letterSpacing = 1.sp)
                // Close button handled by parent AnimatedVisibility toggle
            }
            Text("AI Studio parameters", fontSize = 10.sp, color = G.t3)

            Spacer(Modifier.height(16.dp))

            // ── API KEY ─────────────────────────────────────────────
            Sec("API Key")
            Text(apiKeyMasked, fontSize = 10.sp, color = G.green, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(4.dp))
            var apiKeyInput by remember { mutableStateOf("") }
            var showKey by remember { mutableStateOf(false) }
            OutlinedTextField(
                value = apiKeyInput, onValueChange = { apiKeyInput = it }, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("AIza...", color = G.t3, fontSize = 12.sp) },
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                textStyle = LocalTextStyle.current.copy(fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = G.t1),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = G.blue, unfocusedBorderColor = G.border, cursorColor = G.blue),
                singleLine = true, shape = RoundedCornerShape(8.dp),
                trailingIcon = {
                    Row {
                        IconButton(onClick = { showKey = !showKey }, modifier = Modifier.size(32.dp)) {
                            Icon(if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility, "Toggle", tint = G.t2, modifier = Modifier.size(16.dp))
                        }
                        if (apiKeyInput.isNotBlank()) {
                            IconButton(onClick = { viewModel.setApiKey(apiKeyInput); apiKeyInput = "" }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.Check, "Save", tint = G.green, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            )

            Spacer(Modifier.height(20.dp))

            // ── Presets ─────────────────────────────────────────────
            Sec("Quick Presets")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PChip("ECO", config.maxOutputTokens == 8192 && config.temperature == 0.7f) { onUpdate(GenerationConfig.ECO) }
                PChip("MAX", config.maxOutputTokens == 65_536 && config.temperature == 1.0f) { onUpdate(GenerationConfig.MAX) }
                PChip("CODE", config.temperature == 0.2f) { onUpdate(GenerationConfig.CODE) }
                PChip("Creative", config.temperature == 1.5f) { onUpdate(GenerationConfig.CREATIVE) }
            }

            Spacer(Modifier.height(16.dp))
            Slider("Temperature", config.temperature, 0f..2f, "%.2f", "0=deterministic, 2=creative") { onUpdate(config.copy(temperature = it)) }
            Slider("Top P", config.topP, 0f..1f, "%.2f", "Nucleus sampling probability") { onUpdate(config.copy(topP = it)) }
            Slider("Top K", config.topK.toFloat(), 1f..100f, "%.0f", "Top tokens to consider") { onUpdate(config.copy(topK = it.toInt())) }

            Spacer(Modifier.height(16.dp))
            Sec("Max Output Tokens")
            Text("${"%,d".format(config.maxOutputTokens)} / ${"%,d".format(model.maxOutputTokens)}", fontSize = 12.sp, color = G.blue, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(4.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                listOf(2048, 4096, 8192, 16384, 32768, 65536).filter { it <= model.maxOutputTokens }.forEach { tok ->
                    TokChip("${tok / 1024}K", config.maxOutputTokens == tok) { onUpdate(config.copy(maxOutputTokens = tok)) }
                }
            }

            Spacer(Modifier.height(16.dp))
            Slider("Presence Penalty", config.presencePenalty, -2f..2f, "%.1f", "Penalize repeated topics") { onUpdate(config.copy(presencePenalty = it)) }
            Slider("Frequency Penalty", config.frequencyPenalty, -2f..2f, "%.1f", "Penalize repeated words") { onUpdate(config.copy(frequencyPenalty = it)) }

            if (model.supportsThinking) {
                Spacer(Modifier.height(16.dp))
                Sec("Thinking Level")
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                    ThinkingLevel.entries.forEach { level ->
                        TokChip(level.displayName, config.thinkingLevel == level, G.orange) { onUpdate(config.copy(thinkingLevel = level)) }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Sec("Response Format")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(null to "Auto", "text/plain" to "Text", "application/json" to "JSON").forEach { (mime, label) ->
                    TokChip(label, config.responseMimeType == mime, G.cyan) { onUpdate(config.copy(responseMimeType = mime)) }
                }
            }

            Spacer(Modifier.height(16.dp))
            Sec("Seed")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(null to "Off", 42 to "42", 123 to "123", 0 to "0").forEach { (seed, label) ->
                    TokChip(label, config.seed == seed, G.green) { onUpdate(config.copy(seed = seed)) }
                }
            }

            Spacer(Modifier.height(16.dp))
            Sec("Safety Settings")
            HarmCategory.entries.forEach { cat ->
                val cur = config.safetySettings[cat] ?: SafetyThreshold.BLOCK_MEDIUM_AND_ABOVE
                Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(cat.displayName, fontSize = 10.sp, color = G.t2, modifier = Modifier.weight(1f))
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        SafetyThreshold.entries.forEach { th ->
                            val c = when (th) { SafetyThreshold.BLOCK_NONE -> G.red; SafetyThreshold.BLOCK_ONLY_HIGH -> G.orange; SafetyThreshold.BLOCK_MEDIUM_AND_ABOVE -> G.yellow; SafetyThreshold.BLOCK_LOW_AND_ABOVE -> G.green }
                            Surface(onClick = { val m = config.safetySettings.toMutableMap(); m[cat] = th; onUpdate(config.copy(safetySettings = m)) },
                                color = if (cur == th) c.copy(0.2f) else Color.Transparent, shape = RoundedCornerShape(4.dp),
                                border = BorderStroke(1.dp, if (cur == th) c else G.border), modifier = Modifier.size(width = 34.dp, height = 22.dp)) {
                                Box(contentAlignment = Alignment.Center) { Text(th.displayName, fontSize = 7.sp, fontWeight = if (cur == th) FontWeight.Bold else FontWeight.Normal, color = if (cur == th) c else G.t3) }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Sec("Stop Sequences")
            var stopText by remember(config.stopSequences) { mutableStateOf(config.stopSequences.joinToString(", ")) }
            OutlinedTextField(value = stopText, onValueChange = { stopText = it; val s = it.split(",").map { x -> x.trim() }.filter { x -> x.isNotBlank() }.take(5); onUpdate(config.copy(stopSequences = s)) },
                modifier = Modifier.fillMaxWidth(), placeholder = { Text("e.g. END, ###", color = G.t3, fontSize = 11.sp) },
                textStyle = LocalTextStyle.current.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = G.t1),
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = G.blue, unfocusedBorderColor = G.border, cursorColor = G.blue),
                singleLine = true, shape = RoundedCornerShape(8.dp))

            Spacer(Modifier.height(16.dp))
            Sec("Model Info")
            val caps = listOf("Thinking" to model.supportsThinking, "Grounding" to model.supportsGrounding, "Code Exec" to model.supportsCodeExecution,
                "Function Calling" to model.supportsFunctionCalling, "JSON Mode" to model.supportsJsonMode, "System Instr" to model.supportsSystemInstruction)
            caps.forEach { (n, s) -> Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text(n, fontSize = 10.sp, color = G.t2); Text(if (s) "✅" else "❌", fontSize = 11.sp) } }
            Spacer(Modifier.height(8.dp))
            Text("In: \$${model.inputPricePerM}  Out: \$${model.outputPricePerM} /M", fontSize = 10.sp, color = G.t2, fontFamily = FontFamily.Monospace)
            if (model.longContextThreshold < Int.MAX_VALUE) Text(">200K: \$${model.longInputPricePerM}/\$${model.longOutputPricePerM}", fontSize = 10.sp, color = G.orange, fontFamily = FontFamily.Monospace)
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable private fun Sec(t: String) { Text(t, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = G.t1, letterSpacing = 0.3.sp); Spacer(Modifier.height(6.dp)) }
@Composable private fun PChip(l: String, s: Boolean, c: () -> Unit) {
    Surface(onClick = c, color = if (s) G.blue.copy(0.2f) else Color.Transparent, shape = RoundedCornerShape(6.dp), border = BorderStroke(1.dp, if (s) G.blue else G.border)) {
        Text(l, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = if (s) G.blue else G.t2) }
}
@Composable private fun TokChip(l: String, s: Boolean, c: Color = G.blue, onClick: () -> Unit) {
    Surface(onClick = onClick, color = if (s) c.copy(0.2f) else Color.Transparent, shape = RoundedCornerShape(6.dp), border = BorderStroke(1.dp, if (s) c else G.border)) {
        Text(l, Modifier.padding(horizontal = 8.dp, vertical = 4.dp), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = if (s) c else G.t2) }
}
@Composable private fun Slider(label: String, value: Float, range: ClosedFloatingPointRange<Float>, fmt: String, desc: String, onChange: (Float) -> Unit) {
    Column(Modifier.padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = G.t1)
            Text(String.format(fmt, value), fontSize = 11.sp, color = G.blue, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
        }
        Text(desc, fontSize = 9.sp, color = G.t3)
        androidx.compose.material3.Slider(value = value, onValueChange = onChange, valueRange = range,
            colors = SliderDefaults.colors(thumbColor = G.blue, activeTrackColor = G.blue, inactiveTrackColor = G.border))
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// OPS PANEL, MESSAGES, INPUT — compact implementations
// ═══════════════════════════════════════════════════════════════════════════

@Composable private fun OpsPanel(ops: List<GeminiViewModel.OperationLogItem>, state: androidx.compose.foundation.lazy.LazyListState, eco: Boolean, onClear: () -> Unit) {
    Surface(color = G.surfaceVar, modifier = Modifier.fillMaxWidth().height(150.dp)) {
        Column {
            Surface(color = G.surface, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("OPS", fontSize = 11.sp, fontWeight = FontWeight.Black, fontFamily = FontFamily.Monospace, color = G.t2)
                        Spacer(Modifier.width(6.dp)); Text("(${ops.size})", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = G.t3)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Surface(shape = CircleShape, color = if (eco) G.green.copy(0.2f) else G.red.copy(0.2f)) {
                            Row(Modifier.padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                                Box(Modifier.size(5.dp).clip(CircleShape).background(if (eco) G.green else G.red))
                                Text(if (eco) "ECO" else "MAX", fontSize = 8.sp, fontWeight = FontWeight.ExtraBold, fontFamily = FontFamily.Monospace, color = if (eco) G.green else G.red)
                            }
                        }
                        IconButton(onClick = onClear, modifier = Modifier.size(22.dp)) { Icon(Icons.Default.DeleteSweep, "Clear", tint = G.t3, modifier = Modifier.size(14.dp)) }
                    }
                }
            }
            if (ops.isEmpty()) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("Waiting...", fontSize = 11.sp, color = G.t3, fontStyle = androidx.compose.ui.text.font.FontStyle.Italic) }
            else LazyColumn(state = state, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(ops, key = { it.id }) { item ->
                    val c = when (item.type) { GeminiViewModel.OperationLogType.SUCCESS -> G.green; GeminiViewModel.OperationLogType.ERROR -> G.red; GeminiViewModel.OperationLogType.PROGRESS -> G.yellow; else -> G.t2 }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { Text(item.icon, fontSize = 12.sp, modifier = Modifier.width(22.dp)); Text(item.message, color = c, fontSize = 10.sp, fontFamily = FontFamily.Monospace, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable private fun MsgBubble(msg: ChatMessageEntity, clip: androidx.compose.ui.platform.ClipboardManager, snack: SnackbarHostState, scope: kotlinx.coroutines.CoroutineScope) {
    val isUser = msg.role == MessageRole.USER; val isAssist = msg.role == MessageRole.ASSISTANT
    val MAX = 16_000; val isLong = msg.content.length > MAX; var expanded by remember { mutableStateOf(false) }
    val display = if (isLong && !expanded) msg.content.take(MAX) + "\n─── ${MAX / 1024}KB of ${msg.content.length / 1024}KB ───" else msg.content
    val bg = if (isUser) G.blueSoft else G.surfaceVar

    Column(Modifier.fillMaxWidth(), horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            if (isUser) { Text("👤", fontSize = 11.sp); Text("You", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = G.blue) }
            else { Text("🔷", fontSize = 11.sp); Text("Gemini", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = G.cyan) }
        }
        Surface(color = bg, shape = RoundedCornerShape(topStart = if (isUser) 16.dp else 4.dp, topEnd = if (isUser) 4.dp else 16.dp, bottomStart = 16.dp, bottomEnd = 16.dp),
            border = BorderStroke(1.dp, G.border), modifier = Modifier.fillMaxWidth(if (isUser) 0.88f else 0.96f)) {
            SelectionContainer { Text(display, color = G.t1, fontSize = 13.sp, fontFamily = FontFamily.Monospace, lineHeight = 20.sp, modifier = Modifier.padding(14.dp)) }
        }
        if (isAssist) {
            Row(Modifier.padding(start = 12.dp, top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                var copied by remember { mutableStateOf(false) }
                Surface(onClick = { clip.setText(AnnotatedString(msg.content)); copied = true; scope.launch { snack.showSnackbar("✅ Copied"); delay(2000); copied = false } },
                    color = if (copied) G.green.copy(0.1f) else Color.Transparent, shape = RoundedCornerShape(6.dp)) {
                    Row(Modifier.padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        Icon(if (copied) Icons.Default.Check else Icons.Default.ContentCopy, "Copy", tint = if (copied) G.green else G.t3, modifier = Modifier.size(13.dp))
                    }
                }
                if (isLong) Surface(onClick = { expanded = !expanded }, color = G.blue.copy(0.1f), shape = RoundedCornerShape(6.dp)) {
                    Text(if (expanded) "▲ Collapse" else "▼ Show all", Modifier.padding(horizontal = 8.dp, vertical = 3.dp), fontSize = 9.sp, color = G.blue, fontWeight = FontWeight.Medium) }
            }
        }
    }
}

@Composable private fun StreamBubble(text: String) {
    val t = rememberInfiniteTransition(label = "s"); val a by t.animateFloat(0.3f, 1f, infiniteRepeatable(tween(800), RepeatMode.Reverse), label = "p")
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.Start) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("🔷", fontSize = 11.sp); Text("Gemini", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = G.cyan)
            CircularProgressIndicator(modifier = Modifier.size(10.dp), color = G.blue.copy(a), strokeWidth = 2.dp)
            Text("streaming...", fontSize = 9.sp, color = G.t2.copy(a))
        }
        Surface(color = G.surfaceVar, shape = RoundedCornerShape(4.dp, 16.dp, 16.dp, 16.dp), border = BorderStroke(1.dp, G.blue.copy(0.3f)), modifier = Modifier.fillMaxWidth(0.96f)) {
            val d = if (text.length > 8192) "⏳ ${text.length / 1024}KB...\n${"─".repeat(30)}\n${text.takeLast(8192)}" else text.ifEmpty { "█" }
            SelectionContainer { Text(d, color = G.t1, fontSize = 13.sp, fontFamily = FontFamily.Monospace, lineHeight = 20.sp, modifier = Modifier.padding(14.dp)) }
        }
    }
}

@Composable private fun ErrorBanner(err: String, onDismiss: () -> Unit) {
    Surface(color = G.redSoft, modifier = Modifier.fillMaxWidth().padding(12.dp), shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, G.red)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Error, "Err", tint = G.red, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(10.dp))
            Text(err, color = G.red, fontSize = 12.sp, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
            IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) { Icon(Icons.Default.Close, "X", tint = G.t2, modifier = Modifier.size(16.dp)) }
        }
    }
}

@Composable private fun FileCard(name: String, size: Long, onDetach: () -> Unit) {
    Surface(color = G.greenSoft, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, G.green)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.AttachFile, "File", tint = G.green, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) { Text(name, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = G.green, maxLines = 1, overflow = TextOverflow.Ellipsis); Text("${size / 1024}KB", fontSize = 9.sp, color = G.green.copy(0.7f), fontFamily = FontFamily.Monospace) }
            IconButton(onClick = onDetach, modifier = Modifier.size(26.dp)) { Icon(Icons.Default.Close, "Detach", tint = G.t2, modifier = Modifier.size(14.dp)) }
        }
    }
}

@Composable private fun InputArea(input: String, onChange: (String) -> Unit, streaming: Boolean, eco: Boolean, attached: String?, maxTok: Int, onToggleMode: () -> Unit, onAttachFile: () -> Unit, onSend: () -> Unit) {
    Surface(color = G.surface, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Surface(onClick = onToggleMode, shape = CircleShape, color = if (eco) G.green.copy(0.1f) else G.red.copy(0.1f), border = BorderStroke(2.dp, if (eco) G.green else G.red), modifier = Modifier.size(44.dp)) {
                Box(contentAlignment = Alignment.Center) { Box(Modifier.size(10.dp).clip(CircleShape).background(if (eco) G.green else G.red)) }
            }
            IconButton(onClick = onAttachFile, modifier = Modifier.size(44.dp)) { Icon(Icons.Default.AttachFile, "Attach", tint = if (attached != null) G.green else G.t2, modifier = Modifier.size(20.dp)) }
            OutlinedTextField(value = input, onValueChange = onChange, modifier = Modifier.weight(1f),
                placeholder = { Text(if (eco) "ECO 8K..." else "MAX ${"%,d".format(maxTok)}...", color = G.t2, fontSize = 13.sp) },
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = G.blue, unfocusedBorderColor = G.border, cursorColor = G.blue, focusedTextColor = G.t1, unfocusedTextColor = G.t1),
                maxLines = 6, textStyle = LocalTextStyle.current.copy(fontSize = 14.sp, fontFamily = FontFamily.Monospace),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { onSend() }), shape = RoundedCornerShape(14.dp))
            FilledIconButton(onClick = onSend, enabled = input.isNotBlank() && !streaming,
                colors = IconButtonDefaults.filledIconButtonColors(containerColor = G.blue, contentColor = Color.White, disabledContainerColor = G.border, disabledContentColor = G.t2),
                modifier = Modifier.size(50.dp)) { Icon(if (streaming) Icons.Default.HourglassTop else Icons.Default.Send, "Send", modifier = Modifier.size(22.dp)) }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// DIALOGS
// ═══════════════════════════════════════════════════════════════════════════

@Composable private fun ModelDialog(sel: GeminiModel, onSelect: (GeminiModel) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("GEMINI MODEL", fontWeight = FontWeight.Black, letterSpacing = 1.sp) },
        text = {
            LazyColumn(Modifier.heightIn(max = 500.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(GeminiModel.getActiveModels()) { m ->
                    val s = m == sel
                    Surface(onClick = { onSelect(m) }, color = if (s) G.blue.copy(0.15f) else G.surface, shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.5.dp, if (s) G.blue else G.border), modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(m.emoji, fontSize = 22.sp); Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(m.displayName, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = G.t1)
                                Text(m.description, fontSize = 9.sp, color = G.t2)
                                Spacer(Modifier.height(3.dp))
                                Text("In:\$${m.inputPricePerM} Out:\$${m.outputPricePerM}/M  Ctx:${m.contextWindow / 1000}K  Speed:${m.speedRating}/10", fontSize = 9.sp, color = G.t3, fontFamily = FontFamily.Monospace)
                                if (m.supportsThinking) Text("🧠 Thinking", fontSize = 8.sp, color = G.orange)
                            }
                            if (s) Icon(Icons.Default.CheckCircle, "✓", tint = G.blue, modifier = Modifier.size(22.dp))
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("CLOSE", fontWeight = FontWeight.Bold) } })
}

@Composable private fun StatsDialog(stats: String?, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("SESSION STATS", fontWeight = FontWeight.Black) },
        text = { if (stats != null) SelectionContainer { Text(stats, fontFamily = FontFamily.Monospace, fontSize = 12.sp, lineHeight = 18.sp, color = G.t1) } else Text("No session", color = G.t2) },
        confirmButton = { TextButton(onClick = onDismiss) { Text("CLOSE", fontWeight = FontWeight.Bold) } })
}
