package com.opuside.app.feature.creator.presentation

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.opuside.app.core.network.github.model.GitHubContent
import com.opuside.app.core.util.SyntaxHighlighter
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

    var showNewFileDialog by remember { mutableStateOf(false) }
    var showCommitDialog by remember { mutableStateOf(false) }

    // Dialogs
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

    Column(modifier = Modifier.fillMaxSize()) {
        // ═══════════════════════════════════════════════════════════════════════
        // TOP BAR
        // ═══════════════════════════════════════════════════════════════════════
        
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
            Card(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                colors = CardDefaults.cardColors(MaterialTheme.colorScheme.errorContainer)
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(it, Modifier.weight(1f), MaterialTheme.colorScheme.onErrorContainer)
                    IconButton({ viewModel.clearError() }) {
                        Icon(Icons.Default.Close, "Dismiss")
                    }
                }
            }
        }

        // ═══════════════════════════════════════════════════════════════════════
        // CONTENT
        // ═══════════════════════════════════════════════════════════════════════

        if (selectedFile != null) {
            // EDITOR MODE
            CodeEditor(
                content = fileContent,
                language = detectLanguage(selectedFile!!.name),
                onContentChange = viewModel::updateFileContent,
                hasChanges = hasChanges,
                isSaving = isSaving,
                onSave = { showCommitDialog = true },
                onAddToCache = viewModel::addCurrentFileToCache,
                modifier = Modifier.weight(1f)
            )
        } else {
            // FILE BROWSER MODE
            if (isLoading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
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
    path: String, canGoBack: Boolean, onBack: () -> Unit, onRefresh: () -> Unit,
    onNewFile: () -> Unit, selectedFile: GitHubContent?, onCloseFile: () -> Unit
) {
    Surface(tonalElevation = 2.dp) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (selectedFile != null) {
                IconButton(onCloseFile) {
                    Icon(Icons.Default.Close, "Close file")
                }
                Text(selectedFile.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            } else {
                if (canGoBack) {
                    IconButton(onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
                
                // Breadcrumb path
                Row(
                    modifier = Modifier.weight(1f).horizontalScroll(rememberScrollState()),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Default.Folder, null, Modifier.size(20.dp), MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = if (path.isEmpty()) "/" else "/$path",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                IconButton(onRefresh) { Icon(Icons.Default.Refresh, "Refresh") }
                IconButton(onNewFile) { Icon(Icons.Default.Add, "New file") }
            }
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
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Empty folder", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    } else {
        LazyColumn(modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            items(contents) { item ->
                FileItem(
                    content = item,
                    onClick = {
                        if (item.type == "dir") onFolderClick(item.path)
                        else onFileClick(item)
                    },
                    onAddToCache = { onAddToCache(item) }
                )
            }
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
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isDir) Icons.Default.Folder else Icons.Default.Description,
                contentDescription = null,
                tint = if (isDir) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(12.dp))
            
            Column(Modifier.weight(1f)) {
                Text(content.name, style = MaterialTheme.typography.bodyLarge)
                if (!isDir) {
                    Text(
                        text = formatFileSize(content.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            if (!isDir) {
                IconButton(onAddToCache) {
                    Icon(Icons.Default.AddCircleOutline, "Add to cache", tint = MaterialTheme.colorScheme.secondary)
                }
            }
            
            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════════
// CODE EDITOR
// ═══════════════════════════════════════════════════════════════════════════════

@Composable
private fun CodeEditor(
    content: String, language: String, onContentChange: (String) -> Unit,
    hasChanges: Boolean, isSaving: Boolean, onSave: () -> Unit, onAddToCache: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier) {
        // Editor toolbar
        Surface(tonalElevation = 1.dp) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(language.uppercase(), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                
                TextButton(onClick = onAddToCache) {
                    Icon(Icons.Default.AddCircleOutline, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Add to Cache")
                }
                
                Spacer(Modifier.width(8.dp))
                
                Button(
                    onClick = onSave,
                    enabled = hasChanges && !isSaving
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Save, null, Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(4.dp))
                    Text("Commit")
                }
            }
        }
        
        // Code area with syntax highlighting
        val highlighted = remember(content, language) {
            SyntaxHighlighter.highlight(content, language)
        }
        
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ) {
            Row(Modifier.fillMaxSize().padding(8.dp)) {
                // Line numbers
                val lines = content.lines()
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    lines.forEachIndexed { index, _ ->
                        Text(
                            text = "${index + 1}",
                            style = TextStyle(
                                fontFamily = FontFamily.Monospace,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                            ),
                            modifier = Modifier.padding(end = 12.dp)
                        )
                    }
                }
                
                // Editor
                BasicTextField(
                    value = content,
                    onValueChange = onContentChange,
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    textStyle = TextStyle(
                        fontFamily = FontFamily.Monospace,
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary)
                )
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
                singleLine = true
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onCreate(fileName, "") },
                enabled = fileName.isNotBlank()
            ) { Text("Create") }
        },
        dismissButton = {
            TextButton(onDismiss) { Text("Cancel") }
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
                maxLines = 3
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onCommit(message.ifBlank { "Update file" }) }
            ) { Text("Commit") }
        },
        dismissButton = {
            TextButton(onDismiss) { Text("Cancel") }
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
