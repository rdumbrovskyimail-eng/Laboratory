package com.opuside.app.core.git

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

/**
 * Диалог разрешения Git конфликтов.
 * 
 * Показывает 3-панельный view:
 * - Left: Локальные изменения
 * - Center: Результат слияния (редактируемый)
 * - Right: Удаленные изменения
 */
@Composable
fun ConflictResolverDialog(
    conflict: ConflictResult.Conflict,
    onDismiss: () -> Unit,
    onResolve: (strategy: ConflictStrategy, mergedContent: String?) -> Unit
) {
    var selectedStrategy by remember { mutableStateOf<ConflictStrategy?>(null) }
    var mergedContent by remember { mutableStateOf(conflict.localContent) }
    var showDiff by remember { mutableStateOf(true) }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.9f),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // ═══════════════════════════════════════════════════════════════
                // HEADER
                // ═══════════════════════════════════════════════════════════════
                
                Surface(
                    tonalElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "⚠️ Merge Conflict",
                                    style = MaterialTheme.typography.titleLarge,
                                    color = MaterialTheme.colorScheme.error
                                )
                                Text(
                                    text = conflict.path,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            
                            IconButton(onClick = onDismiss) {
                                Icon(Icons.Default.Close, "Close")
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        // Статистика конфликтов
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            ConflictStat(
                                icon = Icons.Default.Warning,
                                label = "Conflicts",
                                value = conflict.conflictedLines.size.toString(),
                                color = MaterialTheme.colorScheme.error
                            )
                            ConflictStat(
                                icon = Icons.Default.Add,
                                label = "Added",
                                value = conflict.diff.count { it is DiffLine.Added }.toString(),
                                color = Color(0xFF22C55E)
                            )
                            ConflictStat(
                                icon = Icons.Default.Remove,
                                label = "Removed",
                                value = conflict.diff.count { it is DiffLine.Removed }.toString(),
                                color = Color(0xFFEF4444)
                            )
                        }
                    }
                }

                // ═══════════════════════════════════════════════════════════════
                // DIFF VIEW / MERGE EDITOR
                // ═══════════════════════════════════════════════════════════════

                Box(modifier = Modifier.weight(1f)) {
                    if (showDiff && selectedStrategy != ConflictStrategy.MANUAL_MERGE) {
                        ThreeWayDiffView(
                            conflict = conflict,
                            onAcceptLocal = { mergedContent = conflict.localContent },
                            onAcceptRemote = { mergedContent = conflict.remoteContent }
                        )
                    } else {
                        // Редактор для ручного слияния
                        MergeEditor(
                            content = mergedContent,
                            onContentChange = { mergedContent = it },
                            conflictedLines = conflict.conflictedLines
                        )
                    }
                }

                // ═══════════════════════════════════════════════════════════════
                // STRATEGY SELECTION & ACTIONS
                // ═══════════════════════════════════════════════════════════════

                Surface(
                    tonalElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "Choose resolution strategy:",
                            style = MaterialTheme.typography.titleSmall
                        )
                        
                        Spacer(Modifier.height(8.dp))

                        // Кнопки стратегий
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            StrategyButton(
                                icon = Icons.Default.CallReceived,
                                label = "Keep Mine",
                                description = "Overwrite remote",
                                isSelected = selectedStrategy == ConflictStrategy.KEEP_MINE,
                                onClick = { 
                                    selectedStrategy = ConflictStrategy.KEEP_MINE
                                    showDiff = true
                                },
                                modifier = Modifier.weight(1f)
                            )

                            StrategyButton(
                                icon = Icons.Default.CallMade,
                                label = "Keep Theirs",
                                description = "Discard local",
                                isSelected = selectedStrategy == ConflictStrategy.KEEP_THEIRS,
                                onClick = { 
                                    selectedStrategy = ConflictStrategy.KEEP_THEIRS
                                    showDiff = true
                                },
                                modifier = Modifier.weight(1f)
                            )

                            StrategyButton(
                                icon = Icons.Default.Edit,
                                label = "Manual Merge",
                                description = "Edit & merge",
                                isSelected = selectedStrategy == ConflictStrategy.MANUAL_MERGE,
                                onClick = { 
                                    selectedStrategy = ConflictStrategy.MANUAL_MERGE
                                    showDiff = false
                                },
                                modifier = Modifier.weight(1f)
                            )

                            StrategyButton(
                                icon = Icons.Default.FileCopy,
                                label = "Save Copy",
                                description = "New file",
                                isSelected = selectedStrategy == ConflictStrategy.SAVE_AS_COPY,
                                onClick = { 
                                    selectedStrategy = ConflictStrategy.SAVE_AS_COPY
                                    showDiff = true
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Spacer(Modifier.height(16.dp))

                        // Кнопка подтверждения
                        Button(
                            onClick = {
                                val strategy = selectedStrategy ?: return@Button
                                val content = if (strategy == ConflictStrategy.MANUAL_MERGE) {
                                    mergedContent
                                } else null
                                onResolve(strategy, content)
                            },
                            enabled = selectedStrategy != null,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Check, null)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                when (selectedStrategy) {
                                    ConflictStrategy.KEEP_MINE -> "Force Push Local Changes"
                                    ConflictStrategy.KEEP_THEIRS -> "Discard Local Changes"
                                    ConflictStrategy.MANUAL_MERGE -> "Save Merged Version"
                                    ConflictStrategy.SAVE_AS_COPY -> "Save as New File"
                                    null -> "Choose Strategy"
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// THREE-WAY DIFF VIEW
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun ThreeWayDiffView(
    conflict: ConflictResult.Conflict,
    onAcceptLocal: () -> Unit,
    onAcceptRemote: () -> Unit
) {
    Row(modifier = Modifier.fillMaxSize()) {
        // Local changes
        DiffPanel(
            title = "Your Changes",
            content = conflict.localContent,
            color = Color(0xFF3B82F6),
            onAccept = onAcceptLocal,
            modifier = Modifier.weight(1f)
        )

        VerticalDivider()

        // Remote changes
        DiffPanel(
            title = "Remote Changes",
            content = conflict.remoteContent,
            color = Color(0xFFEF4444),
            onAccept = onAcceptRemote,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun DiffPanel(
    title: String,
    content: String,
    color: Color,
    onAccept: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier.fillMaxHeight()) {
        // Header
        Surface(
            color = color.copy(alpha = 0.1f),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.labelMedium,
                    color = color
                )
                TextButton(onClick = onAccept) {
                    Text("Accept", color = color)
                }
            }
        }

        // Content
        LazyColumn(
            modifier = Modifier
                .weight(1f)
                .background(Color(0xFF1E1E1E))
                .padding(8.dp)
        ) {
            content.lines().forEachIndexed { index, line ->
                item {
                    Text(
                        text = line.ifEmpty { " " },
                        style = TextStyle(
                            fontFamily = FontFamily.Monospace,
                            fontSize = 13.sp,
                            color = Color(0xFFD4D4D4)
                        )
                    )
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// MERGE EDITOR
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun MergeEditor(
    content: String,
    onContentChange: (String) -> Unit,
    conflictedLines: List<Int>
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF1E1E1E))
    ) {
        // Hint
        Surface(
            color = Color(0xFFFEF3C7),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    tint = Color(0xFF92400E),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Edit the merged version below. Lines ${conflictedLines.joinToString()} have conflicts.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF92400E)
                )
            }
        }

        // Editor
        BasicTextField(
            value = content,
            onValueChange = onContentChange,
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            textStyle = TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                color = Color(0xFFD4D4D4),
                lineHeight = 20.sp
            )
        )
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// HELPER COMPONENTS
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun ConflictStat(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    color: Color
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = color,
            modifier = Modifier.size(16.dp)
        )
        Spacer(Modifier.width(4.dp))
        Text(
            text = "$value $label",
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

@Composable
private fun StrategyButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    description: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        colors = ButtonDefaults.outlinedButtonColors(
            containerColor = if (isSelected) 
                MaterialTheme.colorScheme.primaryContainer 
            else 
                Color.Transparent
        ),
        border = ButtonDefaults.outlinedButtonBorder.copy(
            width = if (isSelected) 2.dp else 1.dp
        )
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(vertical = 4.dp)
        ) {
            Icon(icon, null, Modifier.size(24.dp))
            Spacer(Modifier.height(4.dp))
            Text(label, style = MaterialTheme.typography.labelMedium)
            Text(
                description,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun VerticalDivider() {
    Box(
        modifier = Modifier
            .fillMaxHeight()
            .width(1.dp)
            .background(Color(0xFF404040))
    )
}