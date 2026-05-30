package com.opuside.app.feature.pipeline.presentation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opuside.app.feature.pipeline.data.*
import com.opuside.app.feature.pipeline.data.LocalRepoManager
import com.opuside.app.feature.pipeline.data.PipelineMode
import com.opuside.app.feature.pipeline.data.PipelineKeyRotator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val LOG_TIME_FORMATTER = SimpleDateFormat("HH:mm:ss", Locale.US)

private fun formatLogTime(timestamp: Long): String =
    synchronized(LOG_TIME_FORMATTER) { LOG_TIME_FORMATTER.format(Date(timestamp)) }

/**
 * ═══════════════════════════════════════════════════════════════════════════
 * PIPELINE SCREEN — UI для G-конвейера
 * ═══════════════════════════════════════════════════════════════════════════
 */

private object PipelineColors {
    val backgroundDark = Color(0xFF0A0A0F)
    val surfaceDark = Color(0xFF15151D)
    val surfaceElevated = Color(0xFF1E1E28)
    val borderSubtle = Color(0xFF2A2A36)
    val textPrimary = Color(0xFFE4E4EA)
    val textSecondary = Color(0xFF9090A0)
    val textTertiary = Color(0xFF606070)

    val accentBlue = Color(0xFF4F8FFF)
    val accentGreen = Color(0xFF22C55E)
    val accentYellow = Color(0xFFEAB308)
    val accentRed = Color(0xFFEF4444)
    val accentPurple = Color(0xFFA855F7)

    val geminiGradient = Brush.horizontalGradient(
        listOf(Color(0xFF4F46E5), Color(0xFF7C3AED))
    )
    val repoGradient = Brush.horizontalGradient(
        listOf(Color(0xFF059669), Color(0xFF0891B2))
    )
}

@Composable
fun PipelineScreen(
    modifier: Modifier = Modifier,
    viewModel: PipelineViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val userPrompt by viewModel.userPrompt.collectAsStateWithLifecycle()
    val geminiLog by viewModel.visibleGeminiLog.collectAsStateWithLifecycle()
    val repoLog by viewModel.visibleRepoLog.collectAsStateWithLifecycle()
    val rawGeminiSize by viewModel.geminiLog.collectAsStateWithLifecycle()
    val rawRepoSize by viewModel.repoLog.collectAsStateWithLifecycle()
    val repoStats by viewModel.repoStats.collectAsStateWithLifecycle()
    val totalCost by viewModel.totalCostEur.collectAsStateWithLifecycle()
    val totalTokens by viewModel.totalTokens.collectAsStateWithLifecycle()
    val userError by viewModel.userError.collectAsStateWithLifecycle()
    val pipelineKeyA by viewModel.pipelineKeyA.collectAsStateWithLifecycle()
    val pipelineKeyB by viewModel.pipelineKeyB.collectAsStateWithLifecycle()
    val pipelineActiveKey by viewModel.pipelineActiveKey.collectAsStateWithLifecycle()
    val localRepoStatus by viewModel.localRepoStatus.collectAsStateWithLifecycle()
    val localRepoProgress by viewModel.localRepoProgress.collectAsStateWithLifecycle()

    var promptExpanded by remember { mutableStateOf(true) }

    LaunchedEffect(state.phase) {
        if (state.isRunning && promptExpanded) {
            promptExpanded = false
        }
    }

    val snackbarHostState = remember { SnackbarHostState() }
    LaunchedEffect(userError) {
        userError?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.dismissError()
        }
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHostState) },
        containerColor = PipelineColors.backgroundDark
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            PipelineHeader(repoStats = repoStats, runId = state.pipelineRunId)

            PipelineKeysSection(
                keyA = pipelineKeyA,
                keyB = pipelineKeyB,
                activeIndex = pipelineActiveKey,
                enabled = !state.isRunning,
                onKeyAChange = viewModel::setPipelineKeyA,
                onKeyBChange = viewModel::setPipelineKeyB,
                onActiveChange = viewModel::setPipelineActiveKey
            )

            PipelineModeSelector(
                currentMode = state.pipelineMode,
                interactive = !state.isRunning,
                onModeChange = viewModel::setPipelineMode
            )

            if (state.pipelineMode == PipelineMode.OFFLINE) {
                LocalCloneStatusSection(
                    status = localRepoStatus,
                    progress = localRepoProgress,
                    interactive = !state.isRunning,
                    onSync = viewModel::syncLocalRepo,
                    onDelete = viewModel::deleteLocalClone
                )
            }

            PromptSection(
                prompt = userPrompt,
                onPromptChange = viewModel::onPromptChange,
                expanded = promptExpanded,
                onToggleExpanded = { promptExpanded = !promptExpanded },
                isRunning = state.isRunning,
                phase = state.phase
            )



            StatusBar(
                state = state,
                totalCost = totalCost,
                totalTokens = totalTokens,
                onPlan = { viewModel.plan() },
                onStart = { viewModel.start() },
                onStop = { viewModel.stop() },
                onReset = { viewModel.reset() },
                onExport = { viewModel.exportRepoToTxt(context) }
            )

            if (state.tasks.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                ProgressSection(
                    state = state,
                    onRemoveTask = { taskId -> viewModel.removeTask(taskId) },
                    onTaskChipClick = { taskId ->
                        viewModel.setLogFilter(
                            if (state.logFilterTaskId == taskId) null else taskId
                        )
                    },
                    onChangeMaxParallel = viewModel::setMaxParallel
                )
            }

            if (rawGeminiSize.isNotEmpty() || rawRepoSize.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                if (state.logFilterTaskId != null) {
                    LogFilterBanner(
                        taskId = state.logFilterTaskId!!,
                        tasks = state.tasks,
                        onClear = { viewModel.setLogFilter(null) }
                    )
                }
                LiveLogsSection(geminiLog = geminiLog, repoLog = repoLog)
            }

            state.finalReport?.let { report ->
                Spacer(Modifier.height(12.dp))
                FinalReportSection(
                    report = report,
                    status = state.overallStatus
                )
            }

            state.fatalError?.takeIf { state.phase == PipelinePhase.FATAL }?.let { err ->
                Spacer(Modifier.height(12.dp))
                FatalErrorCard(err)
            }

            Spacer(Modifier.height(24.dp))
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// HEADER
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun PipelineHeader(repoStats: RepoStats?, runId: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                brush = Brush.horizontalGradient(
                    listOf(Color(0xFF1A1A28), Color(0xFF15151D))
                )
            )
            .border(
                width = 0.5.dp,
                color = PipelineColors.borderSubtle,
                shape = RoundedCornerShape(0.dp)
            )
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth()
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(brush = PipelineColors.geminiGradient),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "G",
                    color = Color.White,
                    fontWeight = FontWeight.Black,
                    fontSize = 24.sp
                )
            }
            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Pipeline",
                    color = PipelineColors.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 18.sp
                )
                if (repoStats != null) {
                    Text(
                        text = "${repoStats.owner}/${repoStats.repo} · ${repoStats.branch}",
                        color = PipelineColors.textSecondary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                } else {
                    Text(
                        text = "Загрузка...",
                        color = PipelineColors.textTertiary,
                        fontSize = 12.sp
                    )
                }
            }

            Text(
                text = "#$runId",
                color = PipelineColors.textTertiary,
                fontSize = 11.sp,
                fontFamily = FontFamily.Monospace
            )
        }
    }

    repoStats?.let { stats ->
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(PipelineColors.surfaceDark)
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            StatMicro("файлов", stats.totalFiles.toString())
            StatMicro("размер", stats.totalSizeFormatted)
            stats.topExtensions(3).forEach { (ext, count) ->
                StatMicro(".$ext", count.toString())
            }
        }
    }
}

@Composable
private fun StatMicro(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            color = PipelineColors.textPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            fontFamily = FontFamily.Monospace
        )
        Text(
            text = label,
            color = PipelineColors.textTertiary,
            fontSize = 10.sp
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// PROMPT SECTION
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun PromptSection(
    prompt: String,
    onPromptChange: (String) -> Unit,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    isRunning: Boolean,
    phase: PipelinePhase
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(PipelineColors.surfaceElevated)
            .border(0.5.dp, PipelineColors.borderSubtle, RoundedCornerShape(12.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggleExpanded)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "📝 Промпт",
                color = PipelineColors.textPrimary,
                fontWeight = FontWeight.Medium,
                fontSize = 14.sp,
                modifier = Modifier.weight(1f)
            )
            if (prompt.isNotBlank()) {
                Text(
                    text = "${prompt.length} ch",
                    color = PipelineColors.textTertiary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(Modifier.width(8.dp))
            }
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = null,
                tint = PipelineColors.textSecondary
            )
        }

        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut()
        ) {
            OutlinedTextField(
                value = prompt,
                onValueChange = onPromptChange,
                readOnly = isRunning,
                placeholder = {
                    Text(
                        "Вставь большой промпт с инструкциями для нескольких файлов. " +
                                "Планировщик разобьёт его на задачи.",
                        color = PipelineColors.textTertiary,
                        fontSize = 13.sp
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp, max = 320.dp)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = PipelineColors.textPrimary,
                    unfocusedTextColor = PipelineColors.textPrimary,
                    focusedContainerColor = PipelineColors.surfaceDark,
                    unfocusedContainerColor = PipelineColors.surfaceDark,
                    focusedBorderColor = PipelineColors.accentBlue,
                    unfocusedBorderColor = PipelineColors.borderSubtle,
                    cursorColor = PipelineColors.accentBlue
                ),
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontSize = 13.sp,
                    fontFamily = FontFamily.Monospace
                ),
                shape = RoundedCornerShape(8.dp)
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// STATUS BAR
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun StatusBar(
    state: PipelineState,
    totalCost: Double,
    totalTokens: Int,
    onPlan: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onReset: () -> Unit,
    onExport: () -> Unit
) {
    val statusColor by animateColorAsState(
        targetValue = when (state.overallStatus) {
            OverallStatus.RUNNING -> PipelineColors.accentBlue
            OverallStatus.SUCCESS_ALL -> PipelineColors.accentGreen
            OverallStatus.SUCCESS_PARTIAL -> PipelineColors.accentYellow
            OverallStatus.FAILED_ALL, OverallStatus.FATAL -> PipelineColors.accentRed
            OverallStatus.CANCELLED -> PipelineColors.textSecondary
        },
        label = "statusColor"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val (actionLabel, actionEnabled, actionHandler) = when (state.phase) {
                PipelinePhase.IDLE -> Triple("📋 Спланировать", true, onPlan)
                PipelinePhase.PLANNING -> Triple("⏳ Планируется...", false, {})
                PipelinePhase.REVIEWING -> {
                    val costStr = if (state.estimatedCost >= 0.0001) {
                        " · ~€${String.format(Locale.US, "%.4f", state.estimatedCost)}"
                    } else ""
                    Triple("▶️ Старт (${state.tasks.size}${costStr})", true, onStart)
                }
                PipelinePhase.EXECUTING -> Triple(
                    "⚡ Выполняется ${state.completedTasks}/${state.totalTasks}" +
                            if (state.runningTaskIds.size > 1) " (×${state.runningTaskIds.size})" else "",
                    false, {}
                )
                PipelinePhase.DEFERRED_PASS -> Triple(
                    "🔄 Retry-проход ${state.completedTasks}/${state.totalTasks}", false, {}
                )
                PipelinePhase.FINALIZING -> Triple("📝 Финальный отчёт...", false, {})
                PipelinePhase.DONE -> Triple("✅ Готово · Сброс?", true, onReset)
                PipelinePhase.CANCELLED -> Triple("⚪ Остановлено · Сброс?", true, onReset)
                PipelinePhase.FATAL -> Triple("🔴 FATAL · Сброс?", true, onReset)
            }

            Button(
                onClick = actionHandler,
                enabled = actionEnabled,
                modifier = Modifier.weight(1f).height(48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = statusColor,
                    disabledContainerColor = statusColor.copy(alpha = 0.4f)
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = actionLabel,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp
                )
            }

            if (state.canStop) {
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = onStop,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(PipelineColors.accentRed)
                ) {
                    Icon(
                        Icons.Default.Stop,
                        contentDescription = "Stop",
                        tint = Color.White
                    )
                }
            }
            if (state.phase == PipelinePhase.DONE) {
                Spacer(Modifier.width(8.dp))
                IconButton(
                    onClick = onExport,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(PipelineColors.surfaceElevated)
                        .border(1.dp, PipelineColors.borderSubtle, RoundedCornerShape(12.dp))
                ) {
                    Text("📤", fontSize = 20.sp)
                }
            }
        }

        if (totalCost > 0 || totalTokens > 0) {
            Spacer(Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "💰 €${String.format(Locale.US, "%.4f", totalCost)}",
                    color = PipelineColors.textSecondary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
                Text(
                    text = "🧮 $totalTokens токенов",
                    color = PipelineColors.textSecondary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace
                )
                if (state.tasks.isNotEmpty()) {
                    Text(
                        text = "✅ ${state.successfulTasks}  ❌ ${state.failedTasks}",
                        color = PipelineColors.textSecondary,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// PROGRESS SECTION
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun ProgressSection(
    state: PipelineState,
    onRemoveTask: (String) -> Unit,
    onTaskChipClick: (String) -> Unit,
    onChangeMaxParallel: (Int) -> Unit
) {
    var statusFilter by remember { mutableStateOf<TaskStatus?>(null) }

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp)) {
        val animatedProgress by animateFloatAsState(
            targetValue = state.progress,
            label = "progress"
        )
        LinearProgressIndicator(
            progress = { animatedProgress },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp)),
            color = PipelineColors.accentBlue,
            trackColor = PipelineColors.surfaceElevated,
            drawStopIndicator = {}
        )

        if (state.phase == PipelinePhase.REVIEWING || state.phase == PipelinePhase.IDLE) {
            Spacer(Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "Параллельно: ${state.maxParallelTasks}",
                    color = PipelineColors.textSecondary,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(end = 8.dp).widthIn(min = 120.dp)
                )
                Slider(
                    value = state.maxParallelTasks.toFloat(),
                    onValueChange = { onChangeMaxParallel(it.toInt()) },
                    valueRange = 1f..8f,
                    steps = 6,
                    modifier = Modifier.weight(1f).height(28.dp),
                    colors = SliderDefaults.colors(
                        thumbColor = PipelineColors.accentBlue,
                        activeTrackColor = PipelineColors.accentBlue,
                        inactiveTrackColor = PipelineColors.surfaceElevated
                    )
                )
            }
        }

        if (state.tasks.size >= 10 && state.phase != PipelinePhase.IDLE) {
            Spacer(Modifier.height(6.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                FilterPill("Все · ${state.tasks.size}",
                    selected = statusFilter == null) { statusFilter = null }
                val processingCount = state.tasks.count {
                    it.status == TaskStatus.PROCESSING || it.status == TaskStatus.PENDING
                }
                if (processingCount > 0) {
                    FilterPill("⏳ $processingCount",
                        selected = statusFilter == TaskStatus.PROCESSING) {
                        statusFilter = if (statusFilter == TaskStatus.PROCESSING) null else TaskStatus.PROCESSING
                    }
                }
                if (state.successfulTasks > 0) {
                    FilterPill("✅ ${state.successfulTasks}",
                        selected = statusFilter == TaskStatus.SUCCESS) {
                        statusFilter = if (statusFilter == TaskStatus.SUCCESS) null else TaskStatus.SUCCESS
                    }
                }
                if (state.failedTasks > 0) {
                    FilterPill("❌ ${state.failedTasks}",
                        selected = statusFilter == TaskStatus.FAILED_FINAL) {
                        statusFilter = if (statusFilter == TaskStatus.FAILED_FINAL) null else TaskStatus.FAILED_FINAL
                    }
                }
                if (state.deferredTasks > 0) {
                    FilterPill("🔄 ${state.deferredTasks}",
                        selected = statusFilter == TaskStatus.DEFERRED) {
                        statusFilter = if (statusFilter == TaskStatus.DEFERRED) null else TaskStatus.DEFERRED
                    }
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        val visibleTasks = remember(state.tasks, statusFilter) {
            if (statusFilter == null) state.tasks
            else if (statusFilter == TaskStatus.PROCESSING)
                state.tasks.filter { it.status == TaskStatus.PROCESSING || it.status == TaskStatus.PENDING }
            else state.tasks.filter { it.status == statusFilter }
        }
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            items(visibleTasks, key = { it.id }) { task ->
                TaskChip(
                    task = task,
                    isCurrent = task.id in state.runningTaskIds,
                    isFilterActive = state.logFilterTaskId == task.id,
                    canRemove = state.phase == PipelinePhase.REVIEWING,
                    onRemove = { onRemoveTask(task.id) },
                    onClick = { onTaskChipClick(task.id) }
                )
            }
        }
    }
}

@Composable
private fun FilterPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(
                if (selected) PipelineColors.accentBlue.copy(alpha = 0.35f)
                else PipelineColors.surfaceElevated
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = label,
            color = if (selected) PipelineColors.textPrimary else PipelineColors.textSecondary,
            fontSize = 10.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun LogFilterBanner(
    taskId: String,
    tasks: List<FileTask>,
    onClear: () -> Unit
) {
    val task = remember(taskId, tasks) { tasks.firstOrNull { it.id == taskId } }
    val fileName = task?.filePath?.substringAfterLast('/') ?: taskId

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(PipelineColors.accentPurple.copy(alpha = 0.15f))
            .border(0.5.dp, PipelineColors.accentPurple.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "🔍 Фильтр: ",
            color = PipelineColors.textSecondary,
            fontSize = 11.sp
        )
        Text(
            text = fileName,
            color = PipelineColors.textPrimary,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        Icon(
            Icons.Default.Close,
            contentDescription = "Сбросить фильтр",
            tint = PipelineColors.accentPurple,
            modifier = Modifier
                .size(16.dp)
                .clickable(onClick = onClear)
        )
    }
}

@Composable
private fun TaskChip(
    task: FileTask,
    isCurrent: Boolean,
    isFilterActive: Boolean,
    canRemove: Boolean,
    onRemove: () -> Unit,
    onClick: () -> Unit
) {
    val bgColor = when (task.status) {
        TaskStatus.PENDING -> PipelineColors.surfaceElevated
        TaskStatus.PROCESSING -> PipelineColors.accentBlue.copy(alpha = 0.25f)
        TaskStatus.SUCCESS -> PipelineColors.accentGreen.copy(alpha = 0.25f)
        TaskStatus.NO_CHANGES_NEEDED -> PipelineColors.textSecondary.copy(alpha = 0.2f)
        TaskStatus.DEFERRED -> PipelineColors.accentYellow.copy(alpha = 0.25f)
        TaskStatus.FAILED_FINAL -> PipelineColors.accentRed.copy(alpha = 0.3f)
    }
    val borderColor = when {
        isFilterActive -> PipelineColors.accentPurple
        isCurrent -> PipelineColors.accentBlue
        else -> PipelineColors.borderSubtle
    }
    val borderWidth = when {
        isFilterActive -> 2.dp
        isCurrent -> 1.5.dp
        else -> 0.5.dp
    }
    val fileName = task.filePath.substringAfterLast('/')

    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .border(borderWidth, borderColor, RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = task.status.emoji,
            fontSize = 14.sp
        )
        Spacer(Modifier.width(4.dp))
        if (task.status == TaskStatus.PENDING || task.status == TaskStatus.PROCESSING) {
            Text(
                text = task.operation.emoji,
                fontSize = 12.sp
            )
            Spacer(Modifier.width(4.dp))
        }
        Text(
            text = fileName,
            color = PipelineColors.textPrimary,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 160.dp)
        )
        if (canRemove) {
            Spacer(Modifier.width(6.dp))
            Icon(
                Icons.Default.Close,
                contentDescription = "Remove",
                modifier = Modifier
                    .size(14.dp)
                    .clickable(onClick = onRemove),
                tint = PipelineColors.textSecondary
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// LIVE LOGS
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun LiveLogsSection(
    geminiLog: List<GeminiLogEvent>,
    repoLog: List<RepoLogEvent>
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp)
            .height(360.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        LogPanel(
            title = "🤖 Gemini",
            gradient = PipelineColors.geminiGradient,
            modifier = Modifier.weight(1f)
        ) {
            GeminiLogList(events = geminiLog)
        }
        LogPanel(
            title = "📦 Repo",
            gradient = PipelineColors.repoGradient,
            modifier = Modifier.weight(1f)
        ) {
            RepoLogList(events = repoLog)
        }
    }
}

@Composable
private fun LogPanel(
    title: String,
    gradient: Brush,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(PipelineColors.surfaceDark)
            .border(0.5.dp, PipelineColors.borderSubtle, RoundedCornerShape(10.dp))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(gradient)
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Text(
                text = title,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
                fontSize = 12.sp
            )
        }
        Box(modifier = Modifier.weight(1f)) {
            content()
        }
    }
}

@Composable
private fun GeminiLogList(events: List<GeminiLogEvent>) {
    val listState = rememberLazyListState()
    LaunchedEffect(events.size) {
        if (events.isNotEmpty()) listState.animateScrollToItem(events.size - 1)
    }

    if (events.isEmpty()) {
        EmptyLog(text = "Жду команды...")
        return
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().padding(8.dp)
    ) {
        items(events, key = { it.id }) { event ->
            LogLine(
                icon = event.icon,
                message = event.message,
                timestamp = event.timestamp,
                accentColor = geminiAccentFor(event.type)
            )
        }
    }
}

@Composable
private fun RepoLogList(events: List<RepoLogEvent>) {
    val listState = rememberLazyListState()
    LaunchedEffect(events.size) {
        if (events.isNotEmpty()) listState.animateScrollToItem(events.size - 1)
    }

    if (events.isEmpty()) {
        EmptyLog(text = "Жду коммитов...")
        return
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().padding(8.dp)
    ) {
        items(events, key = { it.id }) { event ->
            LogLine(
                icon = event.icon,
                message = event.message,
                timestamp = event.timestamp,
                accentColor = repoAccentFor(event.type)
            )
        }
    }
}

@Composable
private fun EmptyLog(text: String) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = PipelineColors.textTertiary,
            fontSize = 12.sp
        )
    }
}

@Composable
private fun LogLine(
    icon: String,
    message: String,
    timestamp: Long,
    accentColor: Color
) {
    val timeStr = remember(timestamp) { formatLogTime(timestamp) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = timeStr,
            color = PipelineColors.textTertiary,
            fontSize = 9.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.width(54.dp).padding(top = 1.dp)
        )
        Text(
            text = icon,
            fontSize = 11.sp,
            modifier = Modifier.padding(end = 4.dp)
        )
        Text(
            text = message,
            color = accentColor,
            fontSize = 11.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.weight(1f)
        )
    }
}

private fun geminiAccentFor(type: GeminiEventType): Color = when (type) {
    GeminiEventType.PLANNER_START, GeminiEventType.PLANNER_DONE -> PipelineColors.accentPurple
    GeminiEventType.AI_REQUEST, GeminiEventType.AI_RESPONSE -> PipelineColors.textPrimary
    GeminiEventType.AI_BLOCKS_PARSED, GeminiEventType.AI_APPLY_OK -> PipelineColors.accentGreen
    GeminiEventType.AI_APPLY_FAIL, GeminiEventType.AI_RETRY -> PipelineColors.accentYellow
    GeminiEventType.SUMMARY_START, GeminiEventType.SUMMARY_DONE -> PipelineColors.accentPurple
    GeminiEventType.ERROR -> PipelineColors.accentRed
    GeminiEventType.TASK_START -> PipelineColors.accentBlue
    GeminiEventType.INFO -> PipelineColors.textSecondary
}

private fun repoAccentFor(type: RepoEventType): Color = when (type) {
    RepoEventType.FILE_READ -> PipelineColors.textSecondary
    RepoEventType.FILE_COMMITTED -> PipelineColors.accentGreen
    RepoEventType.COMMIT_CONFLICT -> PipelineColors.accentYellow
    RepoEventType.CONFLICT_RESOLVED -> PipelineColors.accentYellow
    RepoEventType.WORKFLOW_TRIGGERED -> PipelineColors.accentBlue
    RepoEventType.WORKFLOW_PROGRESS -> PipelineColors.accentBlue
    RepoEventType.WORKFLOW_SUCCESS -> PipelineColors.accentGreen
    RepoEventType.WORKFLOW_FAILURE -> PipelineColors.accentRed
    RepoEventType.INDEX_INVALIDATED -> PipelineColors.textTertiary
    RepoEventType.INFO -> PipelineColors.textSecondary
    RepoEventType.ERROR -> PipelineColors.accentRed
    RepoEventType.CLONE_START, RepoEventType.CLONE_PROGRESS -> PipelineColors.accentBlue
    RepoEventType.CLONE_DONE -> PipelineColors.accentGreen
    RepoEventType.LOCAL_WRITE -> PipelineColors.textSecondary
    RepoEventType.LOCAL_COMMIT -> PipelineColors.accentGreen
    RepoEventType.PUSH_START -> PipelineColors.accentBlue
    RepoEventType.PUSH_DONE -> PipelineColors.accentGreen
    else -> PipelineColors.textSecondary
}

// ═══════════════════════════════════════════════════════════════════════════
// FINAL REPORT
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun FinalReportSection(report: String, status: OverallStatus) {
    val accentColor = when (status) {
        OverallStatus.SUCCESS_ALL -> PipelineColors.accentGreen
        OverallStatus.SUCCESS_PARTIAL -> PipelineColors.accentYellow
        OverallStatus.FAILED_ALL, OverallStatus.FATAL -> PipelineColors.accentRed
        OverallStatus.CANCELLED -> PipelineColors.textSecondary
        else -> PipelineColors.accentBlue
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(PipelineColors.surfaceElevated)
            .border(1.dp, accentColor.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(accentColor.copy(alpha = 0.15f))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "📊 Финальный отчёт",
                color = PipelineColors.textPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp
            )
        }
        SelectionContainer {
            Text(
                text = report,
                color = PipelineColors.textPrimary,
                fontSize = 13.sp,
                lineHeight = 19.sp,
                fontFamily = FontFamily.Default,
                modifier = Modifier.padding(14.dp)
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// FATAL ERROR
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun FatalErrorCard(error: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(PipelineColors.accentRed.copy(alpha = 0.12f))
            .border(1.dp, PipelineColors.accentRed.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .padding(14.dp)
    ) {
        Text(
            text = "🔴 FATAL — конвейер остановлен",
            color = PipelineColors.accentRed,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = error,
            color = PipelineColors.textPrimary,
            fontSize = 12.sp,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun PipelineKeysSection(
    keyA: String,
    keyB: String,
    activeIndex: Int,
    enabled: Boolean,
    onKeyAChange: (String) -> Unit,
    onKeyBChange: (String) -> Unit,
    onActiveChange: (Int) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        KeyRow("Key A", keyA, enabled, activeIndex == 0, { onKeyAChange(it) }, { onActiveChange(0) })
        Spacer(Modifier.height(4.dp))
        KeyRow("Key B", keyB, enabled, activeIndex == 1, { onKeyBChange(it) }, { onActiveChange(1) })
    }
}

@Composable
private fun KeyRow(
    label: String,
    value: String,
    enabled: Boolean,
    isActive: Boolean,
    onValueChange: (String) -> Unit,
    onSelect: () -> Unit
) {
    var isVisible by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(PipelineColors.surfaceElevated)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = isActive, onClick = if (enabled) onSelect else null, enabled = enabled)
        Text(label, color = PipelineColors.textSecondary, fontSize = 12.sp, modifier = Modifier.width(40.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            visualTransformation = if (isVisible) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
            modifier = Modifier.weight(1f).height(50.dp),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp, fontFamily = FontFamily.Monospace),
            colors = OutlinedTextFieldDefaults.colors(focusedContainerColor = PipelineColors.surfaceDark, unfocusedContainerColor = PipelineColors.surfaceDark)
        )
        IconButton(onClick = { isVisible = !isVisible }) {
            Icon(if (isVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility, null, tint = PipelineColors.textSecondary)
        }
    }
}

@Composable
private fun DefaultModelSelector(
    selected: String,
    interactive: Boolean,
    onSelect: (String) -> Unit
) {
    val options = listOf(
        "gemini-3.1-flash-lite" to "🪶 3.1 Flash-Lite",
        "gemini-3.5-flash" to "⚡ 3.5 Flash"
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(PipelineColors.surfaceElevated)
            .border(0.5.dp, PipelineColors.borderSubtle, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(
            "📌 Дефолтная модель",
            color = PipelineColors.textPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(bottom = 6.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            options.forEach { (apiId, label) ->
                val isSelected = selected == apiId
                val bg = if (isSelected) PipelineColors.accentBlue else PipelineColors.surfaceDark
                val fg = if (isSelected) Color.White else PipelineColors.textSecondary
                val border = if (isSelected) PipelineColors.accentBlue else PipelineColors.borderSubtle
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(bg)
                        .border(1.dp, border, RoundedCornerShape(8.dp))
                        .clickable(enabled = interactive) { onSelect(apiId) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label,
                        color = fg,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "Используется когда Override OFF. Temperature=0.0, Thinking=HIGH.",
            color = PipelineColors.textTertiary,
            fontSize = 10.sp
        )
    }
}

@Composable
private fun LiteThinkingSelector(
    selected: String,
    isLite: Boolean,
    interactive: Boolean,
    onSelect: (String) -> Unit
) {
    if (!isLite) return
    val options = listOf(
        "low" to "🌱 LOW",
        "medium" to "⚙️ MEDIUM",
        "high" to "🔥 HIGH"
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(PipelineColors.surfaceElevated)
            .border(0.5.dp, PipelineColors.borderSubtle, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(
            "🧠 Thinking для выбранной модели",
            color = PipelineColors.textPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(bottom = 6.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            options.forEach { (level, label) ->
                val isSelected = selected == level
                val bg = if (isSelected) PipelineColors.accentPurple else PipelineColors.surfaceDark
                val fg = if (isSelected) Color.White else PipelineColors.textSecondary
                val border = if (isSelected) PipelineColors.accentPurple else PipelineColors.borderSubtle
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(34.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(bg)
                        .border(1.dp, border, RoundedCornerShape(8.dp))
                        .clickable(enabled = interactive) { onSelect(level) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        label,
                        color = fg,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = when (selected) {
                "low" -> "Минимум рассуждений — макс. экономия токенов, простые правки"
                "medium" -> "Средний уровень — баланс цены и качества"
                else -> "Максимум рассуждений — лучшее качество, больше TPM"
            },
            color = PipelineColors.textTertiary,
            fontSize = 10.sp
        )
    }
}

@Composable
private fun PipelineModeSelector(
    currentMode: PipelineMode,
    interactive: Boolean,
    onModeChange: (PipelineMode) -> Unit
) {
    val modes = listOf(
        PipelineMode.ONLINE,
        PipelineMode.OFFLINE
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(PipelineColors.surfaceElevated)
            .border(0.5.dp, PipelineColors.borderSubtle, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(
            "⚙️ Режим работы",
            color = PipelineColors.textPrimary,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(bottom = 6.dp)
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            modes.forEach { mode ->
                val isSelected = currentMode == mode
                val bg = if (isSelected) PipelineColors.accentBlue else PipelineColors.surfaceDark
                val fg = if (isSelected) Color.White else PipelineColors.textSecondary
                val border = if (isSelected) PipelineColors.accentBlue else PipelineColors.borderSubtle
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(bg)
                        .border(1.dp, border, RoundedCornerShape(10.dp))
                        .clickable(enabled = interactive) { onModeChange(mode) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "${mode.emoji} ${mode.displayName}",
                        color = fg,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 13.sp
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = when (currentMode) {
                PipelineMode.ONLINE ->
                    "Прямые коммиты в GitHub — по одному на файл. Может ловить rate limit при параллелизме 5+."
                PipelineMode.OFFLINE ->
                    "Клонируем репозиторий локально, правим все файлы, в конце — один коммит и push. Безопасно от rate limit."
            },
            color = PipelineColors.textTertiary,
            fontSize = 10.sp
        )
        if (!interactive) {
            Spacer(Modifier.height(4.dp))
            Text(
                "Переключение режима недоступно во время выполнения",
                color = PipelineColors.accentYellow,
                fontSize = 10.sp
            )
        }
    }
}

@Composable
private fun LocalCloneStatusSection(
    status: LocalRepoManager.RepoStatus,
    progress: String?,
    interactive: Boolean,
    onSync: () -> Unit,
    onDelete: () -> Unit
) {
    val sizeKb = (status.sizeBytes / 1024.0).toInt()
    val sizeFormatted = when {
        sizeKb < 1024 -> "$sizeKb KB"
        else -> "${"%.1f".format(sizeKb / 1024.0)} MB"
    }
    val ageSec = if (status.lastSyncMs > 0)
        ((System.currentTimeMillis() - status.lastSyncMs) / 1000).coerceAtLeast(0)
    else -1L
    val ageStr = when {
        ageSec < 0 -> "ни разу"
        ageSec < 60 -> "${ageSec}с назад"
        ageSec < 3600 -> "${ageSec / 60}мин назад"
        else -> "${ageSec / 3600}ч назад"
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(PipelineColors.surfaceElevated)
            .border(0.5.dp, PipelineColors.borderSubtle, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val icon = when (status.state) {
                LocalRepoManager.CloneState.CLONED -> "📦"
                LocalRepoManager.CloneState.NOT_CLONED -> "⚪"
                LocalRepoManager.CloneState.ERROR -> "❌"
            }
            Text(
                "$icon Локальный клон",
                color = PipelineColors.textPrimary,
                fontWeight = FontWeight.Medium,
                fontSize = 12.sp,
                modifier = Modifier.weight(1f)
            )
            if (progress != null) {
                Text(
                    progress,
                    color = PipelineColors.accentBlue,
                    fontSize = 10.sp,
                    fontFamily = FontFamily.Monospace
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = when (status.state) {
                LocalRepoManager.CloneState.CLONED ->
                    "${status.owner}/${status.repo} · $sizeFormatted · обновлён $ageStr" +
                            if (status.pendingChanges > 0) " · ⚠️ ${status.pendingChanges} несохранённых" else ""
                LocalRepoManager.CloneState.NOT_CLONED ->
                    "Клон ещё не создан. Запустится автоматически при старте пайплайна."
                LocalRepoManager.CloneState.ERROR ->
                    "Ошибка: ${status.errorMessage ?: "неизвестно"}"
            },
            color = PipelineColors.textTertiary,
            fontSize = 10.sp
        )
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(34.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(PipelineColors.accentBlue.copy(alpha = if (interactive) 1f else 0.4f))
                    .clickable(enabled = interactive, onClick = onSync),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "🔄 Синхронизировать",
                    color = Color.White, fontWeight = FontWeight.SemiBold, fontSize = 11.sp
                )
            }
            if (status.state == LocalRepoManager.CloneState.CLONED) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(34.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(PipelineColors.surfaceDark)
                        .border(1.dp, PipelineColors.accentRed.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .clickable(enabled = interactive, onClick = onDelete),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "🗑 Удалить клон",
                        color = PipelineColors.accentRed,
                        fontWeight = FontWeight.SemiBold, fontSize = 11.sp
                    )
                }
            }
        }
    }
}
