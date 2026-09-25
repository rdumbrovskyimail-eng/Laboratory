package com.opuside.app.feature.pipeline.presentation

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.opuside.app.feature.pipeline.data.*
import java.text.SimpleDateFormat
import java.util.*

private val LOG_TIME_FORMATTER = SimpleDateFormat("HH:mm:ss", Locale.US)
private fun formatLogTime(timestamp: Long): String =
    synchronized(LOG_TIME_FORMATTER) { LOG_TIME_FORMATTER.format(Date(timestamp)) }

// ═══════════════════════════════════════════════════════════════════════════
// LIGHT PROFESSIONAL THEME (Samsung S23 Ultra AMOLED Optimized)
// ═══════════════════════════════════════════════════════════════════════════

private object PipelineTheme {
    val background = Color(0xFFF8F9FA)      // Мягкий светлый холст
    val surface = Color(0xFFFFFFFF)         // Чистые белые карточки
    val surfaceSecondary = Color(0xFFF1F3F5)// Фон логов и внутренних панелей
    val border = Color(0xFFE2E8F0)          // Тонкая граница
    val borderStrong = Color(0xFFCBD5E1)

    val textPrimary = Color(0xFF0F172A)     // Глубокий slate для идеальной резкости
    val textSecondary = Color(0xFF475569)
    val textTertiary = Color(0xFF94A3B8)

    val blue = Color(0xFF2563EB)            // Акцентный синий Google/Apple
    val blueSoft = Color(0xFFEFF6FF)
    val green = Color(0xFF059669)           // Изумрудный для успеха и бэкапов
    val greenSoft = Color(0xFFECFDF5)
    val amber = Color(0xFFD97706)           // Предупреждения
    val amberSoft = Color(0xFFFFFBEB)
    val red = Color(0xFFDC2626)             // Ошибки и остановка
    val redSoft = Color(0xFFFEF2F2)
    val purple = Color(0xFF7C3AED)          // План и сводки
    val purpleSoft = Color(0xFFF5F3FF)
}

@OptIn(ExperimentalMaterial3Api::class)
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
    val backups by viewModel.backups.collectAsStateWithLifecycle()

    var promptExpanded by remember { mutableStateOf(true) }
    var showBackupsSheet by remember { mutableStateOf(false) }
    var showKeysExpanded by remember { mutableStateOf(false) }
    var backupToRollback by remember { mutableStateOf<PipelineBackupManager.BackupEntry?>(null) }

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
        containerColor = PipelineTheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            // ── Шапка репозитория и кнопка Бэкапов ────────────────────────
            PipelineHeaderLight(
                repoStats = repoStats,
                runId = state.pipelineRunId,
                backupsCount = backups.size,
                onOpenBackups = {
                    viewModel.loadBackups()
                    showBackupsSheet = true
                }
            )

            // ── Панель независимых опций (Детальный отчёт + Бэкап) ────────
            PipelineOptionsSection(
                detailedReportEnabled = state.isDetailedReportEnabled,
                backupEnabled = state.isBackupEnabled,
                enabled = !state.isRunning,
                onToggleDetailedReport = viewModel::toggleDetailedReport,
                onToggleBackup = viewModel::toggleBackup
            )

            // ── Селектор моделей (строго 3.5 Lite и 3.1 Lite) ─────────────
            ModelSelectorLight(
                selected = state.selectedModelApiId,
                interactive = !state.isRunning,
                onSelect = viewModel::setSelectedModel
            )

            // ── Режим работы (Online / Offline) ───────────────────────────
            ModeSelectorLight(
                currentMode = state.pipelineMode,
                interactive = !state.isRunning,
                onModeChange = viewModel::setPipelineMode
            )

            if (state.pipelineMode == PipelineMode.OFFLINE) {
                OfflineRepoStatusCard(
                    status = localRepoStatus,
                    progress = localRepoProgress,
                    interactive = !state.isRunning,
                    onSync = viewModel::syncLocalRepo,
                    onDelete = viewModel::deleteLocalClone
                )
            }

            // ── Ключи API (сворачиваемый блок) ────────────────────────────
            CollapsibleKeysSection(
                expanded = showKeysExpanded,
                onToggle = { showKeysExpanded = !showKeysExpanded },
                keyA = pipelineKeyA,
                keyB = pipelineKeyB,
                activeIndex = pipelineActiveKey,
                enabled = !state.isRunning,
                onKeyAChange = viewModel::setPipelineKeyA,
                onKeyBChange = viewModel::setPipelineKeyB,
                onActiveChange = viewModel::setPipelineActiveKey
            )

            // ── Поле ввода промпта ─────────────────────────────────────────
            PromptSectionLight(
                prompt = userPrompt,
                onPromptChange = viewModel::onPromptChange,
                expanded = promptExpanded,
                onToggleExpanded = { promptExpanded = !promptExpanded },
                isRunning = state.isRunning
            )

            // ── Главная панель управления (Старт / Стоп / Сброс) ──────────
            ActionBarLight(
                state = state,
                totalCost = totalCost,
                totalTokens = totalTokens,
                onPlan = viewModel::plan,
                onStart = viewModel::start,
                onStop = viewModel::stop,
                onReset = viewModel::reset,
                onExport = { viewModel.exportRepoToTxt(context) }
            )

            // ── Список и прогресс задач ────────────────────────────────────
            if (state.tasks.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                TaskProgressSectionLight(
                    state = state,
                    onRemoveTask = viewModel::removeTask,
                    onTaskChipClick = { taskId ->
                        viewModel.setLogFilter(if (state.logFilterTaskId == taskId) null else taskId)
                    },
                    onChangeMaxParallel = viewModel::setMaxParallel
                )
            }

            // ── Живые логи (Gemini и Repo) ─────────────────────────────────
            if (rawGeminiSize.isNotEmpty() || rawRepoSize.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                if (state.logFilterTaskId != null) {
                    FilterBadgeLight(
                        taskId = state.logFilterTaskId!!,
                        tasks = state.tasks,
                        onClear = { viewModel.setLogFilter(null) }
                    )
                }
                LiveLogsSectionLight(geminiLog = geminiLog, repoLog = repoLog)
            }

            // ── Итоговый аналитический отчёт ──────────────────────────────
            state.finalReport?.let { report ->
                Spacer(Modifier.height(12.dp))
                FinalReportCardLight(
                    report = report,
                    onShareDetailed = {
                        viewModel.shareFullReport(context)
                    }
                )
            }

            // ── Ошибка Fatal ──────────────────────────────────────────────
            state.fatalError?.takeIf { state.phase == PipelinePhase.FATAL }?.let { err ->
                Spacer(Modifier.height(12.dp))
                FatalCardLight(err)
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    // ── Шторка бэкапов (Backups Bottom Sheet) ──────────────────────────────
    if (showBackupsSheet) {
        ModalBottomSheet(
            onDismissRequest = { showBackupsSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false),
            containerColor = PipelineTheme.surface,
            dragHandle = { BottomSheetDefaults.DragHandle(color = PipelineTheme.borderStrong) }
        ) {
            BackupsSheetContent(
                backups = backups,
                isRollingBack = state.isRollingBack,
                onRollbackClick = { backup -> backupToRollback = backup },
                onShareReport = { backup -> viewModel.shareFullReport(context, backup.id) },
                onDelete = { backup -> viewModel.deleteBackup(backup.id) },
                onClose = { showBackupsSheet = false }
            )
        }
    }

    // ── Диалог подтверждения отката ────────────────────────────────────────
    backupToRollback?.let { backup ->
        AlertDialog(
            onDismissRequest = { backupToRollback = null },
            icon = { Icon(Icons.Default.History, null, tint = PipelineTheme.amber, modifier = Modifier.size(32.dp)) },
            title = { Text("Откатить проект?", fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Все затронутые файлы (${backup.affectedFilesCount} шт.) будут полностью возвращены к исходному состоянию на момент ${backup.formattedDate}.\n\n" +
                            "Режим: ${backup.mode.displayName}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = PipelineTheme.textSecondary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        viewModel.rollbackBackup(backup.id)
                        backupToRollback = null
                        showBackupsSheet = false
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = PipelineTheme.amber)
                ) {
                    Text("Да, откатить", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { backupToRollback = null }) { Text("Отмена") }
            },
            containerColor = PipelineTheme.surface
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// ШАПКА РЕПОЗИТОРИЯ
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun PipelineHeaderLight(
    repoStats: RepoStats?,
    runId: String,
    backupsCount: Int,
    onOpenBackups: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = PipelineTheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = PipelineTheme.blueSoft,
                    modifier = Modifier.size(44.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text("G", color = PipelineTheme.blue, fontWeight = FontWeight.Black, fontSize = 22.sp)
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Pipeline", color = PipelineTheme.textPrimary, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                    Text(
                        text = if (repoStats != null) "${repoStats.owner}/${repoStats.repo} · ${repoStats.branch}" else "Подключение к GitHub...",
                        color = PipelineTheme.textSecondary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                OutlinedButton(
                    onClick = onOpenBackups,
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                    border = BorderStroke(1.dp, PipelineTheme.borderStrong)
                ) {
                    Icon(Icons.Default.Shield, null, tint = PipelineTheme.green, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Бэкапы", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = PipelineTheme.textPrimary)
                    if (backupsCount > 0) {
                        Spacer(Modifier.width(4.dp))
                        Surface(
                            shape = CircleShape,
                            color = PipelineTheme.greenSoft,
                            modifier = Modifier.size(18.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Text("$backupsCount", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = PipelineTheme.green)
                            }
                        }
                    }
                }
            }

            if (repoStats != null) {
                Spacer(Modifier.height(10.dp))
                HorizontalDivider(color = PipelineTheme.border, thickness = 0.5.dp)
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    StatBadge("Файлов", "${repoStats.totalFiles}")
                    StatBadge("Размер", repoStats.totalSizeFormatted)
                    repoStats.topExtensions(3).forEach { (ext, cnt) ->
                        StatBadge(".$ext", "$cnt")
                    }
                }
            }
        }
    }
}

@Composable
private fun StatBadge(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, color = PipelineTheme.textPrimary, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
        Text(label, color = PipelineTheme.textTertiary, fontSize = 10.sp)
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// НЕЗАВИСИМЫЕ ОПЦИИ: БЭКАП И ДЕТАЛЬНЫЙ TXT-ОТЧЕТ
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun PipelineOptionsSection(
    detailedReportEnabled: Boolean,
    backupEnabled: Boolean,
    enabled: Boolean,
    onToggleDetailedReport: () -> Unit,
    onToggleBackup: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = PipelineTheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("Опции аудита и безопасности", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = PipelineTheme.textPrimary)
            Spacer(Modifier.height(10.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = enabled, onClick = onToggleDetailedReport)
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = detailedReportEnabled,
                    onCheckedChange = { onToggleDetailedReport() },
                    enabled = enabled,
                    colors = CheckboxDefaults.colors(checkedColor = PipelineTheme.purple)
                )
                Spacer(Modifier.width(6.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Подробный TXT отчёт", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = PipelineTheme.textPrimary)
                    Text("Полный промпт + оригинальные файлы + итог изменений", fontSize = 10.sp, color = PipelineTheme.textSecondary)
                }
            }

            Spacer(Modifier.height(4.dp))

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .clickable(enabled = enabled, onClick = onToggleBackup)
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = backupEnabled,
                    onCheckedChange = { onToggleBackup() },
                    enabled = enabled,
                    colors = CheckboxDefaults.colors(checkedColor = PipelineTheme.green)
                )
                Spacer(Modifier.width(6.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text("Создавать точку отката (Бэкап)", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = PipelineTheme.textPrimary)
                    Text("Моментальный снимок оригиналов для отката в 1 клик", fontSize = 10.sp, color = PipelineTheme.textSecondary)
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// ВЫБОР МОДЕЛИ (СТРОГО 3.5 И 3.1 FLASH-LITE)
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun ModelSelectorLight(
    selected: String,
    interactive: Boolean,
    onSelect: (String) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = PipelineTheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text("Модель Gemini", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = PipelineTheme.textPrimary)
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ModelPill(
                    title = "⚡ 3.5 Flash-Lite",
                    subtitle = "LOW thinking · $0.30/M",
                    isSelected = selected.contains("3.5"),
                    enabled = interactive,
                    modifier = Modifier.weight(1f),
                    onClick = { onSelect("gemini-3.5-flash-lite") }
                )
                ModelPill(
                    title = "💨 3.1 Flash-Lite",
                    subtitle = "MEDIUM thinking · $0.25/M",
                    isSelected = selected.contains("3.1"),
                    enabled = interactive,
                    modifier = Modifier.weight(1f),
                    onClick = { onSelect("gemini-3.1-flash-lite") }
                )
            }
        }
    }
}

@Composable
private fun ModelPill(
    title: String,
    subtitle: String,
    isSelected: Boolean,
    enabled: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        color = if (isSelected) PipelineTheme.blueSoft else PipelineTheme.surfaceSecondary,
        border = BorderStroke(if (isSelected) 1.5.dp else 1.dp, if (isSelected) PipelineTheme.blue else PipelineTheme.border),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(title, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (isSelected) PipelineTheme.blue else PipelineTheme.textPrimary)
            Text(subtitle, fontSize = 9.sp, color = if (isSelected) PipelineTheme.blue.copy(alpha = 0.8f) else PipelineTheme.textSecondary)
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// РЕЖИМ РАБОТЫ (ONLINE / OFFLINE)
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun ModeSelectorLight(
    currentMode: PipelineMode,
    interactive: Boolean,
    onModeChange: (PipelineMode) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = PipelineTheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            PipelineMode.entries.forEach { mode ->
                val isSelected = currentMode == mode
                Surface(
                    onClick = { onModeChange(mode) },
                    enabled = interactive,
                    shape = RoundedCornerShape(10.dp),
                    color = if (isSelected) PipelineTheme.blue else PipelineTheme.surfaceSecondary,
                    modifier = Modifier.weight(1f).height(38.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(
                            "${mode.emoji} ${mode.displayName}",
                            color = if (isSelected) Color.White else PipelineTheme.textPrimary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OfflineRepoStatusCard(
    status: LocalRepoManager.RepoStatus,
    progress: String?,
    interactive: Boolean,
    onSync: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = PipelineTheme.surfaceSecondary)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("📦 Локальный клон:", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = PipelineTheme.textPrimary)
                Spacer(Modifier.width(6.dp))
                Text(
                    if (status.state == LocalRepoManager.CloneState.CLONED) "Готов (${status.sizeBytes / 1024 / 1024} MB)"
                    else "Не создан",
                    fontSize = 11.sp, color = PipelineTheme.textSecondary
                )
                if (progress != null) {
                    Spacer(Modifier.width(6.dp))
                    Text(progress, fontSize = 10.sp, color = PipelineTheme.blue, fontFamily = FontFamily.Monospace)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onSync,
                    enabled = interactive,
                    modifier = Modifier.weight(1f).height(34.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp)
                ) {
                    Text("🔄 Синхронизировать", fontSize = 10.sp)
                }
                if (status.state == LocalRepoManager.CloneState.CLONED) {
                    OutlinedButton(
                        onClick = onDelete,
                        enabled = interactive,
                        modifier = Modifier.weight(1f).height(34.dp),
                        contentPadding = PaddingValues(horizontal = 8.dp),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = PipelineTheme.red)
                    ) {
                        Text("🗑 Удалить клон", fontSize = 10.sp)
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// СВОРАЧИВАЕМЫЙ БЛОК КЛЮЧЕЙ
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun CollapsibleKeysSection(
    expanded: Boolean,
    onToggle: () -> Unit,
    keyA: String,
    keyB: String,
    activeIndex: Int,
    enabled: Boolean,
    onKeyAChange: (String) -> Unit,
    onKeyBChange: (String) -> Unit,
    onActiveChange: (Int) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = PipelineTheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggle),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Key, null, tint = PipelineTheme.blue, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Ключи Pipeline (Резервные)", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = PipelineTheme.textPrimary)
                }
                Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = PipelineTheme.textSecondary)
            }

            AnimatedVisibility(visible = expanded) {
                Column(modifier = Modifier.padding(top = 10.dp)) {
                    KeyInputField("Ключ A", keyA, activeIndex == 0, enabled, onKeyAChange) { onActiveChange(0) }
                    Spacer(Modifier.height(6.dp))
                    KeyInputField("Ключ B", keyB, activeIndex == 1, enabled, onKeyBChange) { onActiveChange(1) }
                }
            }
        }
    }
}

@Composable
private fun KeyInputField(
    label: String,
    value: String,
    isActive: Boolean,
    enabled: Boolean,
    onValueChange: (String) -> Unit,
    onSelectActive: () -> Unit
) {
    var showKey by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(if (isActive) PipelineTheme.blueSoft else PipelineTheme.surfaceSecondary)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = isActive, onClick = if (enabled) onSelectActive else null, enabled = enabled)
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = PipelineTheme.textPrimary, modifier = Modifier.width(50.dp))
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            enabled = enabled,
            singleLine = true,
            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
            modifier = Modifier.weight(1f).height(46.dp),
            textStyle = LocalTextStyle.current.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
            colors = OutlinedTextFieldDefaults.colors(
                focusedContainerColor = PipelineTheme.surface,
                unfocusedContainerColor = PipelineTheme.surface
            )
        )
        IconButton(onClick = { showKey = !showKey }, modifier = Modifier.size(32.dp)) {
            Icon(if (showKey) Icons.Default.VisibilityOff else Icons.Default.Visibility, null, tint = PipelineTheme.textTertiary, modifier = Modifier.size(16.dp))
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// ВВОД ПРОМПТА (ИСПРАВЛЕНА СТРОКА 775: onValueChange = onPromptChange)
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun PromptSectionLight(
    prompt: String,
    onPromptChange: (String) -> Unit,
    expanded: Boolean,
    onToggleExpanded: () -> Unit,
    isRunning: Boolean
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = PipelineTheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onToggleExpanded),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("📝 Промпт задачи", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = PipelineTheme.textPrimary)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (prompt.isNotBlank()) {
                        Text("${prompt.length} симв.", fontSize = 11.sp, color = PipelineTheme.textSecondary, fontFamily = FontFamily.Monospace)
                        Spacer(Modifier.width(6.dp))
                    }
                    Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = PipelineTheme.textSecondary)
                }
            }

            AnimatedVisibility(visible = expanded) {
                OutlinedTextField(
                    value = prompt,
                    onValueChange = onPromptChange, // ИСПРАВЛЕНО: было onPromptChange = onPromptChange
                    readOnly = isRunning,
                    placeholder = {
                        Text(
                            "Вставьте инструкции для изменения нескольких файлов. Планировщик автоматически распределит задачи...",
                            color = PipelineTheme.textTertiary,
                            fontSize = 12.sp
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp, max = 280.dp)
                        .padding(top = 10.dp),
                    textStyle = LocalTextStyle.current.copy(fontSize = 12.sp, fontFamily = FontFamily.Monospace, lineHeight = 18.sp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = PipelineTheme.surfaceSecondary,
                        unfocusedContainerColor = PipelineTheme.surfaceSecondary
                    ),
                    shape = RoundedCornerShape(10.dp)
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// ПАНЕЛЬ ДЕЙСТВИЙ (СТАРТ / СТОП / СБРОС)
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun ActionBarLight(
    state: PipelineState,
    totalCost: Double,
    totalTokens: Int,
    onPlan: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onReset: () -> Unit,
    onExport: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = PipelineTheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val (buttonText, isEnabled, action) = when (state.phase) {
                    PipelinePhase.IDLE -> Triple("📋 Спланировать", true, onPlan)
                    PipelinePhase.PLANNING -> Triple("⏳ Планирование...", false, {})
                    PipelinePhase.REVIEWING -> Triple("▶️ Старт (${state.tasks.size} задач)", true, onStart)
                    PipelinePhase.EXECUTING -> Triple("⚡ Выполнение (${state.completedTasks}/${state.totalTasks})", false, {})
                    PipelinePhase.DEFERRED_PASS -> Triple("🔄 Повторный проход...", false, {})
                    PipelinePhase.FINALIZING -> Triple("📝 Подготовка отчёта...", false, {})
                    PipelinePhase.DONE -> Triple("✅ Готово · Новый запуск", true, onReset)
                    PipelinePhase.CANCELLED -> Triple("⚪ Остановлено · Сброс", true, onReset)
                    PipelinePhase.FATAL -> Triple("🔴 Ошибка · Сброс", true, onReset)
                }

                Button(
                    onClick = action,
                    enabled = isEnabled,
                    modifier = Modifier.weight(1f).height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = when (state.overallStatus) {
                            OverallStatus.RUNNING -> PipelineTheme.blue
                            OverallStatus.SUCCESS_ALL -> PipelineTheme.green
                            OverallStatus.SUCCESS_PARTIAL -> PipelineTheme.amber
                            OverallStatus.FAILED_ALL, OverallStatus.FATAL -> PipelineTheme.red
                            OverallStatus.CANCELLED -> PipelineTheme.textSecondary
                        }
                    )
                ) {
                    Text(buttonText, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = Color.White)
                }

                if (state.canStop) {
                    Spacer(Modifier.width(8.dp))
                    IconButton(
                        onClick = onStop,
                        modifier = Modifier
                            .size(50.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(PipelineTheme.redSoft)
                    ) {
                        Icon(Icons.Default.Stop, null, tint = PipelineTheme.red)
                    }
                }

                if (state.phase == PipelinePhase.DONE) {
                    Spacer(Modifier.width(8.dp))
                    IconButton(
                        onClick = onExport,
                        modifier = Modifier
                            .size(50.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(PipelineTheme.surfaceSecondary)
                    ) {
                        Icon(Icons.Default.Download, null, tint = PipelineTheme.blue)
                    }
                }
            }

            if (totalCost > 0 || totalTokens > 0) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("💰 €${String.format(Locale.US, "%.4f", totalCost)}", fontSize = 11.sp, color = PipelineTheme.textSecondary, fontFamily = FontFamily.Monospace)
                    Text("🧮 $totalTokens токенов", fontSize = 11.sp, color = PipelineTheme.textSecondary, fontFamily = FontFamily.Monospace)
                    Text("✅ ${state.successfulTasks} · ❌ ${state.failedTasks}", fontSize = 11.sp, color = PipelineTheme.textSecondary, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// ПРОГРЕСС ЗАДАЧ
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun TaskProgressSectionLight(
    state: PipelineState,
    onRemoveTask: (String) -> Unit,
    onTaskChipClick: (String) -> Unit,
    onChangeMaxParallel: (Int) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = PipelineTheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            val progress by animateFloatAsState(targetValue = state.progress, label = "progress")
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = PipelineTheme.blue,
                trackColor = PipelineTheme.surfaceSecondary,
                drawStopIndicator = {}
            )

            if (state.phase == PipelinePhase.REVIEWING || state.phase == PipelinePhase.IDLE) {
                Spacer(Modifier.height(8.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "Потоков: ${state.maxParallelTasks}",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = PipelineTheme.textSecondary,
                        modifier = Modifier.width(90.dp)
                    )
                    Slider(
                        value = state.maxParallelTasks.toFloat(),
                        onValueChange = { onChangeMaxParallel(it.toInt()) },
                        valueRange = 1f..8f,
                        steps = 6,
                        modifier = Modifier.weight(1f).height(24.dp),
                        colors = SliderDefaults.colors(thumbColor = PipelineTheme.blue, activeTrackColor = PipelineTheme.blue)
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(state.tasks, key = { it.id }) { task ->
                    TaskChipLight(
                        task = task,
                        isCurrent = task.id in state.runningTaskIds,
                        isSelected = state.logFilterTaskId == task.id,
                        canRemove = state.phase == PipelinePhase.REVIEWING,
                        onRemove = { onRemoveTask(task.id) },
                        onClick = { onTaskChipClick(task.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun TaskChipLight(
    task: FileTask,
    isCurrent: Boolean,
    isSelected: Boolean,
    canRemove: Boolean,
    onRemove: () -> Unit,
    onClick: () -> Unit
) {
    val bg = when {
        isSelected -> PipelineTheme.purpleSoft
        isCurrent -> PipelineTheme.blueSoft
        task.status == TaskStatus.SUCCESS -> PipelineTheme.greenSoft
        task.status == TaskStatus.FAILED_FINAL -> PipelineTheme.redSoft
        else -> PipelineTheme.surfaceSecondary
    }
    val border = when {
        isSelected -> PipelineTheme.purple
        isCurrent -> PipelineTheme.blue
        task.status == TaskStatus.SUCCESS -> PipelineTheme.green
        task.status == TaskStatus.FAILED_FINAL -> PipelineTheme.red
        else -> PipelineTheme.border
    }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = bg,
        border = BorderStroke(1.dp, border)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(task.status.emoji, fontSize = 12.sp)
            Spacer(Modifier.width(4.dp))
            Text(task.filePath.substringAfterLast('/'), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace, color = PipelineTheme.textPrimary)
            if (canRemove) {
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Default.Close, null, tint = PipelineTheme.textTertiary, modifier = Modifier.size(14.dp).clickable(onClick = onRemove))
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// ЛОГИ
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun FilterBadgeLight(taskId: String, tasks: List<FileTask>, onClear: () -> Unit) {
    val name = tasks.find { it.id == taskId }?.filePath?.substringAfterLast('/') ?: taskId
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(PipelineTheme.purpleSoft)
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text("🔍 Фильтр логов: $name", fontSize = 11.sp, color = PipelineTheme.purple, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Icon(Icons.Default.Close, null, tint = PipelineTheme.purple, modifier = Modifier.size(16.dp).clickable(onClick = onClear))
    }
}

@Composable
private fun LiveLogsSectionLight(geminiLog: List<GeminiLogEvent>, repoLog: List<RepoLogEvent>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp)
            .height(300.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        LogPanelLight("🤖 Gemini", PipelineTheme.blue, Modifier.weight(1f)) {
            val listState = rememberLazyListState()
            LaunchedEffect(geminiLog.size) { if (geminiLog.isNotEmpty()) listState.animateScrollToItem(geminiLog.size - 1) }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(8.dp)) {
                items(geminiLog, key = { it.id }) {
                    LogLineItem(it.icon, it.message, it.timestamp)
                }
            }
        }
        LogPanelLight("📦 Репозиторий", PipelineTheme.green, Modifier.weight(1f)) {
            val listState = rememberLazyListState()
            LaunchedEffect(repoLog.size) { if (repoLog.isNotEmpty()) listState.animateScrollToItem(repoLog.size - 1) }
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(8.dp)) {
                items(repoLog, key = { it.id }) {
                    LogLineItem(it.icon, it.message, it.timestamp)
                }
            }
        }
    }
}

@Composable
private fun LogPanelLight(title: String, accent: Color, modifier: Modifier, content: @Composable () -> Unit) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = PipelineTheme.surfaceSecondary),
        border = BorderStroke(0.5.dp, PipelineTheme.border)
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(PipelineTheme.surface)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(accent))
                Spacer(Modifier.width(6.dp))
                Text(title, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = PipelineTheme.textPrimary)
            }
            HorizontalDivider(color = PipelineTheme.border, thickness = 0.5.dp)
            Box(modifier = Modifier.weight(1f)) { content() }
        }
    }
}

@Composable
private fun LogLineItem(icon: String, message: String, timestamp: Long) {
    val time = remember(timestamp) { formatLogTime(timestamp) }
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 1.5.dp), verticalAlignment = Alignment.Top) {
        Text(time, fontSize = 9.sp, color = PipelineTheme.textTertiary, fontFamily = FontFamily.Monospace, modifier = Modifier.width(46.dp))
        Text(icon, fontSize = 10.sp, modifier = Modifier.padding(end = 4.dp))
        Text(message, fontSize = 10.sp, color = PipelineTheme.textPrimary, fontFamily = FontFamily.Monospace, modifier = Modifier.weight(1f))
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// ИТОГОВЫЙ ОТЧЕТ И ОШИБКИ
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun FinalReportCardLight(report: String, onShareDetailed: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = PipelineTheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("📊 Финальная сводка", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = PipelineTheme.textPrimary)
                OutlinedButton(
                    onClick = onShareDetailed,
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                    modifier = Modifier.height(32.dp)
                ) {
                    Icon(Icons.Default.Share, null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("TXT Отчёт", fontSize = 11.sp)
                }
            }
            Spacer(Modifier.height(10.dp))
            SelectionContainer {
                Text(report, fontSize = 12.sp, lineHeight = 18.sp, color = PipelineTheme.textPrimary)
            }
        }
    }
}

@Composable
private fun FatalCardLight(error: String) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = PipelineTheme.redSoft)
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Error, null, tint = PipelineTheme.red, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text("Критическая ошибка (FATAL)", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = PipelineTheme.red)
                Text(error, fontSize = 11.sp, color = PipelineTheme.textPrimary, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// ШТОРКА УПРАВЛЕНИЯ БЭКАПАМИ (MODAL BOTTOM SHEET)
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun BackupsSheetContent(
    backups: List<PipelineBackupManager.BackupEntry>,
    isRollingBack: Boolean,
    onRollbackClick: (PipelineBackupManager.BackupEntry) -> Unit,
    onShareReport: (PipelineBackupManager.BackupEntry) -> Unit,
    onDelete: (PipelineBackupManager.BackupEntry) -> Unit,
    onClose: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Shield, null, tint = PipelineTheme.green, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("История бэкапов", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = PipelineTheme.textPrimary)
            }
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, null, tint = PipelineTheme.textSecondary)
            }
        }

        Text(
            "Каждый запуск создает снимок оригиналов. Вы можете в 1 клик откатить проект или выгрузить полный TXT-отчет.",
            fontSize = 11.sp,
            color = PipelineTheme.textSecondary
        )

        Spacer(Modifier.height(14.dp))

        if (backups.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 40.dp),
                contentAlignment = Alignment.Center
            ) {
                Text("Нет сохранённых снимков", color = PipelineTheme.textTertiary, fontSize = 13.sp)
            }
        } else {
            LazyColumn(
                modifier = Modifier.heightIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(backups, key = { it.id }) { backup ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = PipelineTheme.surfaceSecondary),
                        border = BorderStroke(0.5.dp, PipelineTheme.border)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(backup.formattedDate, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = PipelineTheme.textPrimary)
                                    Spacer(Modifier.width(6.dp))
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = if (backup.isRestored) PipelineTheme.amberSoft else PipelineTheme.greenSoft
                                    ) {
                                        Text(
                                            if (backup.isRestored) "ОТКАЧЕНО" else "АКТИВЕН",
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (backup.isRestored) PipelineTheme.amber else PipelineTheme.green,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                                Text("${backup.affectedFilesCount} файлов", fontSize = 11.sp, color = PipelineTheme.textSecondary)
                            }

                            Spacer(Modifier.height(6.dp))
                            Text(
                                backup.userPrompt.take(120).replace('\n', ' ') + if (backup.userPrompt.length > 120) "..." else "",
                                fontSize = 11.sp,
                                color = PipelineTheme.textSecondary,
                                maxLines = 2
                            )

                            Spacer(Modifier.height(10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(onClick = { onDelete(backup) }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Default.Delete, null, tint = PipelineTheme.red, modifier = Modifier.size(16.dp))
                                }
                                Spacer(Modifier.width(4.dp))
                                OutlinedButton(
                                    onClick = { onShareReport(backup) },
                                    shape = RoundedCornerShape(8.dp),
                                    modifier = Modifier.height(34.dp),
                                    contentPadding = PaddingValues(horizontal = 10.dp)
                                ) {
                                    Icon(Icons.Default.Description, null, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("TXT Отчёт", fontSize = 11.sp)
                                }
                                Spacer(Modifier.width(6.dp))
                                Button(
                                    onClick = { onRollbackClick(backup) },
                                    enabled = !isRollingBack,
                                    shape = RoundedCornerShape(8.dp),
                                    colors = ButtonDefaults.buttonColors(containerColor = PipelineTheme.amber),
                                    modifier = Modifier.height(34.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp)
                                ) {
                                    Icon(Icons.Default.History, null, tint = Color.White, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Откатить", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}