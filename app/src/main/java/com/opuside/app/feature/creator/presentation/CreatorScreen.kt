package com.opuside.app.feature.creator.presentation

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.opuside.app.core.git.ConflictResolverDialog
import com.opuside.app.core.git.ConflictResult
import com.opuside.app.core.git.ConflictStrategy
import com.opuside.app.core.network.github.model.GitHubContent
import com.opuside.app.core.ui.components.VirtualizedCodeEditor
import com.opuside.app.core.util.detectLanguage

@Composable
fun CreatorScreen(
    viewModel: CreatorViewModel = hiltViewModel()
) {
    val currentPath by viewModel.currentPath.collectAsState()
    val contents by viewModel.contents.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val canGoBack by viewModel.canGoBack.collectAsState()
    val selectedFile by viewModel.selectedFile.collectAsState()
    val fileContent by viewModel.fileContent.collectAsState()
    val hasChanges by viewModel.hasChanges.collectAsState()
    val isSaving by viewModel.isSaving.collectAsState()
    val conflictState by viewModel.conflictState.collectAsState()

    var showNewFileDialog by remember { mutableStateOf(false) }
    var showCommitDialog by remember { mutableStateOf(false) }

    // ═══════════════════════════════════════════════════════════════════════════
    // DIALOGS
    // ═══════════════════════════════════════════════════════════════════════════

    if (showNewFileDialog) {
        NewFileDialog(
            onDismiss = { showNewFileDialog = false },
            onCreate = { name, content ->
                viewModel.createNewFile(name, content)
                showNewFileDialog = false
            }
        )
    }

    if (showCommitDialog) {
        CommitDialog(
            onDismiss = { showCommitDialog = false },
            onCommit = { message ->
                viewModel.saveFile(message)
                showCommitDialog = false
            }
        )
    }

    // Git Conflict Dialog
    conflictState?.let { conflict ->
        if (conflict is ConflictResult.Conflict) {
            ConflictResolverDialog(
                conflict = conflict,
                onDismiss = viewModel::dismissConflict,
                onResolve = { strategy, content ->
                    viewModel.resolveConflict(strategy, content)
                }
            )
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // MAIN UI
    // ═══════════════════════════════════════════════════════════════════════════

    Column(modifier = Modifier.fillMaxSize()) {
        // TOP BAR
        TopBar(
            path = currentPath,
            canGoBack = canGoBack,
            onBack = viewModel::navigateBack,
            onRefresh = viewModel::refresh,
            onNewFile = { showNewFileDialog = true },
            selectedFile = selectedFile,
            onCloseFile = viewModel::closeFile
        )

        // Error banner
        error?.let {
            ErrorBanner(
                message = it,
                onDismiss = viewModel::clearError
            )
        }

        // CONTENT
        if (selectedFile != null) {
            // EDITOR MODE - использует новый VirtualizedCodeEditor
            EditorMode(
                file = selectedFile!!,
                content = fileContent,
                hasChanges = hasChanges,
                isSaving = isSaving,
                onContentChange = viewModel::updateFileContent,
                onSave = { showCommitDialog = true },
                onAddToCache = viewModel::addCurrentFileToCache,
                modifier = Modifier.weight(1f)
            )
        } else {
            // FILE BROWSER MODE
            if (isLoading) {
                LoadingState()
            } else {
                FileBrowser(
                    contents = contents,
                    onFolderClick = viewModel::navigateToFolder,
                    onFileClick = viewModel::openFile,
                    onAddToCache = viewModel::addToCache,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// TOP BAR
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun TopBar(
    path: String,
    canGoBack: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
    onNewFile: () -> Unit,
    selectedFile: GitHubContent?,
    onCloseFile: () -> Unit
) {
    Surface(tonalElevation = 2.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selectedFile != null) {
                // File editor mode
                IconButton(onClick = onCloseFile) {
                    Icon(Icons.Default.Close, "Close file")
                }
                Text(
                    text = selectedFile.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
            } else {
                // File browser mode
                if (canGoBack) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }

                // Breadcrumb path
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (path.isEmpty()) "/" else "/$path",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(onClick = onRefresh) {
                    Icon(Icons.Default.Refresh, "Refresh")
                }
                IconButton(onClick = onNewFile) {
                    Icon(Icons.Default.Add, "New file")
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// ERROR BANNER
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun ErrorBanner(
    message: String,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Error,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = message,
                modifier = Modifier.weight(1f),
                color = MaterialTheme.colorScheme.onErrorContainer,
                style = MaterialTheme.typography.bodyMedium
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Dismiss",
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// LOADING STATE
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun LoadingState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CircularProgressIndicator()
            Text(
                text = "Loading files...",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// FILE BROWSER
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun FileBrowser(
    contents: List<GitHubContent>,
    onFolderClick: (String) -> Unit,
    onFileClick: (GitHubContent) -> Unit,
    onAddToCache: (GitHubContent) -> Unit,
    modifier: Modifier = Modifier
) {
    if (contents.isEmpty()) {
        EmptyFolderState(modifier = modifier)
    } else {
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(
                items = contents,
                key = { it.path }
            ) { item ->
                FileItem(
                    content = item,
                    onClick = {
                        if (item.type == "dir") {
                            onFolderClick(item.path)
                        } else {
                            onFileClick(item)
                        }
                    },
                    onAddToCache = { onAddToCache(item) }
                )
            }
        }
    }
}

@Composable
private fun EmptyFolderState(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Default.FolderOpen,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Text(
                text = "Empty folder",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun FileItem(
    content: GitHubContent,
    onClick: () -> Unit,
    onAddToCache: () -> Unit
) {
    val isDir = content.type == "dir"

    Card(
        modifier = Modifier.fillMaxWidth(),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isDir) Icons.Default.Folder else Icons.Default.Description,
                contentDescription = null,
                tint = if (isDir) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
                modifier = Modifier.size(24.dp)
            )

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = content.name,
                    style = MaterialTheme.typography.bodyLarge
                )
                if (!isDir) {
                    Text(
                        text = formatFileSize(content.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (!isDir) {
                IconButton(onClick = onAddToCache) {
                    Icon(
                        imageVector = Icons.Default.AddCircleOutline,
                        contentDescription = "Add to cache",
                        tint = MaterialTheme.colorScheme.secondary
                    )
                }
            }

            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// EDITOR MODE - ОБНОВЛЕНО с VirtualizedCodeEditor
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun EditorMode(
    file: GitHubContent,
    content: String,
    hasChanges: Boolean,
    isSaving: Boolean,
    onContentChange: (String) -> Unit,
    onSave: () -> Unit,
    onAddToCache: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        // Editor toolbar
        EditorToolbar(
            language = detectLanguage(file.name),
            hasChanges = hasChanges,
            isSaving = isSaving,
            onSave = onSave,
            onAddToCache = onAddToCache
        )

        // ✅ НОВЫЙ VirtualizedCodeEditor вместо старого CodeEditor
        VirtualizedCodeEditor(
            content = content,
            onContentChange = onContentChange,
            language = detectLanguage(file.name),
            modifier = Modifier.fillMaxSize(),
            readOnly = false,
            showLineNumbers = true,
            fontSize = 14,
            onCursorPositionChanged = { line, column ->
                // Опционально: можно показывать позицию курсора в статус-баре
                // Пока игнорируем, но можно добавить в будущем
            }
        )
    }
}

@Composable
private fun EditorToolbar(
    language: String,
    hasChanges: Boolean,
    isSaving: Boolean,
    onSave: () -> Unit,
    onAddToCache: () -> Unit
) {
    Surface(tonalElevation = 1.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Language badge
            Surface(
                shape = MaterialTheme.shapes.small,
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Text(
                    text = language.uppercase(),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            Spacer(Modifier.weight(1f))

            // Add to cache button
            TextButton(onClick = onAddToCache) {
                Icon(
                    imageVector = Icons.Default.AddCircleOutline,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(Modifier.width(4.dp))
                Text("Add to Cache")
            }

            Spacer(Modifier.width(8.dp))

            // Save/Commit button
            Button(
                onClick = onSave,
                enabled = hasChanges && !isSaving
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Save,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Spacer(Modifier.width(4.dp))
                Text(if (hasChanges) "Commit" else "No changes")
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// DIALOGS
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun NewFileDialog(
    onDismiss: () -> Unit,
    onCreate: (String, String) -> Unit
) {
    var fileName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Create New File") },
        text = {
            OutlinedTextField(
                value = fileName,
                onValueChange = { fileName = it },
                label = { Text("File name") },
                placeholder = { Text("example.kt") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(fileName, "") },
                enabled = fileName.isNotBlank()
            ) {
                Text("Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun CommitDialog(
    onDismiss: () -> Unit,
    onCommit: (String) -> Unit
) {
    var message by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Commit Changes") },
        text = {
            OutlinedTextField(
                value = message,
                onValueChange = { message = it },
                label = { Text("Commit message") },
                placeholder = { Text("Update file") },
                maxLines = 3,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onCommit(message.ifBlank { "Update file" }) }
            ) {
                Text("Commit")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

// ═══════════════════════════════════════════════════════════════════════════════
// HELPERS
// ═══════════════════════════════════════════════════════════════════════════════

private fun formatFileSize(bytes: Int): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
}