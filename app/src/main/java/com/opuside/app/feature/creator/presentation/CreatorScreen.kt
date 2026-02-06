package com.opuside.app.feature.creator.presentation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.ripple
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.opuside.app.core.git.ConflictResolverDialog
import com.opuside.app.core.git.ConflictResult
import com.opuside.app.core.network.github.model.GitHubContent
import com.opuside.app.core.ui.components.VirtualizedCodeEditor
import com.opuside.app.core.util.detectLanguage

@Composable
fun CreatorScreen(
    viewModel: CreatorViewModel = hiltViewModel()
) {
    val currentOwner by viewModel.currentOwner.collectAsState()
    val currentRepo by viewModel.currentRepo.collectAsState()
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
    var itemToDelete by remember { mutableStateOf<GitHubContent?>(null) }

    // ✅ ПРОБЛЕМА 6: Обработка системной кнопки "Назад"
    BackHandler(enabled = canGoBack) {
        viewModel.navigateBack()
    }

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

    // ✅ ПРОБЛЕМА 7: Диалог удаления для файлов И папок
    itemToDelete?.let { item ->
        DeleteConfirmationDialog(
            itemName = item.name,
            isFolder = item.type == "dir",
            onDismiss = { itemToDelete = null },
            onConfirm = {
                if (item.type == "dir") {
                    viewModel.deleteFolder(item)
                } else {
                    viewModel.deleteFile(item)
                }
                itemToDelete = null
            }
        )
    }

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

    Column(modifier = Modifier.fillMaxSize()) {
        TopBar(
            path = currentPath,
            canGoBack = canGoBack,
            onBack = viewModel::navigateBack,
            onRefresh = viewModel::refresh,
            onNewFile = { showNewFileDialog = true },
            selectedFile = selectedFile,
            onCloseFile = viewModel::closeFile
        )

        error?.let {
            ErrorBanner(
                message = it,
                onDismiss = viewModel::clearError
            )
        }

        if (selectedFile != null) {
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
            when {
                isLoading -> LoadingState()
                currentOwner.isBlank() || currentRepo.isBlank() -> {
                    ConfigurationNeededState()
                }
                else -> FileBrowser(
                    contents = contents,
                    onFolderClick = viewModel::navigateToFolder,
                    onFileClick = viewModel::openFile,
                    onAddToCache = viewModel::addToCache,
                    onDeleteItem = { itemToDelete = it },
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
                IconButton(onClick = onCloseFile) {
                    Icon(Icons.Default.Close, "Close file")
                }
                Text(
                    text = selectedFile.name,
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
            } else {
                if (canGoBack) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
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
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
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
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            CircularProgressIndicator()
            Text(
                "Loading files...", 
                style = MaterialTheme.typography.bodyMedium, 
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// CONFIGURATION NEEDED STATE
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun ConfigurationNeededState() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
            modifier = Modifier.padding(48.dp)
        ) {
            Icon(
                Icons.Default.Settings,
                null,
                Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
            )
            
            Text(
                "GitHub Not Configured",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center
            )
            
            Text(
                "To start working with your repository:\n\n" +
                "1. Go to Settings tab\n" +
                "2. Enter GitHub Owner\n" +
                "3. Enter Repository Name\n" +
                "4. Enter Personal Access Token\n" +
                "5. Click Save",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                lineHeight = 20.sp
            )
            
            Spacer(Modifier.height(8.dp))
            
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                )
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Info,
                            null,
                            Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Need a GitHub Token?",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "1. Go to github.com → Settings\n" +
                        "2. Developer settings → Personal access tokens\n" +
                        "3. Generate new token (classic)\n" +
                        "4. Select 'repo' scope\n" +
                        "5. Copy token and paste in Settings",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        lineHeight = 18.sp
                    )
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// FILE BROWSER (✅ ИСПРАВЛЕНО: Проблемы 7, 8)
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun FileBrowser(
    contents: List<GitHubContent>,
    onFolderClick: (String) -> Unit,
    onFileClick: (GitHubContent) -> Unit,
    onAddToCache: (GitHubContent) -> Unit,
    onDeleteItem: (GitHubContent) -> Unit,
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
            items(items = contents, key = { it.path }) { item ->
                FileItem(
                    content = item,
                    onClick = { 
                        if (item.type == "dir") onFolderClick(item.path) 
                        else onFileClick(item) 
                    },
                    onAddToCache = { onAddToCache(item) },
                    onDelete = { onDeleteItem(item) }
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// EMPTY FOLDER STATE
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun EmptyFolderState(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                Icons.Default.FolderOpen, 
                null, 
                Modifier.size(64.dp), 
                MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
            )
            Text(
                "Empty folder", 
                style = MaterialTheme.typography.bodyLarge, 
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// FILE ITEM (✅ ИСПРАВЛЕНО: Проблемы 7, 8)
// ═══════════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FileItem(
    content: GitHubContent,
    onClick: () -> Unit,
    onAddToCache: () -> Unit,
    onDelete: () -> Unit
) {
    val isDir = content.type == "dir"
    
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { onDelete() }  // ✅ ПРОБЛЕМА 7: Удаление для файлов И папок
                )
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isDir) Icons.Default.Folder else Icons.Default.Description,
                contentDescription = null,
                tint = if (isDir) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
            
            Spacer(Modifier.width(12.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Text(content.name, style = MaterialTheme.typography.bodyLarge)
                if (!isDir) {
                    Text(
                        formatFileSize(content.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            // ✅ ПРОБЛЕМА 8: Изолированная кнопка Add to Cache
            if (!isDir) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = ripple(bounded = false, radius = 24.dp),
                            onClick = {
                                android.util.Log.d("FileItem", "🔥 Add to Cache clicked: ${content.path}")
                                onAddToCache()
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Default.AddCircleOutline,
                        "Add to cache",
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            
            Icon(
                Icons.Default.ChevronRight,
                null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// EDITOR MODE
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
        EditorToolbar(
            language = detectLanguage(file.name),
            hasChanges = hasChanges,
            isSaving = isSaving,
            onSave = onSave,
            onAddToCache = onAddToCache
        )

        key(file.path) {
            VirtualizedCodeEditor(
                content = content,
                onContentChange = onContentChange,
                language = detectLanguage(file.name),
                modifier = Modifier.fillMaxSize(),
                readOnly = false,
                showLineNumbers = true,
                fontSize = 12  // ✅ ПРОБЛЕМА 4: Уменьшен шрифт
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// EDITOR TOOLBAR
// ═══════════════════════════════════════════════════════════════════════════════

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
            Surface(
                shape = MaterialTheme.shapes.small, 
                color = MaterialTheme.colorScheme.primaryContainer
            ) {
                Text(
                    language.uppercase(),
                    Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }
            
            Spacer(Modifier.weight(1f))
            
            TextButton(onClick = onAddToCache) {
                Icon(Icons.Default.AddCircleOutline, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Add to Cache")
            }
            
            Spacer(Modifier.width(8.dp))
            
            Button(onClick = onSave, enabled = hasChanges && !isSaving) {
                if (isSaving) {
                    CircularProgressIndicator(
                        Modifier.size(16.dp), 
                        strokeWidth = 2.dp, 
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Icon(Icons.Default.Save, null, Modifier.size(18.dp))
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
private fun NewFileDialog(onDismiss: () -> Unit, onCreate: (String, String) -> Unit) {
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
            TextButton(onClick = onDismiss) { Text("Cancel") } 
        }
    )
}

@Composable
private fun CommitDialog(onDismiss: () -> Unit, onCommit: (String) -> Unit) {
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
            TextButton(onClick = { onCommit(message.ifBlank { "Update file" }) }) { 
                Text("Commit") 
            }
        },
        dismissButton = { 
            TextButton(onClick = onDismiss) { Text("Cancel") } 
        }
    )
}

// ✅ ПРОБЛЕМА 7: Универсальный диалог удаления для файлов И папок
@Composable
private fun DeleteConfirmationDialog(
    itemName: String,
    isFolder: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                Icons.Default.Delete,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(32.dp)
            )
        },
        title = {
            Text(
                if (isFolder) "Delete Folder?" else "Delete File?",
                style = MaterialTheme.typography.headlineSmall
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Are you sure you want to delete:",
                    style = MaterialTheme.typography.bodyMedium
                )
                
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.errorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            if (isFolder) Icons.Default.Folder else Icons.Default.Description,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.size(20.dp)
                        )
                        Text(
                            itemName,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
                
                Text(
                    if (isFolder) 
                        "This will delete the folder and ALL its contents recursively. This action cannot be undone."
                    else 
                        "This action cannot be undone. The file will be permanently deleted from GitHub.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError
                )
            ) {
                Icon(Icons.Default.Delete, null, Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("Delete")
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
// UTILITIES
// ═══════════════════════════════════════════════════════════════════════════════

private fun formatFileSize(bytes: Int): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "${bytes / 1024} KB"
    else -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
}