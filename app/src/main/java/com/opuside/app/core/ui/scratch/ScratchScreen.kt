package com.opuside.app.feature.scratch.presentation

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.opuside.app.feature.scratch.data.local.ScratchEntity
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScratchScreen(
    viewModel: ScratchViewModel = hiltViewModel()
) {
    val text by viewModel.text.collectAsState()
    val records by viewModel.records.collectAsState()
    val snackMessage by viewModel.snackMessage.collectAsState()
    val context = LocalContext.current

    var showRecords by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }

    // Показываем snackbar при сообщении
    LaunchedEffect(snackMessage) {
        snackMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.snackShown()
        }
    }

    // Bottom Sheet со списком записей
    if (showRecords) {
        ModalBottomSheet(
            onDismissRequest = { showRecords = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = false)
        ) {
            RecordsSheet(
                records = records,
                onSelect = { record ->
                    viewModel.loadRecord(record)
                    showRecords = false
                },
                onDelete = { record ->
                    viewModel.deleteRecord(record)
                }
            )
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Scratch",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            BottomActionBar(
                onCopyAll = {
                    if (text.isNotEmpty()) {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("scratch", text))
                        viewModel.snackMessage.let { }
                        // snack вручную
                        val scope = kotlinx.coroutines.MainScope()
                        scope.launch {
                            snackbarHostState.showSnackbar("Скопировано!")
                        }
                    }
                },
                onClear = { viewModel.clear() },
                onSave = { viewModel.save() },
                onRecords = { showRecords = true },
                recordsCount = records.size
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            CodeTextField(
                value = text,
                onValueChange = viewModel::onTextChange,
                modifier = Modifier.fillMaxSize()
            )

            // Плейсхолдер
            if (text.isEmpty()) {
                Text(
                    text = "Вставь сюда коды...\n\nМожно вставлять несколько блоков подряд.",
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.35f)
                    ),
                    modifier = Modifier
                        .padding(16.dp)
                        .align(Alignment.TopStart)
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
// Поле ввода в стиле кода
// ─────────────────────────────────────────────────────────────

@Composable
private fun CodeTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val textColor = MaterialTheme.colorScheme.onSurface
    val bgColor = MaterialTheme.colorScheme.surface

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .background(bgColor)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        textStyle = TextStyle(
            fontFamily = FontFamily.Monospace,
            fontSize = 13.sp,
            lineHeight = 20.sp,
            color = textColor
        ),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary)
    )
}

// ─────────────────────────────────────────────────────────────
// Нижняя панель с кнопками
// ─────────────────────────────────────────────────────────────

@Composable
private fun BottomActionBar(
    onCopyAll: () -> Unit,
    onClear: () -> Unit,
    onSave: () -> Unit,
    onRecords: () -> Unit,
    recordsCount: Int
) {
    Surface(
        tonalElevation = 3.dp,
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Скопировать всё
            OutlinedButton(
                onClick = onCopyAll,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Копировать", fontSize = 12.sp)
            }

            // Очистить
            OutlinedButton(
                onClick = onClear,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                )
            ) {
                Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Очистить", fontSize = 12.sp)
            }

            Spacer(Modifier.width(4.dp))

            // Записи (иконка-кнопка с бейджом)
            BadgedBox(
                badge = {
                    if (recordsCount > 0) {
                        Badge { Text(recordsCount.toString()) }
                    }
                }
            ) {
                IconButton(onClick = onRecords) {
                    Icon(
                        Icons.Default.BookmarkBorder,
                        contentDescription = "Записи",
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }

            // Сохранить
            Button(
                onClick = onSave,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)
            ) {
                Icon(Icons.Default.Save, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text("Сохранить", fontSize = 12.sp)
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────
// Bottom Sheet: список записей
// ─────────────────────────────────────────────────────────────

@Composable
private fun RecordsSheet(
    records: List<ScratchEntity>,
    onSelect: (ScratchEntity) -> Unit,
    onDelete: (ScratchEntity) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 24.dp)
    ) {
        Text(
            text = "Сохранённые записи",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
        )

        if (records.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 48.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Записей пока нет",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 480.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(records, key = { it.id }) { record ->
                    RecordItem(
                        record = record,
                        onClick = { onSelect(record) },
                        onDelete = { onDelete(record) }
                    )
                }
            }
        }
    }
}

@Composable
private fun RecordItem(
    record: ScratchEntity,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    val dateFormat = remember { SimpleDateFormat("dd MMM, HH:mm", Locale.getDefault()) }
    val preview = record.content.take(80).replace('\n', ' ')

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = record.title,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = preview,
                    style = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    ),
                    maxLines = 1
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = dateFormat.format(Date(record.createdAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }

            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Удалить",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}
